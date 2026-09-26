/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsStore
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentFailure
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentPhase
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentPauseReason
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentUiState
import org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphEnrichmentStagingStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the vault home screen's pure state models: [LearningStatusUiState] (the
 * "새글이 배우는 중" card's priority-ordered single status), [AcceptanceCardUiState] and
 * [VaultStyleChips].
 */
class VaultHomeUiStateTest {

    private fun staging(
        nextChunkIndex: Int = 0,
        totalChunks: Int = 4,
        successCount: Int = 0,
        waitingOnLease: Boolean = false,
        waitingReason: GraphEnrichmentPauseReason = GraphEnrichmentPauseReason.NONE,
        completedChunkDurationMsSum: Long = 0L,
        pauseReason: GraphEnrichmentPauseReason = GraphEnrichmentPauseReason.NONE,
        manual: Boolean = true
    ) = PersonalGraphEnrichmentStagingStore.State(
        cycleStartedMs = 0L,
        sourceSentenceCountAtStart = 0,
        chunks = List(totalChunks) { "chunk$it" },
        nextChunkIndex = nextChunkIndex,
        successCount = successCount,
        failCount = 0,
        waitingOnLease = waitingOnLease,
        waitingOnLeaseSinceMs = 0L,
        waitingReason = waitingReason,
        completedChunkDurationMsSum = completedChunkDurationMsSum,
        pauseReason = pauseReason,
        manual = manual,
        isIncremental = false,
        processedSentenceCount = 0
    )

    private fun graph(
        phase: GraphEnrichmentPhase,
        failure: GraphEnrichmentFailure = GraphEnrichmentFailure.NONE,
        staging: PersonalGraphEnrichmentStagingStore.State? = null,
        automaticEnabled: Boolean = false,
        lastAppliedMs: Long = 0L,
        failureDetail: String? = null
    ) = GraphEnrichmentUiState.from(
        phase = phase, failure = failure, staging = staging, nowMs = 0L,
        automaticEnabled = automaticEnabled, lastAppliedMs = lastAppliedMs,
        graphNodes = 0, graphEdges = 0, graphTopics = 0, failureDetail = failureDetail
    )

    private fun from(
        onDeviceAiAvailable: Boolean = true,
        engineFailed: Boolean = false,
        graph: GraphEnrichmentUiState? = null,
        materialActive: Boolean = false,
        automaticEnabled: Boolean = false,
        notificationBlocked: Boolean = false,
        appliedAtMs: Long = 0L
    ) = LearningStatusUiState.from(
        onDeviceAiAvailable, engineFailed, graph, materialActive, automaticEnabled, notificationBlocked, appliedAtMs
    )

    @Test
    fun unsupportedDeviceAlwaysWinsRegardlessOfEverythingElseButStillOffersTheButton() {
        val state = from(
            onDeviceAiAvailable = false,
            engineFailed = true,
            graph = graph(GraphEnrichmentPhase.RUNNING, staging = staging()),
            materialActive = true
        )

        assertEquals(LearningStatusUiState.Tier.RELEASE, state.tier)
        assertEquals(R.string.vault_learning_body_release, state.bodyRes)
        assertFalse(state.showAutomaticSwitch)
        assertEquals(R.string.enrichment_action_apply_now, state.buttonLabelRes)
        assertEquals(GraphEnrichmentUiState.Action.CREATE, state.buttonAction)
    }

    @Test
    fun engineFailureBeatsAnInProgressGraphButStillOffersTheButton() {
        val state = from(
            engineFailed = true,
            graph = graph(GraphEnrichmentPhase.RUNNING, staging = staging())
        )

        assertEquals(LearningStatusUiState.Tier.ENGINE_FAILED, state.tier)
        assertTrue(state.showAutomaticSwitch)
        assertEquals(R.string.enrichment_action_apply_now, state.buttonLabelRes)
        assertEquals(GraphEnrichmentUiState.Action.CREATE, state.buttonAction)
    }

