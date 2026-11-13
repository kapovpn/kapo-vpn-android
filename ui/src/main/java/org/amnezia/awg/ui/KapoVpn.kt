package org.amnezia.awg.ui

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import org.amnezia.awg.Application
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import org.amnezia.awg.crypto.Key
import org.amnezia.awg.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import org.amnezia.awg.util.LeakMonitor
import org.amnezia.awg.util.SecureAccountStore
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The bridge between the KAPO UI and the real AmneziaWG engine.
 *
 * The device keypair is generated once and kept on the phone; only the public
 * key is ever sent to the server (via EnrollClient). connect() enrolls, builds
 * an AmneziaWG config from the server's response, and brings the tunnel up
 * through the app's existing, tested TunnelManager / GoBackend stack.
 */
object KapoVpn {
    const val TUNNEL_NAME = "KAPO"
    private const val PREFS = "kapo_prefs"
    // Whether the user wants KAPO connected: set when it comes up, cleared only when
    // something asks for it to go down. The backend going down by itself leaves it set,
    // which is how LeakMonitor tells a drop from a disconnect.
    private const val KEY_WANTED = "tunnel_wanted"
    private const val KEY_PRIV = "wg_private_key"
    private const val KEY_ENROLL = "enroll_cache"
    private const val KEY_ENROLL_AT = "enroll_cache_at"
    private const val KEY_VALIDATED = "last_validated"
    private const val KEY_DAYS_LEFT = "days_left"
    // When the last account check was tried, answered or not (for the hourly back-off).
    private const val KEY_CHECK_TRIED = "licence_check_tried_at"
    // What the server said when it last refused the account; empty when it hasn't.
    private const val KEY_REFUSED = "licence_refused"
    private const val TAG = "KapoVpn"
    // How long the last good enrolment is reused while the server can't be reached:
    // LicenceGate.GRACE_MS.

    // Transient failures that should fall back to the grace cache.
    private val TRANSIENT = setOf(
        "network_error", "node_unreachable", "enroll_unconfigured", "processor_error", "error"
    )

    data class ConnectResult(val ok: Boolean, val status: String)

