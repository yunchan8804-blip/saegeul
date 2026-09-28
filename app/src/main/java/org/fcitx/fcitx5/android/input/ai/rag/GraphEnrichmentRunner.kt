/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.BackgroundProgressNotifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks whether the on-device graph-enrichment worker is actively running in this process, and
 * holds the notification helpers it shares with the dashboard's manual "enrich now" flow. The
 * actual enrichment work (loading the Gemma model, generating, parsing) lives in
 * [org.fcitx.fcitx5.android.input.ai.ondevice.gemma] `GemmaGraphEnrichmentWorker`; this object
 * stays alongside it in the main source set so the dashboard can read [isRunning] on every
 * variant, on devices [org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAiSupport] reports as
 * unsupported included, matching the same pattern
 * [org.fcitx.fcitx5.android.ui.main.ai.dashboard.GemmaPreparationController] uses for the rest of the Gemma
 * pipeline.
 */
object GraphEnrichmentRunner {

    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    /** Called by the worker right before it starts (or resumes) processing chunks. */
    fun markRunning() {
        running.set(true)
    }

    /** Called by the worker when it stops running for any reason (paused, finished, or failed). */
    fun markStopped() {
        running.set(false)
    }

    fun notifyCompletion(
        ctx: Context,
        result: PersonalGraphEnricher.EnrichResult?,
        failure: GraphEnrichmentFailure
    ) {
        try {
            val title = ctx.getString(R.string.app_name)
            when {
                result?.ok == true && result.reason == "ok" -> BackgroundProgressNotifier.done(
                    ctx, BackgroundProgressNotifier.ID_GRAPH_ENRICH, title,
                    ctx.getString(R.string.enrich_notify_done, result.nodes, result.edges, result.topics)
                )
                result?.ok == true && result.reason == "partial" -> BackgroundProgressNotifier.done(
                    ctx, BackgroundProgressNotifier.ID_GRAPH_ENRICH, title,
                    ctx.getString(R.string.enrich_notify_partial, result.nodes, result.edges, result.topics)
                )
                result?.ok == false && result.reason == "no_data" -> BackgroundProgressNotifier.done(
                    ctx, BackgroundProgressNotifier.ID_GRAPH_ENRICH, title,
                    ctx.getString(R.string.enrich_notify_no_data)
                )
                else -> BackgroundProgressNotifier.alert(
                    ctx, BackgroundProgressNotifier.ID_GRAPH_ENRICH, title,
                    GraphEnrichmentFailureText.of(failure, ctx),
                    null
                )
            }
        } catch (e: Throwable) {
            android.util.Log.w("SaegeulAI", "graph enrichment completion notification failed: ${e.javaClass.simpleName}")
        }
    }

    fun notifyCancelled(ctx: Context) {
        try {
            BackgroundProgressNotifier.cancel(ctx, BackgroundProgressNotifier.ID_GRAPH_ENRICH)
        } catch (e: Throwable) {
            android.util.Log.w("SaegeulAI", "graph enrichment cancel notification failed: ${e.javaClass.simpleName}")
        }
    }
}
