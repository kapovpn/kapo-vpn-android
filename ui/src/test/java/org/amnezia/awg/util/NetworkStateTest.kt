package org.amnezia.awg.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkStateTest {
    // Build.VERSION_CODES.S is a compile-time constant (31), so it is inlined into the test.
    private val android11 = 30
    private val android12 = 31

    @Test
    fun mobileBesideWorkingWifiIsIgnoredBelowAndroid12() {
        assertTrue(NetworkState.isBackupNetwork(android11, isCellular = true, validatedWifiElsewhere = true))
        assertTrue(NetworkState.isBackupNetwork(24, isCellular = true, validatedWifiElsewhere = true))
    }

    @Test
    fun mobileAloneIsARealNetwork() {
        assertFalse(NetworkState.isBackupNetwork(android11, isCellular = true, validatedWifiElsewhere = false))
    }

    @Test
    fun wifiIsNeverIgnored() {
        assertFalse(NetworkState.isBackupNetwork(android11, isCellular = false, validatedWifiElsewhere = true))
    }

    @Test
    fun android12AndUpReportsOnlyTheNetworkInUse() {
        assertFalse(NetworkState.isBackupNetwork(android12, isCellular = true, validatedWifiElsewhere = true))
    }
}
