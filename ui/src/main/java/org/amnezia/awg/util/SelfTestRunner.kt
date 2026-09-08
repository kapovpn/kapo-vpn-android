/*
 * Copyright © 2026 KAPO VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.util

import org.amnezia.awg.Application
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.ui.KapoState
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A handful of read-only checks a user can run to sanity-check their
 * connection instead of only trusting the "Connected" badge. Every check is
 * passive - it observes state, it never touches the tunnel.
 *
 * v1 shipped with just two checks (tunnel up, API reachable). This adds a
 * real leak check: whether the connection's apparent exit really is the node
 * KAPO thinks it enrolled with (server-side /whereami maps the caller's IP
 * back to a node id - see kapo-license-server - so this never needs to know
 * any real node IP itself). A genuine DNS-leak test (which resolver actually
 * answered a lookup) still needs its own authoritative-side infrastructure
 * and isn't attempted here.
 */
object SelfTestRunner {

    data class CheckResult(val title: String, val passed: Boolean, val detail: String)

    /** Runs every check in order. Must be called from a coroutine (getTunnels() suspends). */
    suspend fun runAll(): List<CheckResult> = listOf(
        tunnelUp(),
        canReachKapoApi(),
        exitNodeMatches(),
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

    /**
     * Asks the server which known node the request's own apparent IP matches
     * (via /whereami - never a raw IP, just an id) and compares it against
     * the exit KapoState.buildChain() says we enrolled with. A mismatch (or
     * no match at all) means traffic isn't really leaving through KAPO - the
     * one thing a "Connected" badge alone can't catch.
     */
    private fun exitNodeMatches(): CheckResult {
        val expected = KapoState.buildChain().lastOrNull()
        return try {
            val conn = (URL("https://api.kapovpn.com/api/v1/whereami").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val node = JSONObject(body).optJSONObject("node")
            val actual = node?.optString("id")
            when {
                actual == null -> CheckResult(TITLE_EXIT, false, "Your traffic doesn't appear to be exiting through a KAPO node")
                actual == expected -> CheckResult(TITLE_EXIT, true, "Exiting via ${node.optString("city")}, ${node.optString("country")}")
                else -> CheckResult(TITLE_EXIT, false, "Expected to exit via $expected, but it's $actual")
            }
        } catch (e: Exception) {
            CheckResult(TITLE_EXIT, false, e.message ?: "Network error")
        }
    }

    // Kept as plain constants (rather than string-resource lookups) so this
    // object has no Context dependency and stays trivially unit-testable.
    // The activity is free to map these back to @string entries if it needs
    // localized titles; the *content* of the check doesn't depend on it.
    private const val TITLE_TUNNEL_UP = "Tunnel is up"
    private const val TITLE_REACHABLE = "Can reach KAPO servers"
    private const val TITLE_EXIT = "Exit node matches your selection"
}
