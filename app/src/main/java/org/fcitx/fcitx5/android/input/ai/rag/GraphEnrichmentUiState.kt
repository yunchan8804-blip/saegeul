/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import android.content.Context
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R

/**
 * Everything the dashboard (and the manual run's foreground notification) needs to render the
 * relationship-graph card in one of its states: what to say (as string resource ids, so this stays
 * a plain-JVM-testable data model - no [android.content.Context]), what numbers to fill into that
 * text, and which single action button to offer. [from] is the single place that turns raw
 * status/staging data into this - see it for the state machine.
 */
data class GraphEnrichmentUiState(
    val kind: Kind,
    @StringRes val titleRes: Int,
    @StringRes val detailRes: Int? = null,
    @StringRes val guidanceRes: Int? = null,
    @StringRes val secondaryGuidanceRes: Int? = null,
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    /** Null while [kind] is [Kind.RUNNING] but no chunk has completed yet this cycle. */
    val etaMinutes: Int? = null,
    val elapsedWaitMinutes: Int = 0,
    val lastAppliedMs: Long = 0L,
    val graphNodes: Int = 0,
    val graphEdges: Int = 0,
    val graphTopics: Int = 0,
    /** Only meaningful while [kind] is [Kind.RUNNING] or [Kind.WAITING]: this cycle only processes sentences new since the graph was last built. */
    val isIncremental: Boolean = false,
    /** How many sentences this cycle is processing in total; only meaningful with [isIncremental]. */
    val processedSentenceCount: Int = 0,
    /** Only set for [Kind.ERROR] from a genuinely unhandled exception: its simple class name, shown as "(code: ...)" so this state never reads as an unexplained generic failure. */
    val errorDetailArg: String? = null,
    val action: Action? = null
) {
    enum class Kind { RUNNING, WAITING, QUEUED, PAUSED, SUCCEEDED, NEVER, MODEL_UNAVAILABLE, ERROR }

    enum class Action { STOP, PRIORITIZE_GRAPH, RESUME, CREATE, OPEN_MODEL_MANAGEMENT, RETRY }

    companion object {

        fun from(
            phase: GraphEnrichmentPhase,
            failure: GraphEnrichmentFailure,
            staging: PersonalGraphEnrichmentStagingStore.State?,
            nowMs: Long,
            automaticEnabled: Boolean,
            lastAppliedMs: Long,
            graphNodes: Int,
            graphEdges: Int,
            graphTopics: Int,
            failureDetail: String? = null
        ): GraphEnrichmentUiState = when (phase) {
            GraphEnrichmentPhase.QUEUED -> GraphEnrichmentUiState(
                kind = Kind.QUEUED,
                titleRes = R.string.enrichment_state_queued_title
            )
            GraphEnrichmentPhase.RUNNING -> runningOrWaiting(staging, nowMs)
            GraphEnrichmentPhase.INTERRUPTED -> paused(staging, automaticEnabled)
            GraphEnrichmentPhase.SUCCEEDED -> succeeded(
                R.string.enrichment_state_succeeded_title, lastAppliedMs, graphNodes, graphEdges, graphTopics, automaticEnabled
            )
            GraphEnrichmentPhase.PARTIAL -> succeeded(
                R.string.enrichment_state_partial_title, lastAppliedMs, graphNodes, graphEdges, graphTopics, automaticEnabled
            )
            GraphEnrichmentPhase.NEVER, GraphEnrichmentPhase.NO_DATA -> GraphEnrichmentUiState(
                kind = Kind.NEVER,
                titleRes = R.string.enrichment_state_never_title,
                detailRes = R.string.enrichment_state_never_detail,
                action = Action.CREATE
            )
            GraphEnrichmentPhase.FAILED -> failed(failure, failureDetail)
        }

        private fun runningOrWaiting(
            staging: PersonalGraphEnrichmentStagingStore.State?,
            nowMs: Long
        ): GraphEnrichmentUiState {
            // Only a manual cycle is user-cancellable/prioritizable through this card; an automatic
            // cycle (screen off, no one watching) is shown read-only.
            val cancellable = staging?.manual == true
            val incremental = staging?.isIncremental == true
            val processedSentenceCount = staging?.processedSentenceCount ?: 0

            if (staging?.waitingOnLease == true) {
                // Preempted by the keyboard specifically: a distinct, button-less "will resume on its
                // own" message - there is nothing to prioritize over (the keyboard, not material
                // generation, holds the lease), unlike the generic wait below.
                if (staging.waitingReason == GraphEnrichmentPauseReason.KEYBOARD_ACTIVE) {
                    return GraphEnrichmentUiState(
                        kind = Kind.WAITING,
                        titleRes = R.string.enrichment_state_waiting_keyboard_title,
                        guidanceRes = R.string.enrichment_state_waiting_keyboard_guidance,
                        isIncremental = incremental,
                        processedSentenceCount = processedSentenceCount,
                        action = null
                    )
                }
                val elapsedMs = (nowMs - staging.waitingOnLeaseSinceMs).coerceAtLeast(0L)
                return GraphEnrichmentUiState(
                    kind = Kind.WAITING,
                    titleRes = R.string.enrichment_state_waiting_title,
                    detailRes = if (elapsedMs < 60_000L) {
                        R.string.enrichment_state_waiting_detail_now
                    } else {
                        R.string.enrichment_state_waiting_detail_minutes
                    },
                    elapsedWaitMinutes = (elapsedMs / 60_000L).toInt(),
                    isIncremental = incremental,
                    processedSentenceCount = processedSentenceCount,
                    action = if (cancellable) Action.PRIORITIZE_GRAPH else null
                )
            }
            val total = staging?.chunks?.size ?: 0
            val current = staging?.nextChunkIndex ?: 0
            val completed = staging?.completedChunkCount ?: 0
            val etaMinutes = if (staging != null && completed > 0) {
                val averageMs = staging.completedChunkDurationMsSum.toDouble() / completed
                val remainingChunks = (total - current).coerceAtLeast(0)
                (((averageMs * remainingChunks) + 30_000.0) / 60_000.0).toInt().coerceAtLeast(1)
            } else {
                null
            }
            val detailRes = if (incremental) {
                if (etaMinutes != null) R.string.enrichment_state_running_detail_incremental_eta
                else R.string.enrichment_state_running_detail_incremental_calculating
            } else {
                if (etaMinutes != null) R.string.enrichment_state_running_detail_eta
                else R.string.enrichment_state_running_detail_calculating
            }
            return GraphEnrichmentUiState(
                kind = Kind.RUNNING,
                titleRes = R.string.enrichment_state_running_title,
                detailRes = detailRes,
                guidanceRes = R.string.enrichment_state_running_guidance,
                progressCurrent = current,
                progressTotal = total,
                etaMinutes = etaMinutes,
                isIncremental = incremental,
                processedSentenceCount = processedSentenceCount,
                action = if (cancellable) Action.STOP else null
            )
        }

        private fun paused(
            staging: PersonalGraphEnrichmentStagingStore.State?,
            automaticEnabled: Boolean
        ): GraphEnrichmentUiState = GraphEnrichmentUiState(
            kind = Kind.PAUSED,
            titleRes = if (staging != null) {
                R.string.enrichment_state_paused_title
            } else {
                R.string.enrichment_state_paused_title_no_progress
            },
            detailRes = pauseReasonDetailRes(staging?.pauseReason ?: GraphEnrichmentPauseReason.NONE),
            secondaryGuidanceRes = if (automaticEnabled) R.string.enrichment_state_paused_auto_hint else null,
            progressCurrent = staging?.nextChunkIndex ?: 0,
            progressTotal = staging?.chunks?.size ?: 0,
            action = Action.RESUME
        )

        private fun succeeded(
            @StringRes titleRes: Int,
            lastAppliedMs: Long,
            nodes: Int,
            edges: Int,
            topics: Int,
            automaticEnabled: Boolean
        ): GraphEnrichmentUiState = GraphEnrichmentUiState(
            kind = Kind.SUCCEEDED,
            titleRes = titleRes,
            detailRes = R.string.enrichment_state_succeeded_detail,
            // Automatic will pick this up on its own once enough new sentences accumulate; only
            // offer the manual button when there is nothing else that will do it.
            guidanceRes = if (automaticEnabled) R.string.enrichment_state_succeeded_auto_hint else null,
            lastAppliedMs = lastAppliedMs,
            graphNodes = nodes,
            graphEdges = edges,
            graphTopics = topics,
            action = if (automaticEnabled) null else Action.RETRY
        )

        private fun failed(failure: GraphEnrichmentFailure, failureDetail: String?): GraphEnrichmentUiState =
            when {
                failure == GraphEnrichmentFailure.MODEL_UNAVAILABLE -> GraphEnrichmentUiState(
                    kind = Kind.MODEL_UNAVAILABLE,
                    titleRes = R.string.enrichment_state_model_unavailable_title,
                    action = Action.OPEN_MODEL_MANAGEMENT
                )
                // Only a genuinely unhandled exception reaches this branch (a real class name was
                // captured for it) - every other stop has its own concrete reason shown elsewhere, so
                // this is the sole place the generic "something went wrong" text with an error code
                // can appear, never a catch-all default for an otherwise-unrecorded pause.
                failure == GraphEnrichmentFailure.UNKNOWN && failureDetail != null -> GraphEnrichmentUiState(
                    kind = Kind.ERROR,
                    titleRes = R.string.enrichment_state_error_title_with_code,
                    errorDetailArg = failureDetail,
                    action = Action.RETRY
                )
                else -> GraphEnrichmentUiState(
                    kind = Kind.ERROR,
                    titleRes = R.string.enrichment_state_error_title,
                    detailRes = GraphEnrichmentFailureText.resourceIdFor(failure),
                    action = Action.RETRY
                )
            }

        private fun pauseReasonDetailRes(reason: GraphEnrichmentPauseReason): Int = when (reason) {
            GraphEnrichmentPauseReason.BATTERY_LEVEL_UNKNOWN -> R.string.enrichment_pause_reason_battery_unknown
            GraphEnrichmentPauseReason.BATTERY_LOW -> R.string.enrichment_pause_reason_battery_low
            GraphEnrichmentPauseReason.POWER_SAVE -> R.string.enrichment_pause_reason_power_save
            GraphEnrichmentPauseReason.THERMAL -> R.string.enrichment_pause_reason_thermal
            GraphEnrichmentPauseReason.LOW_MEMORY -> R.string.enrichment_pause_reason_low_memory
            GraphEnrichmentPauseReason.KEYBOARD_ACTIVE -> R.string.enrichment_pause_reason_keyboard_active
            GraphEnrichmentPauseReason.SCREEN_ON -> R.string.enrichment_pause_reason_screen_on
            GraphEnrichmentPauseReason.NOT_CHARGING -> R.string.enrichment_pause_reason_not_charging
            GraphEnrichmentPauseReason.USER_STOPPED -> R.string.enrichment_pause_reason_user_stopped
            GraphEnrichmentPauseReason.LEASE_WAIT_TIMEOUT -> R.string.enrichment_pause_reason_lease_wait_timeout
            // Every controlled pause path records a concrete reason before returning - NONE surviving
            // to here means the process was killed outright mid-cycle, never a generic "unknown reason".
            GraphEnrichmentPauseReason.NONE -> R.string.enrichment_pause_reason_app_terminated
        }
    }
}

