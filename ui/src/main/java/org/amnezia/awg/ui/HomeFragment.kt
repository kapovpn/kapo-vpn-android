package org.amnezia.awg.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.R
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.databinding.FragmentHomeBinding
import org.amnezia.awg.util.SecureAccountStore

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED }
    var state = ConnState.DISCONNECTED
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var durationSeconds = 0

    companion object {
        private const val LICENSE_RECHECK_MS = 86_400_000L // once a day, per LicenseClient's own doc comment
    }

    // The chain (entry..exit) the live tunnel was built with, to detect a change
    // in location or hop count and reconnect on the new route.
    private var connectedChain: List<String>? = null
    // Ad-block state the live tunnel was built with, to detect a toggle change.
    private var connectedAdblock: Boolean = false

    // Android's one-time "allow this app to set up a VPN?" consent. When the
    // user approves, we proceed with the actual tunnel bring-up.
    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) doConnect()
            else revertToDisconnected("VPN permission is required to connect")
        }

    private val hopCount get() = KapoState.hopCount

    private val onStateChanged: () -> Unit = {
        _binding?.let {
            applyHopUI(KapoState.hopCount)
            // If the user changed location, hop count, or ad-block while
            // connected, reconnect on the new route so the change takes effect.
            if (state == ConnState.CONNECTED && connectedChain != null &&
                (connectedChain != KapoState.buildChain() ||
                 connectedAdblock != KapoState.adBlock)) {
                switchServer()
            }
        }
    }

    private val durationRunnable = object : Runnable {
        override fun run() {
            durationSeconds++
            binding.tvTimer.text = formatDuration(durationSeconds)

            // Real tunnel counters from GoBackend.
            viewLifecycleOwner.lifecycleScope.launch {
                val s = KapoVpn.stats()
                if (_binding != null && s != null) {
                    // Traffic totals live on the Stats screen; Home just feeds them.
                    KapoState.setTraffic(s.first, s.second)
                }
            }

            // Cheap local check every minute of wall-clock ticks (not every second -
            // no need to hit SharedPreferences that often); the real 24h gate lives
            // inside maybeRecheckLicense() against a persisted timestamp, not this
            // counter, so it survives app restarts. See that function's doc.
            if (durationSeconds % 60 == 0) maybeRecheckLicense()

            handler.postDelayed(this, 1000)
        }
    }

    /**
     * Re-checks the account server-side once a day, matching what LicenseClient's
     * own doc comment has always claimed happens ("once a day while running") but
     * that nothing ever actually called during an active session. Gated on a
     * *persisted* timestamp (kapo_prefs.last_validated - the same field
     * LoginActivity already writes on sign-in), not a fragment-local counter: a
     * counter resets every time the app restarts, which on a tunnel that survives
     * app restarts (restoreConnectionState() trusts KapoVpn.isUp(), never
     * re-enrolls) could delay re-validation indefinitely for a user who closes
     * the app often. A wall-clock timestamp can't be reset that way.
     *
     * Enroll already rejects expired/revoked accounts server-side - this only
     * closes the gap for a session that was already up before that happened.
     */
    private fun maybeRecheckLicense() {
        val prefs = requireContext().getSharedPreferences("kapo_prefs", Context.MODE_PRIVATE)
        val lastValidated = prefs.getLong("last_validated", 0L)
        if (System.currentTimeMillis() - lastValidated < LICENSE_RECHECK_MS) return

        val acct = account()
        viewLifecycleOwner.lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { LicenseClient.validate(requireContext(), acct) }
            if (res.status == "network_error") return@launch // stay silent, try again next tick
            if (res.valid) {
                prefs.edit().putLong("last_validated", System.currentTimeMillis()).putInt("days_left", res.daysLeft).apply()
                return@launch
            }
            if (_binding != null) {
                revertToDisconnected(
                    if (res.status == "revoked") "This account has been disabled."
                    else "Your account has expired. Add more time at kapovpn.com."
                )
            }
        }
    }

    private fun formatDuration(total: Int): String {
        val h = (total / 3600).toString().padStart(2, '0')
        val m = ((total % 3600) / 60).toString().padStart(2, '0')
        val s = (total % 60).toString().padStart(2, '0')
        return "$h:$m:$s"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Three live locations, so real single / double / triple hop.
        setupHopBtn(binding.hop1Btn, 1, "FAST", available = true)
        setupHopBtn(binding.hop2Btn, 2, "2X", available = true)
        setupHopBtn(binding.hop3Btn, 3, "3X", available = true)

        binding.btnConnect.setOnClickListener {
            when (state) {
                ConnState.DISCONNECTED -> startConnection()
                ConnState.CONNECTED -> stopConnection()
                ConnState.CONNECTING -> {}
            }
        }

        KapoState.addListener(onStateChanged)

        // Restore the live session if the tunnel is still up (it survives closing
        // the app); otherwise this just paints the disconnected state.
        restoreConnectionState()
    }

    /**
     * Hop taps work in every state. While connected, picking a different hop
     * count automatically drops the tunnel and reconnects on the new route -
     * users tap 2 HOP expecting it to just happen, so it does.
     */
    private fun setupHopBtn(btn: KapoHopButton, n: Int, label: String, available: Boolean) {
        btn.hopNumber = n
        btn.hopLabel = label
        if (!available) {
            btn.isEnabled2 = false
            btn.alpha = 0.4f
            btn.setOnClickListener {
                Toast.makeText(requireContext(), "More locations coming soon", Toast.LENGTH_SHORT).show()
            }
            return
        }
        btn.setOnClickListener {
            when (state) {
                ConnState.DISCONNECTED -> selectHop(n)
                ConnState.CONNECTED -> if (n != hopCount) switchHop(n)
                ConnState.CONNECTING -> {}
            }
        }
    }

    /** Reconnect on a new route: disconnect, flip the selector, reconnect. */
    private fun switchHop(n: Int) {
        stopConnection()
        selectHop(n)
        binding.tvSub.text = "SWITCHING ROUTE..."
        handler.postDelayed({
            if (state == ConnState.DISCONNECTED && _binding != null) startConnection()
        }, 400)
    }

    private fun selectHop(n: Int) {
        KapoState.setHopCount(n)
        applyHopUI(n)
    }

    private fun applyHopUI(n: Int) {
        val b = _binding ?: return
        b.hop1Btn.hopSelected = n == 1
        b.hop2Btn.hopSelected = n == 2
        b.hop3Btn.hopSelected = n == 3
        b.routeRing.hopCount = n
        if (state == ConnState.CONNECTED) {
            b.tvSub.text = subConnectedText()
            buildRouteList(n)
        }
    }

    // The exit location is whatever the user picked in the Servers tab.
    private fun pingFor(n: Int) = KapoState.selectedPing
    private fun serverFor(n: Int) = KapoState.selectedCity

    private fun subConnectedText(): String =
        serverFor(hopCount).uppercase() + " \u00B7 " + pingFor(hopCount)

    /** One entry per hop for the route list: flag, code, city, ping, accent. */
    private data class Hop(val flag: String, val code: String, val city: String,
                           val ping: String, val color: Int)

    // The real chain: entry .. exit. Each node rendered from the shared catalog.
    private fun routeChain(n: Int): List<Hop> =
        KapoState.buildChain().map { id ->
            val info = KapoState.nodeCatalog[id]
            Hop(
                info?.flag ?: KapoState.selectedFlag,
                info?.code ?: id.uppercase(),
                info?.city ?: id,
                (info?.ping ?: KapoState.selectedPing).lowercase(),
                R.color.green
            )
        }

    /** Rebuild the route list rows for the current hop count. */
    private fun buildRouteList(n: Int) {
        val b = _binding ?: return
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val rounded = android.graphics.Typeface.create("sans-serif-rounded", android.graphics.Typeface.BOLD)
        b.routeList.removeAllViews()

        val chain = routeChain(n)
        chain.forEachIndexed { i, hop ->
            val row = android.widget.LinearLayout(ctx)
            row.orientation = android.widget.LinearLayout.HORIZONTAL
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            val rlp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            if (i > 0) rlp.topMargin = dp(10)
            row.layoutParams = rlp

            val step = android.widget.TextView(ctx)
            step.text = (i + 1).toString()
            step.setTextColor(ctx.getColor(hop.color))
            step.textSize = 10f
            step.typeface = rounded
            step.gravity = android.view.Gravity.CENTER
            val stepLp = android.widget.LinearLayout.LayoutParams(dp(20), dp(20))
            stepLp.marginEnd = dp(10)
            step.layoutParams = stepLp
            row.addView(step)

            val flag = android.widget.TextView(ctx)
            flag.text = hop.flag
            flag.textSize = 18f
            val flagLp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            flagLp.marginEnd = dp(12)
            flag.layoutParams = flagLp
            row.addView(flag)

            val col = android.widget.LinearLayout(ctx)
            col.orientation = android.widget.LinearLayout.VERTICAL
            col.layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val city = android.widget.TextView(ctx)
            city.text = hop.city
            city.setTextColor(ctx.getColor(R.color.text))
            city.textSize = 14f
            city.typeface = rounded
            col.addView(city)
            val role = android.widget.TextView(ctx)
            role.text = when {
                chain.size == 1 -> "Entry \u00B7 Exit"
                i == 0 -> "Entry node"
                i == chain.size - 1 -> "Exit node"
                else -> "Relay node"
            }
            role.setTextColor(ctx.getColor(R.color.text_faint))
            role.textSize = 10f
            role.typeface = android.graphics.Typeface.create("sans-serif-rounded", android.graphics.Typeface.NORMAL)
            col.addView(role)
            row.addView(col)

            val ping = android.widget.TextView(ctx)
            ping.text = hop.ping
            ping.setTextColor(ctx.getColor(hop.color))
            ping.textSize = 13f
            ping.typeface = rounded
            row.addView(ping)

            b.routeList.addView(row)
        }
    }

    private fun account(): String =
        SecureAccountStore.read(requireContext().getSharedPreferences("kapo_prefs", Context.MODE_PRIVATE))

    private fun enrollError(status: String): String = when (status) {
        "not_found"            -> "Account not found. Check your number."
        "expired"              -> "Your plan has expired. Renew to connect."
        "revoked"              -> "This account has been revoked."
        "device_limit"         -> "Device limit reached for this account."
        "network_error"        -> "Cannot reach the server. Check your connection."
        "node_unreachable"     -> "Server node is temporarily unavailable."
        "pool_exhausted"       -> "This location is at capacity. Try another."
        "enroll_unconfigured"  -> "Service not ready yet. Please try again shortly."
        else                   -> "Could not connect ($status)."
    }

    private fun startConnection() {
        state = ConnState.CONNECTING
        binding.btnConnect.state = KapoOrbButton.State.CONNECTING
        binding.routeRing.setMode(KapoRouteRingView.Mode.CONNECTING)
        setStatusUI("CONNECTING...", false)
        binding.tvSub.text = "ESTABLISHING TUNNEL..."

        // First run triggers Android's VPN-consent dialog; after that it's null.
        val prepare = GoBackend.VpnService.prepare(requireContext().applicationContext)
        if (prepare != null) vpnPermissionLauncher.launch(prepare) else doConnect()
    }

    /** Permission is granted; enroll + bring the real tunnel up. */
    private fun doConnect() {
        val acct = account()
        if (acct.isEmpty()) { revertToDisconnected("Please sign in again"); return }
        val chain = KapoState.buildChain()
        val adblock = KapoState.adBlock
        viewLifecycleOwner.lifecycleScope.launch {
            val res = KapoVpn.connect(requireContext().applicationContext, acct, chain, adblock)
            if (_binding == null) return@launch
            if (res.ok) { connectedChain = chain; connectedAdblock = adblock; onConnected() }
            else revertToDisconnected(enrollError(res.status))
        }
    }

    /** User picked a different location while connected: drop + reconnect there. */
    private fun switchServer() {
        stopConnection()
        binding.tvSub.text = "SWITCHING LOCATION..."
        handler.postDelayed({
            if (state == ConnState.DISCONNECTED && _binding != null) startConnection()
        }, 400)
    }

    private fun onConnected() {
        state = ConnState.CONNECTED
        durationSeconds = 0
        binding.tvTimer.text = formatDuration(0)
        binding.tvTimer.visibility = View.VISIBLE
        binding.tvTimerLabel.visibility = View.VISIBLE
        binding.btnConnect.state = KapoOrbButton.State.CONNECTED
        binding.routeRing.setMode(KapoRouteRingView.Mode.ON)
        setStatusUI("PROTECTED", true)
        binding.tvSub.text = subConnectedText()
        buildRouteList(hopCount)
        binding.cardRoute.visibility = View.VISIBLE
        KapoState.setConnected(true)
        persistConnected()
        handler.post(durationRunnable)
        maybeAskBatteryExemption()
    }

    /**
     * Ask Android once to stop battery-optimizing KAPO, so the tunnel keeps
     * running in the background and isn't killed while the screen is off. Only
     * prompts a single time; the foreground service does the rest.
     */
    private fun maybeAskBatteryExemption() {
        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences("kapo_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("batt_asked", false)) return
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            prefs.edit().putBoolean("batt_asked", true).apply(); return
        }
        prefs.edit().putBoolean("batt_asked", true).apply()
        try {
            startActivity(Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:" + ctx.packageName)
            ))
        } catch (_: Exception) {
            // Some OEMs don't expose this intent; the foreground service still runs.
        }
    }

    /** User-initiated disconnect: drop the tunnel, then reset the UI. */
    private fun stopConnection() {
        viewLifecycleOwner.lifecycleScope.launch { KapoVpn.disconnect() }
        resetDisconnectedUi()
    }

    /** Roll back to the disconnected look after a failed connect, with a toast. */
    private fun revertToDisconnected(message: String?) {
        viewLifecycleOwner.lifecycleScope.launch { KapoVpn.disconnect() }
        resetDisconnectedUi()
        message?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() }
    }

    private fun resetDisconnectedUi() {
        if (_binding == null) return
        state = ConnState.DISCONNECTED
        handler.removeCallbacks(durationRunnable)
        durationSeconds = 0

        KapoState.setConnected(false)
        KapoState.resetTraffic()
        clearPersistedConnection()

        binding.btnConnect.state = KapoOrbButton.State.DISCONNECTED
        binding.tvTimer.visibility = View.GONE
        binding.tvTimerLabel.visibility = View.GONE
        binding.routeRing.setMode(KapoRouteRingView.Mode.OFF)
        setStatusUI("NOT PROTECTED", false)
        binding.tvSub.text = "TAP THE SPHERE TO CONNECT"
        binding.cardRoute.visibility = View.GONE
    }

    private fun setStatusUI(text: String, connected: Boolean) {
        binding.tvStatus.text = text
        binding.tvStatus.setTextColor(
            requireContext().getColor(if (connected) R.color.teal_ink else R.color.text_muted)
        )
        binding.statusBadge.setBackgroundResource(
            if (connected) R.drawable.bg_status_badge_on else R.drawable.bg_status_badge
        )
        binding.badgeDot.setBackgroundResource(
            if (connected) R.drawable.bg_badge_dot_on else R.drawable.bg_badge_dot
        )
        // Wordmark "VPN" stays cyan in every state (was flipping magenta/cyan).
        binding.tvLogoVpn.setTextColor(requireContext().getColor(R.color.teal))
    }

    // ---- session persistence: survive an app close/reopen ------------------

    private fun statePrefs() =
        requireContext().getSharedPreferences("kapo_prefs", Context.MODE_PRIVATE)

    /** Remember the active session (hop, exit, start time) so reopening the app
     *  can restore exactly what's running. */
    private fun persistConnected() {
        statePrefs().edit()
            .putInt("conn_hop", hopCount)
            .putLong("conn_started_at", System.currentTimeMillis() - durationSeconds * 1000L)
            .putString("conn_node", KapoState.selectedNode)
            .putString("conn_city", KapoState.selectedCity)
            .putString("conn_code", KapoState.selectedCode)
            .putString("conn_ping", KapoState.selectedPing)
            .putString("conn_flag", KapoState.selectedFlag)
            .apply()
    }

    private fun clearPersistedConnection() {
        statePrefs().edit()
            .remove("conn_hop").remove("conn_started_at")
            .remove("conn_node").remove("conn_city").remove("conn_code")
            .remove("conn_ping").remove("conn_flag")
            .apply()
    }

    /** Repaint Home as CONNECTED from the saved session and start the ticking
     *  timer where it left off. Used when the tunnel is (still) up. */
    private fun applyRestoredConnected() {
        if (_binding == null) return
        val p = statePrefs()
        val savedHop = p.getInt("conn_hop", KapoState.hopCount).coerceIn(1, 3)
        val startedAt = p.getLong("conn_started_at", System.currentTimeMillis())
        p.getString("conn_node", null)?.let { node ->
            KapoState.setSelectedServer(
                node,
                p.getString("conn_city", "Reykjavik")!!,
                p.getString("conn_code", "IS")!!,
                p.getString("conn_ping", "45 MS")!!,
                p.getString("conn_flag", KapoState.selectedFlag)!!
            )
        }
        KapoState.setHopCount(savedHop)
        durationSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).toInt().coerceAtLeast(0)
        state = ConnState.CONNECTED
        connectedChain = KapoState.buildChain()
        connectedAdblock = KapoState.adBlock
        KapoState.setConnected(true)
        restoreUI()
        applyHopUI(savedHop)
        handler.removeCallbacks(durationRunnable)
        handler.post(durationRunnable)
    }

    /**
     * On open, show the truth: the real AmneziaWG tunnel state is authoritative.
     * If it's up (it keeps running after the app is closed), restore the
     * connected view with the saved hop count + a live timer; otherwise show
     * disconnected. This way the UI can never claim you're off while the tunnel
     * is actually protecting you - or vice versa.
     */
    private fun restoreConnectionState() {
        val startedAt = statePrefs().getLong("conn_started_at", 0L)
        val looksConnected = KapoState.connected || startedAt > 0L

        if (looksConnected) applyRestoredConnected()
        else { restoreUI(); applyHopUI(KapoState.hopCount) }

        // Confirm against the actual tunnel and correct any mismatch.
        viewLifecycleOwner.lifecycleScope.launch {
            val up = KapoVpn.isUp()
            if (_binding == null) return@launch
            if (up && state != ConnState.CONNECTED) {
                applyRestoredConnected()
            } else if (!up && state == ConnState.CONNECTED) {
                resetDisconnectedUi()   // also clears the saved session
            }
        }

        // A tunnel found already up here was never re-enrolled (isUp() above just
        // asks the real backend, it doesn't touch the license server) - it may
        // have been sitting connected across app restarts since before it expired.
        // Check immediately rather than waiting for the next once-a-minute tick.
        if (looksConnected) maybeRecheckLicense()
    }

    private fun restoreUI() {
        when (state) {
            ConnState.DISCONNECTED -> {
                binding.btnConnect.state = KapoOrbButton.State.DISCONNECTED
                binding.routeRing.setMode(KapoRouteRingView.Mode.OFF)
                setStatusUI("NOT PROTECTED", false)
                binding.tvSub.text = "TAP THE SPHERE TO CONNECT"
                binding.cardRoute.visibility = View.GONE
            }
            ConnState.CONNECTING -> {
                binding.btnConnect.state = KapoOrbButton.State.CONNECTING
                binding.tvSub.text = "ESTABLISHING TUNNEL..."
            }
            ConnState.CONNECTED -> {
                binding.btnConnect.state = KapoOrbButton.State.CONNECTED
                binding.tvTimer.text = formatDuration(durationSeconds)
                binding.tvTimer.visibility = View.VISIBLE
                binding.tvTimerLabel.visibility = View.VISIBLE
                binding.routeRing.setMode(KapoRouteRingView.Mode.ON)
                setStatusUI("PROTECTED", true)
                buildRouteList(hopCount)
                binding.cardRoute.visibility = View.VISIBLE
                binding.tvSub.text = subConnectedText()
            }
        }
    }

    /** Real tunnel stats, once GoBackend is wired in. */
    fun updateRealStats(up: Long, down: Long) {
        // Totals surface on the Stats screen; keep the shared state fed.
        KapoState.setTraffic(up, down)
    }

    override fun onDestroyView() {
        KapoState.removeListener(onStateChanged)
        handler.removeCallbacksAndMessages(null)
        _binding = null
        super.onDestroyView()
    }
}
