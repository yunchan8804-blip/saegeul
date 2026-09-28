/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAutomaticSuggestionWarmupState
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionCoordinator.State
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionCoordinator.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which status [HorizontalCandidateComponent]'s status row asks for from the on-device
 * suggestion readings, and when NO_CANDIDATE is allowed to appear.
 */
class StatusRowPolicyTest {

    private fun requested(
        state: State,
        errorCode: String? = null,
        warmupState: OnDeviceAutomaticSuggestionWarmupState = OnDeviceAutomaticSuggestionWarmupState.Idle,
        warmupFailed: Boolean = false,
        collectionFeedbackActive: Boolean = false
    ) = StatusRowPolicy.requestedState(warmupState, Status(state, errorCode), warmupFailed, collectionFeedbackActive)

    @Test
    fun `only debouncing and generating count as generating`() {
        assertTrue(StatusRowPolicy.isGenerating(State.DEBOUNCING))
        assertTrue(StatusRowPolicy.isGenerating(State.GENERATING))
        assertFalse(StatusRowPolicy.isGenerating(State.OFF))
        assertFalse(StatusRowPolicy.isGenerating(State.READY))
        assertFalse(StatusRowPolicy.isGenerating(State.NO_CANDIDATE))
        assertFalse(StatusRowPolicy.isGenerating(State.ERROR))
    }

    @Test
    fun `engine warmup outranks every other reading`() {
        State.entries.forEach { state ->
            assertEquals(
                StatusRowLogicalState.WARMUP,
                requested(
                    state = state,
                    errorCode = "ENGINE_UNRECOVERABLE",
                    warmupState = OnDeviceAutomaticSuggestionWarmupState.Preparing,
                    warmupFailed = true,
                    collectionFeedbackActive = true
                )
            )
        }
    }

    @Test
    fun `coordinator states map to generating, no candidate and error`() {
        assertEquals(StatusRowLogicalState.GENERATING, requested(State.DEBOUNCING, collectionFeedbackActive = true))
        assertEquals(StatusRowLogicalState.GENERATING, requested(State.GENERATING, warmupFailed = true))
        assertEquals(StatusRowLogicalState.NO_CANDIDATE, requested(State.NO_CANDIDATE, warmupFailed = true))
        assertEquals(StatusRowLogicalState.ERROR, requested(State.ERROR, errorCode = "TIMEOUT", warmupFailed = true))
        assertEquals(StatusRowLogicalState.ERROR, requested(State.ERROR, errorCode = null))
    }

    @Test
    fun `rejected input is not shown as an error`() {
        assertNull(requested(State.ERROR, errorCode = "INVALID_INPUT"))
        assertEquals(
            StatusRowLogicalState.WARMUP_FAILED,
            requested(State.ERROR, errorCode = "INVALID_INPUT", warmupFailed = true)
        )
        assertEquals(
            StatusRowLogicalState.COLLECTION_FEEDBACK,
            requested(State.ERROR, errorCode = "INVALID_INPUT", collectionFeedbackActive = true)
        )
    }

    @Test
    fun `idle coordinator falls back to warmup failure, then collection feedback, then nothing`() {
        listOf(State.OFF, State.READY).forEach { state ->
            assertEquals(
                StatusRowLogicalState.WARMUP_FAILED,
                requested(state, warmupFailed = true, collectionFeedbackActive = true)
            )
            assertEquals(StatusRowLogicalState.COLLECTION_FEEDBACK, requested(state, collectionFeedbackActive = true))
            assertNull(requested(state))
        }
    }

    @Test
    fun `no candidate is entered only right after generating`() {
        assertEquals(
            StatusRowLogicalState.NO_CANDIDATE,
            StatusRowPolicy.nextState(StatusRowLogicalState.NO_CANDIDATE, null, State.GENERATING)
        )
        assertEquals(
            StatusRowLogicalState.NO_CANDIDATE,
            StatusRowPolicy.nextState(StatusRowLogicalState.NO_CANDIDATE, StatusRowLogicalState.GENERATING, State.GENERATING)
        )
        listOf(null, State.OFF, State.DEBOUNCING, State.READY, State.NO_CANDIDATE, State.ERROR).forEach { previous ->
            assertNull(StatusRowPolicy.nextState(StatusRowLogicalState.NO_CANDIDATE, null, previous))
            assertNull(
                StatusRowPolicy.nextState(StatusRowLogicalState.NO_CANDIDATE, StatusRowLogicalState.GENERATING, previous)
            )
        }
    }

    @Test
    fun `no candidate already on screen stays regardless of the previous coordinator state`() {
        listOf(null, State.OFF, State.READY, State.NO_CANDIDATE).forEach { previous ->
            assertEquals(
                StatusRowLogicalState.NO_CANDIDATE,
                StatusRowPolicy.nextState(StatusRowLogicalState.NO_CANDIDATE, StatusRowLogicalState.NO_CANDIDATE, previous)
            )
        }
    }

    @Test
    fun `every other requested state passes through unchanged`() {
        val others = StatusRowLogicalState.entries.filter { it != StatusRowLogicalState.NO_CANDIDATE } + null
        others.forEach { requested ->
            listOf(null, State.GENERATING, State.READY).forEach { previous ->
                assertEquals(requested, StatusRowPolicy.nextState(requested, null, previous))
                assertEquals(requested, StatusRowPolicy.nextState(requested, StatusRowLogicalState.NO_CANDIDATE, previous))
            }
        }
    }
}
