/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.amnezia.awg.util.applicationScope
import kotlinx.coroutines.launch

class BootShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        applicationScope.launch {
            // TunnelManager.restoreState()/saveState() go through the active
            // Backend interface, so this works the same for GoBackend
            // (virtually all real users - non-rooted) as it does for the
            // root-only AwgQuickBackend. Originally gated to AwgQuickBackend
            // only, which meant the tunnel silently never came back after a
            // reboot for almost anyone - contradicting the app's own
            // always-on Kill Switch/DNS Protection promise (a user could
            // reboot, assume they're still protected, and not be). force=true
            // so this doesn't depend on the "restore_on_boot" DataStore
            // preference, which defaults to false and has no toggle anywhere
            // in the KAPO UI to turn on - same reasoning as Kill Switch/DNS
            // Protection being unconditional rather than user-togglable.
            val tunnelManager = Application.getTunnelManager()
            if (Intent.ACTION_BOOT_COMPLETED == action) {
                Log.i(TAG, "Broadcast receiver restoring state (boot)")
                tunnelManager.restoreState(true)
            } else if (Intent.ACTION_SHUTDOWN == action) {
                Log.i(TAG, "Broadcast receiver saving state (shutdown)")
                tunnelManager.saveState()
            }
        }
    }

    companion object {
        private const val TAG = "KapoVPN/BootShutdownReceiver"
    }
}
