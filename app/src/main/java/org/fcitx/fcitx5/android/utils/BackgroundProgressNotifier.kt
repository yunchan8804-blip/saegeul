/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.MainActivity
import timber.log.Timber

/**
 * Central notifier for background AI work (personal graph enrichment, debug-only sentence
 * material accumulation). Two channels: an ongoing low-importance progress channel, and a
 * default-importance channel for outcomes that need the user's attention.
 *
 * Every call is a no-op when `POST_NOTIFICATIONS` isn't granted (API 33+); progress/done are
 * additionally gated by [AppPrefs.Internal.backgroundProgressNotifications], while alerts always
 * show as long as the permission is held.
 */
object BackgroundProgressNotifier {

    const val CHANNEL_PROGRESS = "saegeul-ai-progress"
    const val CHANNEL_ALERTS = "saegeul-ai-alerts"

    const val ID_GRAPH_ENRICH = 0xda7a
    const val ID_GEMMA_ACCUMULATION = 0xda7b
    const val ID_GEMMA_INSTALL = 0xda7c

    private const val LEGACY_CHANNEL_ID = "saegeul-graph-enrich"
    private const val STATUS_PREFS_NAME = "notifier_status"
    private const val KEY_LAST_BLOCKED_MS = "last_blocked_ms"
    private const val LOG_TAG = "SaegeulNotify"
    private const val MAIN_ACTIVITY_REQUEST_CODE = 0

    internal enum class Kind { PROGRESS, DONE, ALERT }

    /** Pure decision of whether a notification of [kind] may be posted. No I/O; easy to unit test. */
    internal fun shouldPost(kind: Kind, permissionGranted: Boolean, prefEnabled: Boolean): Boolean {
        if (!permissionGranted) return false
        return when (kind) {
            Kind.PROGRESS, Kind.DONE -> prefEnabled
            Kind.ALERT -> true
        }
    }

    /** Creates both channels and removes the legacy single-purpose enrichment channel. */
    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ctx.notificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                ctx.getText(R.string.notify_channel_ai_progress),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                ctx.getText(R.string.notify_channel_ai_alerts),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
    }

    /**
     * What an ongoing progress notification shows: [current] of [total] (indeterminate when [total]
     * is not positive), plus an optional [action] button (e.g. a "Stop" action wired to
     * `WorkManager.createCancelPendingIntent`).
     */
    data class ProgressSpec(
        val title: String,
        val text: String,
        val current: Int,
        val total: Int,
        val action: NotificationCompat.Action? = null
    )

    fun progress(ctx: Context, id: Int, spec: ProgressSpec) {
        val granted = hasPermission(ctx)
        if (!shouldPost(Kind.PROGRESS, granted, prefEnabled())) {
            if (!granted) recordBlocked(ctx)
            return
        }
        ctx.notificationManager.notify(id, buildProgressNotification(ctx, spec))
    }

    /**
     * Builds the same ongoing progress notification [progress] posts via `NotificationManager`,
     * without the permission/preference gate: a foreground service must supply a `Notification` to
     * `startForeground`/`setForeground` unconditionally (the OS, not this notifier, decides whether
     * it is actually shown without `POST_NOTIFICATIONS`). Posting a later update through [progress]
     * or this same id updates the one currently shown as the foreground notification in place.
     */
    fun buildProgressNotification(ctx: Context, spec: ProgressSpec): android.app.Notification =
        NotificationCompat.Builder(ctx, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_baseline_sync_24)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setProgress(spec.total, spec.current, spec.total <= 0)
            .setContentIntent(mainActivityPendingIntent(ctx))
            .apply { spec.action?.let { addAction(it) } }
            .build()

    fun done(ctx: Context, id: Int, title: String, text: String, contentIntent: PendingIntent? = null) {
        val granted = hasPermission(ctx)
        if (!shouldPost(Kind.DONE, granted, prefEnabled())) {
            if (!granted) recordBlocked(ctx)
            return
        }
        val notification = NotificationCompat.Builder(ctx, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_baseline_sync_24)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent ?: mainActivityPendingIntent(ctx))
            .build()
        ctx.notificationManager.notify(id, notification)
    }

    fun alert(ctx: Context, id: Int, title: String, text: String, contentIntent: PendingIntent? = null) {
        val granted = hasPermission(ctx)
        if (!shouldPost(Kind.ALERT, granted, prefEnabled())) {
            if (!granted) recordBlocked(ctx)
            return
        }
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_baseline_sync_24)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent ?: mainActivityPendingIntent(ctx))
            .build()
        ctx.notificationManager.notify(id, notification)
    }

    fun cancel(ctx: Context, id: Int) {
        ctx.notificationManager.cancel(id)
    }

    /** Epoch millis of the last time a notification was suppressed for lack of permission, or 0. */
    fun lastBlockedAtMs(ctx: Context): Long =
        statusPrefs(ctx).getLong(KEY_LAST_BLOCKED_MS, 0L)

    private var lastRecordedBlockedMs = 0L

    private fun recordBlocked(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastRecordedBlockedMs < 1_000L) return
        lastRecordedBlockedMs = now
        Timber.tag(LOG_TAG).i("suppressed: permission")
        statusPrefs(ctx).edit { putLong(KEY_LAST_BLOCKED_MS, now) }
    }

    private fun hasPermission(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun prefEnabled(): Boolean =
        AppPrefs.getInstance().internal.backgroundProgressNotifications.getValue()

    private fun statusPrefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(STATUS_PREFS_NAME, Context.MODE_PRIVATE)

    private fun mainActivityPendingIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx,
        MAIN_ACTIVITY_REQUEST_CODE,
        Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE
    )
}
