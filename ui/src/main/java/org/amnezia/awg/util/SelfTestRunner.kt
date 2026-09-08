/*
 * Copyright © 2026 KAPO VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.util

import org.amnezia.awg.Application
import org.amnezia.awg.backend.Tunnel
import java.net.HttpURLConnection
import java.net.URL

/**
 * A handful of read-only checks a user can run to sanity-check their
 * connection instead of only trusting the "Connected" badge. Every check is
 * passive - it observes state, it never touches the tunnel.
 *
 * v1 is deliberately small: it confirms a tunnel is actually up, and that the
 * device can still reach the KAPO API through it. It does NOT yet do a real
 * DNS-leak test or confirm the egress IP matches the connected node - both
 * need a bit more plumbing (a public "what's my IP" style endpoint on the
 * server, and a reliable way to read the active network's effective DNS
 * servers across API levels) and are the natural next additions here, not a
 * reason to hold this back.
 */
object SelfTestRunner {

    data class CheckResult(val title: String, val passed: Boolean, val detail: String)

    /** Runs every check in order. Must be called from a coroutine (getTunnels() suspends). */
    suspend fun runAll(): List<CheckResult> = listOf(
        tunnelUp(),
        canReachKapoApi(),
    )

    private suspend fun tunnelUp(): CheckResult {
        val active = Application.getTunnelManager().getTunnels().firstOrNull { it.state == Tunnel.State.UP }
        return if (active != null) {
            CheckResult(TITLE_TUNNEL_UP, true, "Connected: ${active.name}")
        } else {
            CheckResult(TITLE_TUNNEL_UP, false, "No tunnel is currently connected")
        }
    }

    /**
     * Confirms the device can still reach the KAPO control-plane API. This is
     * a basic "traffic isn't silently failing" signal, not a leak test by
     * itself - see the class doc for what's still missing.
     */
    private fun canReachKapoApi(): CheckResult {
        return try {
            val conn = (URL("https://api.kapovpn.com/api/v1/health").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
            }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            CheckResult(TITLE_REACHABLE, ok, if (ok) "Reached api.kapovpn.com" else "Unexpected response (HTTP ${conn.responseCode})")
        } catch (e: Exception) {
            CheckResult(TITLE_REACHABLE, false, e.message ?: "Network error")
        }
    }

    // Kept as plain constants (rather than string-resource lookups) so this
    // object has no Context dependency and stays trivially unit-testable.
    // The activity is free to map these back to @string entries if it needs
    // localized titles; the *content* of the check doesn't depend on it.
    private const val TITLE_TUNNEL_UP = "Tunnel is up"
    private const val TITLE_REACHABLE = "Can reach KAPO servers"
}