/**
 * Renders [GraphEnrichmentUiState.titleRes] with whatever numeric args that particular title needs.
 * Shared by the dashboard (main) and the manual run's foreground notification (debug), so both show
 * literally the same text - the only reason this needs [Context] instead of living in [from].
 */
fun GraphEnrichmentUiState.title(ctx: Context): String = when (kind) {
    GraphEnrichmentUiState.Kind.PAUSED -> if (progressTotal > 0) {
        ctx.getString(titleRes, progressCurrent, progressTotal)
    } else {
        ctx.getString(titleRes)
    }
    GraphEnrichmentUiState.Kind.ERROR -> if (errorDetailArg != null) {
        ctx.getString(titleRes, errorDetailArg)
    } else {
        ctx.getString(titleRes)
    }
    else -> ctx.getString(titleRes)
}

/**
 * Renders [GraphEnrichmentUiState.detailRes], or null when this state has none (QUEUED,
 * MODEL_UNAVAILABLE). [Kind.SUCCEEDED] additionally needs [appliedAtText] - a caller-formatted
 * "today HH:mm" / "yesterday HH:mm" / date string, since that formatting is locale-sensitive
 * presentation rather than state-machine logic.
 */
fun GraphEnrichmentUiState.detail(ctx: Context, appliedAtText: String? = null): String? {
    val res = detailRes ?: return null
    return when (kind) {
        GraphEnrichmentUiState.Kind.RUNNING -> if (isIncremental) {
            if (etaMinutes != null) {
                ctx.getString(res, processedSentenceCount, progressCurrent, progressTotal, etaMinutes)
            } else {
                ctx.getString(res, processedSentenceCount, progressCurrent, progressTotal)
            }
        } else if (etaMinutes != null) {
            ctx.getString(res, progressCurrent, progressTotal, etaMinutes)
        } else {
            ctx.getString(res, progressCurrent, progressTotal)
        }
        GraphEnrichmentUiState.Kind.WAITING -> if (elapsedWaitMinutes > 0) {
            ctx.getString(res, elapsedWaitMinutes)
        } else {
            ctx.getString(res)
        }
        GraphEnrichmentUiState.Kind.SUCCEEDED ->
            ctx.getString(res, appliedAtText.orEmpty(), graphNodes, graphEdges, graphTopics)
        else -> ctx.getString(res)
    }
}

fun GraphEnrichmentUiState.guidance(ctx: Context): String? = guidanceRes?.let(ctx::getString)

fun GraphEnrichmentUiState.secondaryGuidance(ctx: Context): String? = secondaryGuidanceRes?.let(ctx::getString)

fun GraphEnrichmentUiState.actionLabel(ctx: Context): String? = action?.let { ctx.getString(it.labelRes()) }

private fun GraphEnrichmentUiState.Action.labelRes(): Int = when (this) {
    GraphEnrichmentUiState.Action.STOP -> R.string.enrichment_action_stop
    GraphEnrichmentUiState.Action.PRIORITIZE_GRAPH -> R.string.enrichment_action_prioritize_graph
    // Unified per design: resuming, creating for the first time, and rebuilding after success are
    // all the same one action from the user's point of view - make the graph reflect what is there
    // right now.
    GraphEnrichmentUiState.Action.RESUME,
    GraphEnrichmentUiState.Action.CREATE,
    GraphEnrichmentUiState.Action.RETRY -> R.string.enrichment_action_apply_now
    GraphEnrichmentUiState.Action.OPEN_MODEL_MANAGEMENT -> R.string.enrichment_action_open_model_management
}
