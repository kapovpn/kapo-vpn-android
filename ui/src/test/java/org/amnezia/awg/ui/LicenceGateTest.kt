package org.amnezia.awg.ui

import org.amnezia.awg.ui.LicenceGate.Recheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenceGateTest {
    private val hour = 60L * 60 * 1000
    private val now = 1_000_000L * hour

    @Test
    fun confirmationInsideTheWindowCounts() {
        assertTrue(LicenceGate.confirmedRecently(now, now - 71 * hour))
        assertTrue(LicenceGate.confirmedRecently(now, now))
    }

    @Test
    fun confirmationOutsideTheWindowDoesNot() {
        assertFalse(LicenceGate.confirmedRecently(now, now - 73 * hour))
        assertFalse(LicenceGate.confirmedRecently(now, 0L))
    }

    @Test
    fun aConfirmationInTheFutureDoesNotCount() {
        // A clock set back must not stretch the window for reusing the last enrolment.
        assertFalse(LicenceGate.confirmedRecently(now, now + hour))
    }

    @Test
    fun recheckIsDueOnceADay() {
        assertFalse(LicenceGate.recheckDue(now, now - 23 * hour, 0L))
        assertTrue(LicenceGate.recheckDue(now, now - 25 * hour, 0L))
        assertTrue(LicenceGate.recheckDue(now, 0L, 0L))
        assertTrue(LicenceGate.recheckDue(now, now + hour, 0L))
    }

    @Test
    fun noAnswerIsAskedAgainHourlyNotEveryMinute() {
        val stale = now - 30 * hour
        assertFalse(LicenceGate.recheckDue(now, stale, now - 10 * 60 * 1000L))
        assertTrue(LicenceGate.recheckDue(now, stale, now - 61 * 60 * 1000L))
        assertTrue(LicenceGate.recheckDue(now, stale, now + hour))   // clock set back
    }

    @Test
    fun explicitNoFromTheServerStopsProtection() {
        for (s in listOf("expired", "revoked", "not_found", "device_limit"))
            assertEquals(s, Recheck.REJECTED, LicenceGate.outcome(fromServer = true, valid = false, status = s))
    }

    @Test
    fun paidTimeEndedStopsProtection() {
        // valid=false with an active status means the paid time has ended.
        assertEquals(Recheck.REJECTED, LicenceGate.outcome(fromServer = true, valid = false, status = "active"))
        assertEquals("expired", LicenceGate.checkStatus(fromServer = true, valid = false, status = "active"))
    }

    @Test
    fun aReplyWithNoStatusStaysTryLater() {
        // "Too many tries" comes back with no status, which LicenseClient reads as "error".
        assertEquals(Recheck.RETRY, LicenceGate.outcome(fromServer = true, valid = false, status = "error"))
        assertEquals("error", LicenceGate.checkStatus(fromServer = true, valid = false, status = "error"))
    }

    @Test
    fun aValidActiveAccountKeepsTheTunnel() {
        assertEquals(Recheck.OK, LicenceGate.outcome(fromServer = true, valid = true, status = "active"))
        assertEquals("active", LicenceGate.checkStatus(fromServer = true, valid = true, status = "active"))
        // Not from the server: never turned into "expired".
        assertEquals(Recheck.RETRY, LicenceGate.outcome(fromServer = false, valid = false, status = "active"))
    }

    @Test
    fun renewHintOnlyWhenBlockIsOnAndThePlanEnded() {
        val hint = LicenceGate.renewHint("expired", blockOn = true)
        assertTrue(hint != null && hint.contains("Block connections without VPN") && hint.contains("kapovpn.com"))
        assertEquals(null, LicenceGate.renewHint("expired", blockOn = false))
        assertEquals(null, LicenceGate.renewHint("revoked", blockOn = true))
    }

    @Test
    fun unreachableKeepsTheTunnel() {
        // Offline, a timeout, an outage or a blocked server: LicenseClient's network_error.
        assertEquals(Recheck.RETRY, LicenceGate.outcome(fromServer = false, valid = false, status = "network_error"))
        // However long it lasts: there is no time limit on "no answer".
        assertEquals(Recheck.RETRY, LicenceGate.outcome(false, false, "error"))
    }

    @Test
    fun busyOrRateLimitedMeansTryLater() {
        for (s in listOf("rate_limited", "busy", "pool_exhausted", "unknown_node", "bad_request", "error", "invalid"))
            assertEquals(s, Recheck.RETRY, LicenceGate.outcome(fromServer = true, valid = false, status = s))
        for (s in listOf("rate_limited", "busy", "pool_exhausted"))
            assertFalse(s, LicenceGate.isLicenceNo(true, s))
    }

    @Test
    fun aLocalErrorIsNeverTheServerSayingNo() {
        // "invalid" from the phone's own length check, or a status that looks like a refusal
        // but did not come from the server, must not stop a paying user's tunnel.
        assertEquals(Recheck.RETRY, LicenceGate.outcome(fromServer = false, valid = false, status = "invalid"))
        for (s in listOf("expired", "revoked", "not_found", "device_limit"))
            assertFalse(s, LicenceGate.isLicenceNo(fromServer = false, status = s))
    }

    @Test
    fun aConfirmedAccountIsOk() {
        assertEquals(Recheck.OK, LicenceGate.outcome(fromServer = true, valid = true, status = "active"))
    }

    @Test
    fun autoStartIsOnlyRefusedAfterAnExplicitNo() {
        assertTrue(LicenceGate.mayRaise(null))       // nothing stored: an update from an older version
        assertTrue(LicenceGate.mayRaise(""))
        assertFalse(LicenceGate.mayRaise("expired"))
        assertFalse(LicenceGate.mayRaise("revoked"))
    }

    @Test
    fun upgradeKeepsARunningTunnelWanted() {
        // An older version never saved "wanted": a tunnel that was running counts as wanted,
        // so a failed restore after the update raises an alert instead of nothing.
        assertTrue(LicenceGate.wantedOnRestore(stored = null, wasRunning = true))
        assertFalse(LicenceGate.wantedOnRestore(stored = null, wasRunning = false))
        // A saved choice always wins.
        assertFalse(LicenceGate.wantedOnRestore(stored = false, wasRunning = true))
        assertTrue(LicenceGate.wantedOnRestore(stored = true, wasRunning = false))
    }
}
