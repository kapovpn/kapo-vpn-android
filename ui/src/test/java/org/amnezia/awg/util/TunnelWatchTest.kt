package org.amnezia.awg.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelWatchTest {
    @Test
    fun upAlwaysRunsTheChecks() {
        assertEquals(TunnelWatch.Action.RUN_CHECKS, TunnelWatch.decide(up = true, wanted = true))
        assertEquals(TunnelWatch.Action.RUN_CHECKS, TunnelWatch.decide(up = true, wanted = false))
    }

    @Test
    fun downWhileWantedIsADrop() {
        // The case the monitor used to skip: it returned as soon as the tunnel was down.
        assertEquals(TunnelWatch.Action.ALERT_DROPPED, TunnelWatch.decide(up = false, wanted = true))
    }

    @Test
    fun downBecauseTheUserSaidSoIsQuiet() {
        assertEquals(TunnelWatch.Action.IDLE, TunnelWatch.decide(up = false, wanted = false))
    }
}
