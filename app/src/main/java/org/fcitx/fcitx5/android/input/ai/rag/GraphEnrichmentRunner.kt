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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.AiAction
import org.fcitx.fcitx5.android.input.ai.AiModelTier
import org.fcitx.fcitx5.android.input.ai.AiProviderException
import org.fcitx.fcitx5.android.input.ai.AiProviderFailureKind
import org.fcitx.fcitx5.android.input.ai.AiProviderProfile
import org.fcitx.fcitx5.android.input.ai.AiReauthenticationRequiredException
import org.fcitx.fcitx5.android.input.ai.AiSuggestionContractException
import org.fcitx.fcitx5.android.input.ai.AndroidAiBearerTokenProvider
import org.fcitx.fcitx5.android.input.ai.OpenAiResponsesClient
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.utils.notificationManager
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

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
        val statusStore = GraphEnrichmentStatusStore(appContext)
        try {
            statusStore.recordStarted(System.currentTimeMillis())
        } catch (e: Throwable) {
            inFlight.set(false)
            throw e
        }
        val startedElapsedMs = SystemClock.elapsedRealtime()
        if (notify) {
            notifyRunning(appContext)
        }
        app.applicationScope.launch {
            try {
                val client = OpenAiResponsesClient(
                    profile,
                    authorizationProvider = AndroidAiBearerTokenProvider(appContext)
                )
                val enricher = PersonalGraphEnricher(
                    app.personalSentenceVault,
                    app.personalGraphStore,
                    onChunkOutcome = { outcome ->
                        android.util.Log.i("SaegeulAI", "background enrichment chunk: outcome=${outcome.name}")
                    }
                )
                val result = enricher.enrich(generate = { _, input ->
                    client.generate(
                        action = AiAction.GraphEnrich,
                        input = input,
                        tierOverride = AiModelTier.Fast
                    ).suggestions
                })
                statusStore.recordResult(result, System.currentTimeMillis())
                if (!result.ok && result.reason == "parse_failed") {
                    logOutcome(GraphEnrichmentFailure.INVALID_RESPONSE, null, elapsedMillis(startedElapsedMs))
                }
                if (notify) {
                    notifyCompletion(appContext, result, elapsedMillis(startedElapsedMs))
                }
            } catch (e: CancellationException) {
                statusStore.recordFailure(System.currentTimeMillis(), interrupted = true)
                throw e
            } catch (e: Throwable) {
                val failure = classifyFailure(e)
                val elapsedMillis = elapsedMillis(startedElapsedMs)
                logOutcome(failure, e, elapsedMillis)
                statusStore.recordFailure(System.currentTimeMillis(), failure = failure)
                if (notify) {
                    notifyCompletion(appContext, null, elapsedMillis)
                }
            } finally {
                inFlight.set(false)
            }
        }
        return true
    }

    private fun notifyRunning(ctx: Context) {
        try {
            createNotificationChannel(ctx)
            ctx.notificationManager.notify(NOTIFY_ID, runningNotification(ctx))
        } catch (e: Throwable) {
            logNotificationFailure(e, 0L)
        }
    }

    private fun notifyCompletion(
        ctx: Context,
        result: PersonalGraphEnricher.EnrichResult?,
        elapsedMillis: Long
    ) {
        try {
            ctx.notificationManager.notify(NOTIFY_ID, completionNotification(ctx, result))
        } catch (e: Throwable) {
            logNotificationFailure(e, elapsedMillis)
        }
    }

    internal fun classifyFailure(error: Throwable): GraphEnrichmentFailure = when (error) {
        is AiReauthenticationRequiredException -> GraphEnrichmentFailure.REAUTH_REQUIRED
        is SocketTimeoutException -> GraphEnrichmentFailure.TIMEOUT
        is UnknownHostException, is ConnectException, is SSLException -> GraphEnrichmentFailure.NETWORK
        is GeneralSecurityException -> GraphEnrichmentFailure.STORAGE
        is AiProviderException -> when {
            error.httpStatus == 429 -> GraphEnrichmentFailure.PROVIDER_BUSY
            error.failureKind in setOf(
                AiProviderFailureKind.InvalidJson,
                AiProviderFailureKind.EmptyOutput,
                AiProviderFailureKind.NotCompleted
            ) -> GraphEnrichmentFailure.INVALID_RESPONSE
            else -> GraphEnrichmentFailure.PROVIDER_ERROR
        }
        is AiSuggestionContractException -> GraphEnrichmentFailure.INVALID_RESPONSE
        else -> GraphEnrichmentFailure.UNKNOWN
    }

    private fun elapsedMillis(startedElapsedMs: Long): Long =
        (SystemClock.elapsedRealtime() - startedElapsedMs).coerceAtLeast(0L)

    private fun logOutcome(failure: GraphEnrichmentFailure, error: Throwable?, elapsedMillis: Long) {
        val httpStatus = (error as? AiProviderException)?.httpStatus ?: 0
        val exceptionClass = error?.javaClass?.simpleName ?: "none"
        android.util.Log.w(
            "SaegeulAI",
            "graphEnrichment failure=$failure exception=$exceptionClass httpStatus=$httpStatus elapsedMillis=$elapsedMillis"
        )
    }

    private fun logNotificationFailure(error: Throwable, elapsedMillis: Long) {
        val failure = classifyFailure(error)
        val httpStatus = (error as? AiProviderException)?.httpStatus ?: 0
        android.util.Log.w(
            "SaegeulAI",
            "background enrichment notification failed: failure=$failure exception=${error.javaClass.simpleName} httpStatus=$httpStatus elapsedMillis=$elapsedMillis"
        )
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
        result: PersonalGraphEnricher.EnrichResult?
    ): android.app.Notification {
        val text = when {
            result?.ok == true && result.reason == "ok" ->
                ctx.getString(R.string.enrich_notify_done, result.nodes, result.edges, result.topics)
            result?.ok == true && result.reason == "partial" ->
                ctx.getString(R.string.enrich_notify_partial, result.nodes, result.edges, result.topics)
            result?.ok == false && result.reason == "no_data" ->
                ctx.getString(R.string.enrich_notify_no_data)
            else ->
                ctx.getString(R.string.enrich_notify_failed)
        }
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
