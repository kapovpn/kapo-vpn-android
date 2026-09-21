package org.amnezia.awg.ui

/**
 * Single source of truth for anything more than one screen needs to agree on.
 *
 * Before this, Home kept its own byte counters and Stats kept its own, so Stats
 * sat at 0.0 forever; and Multi-Hop was a separate Boolean that could say
 * "route through 2 servers" while the hop selector was on 1. Both screens now
 * read and write here, and anyone can listen for changes.
 *
 * Deliberately a plain object rather than a ViewModel: fragments here are kept
 * alive with hide/show, and this needs to survive independently of any one of
 * them anyway.
 */
object KapoState {

    // ── traffic ──
    var uploadBytes = 0L
        private set
    var downloadBytes = 0L
        private set

    /** Instantaneous rate in MB/s, derived from successive updates. */
    var uploadRate = 0f
        private set
    var downloadRate = 0f
        private set

    private var lastUpdateMs = 0L

    // ── connection ──
    var connected = false
        private set

    /** 1, 2 or 3. Multi-Hop is just "more than 1", not a separate flag. */
    var hopCount = 1
        private set

    // ── protection toggles ──
    // Kill switch and DNS protection are always-on facts about the tunnel
    // (full-tunnel routing + setBlocking(true) in GoBackend; DNS is always
    // the server-assigned resolver), not settings - so they live only as
    // fixed copy in StatsFragment, not as state here. See its setupAlwaysOn().
    /** Ad/tracker blocking. OFF by default; when the tunnel is up, flipping this
     *  reconnects so the node hands out (or stops handing out) its filtering DNS. */
    var adBlock = false
        private set
    /** Adult-content blocking. OFF by default. The node's adult resolver is
     *  chained behind its ad-block resolver, so this can't be on without
     *  [adBlock] also being on - enforced here, not just server-side. */
    var blockAdult = false
        private set
    var protocol = 0                // 0 = AmneziaWG, 1 = VLESS, 2 = Auto

    // ── split tunneling ──
    // Mirrors Interface's own two-list model (ExcludedApplications /
    // IncludedApplications - see KapoVpn.buildConfig()): at most one of the
    // two sets is ever populated. Empty both = every app uses the tunnel,
    // matching AppListDialogFragment's own "all apps" convention.
    var splitTunnelApps: Set<String> = emptySet(); private set
    var splitTunnelExcluded: Boolean = true; private set

    fun setSplitTunnelApps(apps: Set<String>, excluded: Boolean) {
        if (splitTunnelApps == apps && splitTunnelExcluded == excluded) return
        splitTunnelApps = apps
        splitTunnelExcluded = excluded
        notifyChanged()
    }

    fun setAdBlock(v: Boolean) {
        if (adBlock == v) return
        adBlock = v
        if (!adBlock) blockAdult = false
        notifyChanged()
    }

    fun setBlockAdult(v: Boolean) {
        if (blockAdult == v) return
        blockAdult = v
        if (blockAdult) adBlock = true
        notifyChanged()
    }

    // ── selected exit location (which node the app enrolls with) ──
    var selectedNode = "is"; private set
    var selectedCity = "Reykjavik"; private set
    var selectedCode = "IS"; private set
    var selectedPing = "45 MS"; private set
    var selectedFlag = "\uD83C\uDDEE\uD83C\uDDF8"; private set

    fun setSelectedServer(node: String, city: String, code: String, ping: String, flag: String) {
        if (node == selectedNode) return
        selectedNode = node; selectedCity = city; selectedCode = code
        selectedPing = ping; selectedFlag = flag
        notifyChanged()
    }

    val multiHop: Boolean get() = hopCount > 1

    // ── multi-hop: the live locations and how we build a chain ──
    data class NodeInfo(val id: String, val city: String, val code: String, val flag: String, val ping: String)

