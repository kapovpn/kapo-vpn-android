/*
 * Copyright © 2026 KAPO VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.util

/**
 * What LeakMonitor does on a cycle, from the real tunnel state and whether the user wants
 * KAPO connected. Kept apart from LeakMonitor so it can be unit-tested without Android.
 */
internal object TunnelWatch {
    enum class Action {
        /** Up: run the leak checks. */
        RUN_CHECKS,
        /** Down, but the user never switched it off: it dropped by itself. */
        ALERT_DROPPED,
        /** Down because the user switched it off: nothing to watch. */
        IDLE
    }

    fun decide(up: Boolean, wanted: Boolean): Action = when {
        up -> Action.RUN_CHECKS
        wanted -> Action.ALERT_DROPPED
        else -> Action.IDLE
    }
}
