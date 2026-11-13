/*
 * Copyright © 2026 KAPO VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.ui

/**
 * Where the Account tab's "Add time" button goes. Never carries the account code: a code
 * in a link reaches whichever app opens it (browser history, tab restore, synced history,
 * any app that claims https links), where Sign out and the panic wipe can't reach it.
 * The page asks for the code instead. Unit-tested in AddTimeLinkTest.
 */
internal object AddTimeLink {
    const val URL = "https://kapovpn.com/account.html"
}
