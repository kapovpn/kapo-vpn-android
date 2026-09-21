/*
 * Copyright © 2026 KAPO VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.amnezia.awg.activity.SelfTestActivity
import org.amnezia.awg.ui.KapoVpn

/**
 * Most VPNs only check for leaks when you remember to ask (Settings ->
 * "Verify my protection"). This runs the same checks SelfTestRunner already
 * has - passively, read-only, no new infrastructure - every 90 seconds
 * while a tunnel is actually up, and pushes a real notification the moment
 * one fails, instead of the leak sitting unnoticed until a manual check.
 *
 * Started once from Application.onCreate() on the app's own long-lived
 * scope, so it keeps watching regardless of which screen (if any) is open.
 */
object LeakMonitor {
    private const val CHECK_INTERVAL_MS = 90_000L
    private const val CHANNEL_ID = "kapo_leak_alerts"
    private const val NOTIFICATION_ID = 4201

    // Dedup key for the last alert fired, so an ongoing leak doesn't repost
    // a notification every single cycle - only on a new/changed failure, or
    // after it resolves and later fails again.
    private var lastAlertKey: String? = null

    fun start(ctx: Context, scope: CoroutineScope) {
        ensureChannel(ctx)
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                try {
                    checkOnce(ctx)
                } catch (_: Exception) {
                    // A single check failing to even run (transient network hiccup,
                    // etc.) isn't itself a leak - just skip this cycle silently.
                }
            }
        }
    }

    private suspend fun checkOnce(ctx: Context) {
        if (!KapoVpn.isUp()) {
            lastAlertKey = null   // disconnected - re-arm for the next connection
            return
        }
        val failed = SelfTestRunner.runAll().firstOrNull { !it.passed } ?: run {
            lastAlertKey = null   // everything passing - re-arm in case of a future leak
            return
        }
        val key = failed.title + "|" + failed.detail
        if (key == lastAlertKey) return   // same failure already alerted, don't spam
        lastAlertKey = key
        postAlert(ctx, failed.title, failed.detail)
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Security Alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Warns you immediately if your connection stops behaving like it should"
        }
        ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    // SelfTestRunner's `detail` is written for the on-demand diagnostics
    // screen, where a technical reader benefits from precision - for
    // canReachKapoApi() that's a raw exception message (java.net's
    // SocketTimeoutException text includes local IP:port plumbing, e.g.
    // "failed to connect to api.kapovpn.com/1.2.3.4 (port 443) from
    // /10.0.0.5 (port 36856) after 6000ms"). Confirmed live: that's exactly
    // what showed up in the first real test of this alert. A push
    // notification is glanced at, not read like a stack trace, so give the
    // two known failure titles a plain-language body here instead of
    // passing the raw detail straight through. exitNodeMatches()'s own
    // detail is already written for a human reader, so that one passes
    // through unchanged - it's the actual leak signal and reads fine.
    private fun humanBody(title: String, detail: String): String = when (title) {
        "Tunnel is up" -> "Your KAPO tunnel dropped unexpectedly."
        "Can reach KAPO servers" -> "Can't reach KAPO's servers right now — your connection may not be fully protected."
        else -> detail
    }

    private fun postAlert(ctx: Context, title: String, detail: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return   // never granted (or denied) - can't force it, fail silently

        val body = humanBody(title, detail)
        val openIntent = Intent(ctx, SelfTestActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            ctx, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Possible connection leak")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check above and here - ignore.
        }
    }
}
