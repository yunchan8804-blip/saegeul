/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.AiAction
import org.fcitx.fcitx5.android.input.ai.AiModelTier
import org.fcitx.fcitx5.android.input.ai.AiProviderProfile
import org.fcitx.fcitx5.android.input.ai.AndroidAiBearerTokenProvider
import org.fcitx.fcitx5.android.input.ai.OpenAiResponsesClient
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.utils.notificationManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs personal knowledge graph enrichment on the application's process-lifetime coroutine scope,
 * so it survives the dashboard (or any UI) being navigated away from or closed. At most one run is
 * in flight at a time; callers that ask to run while one is already running are ignored.
 */
object GraphEnrichmentRunner {

    private val inFlight = AtomicBoolean(false)

    private const val CHANNEL_ID = "saegeul-graph-enrich"
    private const val NOTIFY_ID = 0xda7a

    fun isRunning(): Boolean = inFlight.get()

    /**
     * Starts a background enrichment run using [profile], which must already be loaded and gated
     * by the caller (offline mode / network permission checks). If [notify] is true, a progress
     * notification is shown while running and replaced with a completion notification when done.
     * Returns false without doing anything if a run is already in progress.
     */
    fun start(context: Context, profile: AiProviderProfile, notify: Boolean): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false
        val appContext = context.applicationContext
        val app = FcitxApplication.getInstance()
        if (notify) {
            createNotificationChannel(appContext)
            appContext.notificationManager.notify(NOTIFY_ID, runningNotification(appContext))
        }
        app.applicationScope.launch {
            try {
                val client = OpenAiResponsesClient(
                    profile,
                    authorizationProvider = AndroidAiBearerTokenProvider(appContext)
                )
                val enricher = PersonalGraphEnricher(app.personalSentenceVault, app.personalGraphStore)
                val result = runCatching {
                    enricher.enrich(generate = { _, input ->
                        client.generate(
                            action = AiAction.GraphEnrich,
                            input = input,
                            tierOverride = AiModelTier.Fast
                        ).suggestions
                    })
                }
                if (notify) {
                    appContext.notificationManager.notify(NOTIFY_ID, completionNotification(appContext, result))
                }
            } catch (e: Throwable) {
                android.util.Log.w("SaegeulAI", "background enrichment failed: ${e.javaClass.simpleName}")
                if (notify) {
                    appContext.notificationManager.notify(
                        NOTIFY_ID,
                        completionNotification(appContext, Result.failure(e))
                    )
                }
            } finally {
                inFlight.set(false)
            }
        }
        return true
    }

    private fun createNotificationChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                ctx.getText(R.string.enrich_notify_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            ctx.notificationManager.createNotificationChannel(channel)
        }
    }

    private fun contentPendingIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx,
        0,
        Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun runningNotification(ctx: Context) =
        NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_sync_24)
            .setContentTitle(ctx.getText(R.string.app_name))
            .setContentText(ctx.getText(R.string.enrich_notify_running))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setProgress(0, 0, true)
            .setContentIntent(contentPendingIntent(ctx))
            .build()

    private fun completionNotification(
        ctx: Context,
        result: Result<PersonalGraphEnricher.EnrichResult>
    ): android.app.Notification {
        val text = result.fold(
            onSuccess = { r ->
                when {
                    r.ok && r.reason == "ok" ->
                        ctx.getString(R.string.enrich_notify_done, r.nodes, r.edges, r.topics)
                    r.ok && r.reason == "partial" ->
                        ctx.getString(R.string.enrich_notify_partial, r.nodes, r.edges, r.topics)
                    !r.ok && r.reason == "no_data" ->
                        ctx.getString(R.string.enrich_notify_no_data)
                    else ->
                        ctx.getString(R.string.enrich_notify_failed)
                }
            },
            onFailure = { ctx.getString(R.string.enrich_notify_failed) }
        )
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_sync_24)
            .setContentTitle(ctx.getText(R.string.app_name))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent(ctx))
            .build()
    }
}
