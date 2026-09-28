/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.dashboard

import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsStore
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentUiState
import kotlin.math.roundToInt

/**
 * The single "새글이 배우는 중" (learning status) line shown on the vault home screen: collapses the
 * on-device AI suggestion engine, the relationship graph, and public sentence-material generation
 * into one prioritized state so only one thing is ever shown happening at a time. Kept Context-free
 * (string resource ids + int/resource args) like [GraphEnrichmentUiState] so [from] stays a plain-JVM
 * -testable pure function; the activity resolves [bodyRes] (substituting [reasonRes]'s text into it
 * when present) and [buttonLabelRes] with a [android.content.Context].
 */
data class LearningStatusUiState(
    val tier: Tier,
    @StringRes val bodyRes: Int,
    /** Substituted as this state's only %d argument (e.g. remaining minutes); null when [bodyRes] takes none. */
    val bodyIntArg: Int? = null,
    /** Resolved to text and substituted as this state's only %s argument (e.g. a pause reason); null when [bodyRes] takes none. */
    @StringRes val reasonRes: Int? = null,
    val showProgress: Boolean = false,
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    @StringRes val buttonLabelRes: Int? = null,
    val buttonAction: GraphEnrichmentUiState.Action? = null,
    val showAutomaticSwitch: Boolean = false,
    val automaticChecked: Boolean = false,
    val showNotificationHint: Boolean = false,
    /** Only meaningful for [Tier.UP_TO_DATE]: 0 when nothing has ever been applied yet. */
    val appliedAtMs: Long = 0L
) {
    enum class Tier {
        RELEASE, ENGINE_FAILED, RUNNING, WAITING, WAITING_KEYBOARD, PAUSED,
        MATERIAL_ONLY, MODEL_UNAVAILABLE, UP_TO_DATE
    }

    companion object {

        /**
         * Priority, highest first: a broken suggestion engine beats a graph in progress, which beats
         * material generation running on its own, which beats a missing model, which beats "nothing
         * to report right now". [graph] is only read when [onDeviceAiAvailable] is true - a device
         * with no on-device Gemma controller (older/unsupported hardware, or a release build that
         * never wired one up) never computes it (this dashboard's graph/material pipeline needs it).
         *
         * The manual "지금 반영하기" button (see [buttonAction]) is the sync flow that shows an
         * interstitial ad on completion once it's done
         * ([org.fcitx.fcitx5.android.ui.main.ai.TypingDnaDashboardActivity.startSync]), so - other
         * than while [Tier.RUNNING] or a wait tier is already showing its own button - it stays
         * offered regardless of [onDeviceAiAvailable], engine health, or whether automatic learning
         * is on and nothing is actually pending.
         */
        fun from(
            onDeviceAiAvailable: Boolean,
            engineFailed: Boolean,
            graph: GraphEnrichmentUiState?,
            materialActive: Boolean,
            automaticEnabled: Boolean,
            notificationBlocked: Boolean,
            appliedAtMs: Long
        ): LearningStatusUiState {
            if (!onDeviceAiAvailable || graph == null) {
                return LearningStatusUiState(
                    tier = Tier.RELEASE,
                    bodyRes = R.string.vault_learning_body_release,
                    buttonLabelRes = R.string.enrichment_action_apply_now,
                    buttonAction = GraphEnrichmentUiState.Action.CREATE
                )
            }
            if (engineFailed) {
                return LearningStatusUiState(
                    tier = Tier.ENGINE_FAILED,
                    bodyRes = R.string.vault_learning_body_engine_failed,
                    buttonLabelRes = R.string.enrichment_action_apply_now,
                    buttonAction = GraphEnrichmentUiState.Action.CREATE,
                    showAutomaticSwitch = true,
                    automaticChecked = automaticEnabled
                )
            }
            val isKeyboardWait = graph.kind == GraphEnrichmentUiState.Kind.WAITING &&
                graph.titleRes == R.string.enrichment_state_waiting_keyboard_title

            return when (graph.kind) {
                GraphEnrichmentUiState.Kind.RUNNING -> LearningStatusUiState(
                    tier = Tier.RUNNING,
                    bodyRes = if (graph.etaMinutes != null) {
                        R.string.vault_learning_body_running_eta
                    } else {
                        R.string.vault_learning_body_running_calculating
                    },
                    bodyIntArg = graph.etaMinutes,
                    showProgress = true,
                    progressCurrent = graph.progressCurrent,
                    progressTotal = graph.progressTotal,
                    buttonLabelRes = graph.action?.let { R.string.enrichment_action_stop },
                    buttonAction = graph.action,
                    showAutomaticSwitch = true,
                    automaticChecked = automaticEnabled,
                    showNotificationHint = notificationBlocked
                )
                GraphEnrichmentUiState.Kind.WAITING -> if (isKeyboardWait) {
                    LearningStatusUiState(
                        tier = Tier.WAITING_KEYBOARD,
                        bodyRes = R.string.vault_learning_body_waiting_keyboard,
                        showAutomaticSwitch = true,
                        automaticChecked = automaticEnabled,
                        showNotificationHint = notificationBlocked
                    )
                } else {
                    LearningStatusUiState(
                        tier = Tier.WAITING,
                        bodyRes = R.string.vault_learning_body_waiting,
                        showAutomaticSwitch = true,
                        automaticChecked = automaticEnabled,
                        showNotificationHint = notificationBlocked
                    )
                }
                GraphEnrichmentUiState.Kind.QUEUED -> LearningStatusUiState(
                    tier = Tier.WAITING,
                    bodyRes = R.string.vault_learning_body_waiting,
                    showAutomaticSwitch = true,
                    automaticChecked = automaticEnabled,
                    showNotificationHint = notificationBlocked
                )
                GraphEnrichmentUiState.Kind.PAUSED -> LearningStatusUiState(
                    tier = Tier.PAUSED,
                    bodyRes = R.string.vault_learning_body_paused,
                    reasonRes = graph.detailRes,
                    buttonLabelRes = graph.action?.let { R.string.enrichment_action_apply_now },
                    buttonAction = graph.action,
                    showAutomaticSwitch = true,
                    automaticChecked = automaticEnabled
                )
                GraphEnrichmentUiState.Kind.ERROR -> LearningStatusUiState(
                    tier = Tier.PAUSED,
                    bodyRes = R.string.vault_learning_body_paused,
                    reasonRes = R.string.vault_learning_reason_problem,
                    buttonLabelRes = graph.action?.let { R.string.enrichment_action_apply_now },
                    buttonAction = graph.action,
                    showAutomaticSwitch = true,
                    automaticChecked = automaticEnabled
                )
                GraphEnrichmentUiState.Kind.MODEL_UNAVAILABLE -> LearningStatusUiState(
                    tier = Tier.MODEL_UNAVAILABLE,
                    bodyRes = R.string.vault_learning_body_model_unavailable,
                    buttonLabelRes = graph.action?.let { R.string.vault_learning_action_get_model },
                    buttonAction = graph.action,
                    showAutomaticSwitch = true,
                    automaticChecked = automaticEnabled
                )
                GraphEnrichmentUiState.Kind.SUCCEEDED, GraphEnrichmentUiState.Kind.NEVER -> if (materialActive) {
                    LearningStatusUiState(
                        tier = Tier.MATERIAL_ONLY,
                        bodyRes = R.string.vault_learning_body_material_only,
                        // Always offered here too, independent of [graph.action] (which the graph
                        // card itself leaves null while automatic learning is on) - see [from]'s doc.
                        buttonLabelRes = R.string.enrichment_action_apply_now,
                        buttonAction = GraphEnrichmentUiState.Action.RETRY,
                        showAutomaticSwitch = true,
                        automaticChecked = automaticEnabled,
                        showNotificationHint = notificationBlocked
                    )
                } else {
                    LearningStatusUiState(
                        tier = Tier.UP_TO_DATE,
                        bodyRes = if (appliedAtMs > 0L) R.string.vault_learning_body_up_to_date else R.string.vault_learning_body_ready,
                        // Always offered, not gated on [graph.action] - see [from]'s doc.
                        buttonLabelRes = R.string.enrichment_action_apply_now,
                        buttonAction = GraphEnrichmentUiState.Action.RETRY,
                        showAutomaticSwitch = true,
                        automaticChecked = automaticEnabled,
                        appliedAtMs = appliedAtMs
                    )
                }
            }
        }
    }
}