    @Test
    fun runningWithAnEtaShowsMinutesAndProgress() {
        val staged = staging(nextChunkIndex = 2, totalChunks = 4, successCount = 2, completedChunkDurationMsSum = 60_000L)
        val state = from(graph = graph(GraphEnrichmentPhase.RUNNING, staging = staged))

        assertEquals(LearningStatusUiState.Tier.RUNNING, state.tier)
        assertEquals(R.string.vault_learning_body_running_eta, state.bodyRes)
        assertEquals(1, state.bodyIntArg)
        assertTrue(state.showProgress)
        assertEquals(2, state.progressCurrent)
        assertEquals(4, state.progressTotal)
    }

    @Test
    fun runningWithNoCompletedChunksShowsCalculating() {
        val state = from(graph = graph(GraphEnrichmentPhase.RUNNING, staging = staging()))

        assertEquals(R.string.vault_learning_body_running_calculating, state.bodyRes)
        assertNull(state.bodyIntArg)
    }

    @Test
    fun runningBeatsMaterialGenerationRunningOnItsOwn() {
        val state = from(graph = graph(GraphEnrichmentPhase.RUNNING, staging = staging()), materialActive = true)

        assertEquals(LearningStatusUiState.Tier.RUNNING, state.tier)
    }

    @Test
    fun waitingForMaterialGenerationIsStartingSoonWithNoButton() {
        val staged = staging(waitingOnLease = true, waitingReason = GraphEnrichmentPauseReason.NONE)
        val state = from(graph = graph(GraphEnrichmentPhase.RUNNING, staging = staged), notificationBlocked = true)

        assertEquals(LearningStatusUiState.Tier.WAITING, state.tier)
        assertEquals(R.string.vault_learning_body_waiting, state.bodyRes)
        assertNull(state.buttonAction)
        assertTrue(state.showNotificationHint)
    }

    @Test
    fun waitingBecauseOfTheKeyboardIsItsOwnTier() {
        val staged = staging(waitingOnLease = true, waitingReason = GraphEnrichmentPauseReason.KEYBOARD_ACTIVE)
        val state = from(graph = graph(GraphEnrichmentPhase.RUNNING, staging = staged))

        assertEquals(LearningStatusUiState.Tier.WAITING_KEYBOARD, state.tier)
        assertEquals(R.string.vault_learning_body_waiting_keyboard, state.bodyRes)
    }

    @Test
    fun queuedIsAlsoStartingSoon() {
        val state = from(graph = graph(GraphEnrichmentPhase.QUEUED))

        assertEquals(LearningStatusUiState.Tier.WAITING, state.tier)
        assertEquals(R.string.vault_learning_body_waiting, state.bodyRes)
    }

    @Test
    fun pausedShowsTheGraphsOwnPlainLanguageReason() {
        val staged = staging(pauseReason = GraphEnrichmentPauseReason.BATTERY_LOW)
        val state = from(graph = graph(GraphEnrichmentPhase.INTERRUPTED, staging = staged))

        assertEquals(LearningStatusUiState.Tier.PAUSED, state.tier)
        assertEquals(R.string.vault_learning_body_paused, state.bodyRes)
        assertEquals(R.string.enrichment_pause_reason_battery_low, state.reasonRes)
        assertEquals(GraphEnrichmentUiState.Action.RESUME, state.buttonAction)
    }

    @Test
    fun errorFallsIntoTheSamePausedBucketWithAGenericReason() {
        val state = from(graph = graph(GraphEnrichmentPhase.FAILED, failure = GraphEnrichmentFailure.STORAGE))

        assertEquals(LearningStatusUiState.Tier.PAUSED, state.tier)
        assertEquals(R.string.vault_learning_reason_problem, state.reasonRes)
        assertEquals(GraphEnrichmentUiState.Action.RETRY, state.buttonAction)
    }

    @Test
    fun modelUnavailableOffersTheGetModelButton() {
        val state = from(graph = graph(GraphEnrichmentPhase.FAILED, failure = GraphEnrichmentFailure.MODEL_UNAVAILABLE))

        assertEquals(LearningStatusUiState.Tier.MODEL_UNAVAILABLE, state.tier)
        assertEquals(R.string.vault_learning_action_get_model, state.buttonLabelRes)
        assertEquals(GraphEnrichmentUiState.Action.OPEN_MODEL_MANAGEMENT, state.buttonAction)
    }