    /** The currently live exit nodes, in a stable preference order for auto-
     *  filling the entry/relay hops of a multi-hop chain. */
    val activeOrder = listOf("is", "fi", "my")
    val nodeCatalog = mapOf(
        "is" to NodeInfo("is", "Reykjavik",    "IS", "\uD83C\uDDEE\uD83C\uDDF8", "45 MS"),
        "fi" to NodeInfo("fi", "Helsinki",     "FI", "\uD83C\uDDEB\uD83C\uDDEE", "52 MS"),
        "my" to NodeInfo("my", "Kuala Lumpur", "MY", "\uD83C\uDDF2\uD83C\uDDFE", "182 MS"),
    )

    /** Build the ordered node chain (entry .. exit) from the selected exit and
     *  the hop count. The selected location is always the EXIT (where you appear);
     *  entry/relay hops are auto-filled from the other live locations. */
    fun buildChain(): List<String> {
        val exit = if (nodeCatalog.containsKey(selectedNode)) selectedNode else "is"
        val others = activeOrder.filter { it != exit }
        return when (hopCount) {
            3 -> if (others.size >= 2) listOf(others[0], others[1], exit) else listOf(exit)
            2 -> if (others.isNotEmpty()) listOf(others[0], exit) else listOf(exit)
            else -> listOf(exit)
        }
    }

    // ── listeners ──
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() { listeners.toList().forEach { it() } }

    // ── mutations ──

    /** Absolute cumulative byte counts, straight from the tunnel. */
    fun setTraffic(up: Long, down: Long) {
        val now = System.currentTimeMillis()
        val dt = (now - lastUpdateMs) / 1000f
        if (lastUpdateMs != 0L && dt > 0.05f) {
            val dUp = (up - uploadBytes).coerceAtLeast(0L)
            val dDown = (down - downloadBytes).coerceAtLeast(0L)
            uploadRate = dUp / dt / 1_048_576f
            downloadRate = dDown / dt / 1_048_576f
        }
        lastUpdateMs = now
        uploadBytes = up
        downloadBytes = down
        notifyChanged()
    }

    fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        if (!value) {
            uploadRate = 0f
            downloadRate = 0f
        }
        notifyChanged()
    }

    fun setHopCount(value: Int) {
        val v = value.coerceIn(1, 3)
        if (hopCount == v) return
        hopCount = v
        notifyChanged()
    }

    /** Panic/duress wipe: reset every in-memory field to its fresh-install
     *  default, not just connection/traffic. KapoState is a process-lifetime
     *  singleton (not SharedPreferences-backed), so KapoVpn.panicWipe()
     *  clearing prefs alone leaves stale toggle state (e.g. "3 apps
     *  excluded") visible the moment someone logs back in - a forensic hint
     *  a real wipe shouldn't leave behind. */
    fun panicReset() {
        connected = false
        uploadBytes = 0L; downloadBytes = 0L
        uploadRate = 0f; downloadRate = 0f
        lastUpdateMs = 0L
        hopCount = 1
        adBlock = false
        blockAdult = false
        protocol = 0
        splitTunnelApps = emptySet()
        splitTunnelExcluded = true
        selectedNode = "is"; selectedCity = "Reykjavik"; selectedCode = "IS"
        selectedPing = "45 MS"; selectedFlag = "🇮🇸"
        notifyChanged()
    }

    fun resetTraffic() {
        uploadBytes = 0L
        downloadBytes = 0L
        uploadRate = 0f
        downloadRate = 0f
        lastUpdateMs = 0L
        notifyChanged()
    }

    // ── formatting, shared so both screens render identically ──

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /** Totals for the Stats tiles, in MB, but with enough precision that a few
     *  hundred KB doesn't display as a flat "0". */
    fun toMegabytes(bytes: Long): String {
        val mb = bytes / 1_048_576.0
        return when {
            mb >= 100 -> "%.0f".format(mb)
            mb >= 10 -> "%.1f".format(mb)
            else -> "%.2f".format(mb)
        }
    }
}
