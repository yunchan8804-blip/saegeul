/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import org.fcitx.fcitx5.android.input.ai.ondevice.IngestionReport

data class GemmaOpenMaterialPlan(
    val sequence: Long,
    val prompt: String,
    val generationEpoch: Long = 0L
)

internal object GemmaOpenMaterialTransition {
    const val MAX_CONSECUTIVE_UNPRODUCTIVE = 6
    const val COOLDOWN_MS = 4 * 3600 * 1000L

    fun requestManual(state: GemmaAccumulationState): Pair<GemmaAccumulationState, Boolean> {
        if (state.manualRequested) return state to false
        return state.copy(
            manualRequested = true,
            consecutiveUnproductive = 0,
            status = GemmaAccumulationState.STATUS_IDLE,
            error = null
        ) to true
    }

    fun canGenerate(state: GemmaAccumulationState): Boolean =
        state.enabled || state.manualRequested

    fun canPlan(state: GemmaAccumulationState): Boolean {
        if (!canGenerate(state)) return false
        if (!state.manualRequested && state.consecutiveUnproductive >= MAX_CONSECUTIVE_UNPRODUCTIVE) {
            return false
        }
        check(state.openSequence != Long.MAX_VALUE) { "공개 문맥 생성 순번이 최대값에 도달했습니다." }
        return true
    }

    fun canStart(state: GemmaAccumulationState, plan: GemmaOpenMaterialPlan): Boolean =
        canGenerate(state) &&
            state.openSequence == plan.sequence &&
            state.generationEpoch == plan.generationEpoch &&
            state.openSequence >= 0L &&
            state.openSequence < Long.MAX_VALUE &&
            (state.manualRequested || state.consecutiveUnproductive < MAX_CONSECUTIVE_UNPRODUCTIVE)

    fun afterCommit(
        state: GemmaAccumulationState,
        report: IngestionReport
    ): GemmaAccumulationState {
        val (nextUnproductive, stride) = if (report.added > 0) {
            0 to 1L
        } else {
            (state.consecutiveUnproductive + 1) to 3L
        }
        return state.copy(
            openSequence = advanceSequence(state.openSequence, stride),
            consecutiveUnproductive = nextUnproductive
        )
    }

    fun finish(state: GemmaAccumulationState): GemmaAccumulationState {
        val status = if (state.consecutiveUnproductive >= MAX_CONSECUTIVE_UNPRODUCTIVE) {
            GemmaAccumulationState.STATUS_ATTEMPTS_EXHAUSTED
        } else if (state.manualRequested) {
            GemmaAccumulationState.STATUS_COMPLETED
        } else {
            GemmaAccumulationState.STATUS_IDLE
        }
        return state.copy(manualRequested = false, status = status, error = null)
    }

    fun disable(state: GemmaAccumulationState): GemmaAccumulationState {
        check(state.generationEpoch != Long.MAX_VALUE) {
            "Gemma 생성 세대가 최대값에 도달했습니다."
        }
        return state.copy(
            enabled = false,
            manualRequested = false,
            generationEpoch = state.generationEpoch + 1L,
            status = "사용 안 함",
            error = null
        )
    }

    fun advanceSequence(sequence: Long, stride: Long = 1L): Long {
        check(sequence <= Long.MAX_VALUE - stride) { "공개 문맥 생성 순번이 최대값에 도달했습니다." }
        return sequence + stride
    }

    fun nextSequence(sequence: Long): Long = advanceSequence(sequence, 1L)

    fun recoverIfCooldownElapsed(
        state: GemmaAccumulationState,
        currentEpochMs: Long
    ): GemmaAccumulationState {
        if (state.consecutiveUnproductive >= MAX_CONSECUTIVE_UNPRODUCTIVE &&
            state.lastRunEpochMs > 0L &&
            currentEpochMs - state.lastRunEpochMs >= COOLDOWN_MS
        ) {
            return state.copy(
                consecutiveUnproductive = 0,
                status = GemmaAccumulationState.STATUS_IDLE,
                error = null
            )
        }
        return state
    }
}