    /** Load the stored device keypair, or generate + persist one on first use. */
    private fun keyPair(ctx: Context): KeyPair {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_PRIV, null)?.let {
            try { return KeyPair(Key.fromBase64(it)) } catch (_: Exception) {}
        }
        val kp = KeyPair()
        prefs.edit().putString(KEY_PRIV, kp.privateKey.toBase64()).apply()
        return kp
    }

    /** The public key we register with the server. */
    fun devicePublicKey(ctx: Context): String = keyPair(ctx).publicKey.toBase64()

    private fun buildConfig(
        privBase64: String,
        e: EnrollClient.Result,
        excludedApps: Set<String> = emptySet(),
        includedApps: Set<String> = emptySet()
    ): Config {
        // IPv6 leak protection: the node has no v6 exit, so we still CAPTURE all
        // v6 traffic into the tunnel (AllowedIPs ::/0 + a private ULA address on
        // the interface). Captured v6 packets die at the node instead of leaking
        // out over the phone's normal v6 connection.
        val conf = buildString {
            append("[Interface]\n")
            append("PrivateKey = ").append(privBase64).append('\n')
            append("Address = ").append(e.assignedIp).append("/32, fd66:66:66::2/128\n")
            append("DNS = ").append(e.dns).append('\n')
            append("Jc = ").append(e.jc).append('\n')
            append("Jmin = ").append(e.jmin).append('\n')
            append("Jmax = ").append(e.jmax).append('\n')
            append("S1 = ").append(e.s1).append('\n')
            append("S2 = ").append(e.s2).append('\n')
            append("H1 = ").append(e.h1).append('\n')
            append("H2 = ").append(e.h2).append('\n')
            append("H3 = ").append(e.h3).append('\n')
            append("H4 = ").append(e.h4).append('\n')
            // Split tunneling: at most one of these is ever non-empty (see
            // KapoState.setSplitTunnelApps). Parsed by Interface.Builder's
            // parseExcludedApplications()/parseIncludedApplications() and
            // turned into VpnService.Builder.addDisallowedApplication()/
            // addAllowedApplication() calls in GoBackend - the routing logic
            // itself already existed upstream, just never had a KAPO caller.
            if (excludedApps.isNotEmpty())
                append("ExcludedApplications = ").append(excludedApps.joinToString(", ")).append('\n')
            if (includedApps.isNotEmpty())
                append("IncludedApplications = ").append(includedApps.joinToString(", ")).append('\n')
            append("\n[Peer]\n")
            append("PublicKey = ").append(e.serverPublicKey).append('\n')
            append("Endpoint = ").append(e.endpoint).append('\n')
            append("AllowedIPs = 0.0.0.0/0, ::/0\n")
            append("PersistentKeepalive = 25\n")
        }
        return Config.parse(ByteArrayInputStream(conf.toByteArray()))
    }

    /**
     * Enroll (if needed) and bring the tunnel up. Returns ok=false with a
     * status string the UI can translate (not_found / expired / device_limit /
     * network_error / ...). VPN permission must already be granted - the caller
     * handles GoBackend.VpnService.prepare() before calling this.
     */
    suspend fun connect(
        ctx: Context,
        account: String,
        chain: List<String> = listOf("is"),
        adblock: Boolean = false,
        adult: Boolean = false,
        excludedApps: Set<String> = emptySet(),
        includedApps: Set<String> = emptySet()
    ): ConnectResult = withContext(Dispatchers.IO) {
        val pub = devicePublicKey(ctx)
        val e = EnrollClient.enroll(ctx, account, pub, chain, adblock, adult)

        // Decide which enrollment params to use.
        val effective: EnrollClient.Result
        val statusOut: String
        when {
            e.ok -> { saveEnroll(ctx, e); effective = e; statusOut = "ok" }
            // EnrollClient's statuses other than network_error are the server's own answers.
            LicenceGate.isLicenceNo(fromServer = true, status = e.status) -> {
                // The account may not connect: stop and remove the tunnel and drop the cache,
                // and tell the user (also when it happened during a location switch).
                forgetTunnel(ctx, e.status)
                return@withContext ConnectResult(false, e.status)
            }
            e.status in TRANSIENT -> {
                // Server/node briefly unreachable: reuse the last good enrollment
                // if it is still within the grace window. The peer is already
                // registered on the node, so this reconnects cleanly.
                val cached = loadFreshEnroll(ctx)
                    ?: return@withContext ConnectResult(false, e.status)
                effective = cached; statusOut = "grace"
            }
            else -> {
                // Any other answer (rate_limited, busy, pool_exhausted, unknown_node, ...) fails
                // this attempt only. A tunnel that is already up stays up, untouched.
                Log.i(TAG, "Connect refused for now: ${e.status}")
                return@withContext ConnectResult(false, e.status)
            }
        }

        try {
            val cfg = buildConfig(keyPair(ctx).privateKey.toBase64(), effective, excludedApps, includedApps)
            val mgr = Application.getTunnelManager()
            val tunnel = mgr.getTunnels().firstOrNull { it.name == TUNNEL_NAME }
                ?: mgr.create(TUNNEL_NAME, cfg)
            tunnel.setConfigAsync(cfg)          // refresh in case the IP/params changed
            tunnel.setStateAsync(Tunnel.State.UP)
            ConnectResult(true, statusOut)
        } catch (ex: Exception) {
            ConnectResult(false, "tunnel_error")
        }
    }

    // ---- grace cache: remember the last successful enrollment --------------
    private fun saveEnroll(ctx: Context, e: EnrollClient.Result) {
        val j = JSONObject()
            .put("ip", e.assignedIp).put("endpoint", e.endpoint)
            .put("spk", e.serverPublicKey).put("dns", e.dns)
            .put("jc", e.jc).put("jmin", e.jmin).put("jmax", e.jmax)
            .put("s1", e.s1).put("s2", e.s2)
            .put("h1", e.h1).put("h2", e.h2).put("h3", e.h3).put("h4", e.h4)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENROLL, j.toString())
            .putLong(KEY_ENROLL_AT, System.currentTimeMillis())
            .remove(KEY_REFUSED)
            .apply()
    }

    private fun loadFreshEnroll(ctx: Context): EnrollClient.Result? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_ENROLL_AT, 0L)
        // Also refuses a time in the future: setting the clock back used to keep the grace open.
        if (!LicenceGate.confirmedRecently(System.currentTimeMillis(), at)) return null
        val raw = prefs.getString(KEY_ENROLL, null) ?: return null
        return try {
            val j = JSONObject(raw)
            EnrollClient.Result(
                ok = true, status = "grace",
                assignedIp = j.getString("ip"), endpoint = j.getString("endpoint"),
                serverPublicKey = j.getString("spk"), dns = j.optString("dns", "9.9.9.9"),
                jc = j.getInt("jc"), jmin = j.getInt("jmin"), jmax = j.getInt("jmax"),
                s1 = j.getInt("s1"), s2 = j.getInt("s2"),
                h1 = j.getLong("h1"), h2 = j.getLong("h2"), h3 = j.getLong("h3"), h4 = j.getLong("h4")
            )
        } catch (_: Exception) { null }
    }

    /** What the server said when it last refused this account, or null if it hasn't. */
    fun licenceRefusal(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REFUSED, null)
            ?.takeIf { it.isNotEmpty() }

    /**
     * Check the account again while the tunnel is up: once a day (or now, with [force]), at most
     * hourly while there is no answer. Called from Home and from LeakMonitor, so a tunnel running
     * with no screen open is checked too. Only a clear "no" from the server stops protection
     * (see LicenceGate); anything else keeps the tunnel and is tried again later.
     */
    suspend fun recheckLicence(ctx: Context, force: Boolean = false): LicenceGate.Recheck = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && !LicenceGate.recheckDue(now, prefs.getLong(KEY_VALIDATED, 0L), prefs.getLong(KEY_CHECK_TRIED, 0L)))
            return@withContext LicenceGate.Recheck.NOT_DUE
        prefs.edit().putLong(KEY_CHECK_TRIED, now).apply()
        val acct = SecureAccountStore.read(prefs)
        if (acct.isEmpty()) {
            // The saved account couldn't be read (a keystore problem on some phones). That is
            // a problem on this phone, not an answer about the account: keep the tunnel.
            Log.w(TAG, "Account check skipped: saved account could not be read")
            return@withContext LicenceGate.Recheck.RETRY
        }
        val res = LicenseClient.validate(ctx, acct)
        val outcome = LicenceGate.outcome(res.fromServer, res.valid, res.status)
        when (outcome) {
            LicenceGate.Recheck.OK -> prefs.edit()
                .putLong(KEY_VALIDATED, now).putInt(KEY_DAYS_LEFT, res.daysLeft).remove(KEY_REFUSED).apply()
            LicenceGate.Recheck.REJECTED ->
                forgetTunnel(ctx, LicenceGate.checkStatus(res.fromServer, res.valid, res.status))
            else -> Log.i(TAG, "Account check got no usable answer (${res.status}); keeping the tunnel")
        }
        outcome
    }

    /**
     * After the server said no ([status]): tunnel down, tunnel and KAPO.conf deleted, enrolment
     * cache and last check dropped, the answer remembered so nothing raises the tunnel again
     * until a new connect is accepted, and, if protection was on, a notification saying it is
     * now off. (A first connect that is refused shows its reason on Home instead.)
     * The account itself stays, so the user can renew and connect again.
     */
    suspend fun forgetTunnel(ctx: Context, status: String) = withContext(Dispatchers.IO) {
        val mgr = Application.getTunnelManager()
        val existing = mgr.getTunnels().firstOrNull { it.name == TUNNEL_NAME }
        // Protection was on (or meant to be): then this really turns it off, and says so.
        val wasOn = isWanted(ctx) || existing?.state == Tunnel.State.UP
        setWanted(ctx, false)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REFUSED, status).commit()
        existing?.let { tunnel ->
            try {
                if (tunnel.state == Tunnel.State.UP) tunnel.setStateAsync(Tunnel.State.DOWN)
            } catch (_: Exception) { }
            try {
                mgr.delete(tunnel)
            } catch (_: Exception) { }
        }
        try {
            File(ctx.filesDir, "$TUNNEL_NAME.conf").delete()
        } catch (_: Exception) { }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_ENROLL).remove(KEY_ENROLL_AT).remove(KEY_VALIDATED).commit()
        if (wasOn) LeakMonitor.notifyProtectionStopped(ctx, accountProblemText(status))
        Unit
    }

    /** Plain words for a refusal, for the notification and the Home screen. */
    fun accountProblemText(status: String): String = when (status) {
        "device_limit" -> "KAPO VPN is off: this account is already used on its maximum number of devices."
        "revoked" -> "KAPO VPN is off: this account has been disabled."
        "not_found" -> "KAPO VPN is off: this account was not found."
        else -> "KAPO VPN is off: your account has expired. Add more time at kapovpn.com."
    }

    fun isWanted(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WANTED, false)

    fun setWanted(ctx: Context, wanted: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WANTED, wanted).apply()
    }

    /** At a restore: fill in "wanted" when an older version never saved it (LicenceGate.wantedOnRestore). */
    fun adoptWantedOnRestore(ctx: Context, wasRunning: Boolean) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = if (prefs.contains(KEY_WANTED)) prefs.getBoolean(KEY_WANTED, false) else null
        if (stored == null) setWanted(ctx, LicenceGate.wantedOnRestore(null, wasRunning))
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        // Also when the tunnel is already down (it dropped): the user has now said "off".
        setWanted(Application.get(), false)
        val mgr = Application.getTunnelManager()
        mgr.getTunnels().firstOrNull { it.name == TUNNEL_NAME }
            ?.setStateAsync(Tunnel.State.DOWN)
        Unit
    }

    /**
     * Panic/duress wipe: tear the tunnel down, delete its on-disk config
     * (FileConfigStore persists the private key/assigned IP/obfuscation
     * params separately from kapo_prefs - a plain prefs.clear() alone would
     * leave that file behind), and erase every KAPO SharedPreferences key
     * (the encrypted account credential, WireGuard keypair, enrollment
     * cache). No confirmation, no toast - a real duress action can't ask
     * "are you sure?" or announce what it did. The caller is responsible for
     * resetting in-memory UI state (KapoState) and navigating to login.
     */
    suspend fun panicWipe(ctx: Context) = withContext(Dispatchers.IO) {
        wipeLocalData(ctx, alsoLogs = true)
    }

    /**
     * Sign out: the same wipe as panicWipe, except that logs the user saved to Downloads stay
     * (an export zip with keys still goes). Logout used to clear only kapo_prefs, so the
     * signed-out account's tunnel (KAPO.conf with the WireGuard private key, endpoint and
     * address) stayed on the phone and the quick tile or the next app start could raise it.
     */
    suspend fun logout(ctx: Context) = withContext(Dispatchers.IO) {
        wipeLocalData(ctx, alsoLogs = false)
    }

    private suspend fun wipeLocalData(ctx: Context, alsoLogs: Boolean) {
        val mgr = Application.getTunnelManager()
        mgr.getTunnels().firstOrNull { it.name == TUNNEL_NAME }?.let { tunnel ->
            try {
                if (tunnel.state == Tunnel.State.UP) tunnel.setStateAsync(Tunnel.State.DOWN)
            } catch (_: Exception) { }
            try {
                mgr.delete(tunnel)   // also forgets it as the last used tunnel
            } catch (_: Exception) {
                // Even if config deletion fails, still wipe the credential below -
                // a stray on-disk tunnel config with no account behind it is far
                // less sensitive than the account itself surviving a wipe.
            }
        }
        // The file itself, in case the delete above failed or the tunnel list never loaded.
        try {
            File(ctx.filesDir, "$TUNNEL_NAME.conf").delete()
        } catch (_: Exception) { }
        // commit(), not apply(): the caller shows the login screen next, and the app may be
        // closed right after; the wipe must be on disk by then.
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        deleteOwnDownloads(ctx, alsoLogs)
    }

    /**
     * Copies this app itself put in Downloads: an old "export tunnels" zip (every tunnel's
     * private key, in plain text) and, with [alsoLogs], saved logs. Android 10+ only lists an app's own entries
     * in Downloads, so nothing else the user saved is touched. Best effort: after a reinstall
     * Android no longer counts old files as this app's, and those must be deleted by hand.
     */
    private fun deleteOwnDownloads(ctx: Context, alsoLogs: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val patterns = if (alsoLogs) arrayOf("amneziawg-export%.zip", "amneziawg-log%.txt")
                       else arrayOf("amneziawg-export%.zip")
        for (pattern in patterns) {
            try {
                ctx.contentResolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf(pattern)
                )
            } catch (_: Exception) { }
        }
    }

    suspend fun isUp(): Boolean = withContext(Dispatchers.IO) {
        val mgr = Application.getTunnelManager()
        mgr.getTunnels().firstOrNull { it.name == TUNNEL_NAME }?.state == Tunnel.State.UP
    }

    /** Live (tx, rx) byte totals, or null if the tunnel is down. */
    suspend fun stats(): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val mgr = Application.getTunnelManager()
        val tunnel = mgr.getTunnels().firstOrNull { it.name == TUNNEL_NAME } ?: return@withContext null
        if (tunnel.state != Tunnel.State.UP) return@withContext null
        try {
            val s = tunnel.getStatisticsAsync()
            Pair(s.totalTx(), s.totalRx())
        } catch (_: Exception) { null }
    }
}