/**
 * The "추천이 도움이 됐나요" card's numbers, derived from [PredictionMetricsStore.Summary]. [hasData] is
 * false until at least one contextual recommendation has ever been shown, matching the empty-state
 * copy the card falls back to.
 */
data class AcceptanceCardUiState(
    val hasData: Boolean,
    val acceptPercent: Int = 0,
    val totalShown: Int = 0,
    val totalAccepted: Int = 0,
    val personalPercent: Int = 0
) {
    companion object {
        fun from(metrics: PredictionMetricsStore.Summary): AcceptanceCardUiState {
            if (metrics.totalShown <= 0) return AcceptanceCardUiState(hasData = false)
            return AcceptanceCardUiState(
                hasData = true,
                acceptPercent = (metrics.acceptRate * 100f).roundToInt(),
                totalShown = metrics.totalShown,
                totalAccepted = metrics.totalAccepted,
                personalPercent = if (metrics.totalAccepted > 0) (metrics.personalShare * 100f).roundToInt() else 0
            )
        }
    }
}

/**
 * Pure helpers for the "내 말투" card: which frequent-word chips are worth showing, and which app
 * categories to name as "주로 쓰는 곳".
 */
object VaultStyleChips {
    private val hasLetterRegex = Regex("\\p{L}")

    /** Drops tokens shorter than 2 characters or made up only of digits/symbols (no letters), then caps at [limit]. */
    fun filterChips(words: List<String>, limit: Int = 8): List<String> =
        words.filter { it.length >= 2 && hasLetterRegex.containsMatchIn(it) }.take(limit)

    /** The top [limit] category ids by count, ties broken by [categoryCounts]' own iteration order. */
    fun topCategoryIds(categoryCounts: Map<String, Int>, limit: Int = 2): List<String> =
        categoryCounts.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
}
