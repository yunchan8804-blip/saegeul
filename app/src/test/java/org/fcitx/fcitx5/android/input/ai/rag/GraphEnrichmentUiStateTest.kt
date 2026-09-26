/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import org.fcitx.fcitx5.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [GraphEnrichmentUiState.from]: the state mapping the dashboard (and the manual
 * run's foreground notification) both render from, including the remaining-time estimate,
 * pause-reason mapping, the incremental-vs-full detail shape, and which states offer an action.
 */
class GraphEnrichmentUiStateTest {

    private fun stagingWith(
        nextChunkIndex: Int = 0,
        totalChunks: Int = 4,
        successCount: Int = 0,
        failCount: Int = 0,
        waitingOnLease: Boolean = false,
        waitingOnLeaseSinceMs: Long = 0L,
        waitingReason: GraphEnrichmentPauseReason = GraphEnrichmentPauseReason.NONE,
        completedChunkDurationMsSum: Long = 0L,
        pauseReason: GraphEnrichmentPauseReason = GraphEnrichmentPauseReason.NONE,
        manual: Boolean = true,
        isIncremental: Boolean = false,
        processedSentenceCount: Int = 0
    ) = PersonalGraphEnrichmentStagingStore.State(
        cycleStartedMs = 0L,
        sourceSentenceCountAtStart = 0,
        chunks = List(totalChunks) { "chunk$it" },
        nextChunkIndex = nextChunkIndex,
        successCount = successCount,
        failCount = failCount,
        waitingOnLease = waitingOnLease,
        waitingOnLeaseSinceMs = waitingOnLeaseSinceMs,
        waitingReason = waitingReason,
        completedChunkDurationMsSum = completedChunkDurationMsSum,
        pauseReason = pauseReason,
        manual = manual,
        isIncremental = isIncremental,
        processedSentenceCount = processedSentenceCount
    )

    private fun from(
        phase: GraphEnrichmentPhase,
        failure: GraphEnrichmentFailure = GraphEnrichmentFailure.NONE,
        staging: PersonalGraphEnrichmentStagingStore.State? = null,
        nowMs: Long = 0L,
        automaticEnabled: Boolean = false,
        lastAppliedMs: Long = 0L,
        graphNodes: Int = 0,
        graphEdges: Int = 0,
        graphTopics: Int = 0,
        failureDetail: String? = null
    ) = GraphEnrichmentUiState.from(
        phase, failure, staging, nowMs, automaticEnabled, lastAppliedMs, graphNodes, graphEdges, graphTopics, failureDetail
    )

    @Test
    fun runningWithNoCompletedChunksShowsCalculatingInsteadOfAnEta() {
        val state = from(GraphEnrichmentPhase.RUNNING, staging = stagingWith(nextChunkIndex = 0, totalChunks = 4))

        assertEquals(GraphEnrichmentUiState.Kind.RUNNING, state.kind)
        assertEquals(R.string.enrichment_state_running_title, state.titleRes)
        assertEquals(R.string.enrichment_state_running_detail_calculating, state.detailRes)
        assertEquals(R.string.enrichment_state_running_guidance, state.guidanceRes)
        assertNull(state.etaMinutes)
        assertEquals(0, state.progressCurrent)
        assertEquals(4, state.progressTotal)
        assertEquals(GraphEnrichmentUiState.Action.STOP, state.action)
    }

    @Test
    fun runningEtaIsAverageCompletedDurationTimesRemainingChunks() {
        // 2 chunks completed in 60_000ms total (avg 30_000ms each); 2 chunks remain -> 60_000ms -> 1 minute.
        val staging = stagingWith(
            nextChunkIndex = 2, totalChunks = 4, successCount = 2, completedChunkDurationMsSum = 60_000L
        )

        val state = from(GraphEnrichmentPhase.RUNNING, staging = staging)

        assertEquals(R.string.enrichment_state_running_detail_eta, state.detailRes)
        assertEquals(1, state.etaMinutes)
        assertEquals(2, state.progressCurrent)
        assertEquals(4, state.progressTotal)
    }

    @Test
    fun runningEtaRoundsUpToAtLeastOneMinute() {
        // 1 chunk completed in 1000ms; 1 remains -> 1000ms remaining, rounds up to the 1-minute floor.
        val staging = stagingWith(
            nextChunkIndex = 1, totalChunks = 2, successCount = 1, completedChunkDurationMsSum = 1_000L
        )

        val state = from(GraphEnrichmentPhase.RUNNING, staging = staging)

        assertEquals(1, state.etaMinutes)
    }

    @Test
    fun runningForAnAutomaticCycleOffersNoAction() {
        val staging = stagingWith(manual = false)

        val state = from(GraphEnrichmentPhase.RUNNING, staging = staging)

        assertEquals(GraphEnrichmentUiState.Kind.RUNNING, state.kind)
        assertNull(state.action)
    }

    @Test
    fun runningIncrementalUsesTheIncrementalDetailShapeWithTheSentenceCount() {
        val staging = stagingWith(
            nextChunkIndex = 1, totalChunks = 3, successCount = 1, completedChunkDurationMsSum = 60_000L,
            isIncremental = true, processedSentenceCount = 42
        )

        val state = from(GraphEnrichmentPhase.RUNNING, staging = staging)

        assertEquals(R.string.enrichment_state_running_detail_incremental_eta, state.detailRes)
        assertEquals(42, state.processedSentenceCount)
        assertEquals(true, state.isIncremental)
    }

    @Test
    fun runningIncrementalWithNoCompletedChunksUsesTheIncrementalCalculatingDetail() {
        val staging = stagingWith(isIncremental = true, processedSentenceCount = 10)

        val state = from(GraphEnrichmentPhase.RUNNING, staging = staging)

        assertEquals(R.string.enrichment_state_running_detail_incremental_calculating, state.detailRes)
    }

    @Test
    fun waitingUnderOneMinuteShowsTheNoCountDetail() {
        val staging = stagingWith(waitingOnLease = true, waitingOnLeaseSinceMs = 100_000L)

        val state = from(GraphEnrichmentPhase.RUNNING, staging = staging, nowMs = 150_000L)

        assertEquals(GraphEnrichmentUiState.Kind.WAITING, state.kind)
        assertEquals(R.string.enrichment_state_waiting_title, state.titleRes)
        assertEquals(R.string.enrichment_state_waiting_detail_now, state.detailRes)
        assertEquals(0, state.elapsedWaitMinutes)
        assertEquals(GraphEnrichmentUiState.Action.PRIORITIZE_GRAPH, state.action)
    }

    @Test
    fun waitingOverOneMinuteShowsTheElapsedMinuteCount() {
        val staging = stagingWith(waitingOnLease = true, waitingOnLeaseSinceMs = 0L)

        val state = from(GraphEnrichmentPhase.RUNNING, staging = staging, nowMs = 125_000L)

        assertEquals(R.string.enrichment_state_waiting_detail_minutes, state.detailRes)
        assertEquals(2, state.elapsedWaitMinutes)
    }

    @Test
    fun waitingBecauseOfTheKeyboardShowsADistinctButtonLessMessage() {
        val staging = stagingWith(waitingOnLease = true, waitingReason = GraphEnrichmentPauseReason.KEYBOARD_ACTIVE)

        val state = from(GraphEnrichmentPhase.RUNNING, staging = staging)

        assertEquals(GraphEnrichmentUiState.Kind.WAITING, state.kind)
        assertEquals(R.string.enrichment_state_waiting_keyboard_title, state.titleRes)
        assertEquals(R.string.enrichment_state_waiting_keyboard_guidance, state.guidanceRes)
        assertNull(state.detailRes)
        assertNull(state.action)
    }

    @Test
    fun waitingForMaterialGenerationRemainsTheGenericMessageWithItsAction() {
        val staging = stagingWith(waitingOnLease = true, waitingReason = GraphEnrichmentPauseReason.NONE)

        val state = from(GraphEnrichmentPhase.RUNNING, staging = staging)

        assertEquals(R.string.enrichment_state_waiting_title, state.titleRes)
        assertEquals(GraphEnrichmentUiState.Action.PRIORITIZE_GRAPH, state.action)
    }

    @Test
    fun waitingForAnAutomaticCycleOffersNoAction() {
        val staging = stagingWith(waitingOnLease = true, manual = false)

        val state = from(GraphEnrichmentPhase.RUNNING, staging = staging)

        assertEquals(GraphEnrichmentUiState.Kind.WAITING, state.kind)
        assertNull(state.action)
    }

    @Test
    fun queuedHasATitleAndNoDetail() {
        val state = from(GraphEnrichmentPhase.QUEUED)

        assertEquals(GraphEnrichmentUiState.Kind.QUEUED, state.kind)
        assertEquals(R.string.enrichment_state_queued_title, state.titleRes)
        assertNull(state.detailRes)
        assertNull(state.action)
    }

    @Test
    fun pausedWithStagingShowsProgressAndTheMappedReason() {
        val staging = stagingWith(nextChunkIndex = 3, totalChunks = 14, pauseReason = GraphEnrichmentPauseReason.BATTERY_LOW)

        val state = from(GraphEnrichmentPhase.INTERRUPTED, staging = staging, automaticEnabled = true)

        assertEquals(GraphEnrichmentUiState.Kind.PAUSED, state.kind)
        assertEquals(R.string.enrichment_state_paused_title, state.titleRes)
        assertEquals(3, state.progressCurrent)
        assertEquals(14, state.progressTotal)
        assertEquals(R.string.enrichment_pause_reason_battery_low, state.detailRes)
        assertEquals(R.string.enrichment_state_paused_auto_hint, state.secondaryGuidanceRes)
        assertEquals(GraphEnrichmentUiState.Action.RESUME, state.action)
    }

    @Test
    fun pausedWithoutStagingFallsBackToTheNoProgressTitleAndTheAppTerminatedReason() {
        val state = from(GraphEnrichmentPhase.INTERRUPTED, staging = null, automaticEnabled = false)

        assertEquals(R.string.enrichment_state_paused_title_no_progress, state.titleRes)
        // NONE only survives to here when the process was killed outright mid-cycle (every
        // controlled pause path records a concrete reason before returning) - never a generic
        // "unknown reason" message.
        assertEquals(R.string.enrichment_pause_reason_app_terminated, state.detailRes)
        assertNull(state.secondaryGuidanceRes)
    }

    @Test
    fun pauseReasonNoneAlwaysMapsToTheAppTerminatedTextNeverAGenericUnknownOne() {
        val state = from(GraphEnrichmentPhase.INTERRUPTED, staging = stagingWith(pauseReason = GraphEnrichmentPauseReason.NONE))

        assertEquals(R.string.enrichment_pause_reason_app_terminated, state.detailRes)
    }

    @Test
    fun everyPauseReasonMapsToADistinctDetailResource() {
        val resources = GraphEnrichmentPauseReason.entries.associateWith { reason ->
            from(GraphEnrichmentPhase.INTERRUPTED, staging = stagingWith(pauseReason = reason)).detailRes
        }
        assertEquals(GraphEnrichmentPauseReason.entries.size, resources.values.toSet().size)
    }

    @Test
    fun succeededCarriesTheAppliedGraphNumbersAndOffersTheActionWhenAutomaticIsOff() {
        val state = from(
            GraphEnrichmentPhase.SUCCEEDED,
            automaticEnabled = false,
            lastAppliedMs = 42_000L, graphNodes = 30, graphEdges = 18, graphTopics = 10
        )

        assertEquals(GraphEnrichmentUiState.Kind.SUCCEEDED, state.kind)
        assertEquals(R.string.enrichment_state_succeeded_title, state.titleRes)
        assertEquals(R.string.enrichment_state_succeeded_detail, state.detailRes)
        assertEquals(42_000L, state.lastAppliedMs)
        assertEquals(30, state.graphNodes)
        assertEquals(18, state.graphEdges)
        assertEquals(10, state.graphTopics)
        assertEquals(GraphEnrichmentUiState.Action.RETRY, state.action)
        assertNull(state.guidanceRes)
    }

    @Test
    fun succeededHidesTheActionAndShowsTheAutoHintWhenAutomaticIsOn() {
        val state = from(GraphEnrichmentPhase.SUCCEEDED, automaticEnabled = true)

        assertNull(state.action)
        assertEquals(R.string.enrichment_state_succeeded_auto_hint, state.guidanceRes)
    }

    @Test
    fun partialUsesItsOwnTitleButTheSameDetailShapeAsSucceeded() {
        val state = from(GraphEnrichmentPhase.PARTIAL)

        assertEquals(GraphEnrichmentUiState.Kind.SUCCEEDED, state.kind)
        assertEquals(R.string.enrichment_state_partial_title, state.titleRes)
        assertEquals(R.string.enrichment_state_succeeded_detail, state.detailRes)
    }

    @Test
    fun neverAndNoDataBothOfferToCreate() {
        val never = from(GraphEnrichmentPhase.NEVER)
        val noData = from(GraphEnrichmentPhase.NO_DATA)

        assertEquals(GraphEnrichmentUiState.Kind.NEVER, never.kind)
        assertEquals(GraphEnrichmentUiState.Kind.NEVER, noData.kind)
        assertEquals(R.string.enrichment_state_never_title, never.titleRes)
        assertEquals(never.titleRes, noData.titleRes)
        assertEquals(GraphEnrichmentUiState.Action.CREATE, never.action)
    }

    @Test
    fun modelUnavailableOffersModelManagement() {
        val state = from(GraphEnrichmentPhase.FAILED, failure = GraphEnrichmentFailure.MODEL_UNAVAILABLE)

        assertEquals(GraphEnrichmentUiState.Kind.MODEL_UNAVAILABLE, state.kind)
        assertEquals(R.string.enrichment_state_model_unavailable_title, state.titleRes)
        assertEquals(GraphEnrichmentUiState.Action.OPEN_MODEL_MANAGEMENT, state.action)
    }

    @Test
    fun anyOtherFailureFallsBackToTheGenericErrorStateWithItsOwnReasonText() {
        val state = from(GraphEnrichmentPhase.FAILED, failure = GraphEnrichmentFailure.STORAGE)

        assertEquals(GraphEnrichmentUiState.Kind.ERROR, state.kind)
        assertEquals(R.string.enrichment_state_error_title, state.titleRes)
        assertEquals(GraphEnrichmentFailureText.resourceIdFor(GraphEnrichmentFailure.STORAGE), state.detailRes)
        assertEquals(GraphEnrichmentUiState.Action.RETRY, state.action)
    }

    @Test
    fun aGenuinelyUnhandledExceptionShowsTheErrorCodeInsteadOfAGenericMessage() {
        val state = from(GraphEnrichmentPhase.FAILED, failure = GraphEnrichmentFailure.UNKNOWN, failureDetail = "IllegalStateException")

        assertEquals(GraphEnrichmentUiState.Kind.ERROR, state.kind)
        assertEquals(R.string.enrichment_state_error_title_with_code, state.titleRes)
        assertEquals("IllegalStateException", state.errorDetailArg)
        assertEquals(GraphEnrichmentUiState.Action.RETRY, state.action)
    }

    @Test
    fun anUnknownFailureWithNoDetailFallsBackToTheGenericErrorStateNotTheErrorCodeOne() {
        // UNKNOWN can also come from other paths (e.g. GraphEnrichmentStatusStore.recordResult's own
        // catch-all) that never captured an exception class name - only a genuine unhandled
        // exception (which always carries one) reaches the error-code state.
        val state = from(GraphEnrichmentPhase.FAILED, failure = GraphEnrichmentFailure.UNKNOWN, failureDetail = null)

        assertEquals(R.string.enrichment_state_error_title, state.titleRes)
        assertNull(state.errorDetailArg)
    }
}
