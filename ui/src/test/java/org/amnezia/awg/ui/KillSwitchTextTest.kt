package org.amnezia.awg.ui

import org.amnezia.awg.backend.GoBackend.KillSwitchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KillSwitchTextTest {
    @Test
    fun onlyLockdownCountsAsOn() {
        assertTrue(KillSwitchText.isOn(KillSwitchState.ON))
        assertFalse(KillSwitchText.isOn(KillSwitchState.ALWAYS_ON_ONLY))
        assertFalse(KillSwitchText.isOn(KillSwitchState.OFF))
        assertFalse(KillSwitchText.isOn(KillSwitchState.UNKNOWN))
    }

    @Test
    fun neverClaimsAlwaysOn() {
        // The old row said "Always on - blocks traffic if the tunnel drops" whatever Android's
        // setting was. No state may say that again.
        for (s in KillSwitchState.values()) {
            val text = KillSwitchText.describe(s)
            assertFalse(text, text.contains("Always on -"))
            assertTrue(text.isNotBlank())
        }
    }

    @Test
    fun oldAndroidIsNotAskedToConnect() {
        // Below Android 10 the state is always unknown, even while connected.
        val old = KillSwitchText.describe(KillSwitchState.UNKNOWN, 28)
        assertFalse(old, old.startsWith("Connect to check"))
        assertTrue(KillSwitchText.describe(KillSwitchState.UNKNOWN, 29).startsWith("Connect to check"))
    }

    @Test
    fun offStatesSayOff() {
        assertTrue(KillSwitchText.describe(KillSwitchState.OFF).startsWith("Off"))
        assertTrue(KillSwitchText.describe(KillSwitchState.ALWAYS_ON_ONLY).startsWith("Off"))
        assertEquals(true, KillSwitchText.describe(KillSwitchState.ON).startsWith("On"))
    }
}
