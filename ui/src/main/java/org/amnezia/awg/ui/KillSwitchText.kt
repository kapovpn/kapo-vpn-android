package org.amnezia.awg.ui

import org.amnezia.awg.backend.GoBackend

/**
 * What the Kill Switch row says. KAPO can't block traffic by itself once its tunnel is down:
 * only Android's "Block connections without VPN" does that, and an app can read that setting
 * but not change it. So the row tells the truth about it instead of a fixed "always on".
 */
internal object KillSwitchText {
    fun isOn(state: GoBackend.KillSwitchState): Boolean = state == GoBackend.KillSwitchState.ON

    /** Android 10 (API 29) is the first that lets an app read the setting. */
    private const val FIRST_SDK_THAT_REPORTS = 29

    fun describe(state: GoBackend.KillSwitchState, sdkInt: Int = FIRST_SDK_THAT_REPORTS): String = when (state) {
        GoBackend.KillSwitchState.ON ->
            "On: Android blocks all traffic if the VPN stops"
        GoBackend.KillSwitchState.ALWAYS_ON_ONLY ->
            "Off: Always-on is on, but traffic isn't blocked if the VPN stops - tap to turn on"
        GoBackend.KillSwitchState.OFF ->
            "Off: traffic goes out unprotected if the VPN stops - tap to turn on"
        GoBackend.KillSwitchState.UNKNOWN -> if (sdkInt < FIRST_SDK_THAT_REPORTS)
            "This Android version can't report it. Check \"Block connections without VPN\" yourself - tap for VPN settings"
        else
            "Connect to check. Relies on Android's \"Block connections without VPN\" - tap for VPN settings"
    }

    const val EXPLAINER =
        "KAPO can only block traffic while its tunnel is running. If the tunnel stops (an " +
        "update, a crash, another VPN app, the phone restarting), only Android itself can " +
        "keep apps offline.\n\nIn the next screen, tap the gear next to KAPO VPN and turn on " +
        "both \"Always-on VPN\" and \"Block connections without VPN\"."
}
