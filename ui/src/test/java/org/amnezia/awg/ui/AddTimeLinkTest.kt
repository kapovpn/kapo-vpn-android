/*
 * Copyright © 2026 KAPO VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The Add-time browser link never carries the account code. */
class AddTimeLinkTest {
    @Test
    fun linkIsThePlainAccountPage() {
        assertEquals("https://kapovpn.com/account.html", AddTimeLink.URL)
    }

    @Test
    fun linkHasNoPlaceForACode() {
        assertFalse(AddTimeLink.URL.contains('#'))
        assertFalse(AddTimeLink.URL.contains('?'))
        assertFalse(AddTimeLink.URL.contains("code", ignoreCase = true))
    }
}