    @Test
    fun materialOnlyShowsWhenTheGraphHasNothingToDoButMaterialIsRunning() {
        val state = from(graph = graph(GraphEnrichmentPhase.NEVER), materialActive = true)

        assertEquals(LearningStatusUiState.Tier.MATERIAL_ONLY, state.tier)
        assertEquals(R.string.vault_learning_body_material_only, state.bodyRes)
        assertFalse(state.showProgress)
        assertEquals(R.string.enrichment_action_apply_now, state.buttonLabelRes)
        assertEquals(GraphEnrichmentUiState.Action.RETRY, state.buttonAction)
    }

    @Test
    fun upToDateWithATimestampUsesTheAppliedAtBody() {
        val state = from(
            graph = graph(GraphEnrichmentPhase.SUCCEEDED, automaticEnabled = false, lastAppliedMs = 1_000L),
            appliedAtMs = 1_000L
        )

        assertEquals(LearningStatusUiState.Tier.UP_TO_DATE, state.tier)
        assertEquals(R.string.vault_learning_body_up_to_date, state.bodyRes)
        assertEquals(1_000L, state.appliedAtMs)
        assertEquals(GraphEnrichmentUiState.Action.RETRY, state.buttonAction)
    }

    @Test
    fun upToDateWithNothingEverAppliedUsesTheReadyBody() {
        val state = from(graph = graph(GraphEnrichmentPhase.NEVER), appliedAtMs = 0L)

        assertEquals(LearningStatusUiState.Tier.UP_TO_DATE, state.tier)
        assertEquals(R.string.vault_learning_body_ready, state.bodyRes)
    }

    @Test
    fun upToDateStillOffersTheButtonWhenAutomaticIsOn() {
        val state = from(graph = graph(GraphEnrichmentPhase.SUCCEEDED, automaticEnabled = true))

        assertEquals(R.string.enrichment_action_apply_now, state.buttonLabelRes)
        assertEquals(GraphEnrichmentUiState.Action.RETRY, state.buttonAction)
    }

    // -- AcceptanceCardUiState --

    private fun summary(
        totalShown: Int = 0,
        totalAccepted: Int = 0,
        acceptRate: Float = 0f,
        personalShare: Float = 0f
    ) = PredictionMetricsStore.Summary(
        totalShown = totalShown,
        totalAccepted = totalAccepted,
        acceptRate = acceptRate,
        personalShare = personalShare,
        keystrokesSaved = 0,
        typoCorrected = 0,
        learnedSentences = 0,
        learnedWords = 0,
        activeDays = 0,
        firstDay = null,
        lastDay = null,
        recent = emptyList()
    )

    @Test
    fun acceptanceBeforeAnySuggestionShownHasNoData() {
        val state = AcceptanceCardUiState.from(summary(totalShown = 0))

        assertFalse(state.hasData)
    }

    @Test
    fun acceptanceAfterSuggestionsShownComputesPercentages() {
        val state = AcceptanceCardUiState.from(
            summary(totalShown = 40, totalAccepted = 30, acceptRate = 0.75f, personalShare = 0.6f)
        )

        assertTrue(state.hasData)
        assertEquals(75, state.acceptPercent)
        assertEquals(40, state.totalShown)
        assertEquals(30, state.totalAccepted)
        assertEquals(60, state.personalPercent)
    }

    // -- VaultStyleChips --

    @Test
    fun chipFilterDropsOneCharacterDigitAndSymbolOnlyTokens() {
        val result = VaultStyleChips.filterChips(listOf("안녕", "1", "12", "!!", "가", "고마워"))

        assertEquals(listOf("안녕", "고마워"), result)
    }

    @Test
    fun chipFilterCapsAtTheLimit() {
        val words = (1..12).map { "단어$it" }

        val result = VaultStyleChips.filterChips(words, limit = 8)

        assertEquals(8, result.size)
    }

    @Test
    fun topCategoryIdsPicksTheTwoHighestNonZeroCounts() {
        val counts = mapOf("messenger" to 5, "work" to 20, "general" to 0, "browser" to 8)

        val result = VaultStyleChips.topCategoryIds(counts)

        assertEquals(listOf("work", "browser"), result)
    }

    @Test
    fun topCategoryIdsIsEmptyWhenNothingHasBeenCategorizedYet() {
        val result = VaultStyleChips.topCategoryIds(emptyMap())

        assertTrue(result.isEmpty())
    }
}
