package org.amnezia.awg.ui

import android.content.Context
import org.amnezia.awg.Application
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import org.amnezia.awg.crypto.Key
import org.amnezia.awg.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * The bridge between the KAPO UI and the real AmneziaWG engine.
 *
 * The device keypair is generated once and kept on the phone; only the public
 * key is ever sent to the server (via EnrollClient). connect() enrolls, builds
 * an AmneziaWG config from the server's response, and brings the tunnel up
 * through the app's existing, tested TunnelManager / GoBackend stack.
 */
object KapoVpn {
    private const val TUNNEL_NAME = "KAPO"
    private const val PREFS = "kapo_prefs"
    private const val KEY_PRIV = "wg_private_key"
    private const val KEY_ENROLL = "enroll_cache"
    private const val KEY_ENROLL_AT = "enroll_cache_at"

    // How long we trust the last good enrollment when the control plane is
    // briefly unreachable, so a paying user is never locked out by a hiccup.
    private const val GRACE_MS = 72L * 60 * 60 * 1000

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
            e.status in TRANSIENT -> {
                // Server/node briefly unreachable: reuse the last good enrollment
                // if it is still within the grace window. The peer is already
                // registered on the node, so this reconnects cleanly.
                val cached = loadFreshEnroll(ctx)
                    ?: return@withContext ConnectResult(false, e.status)
                effective = cached; statusOut = "grace"
            }
            else -> {
                // Definitive licence rejection (not_found/revoked/expired/
                // device_limit): block AND drop the cache so grace can't be
                // used to keep a revoked account online.
                clearEnroll(ctx)
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
            .apply()
    }

    private fun loadFreshEnroll(ctx: Context): EnrollClient.Result? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_ENROLL_AT, 0L)
        if (at == 0L || System.currentTimeMillis() - at > GRACE_MS) return null
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

    private fun clearEnroll(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_ENROLL).remove(KEY_ENROLL_AT).apply()
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
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
        val mgr = Application.getTunnelManager()
        mgr.getTunnels().firstOrNull { it.name == TUNNEL_NAME }?.let { tunnel ->
            try {
                if (tunnel.state == Tunnel.State.UP) tunnel.setStateAsync(Tunnel.State.DOWN)
                mgr.delete(tunnel)
            } catch (_: Exception) {
                // Even if config deletion fails, still wipe the credential below -
                // a stray on-disk tunnel config with no account behind it is far
                // less sensitive than the account itself surviving a wipe.
            }
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        Unit
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
