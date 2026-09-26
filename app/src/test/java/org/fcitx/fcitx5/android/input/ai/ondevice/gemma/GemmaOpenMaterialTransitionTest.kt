/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import org.fcitx.fcitx5.android.input.ai.ondevice.IngestionReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaOpenMaterialTransitionTest {
    @Test
    fun `stale plan cannot start`() {
        val state = GemmaAccumulationState(enabled = true, openSequence = 8L)

        assertFalse(GemmaOpenMaterialTransition.canStart(state, GemmaOpenMaterialPlan(7L, "prompt")))
        assertTrue(GemmaOpenMaterialTransition.canStart(state, GemmaOpenMaterialPlan(8L, "prompt")))
    }

    @Test
    fun `manual request can run with automatic option off`() {
        val state = GemmaAccumulationState(
            enabled = false,
            manualRequested = true,
            openSequence = 8L,
            generationEpoch = 3L
        )

        assertTrue(GemmaOpenMaterialTransition.canGenerate(state))
        assertTrue(
            GemmaOpenMaterialTransition.canStart(
                state,
                GemmaOpenMaterialPlan(8L, "prompt", generationEpoch = 3L)
            )
        )
    }

    @Test
    fun `disabled epoch rejects an in flight manual plan`() {
        val requested = GemmaAccumulationState(
            manualRequested = true,
            openSequence = 8L,
            generationEpoch = 3L
        )
        val plan = GemmaOpenMaterialPlan(8L, "prompt", generationEpoch = 3L)
        val disabled = GemmaOpenMaterialTransition.disable(requested)

        assertEquals(4L, disabled.generationEpoch)
        assertFalse(disabled.manualRequested)
        assertFalse(GemmaOpenMaterialTransition.canStart(disabled, plan))
    }

    @Test
    fun `duplicate manual request keeps its generation epoch`() {
        val (requested, firstWasNew) = GemmaOpenMaterialTransition.requestManual(
            GemmaAccumulationState(generationEpoch = 3L)
        )
        val (duplicate, duplicateWasNew) = GemmaOpenMaterialTransition.requestManual(requested)

        assertTrue(firstWasNew)
        assertFalse(duplicateWasNew)
        assertEquals(requested, duplicate)
        assertEquals(3L, duplicate.generationEpoch)
        assertTrue(duplicate.manualRequested)
    }

    @Test
    fun `manual completion clears request and records completion`() {
        val completed = GemmaOpenMaterialTransition.finish(
            GemmaAccumulationState(manualRequested = true, generationEpoch = 3L)
        )

        assertFalse(completed.manualRequested)
        assertFalse(completed.enabled)
        assertEquals(GemmaAccumulationState.STATUS_COMPLETED, completed.status)
        assertEquals(3L, completed.generationEpoch)
    }

    @Test
    fun `manual request preserves exhausted status without generation`() {
        val exhausted = GemmaOpenMaterialTransition.finish(
            GemmaAccumulationState(
                manualRequested = true,
                consecutiveUnproductive = GemmaOpenMaterialTransition.MAX_CONSECUTIVE_UNPRODUCTIVE
            )
        )

        assertFalse(exhausted.manualRequested)
        assertEquals(GemmaAccumulationState.STATUS_ATTEMPTS_EXHAUSTED, exhausted.status)
    }

    @Test
    fun `legacy state defaults keep automatic generation off`() {
        val restored = GemmaAccumulationState()

        assertFalse(restored.enabled)
        assertFalse(restored.manualRequested)
        assertEquals(0L, restored.generationEpoch)
        assertFalse(GemmaOpenMaterialTransition.canPlan(restored))
    }

    @Test(expected = IllegalStateException::class)
    fun `disable does not wrap generation epoch`() {
        GemmaOpenMaterialTransition.disable(
            GemmaAccumulationState(manualRequested = true, generationEpoch = Long.MAX_VALUE)
        )
    }

    @Test
    fun `exhausted and terminal sequences cannot start`() {
        assertFalse(
            GemmaOpenMaterialTransition.canStart(
                GemmaAccumulationState(
                    enabled = true,
                    openSequence = 8L,
                    consecutiveUnproductive = 6
                ),
                GemmaOpenMaterialPlan(8L, "prompt")
            )
        )
        assertFalse(
            GemmaOpenMaterialTransition.canStart(
                GemmaAccumulationState(enabled = true, openSequence = Long.MAX_VALUE),
                GemmaOpenMaterialPlan(Long.MAX_VALUE, "prompt")
            )
        )
        assertFalse(
            GemmaOpenMaterialTransition.canStart(
                GemmaAccumulationState(enabled = true, openSequence = -1L),
                GemmaOpenMaterialPlan(-1L, "prompt")
            )
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `maximum sequence fails instead of wrapping`() {
        GemmaOpenMaterialTransition.canPlan(
            GemmaAccumulationState(enabled = true, openSequence = Long.MAX_VALUE)
        )
    }

    @Test
    fun `requestManual resets consecutive unproductive counter`() {
        val state = GemmaAccumulationState(
            consecutiveUnproductive = GemmaOpenMaterialTransition.MAX_CONSECUTIVE_UNPRODUCTIVE,
            status = GemmaAccumulationState.STATUS_ATTEMPTS_EXHAUSTED,
            error = "이전 오류"
        )
        val (requested, newlyRequested) = GemmaOpenMaterialTransition.requestManual(state)

        assertTrue(newlyRequested)
        assertTrue(requested.manualRequested)
        assertEquals(0, requested.consecutiveUnproductive)
        assertEquals(GemmaAccumulationState.STATUS_IDLE, requested.status)
        assertEquals(null, requested.error)
    }

    @Test
    fun `manual request allows planning even when previously exhausted`() {
        val state = GemmaAccumulationState(
            enabled = false,
            manualRequested = true,
            consecutiveUnproductive = GemmaOpenMaterialTransition.MAX_CONSECUTIVE_UNPRODUCTIVE
        )

        assertTrue(GemmaOpenMaterialTransition.canPlan(state))
    }

    @Test
    fun `cooldown recovery restores idle status and resets counter`() {
        val lastRun = 1000L
        val state = GemmaAccumulationState(
            lastRunEpochMs = lastRun,
            consecutiveUnproductive = GemmaOpenMaterialTransition.MAX_CONSECUTIVE_UNPRODUCTIVE,
            status = GemmaAccumulationState.STATUS_ATTEMPTS_EXHAUSTED,
            error = "소진 상태"
        )
        val currentEpochMs = lastRun + GemmaOpenMaterialTransition.COOLDOWN_MS + 10L
        val recovered = GemmaOpenMaterialTransition.recoverIfCooldownElapsed(state, currentEpochMs)

        assertEquals(0, recovered.consecutiveUnproductive)
        assertEquals(GemmaAccumulationState.STATUS_IDLE, recovered.status)
        assertEquals(null, recovered.error)

        val notElapsed = GemmaOpenMaterialTransition.recoverIfCooldownElapsed(
            state,
            lastRun + GemmaOpenMaterialTransition.COOLDOWN_MS - 10L
        )
        assertEquals(state, notElapsed)
    }

    @Test
    fun `committed report advances sequence`() {
        val next = GemmaOpenMaterialTransition.afterCommit(
            GemmaAccumulationState(enabled = true, openSequence = 8L),
            IngestionReport(added = 0, duplicate = 2, rejected = 0)
        )

        assertEquals(11L, next.openSequence)
    }

    @Test
    fun `unproductive commit advances sequence by stride 3`() {
        val next = GemmaOpenMaterialTransition.afterCommit(
            GemmaAccumulationState(enabled = true, openSequence = 8L),
            IngestionReport(added = 0, duplicate = 2, rejected = 0)
        )

        assertEquals(11L, next.openSequence)
        assertEquals(1, next.consecutiveUnproductive)
    }

    @Test
    fun `six unproductive commits stop planning`() {
        val state = GemmaAccumulationState(enabled = true, consecutiveUnproductive = 5)
        val next = GemmaOpenMaterialTransition.afterCommit(
            state,
            IngestionReport(added = 0, duplicate = 2, rejected = 0)
        )

        assertEquals(6, next.consecutiveUnproductive)
        assertFalse(GemmaOpenMaterialTransition.canPlan(next))
    }

    @Test
    fun `productive commit resets unproductive streak`() {
        val next = GemmaOpenMaterialTransition.afterCommit(
            GemmaAccumulationState(enabled = true, openSequence = 8L, consecutiveUnproductive = 5),
            IngestionReport(added = 1, duplicate = 0, rejected = 1)
        )

        assertEquals(9L, next.openSequence)
        assertEquals(0, next.consecutiveUnproductive)
        assertTrue(GemmaOpenMaterialTransition.canPlan(next))
    }

}
