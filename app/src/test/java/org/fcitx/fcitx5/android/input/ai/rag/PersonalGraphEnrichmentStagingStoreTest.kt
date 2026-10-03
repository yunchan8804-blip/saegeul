/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersonalGraphEnrichmentStagingStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun stateOf(
        chunks: List<String> = listOf("한 조각"),
        nextChunkIndex: Int = 0,
        successCount: Int = 0,
        failCount: Int = 0,
        waitingOnLease: Boolean = false,
        waitingOnLeaseSinceMs: Long = 0L,
        completedChunkDurationMsSum: Long = 0L,
        pauseReason: GraphEnrichmentPauseReason = GraphEnrichmentPauseReason.NONE,
        manual: Boolean = false,
        isIncremental: Boolean = false,
        processedSentenceCount: Int = 0
    ) = PersonalGraphEnrichmentStagingStore.State(
        cycleStartedMs = 1L,
        sourceSentenceCountAtStart = 0,
        chunks = chunks,
        nextChunkIndex = nextChunkIndex,
        successCount = successCount,
        failCount = failCount,
        waitingOnLease = waitingOnLease,
        waitingOnLeaseSinceMs = waitingOnLeaseSinceMs,
        completedChunkDurationMsSum = completedChunkDurationMsSum,
        pauseReason = pauseReason,
        manual = manual,
        isIncremental = isIncremental,
        processedSentenceCount = processedSentenceCount
    )

    @Test
    fun loadReturnsNullWhenNothingWasSaved() {
        val store = PersonalGraphEnrichmentStagingStore(tempFolder.newFile("staging_missing.json").also { it.delete() })

        assertNull(store.load())
    }

    @Test
    fun savedStateRoundTripsExactly() {
        val store = PersonalGraphEnrichmentStagingStore(tempFolder.newFile("staging.json"))
        val state = stateOf(
            chunks = listOf("첫 번째 조각", "두 번째 조각"),
            nextChunkIndex = 1,
            successCount = 1,
            failCount = 0,
            manual = true,
            isIncremental = true,
            processedSentenceCount = 42
        )

        store.save(state)
        val loaded = store.load()

        assertEquals(state, loaded)
    }

    @Test
    fun waitingOnLeaseRoundTripsAndDefaultsToFalse() {
        val store = PersonalGraphEnrichmentStagingStore(tempFolder.newFile("staging_waiting.json"))
        val state = stateOf()
        assertEquals(false, state.waitingOnLease)

        store.save(state.copy(waitingOnLease = true, waitingOnLeaseSinceMs = 123L))
        val waiting = requireNotNull(store.load())
        assertEquals(true, waiting.waitingOnLease)
        assertEquals(123L, waiting.waitingOnLeaseSinceMs)

        store.save(state.copy(waitingOnLease = false))
        assertEquals(false, requireNotNull(store.load()).waitingOnLease)
    }

    @Test
    fun waitingReasonRoundTripsAndDefaultsToNone() {
        val store = PersonalGraphEnrichmentStagingStore(tempFolder.newFile("staging_waiting_reason.json"))
        val state = stateOf()
        assertEquals(GraphEnrichmentPauseReason.NONE, state.waitingReason)

        store.save(state.copy(waitingOnLease = true, waitingReason = GraphEnrichmentPauseReason.KEYBOARD_ACTIVE))
        val waiting = requireNotNull(store.load())
        assertEquals(GraphEnrichmentPauseReason.KEYBOARD_ACTIVE, waiting.waitingReason)

        store.save(state.copy(waitingOnLease = false))
        assertEquals(GraphEnrichmentPauseReason.NONE, requireNotNull(store.load()).waitingReason)
    }

    @Test
    fun lastWaitMsRoundTripsAndDefaultsToZero() {
        val store = PersonalGraphEnrichmentStagingStore(tempFolder.newFile("staging_last_wait.json"))
        val state = stateOf()
        assertEquals(0L, state.lastWaitMs)

        store.save(state.copy(lastWaitMs = 4_200L))

        assertEquals(4_200L, requireNotNull(store.load()).lastWaitMs)
    }

    @Test
    fun skippedSentenceCountRoundTripsAndDefaultsToZero() {
        val store = PersonalGraphEnrichmentStagingStore(tempFolder.newFile("staging_skipped.json"))
        val state = stateOf()
        assertEquals(0, state.skippedSentenceCount)

        store.save(state)
        assertEquals(0, requireNotNull(store.load()).skippedSentenceCount)

        store.save(state.copy(skippedSentenceCount = 3))
        assertEquals(3, requireNotNull(store.load()).skippedSentenceCount)
    }

    @Test
    fun clearRemovesTheSavedCycle() {
        val store = PersonalGraphEnrichmentStagingStore(tempFolder.newFile("staging_clear.json"))
        store.save(stateOf())

        store.clear()

        assertNull(store.load())
    }

    @Test
    fun nextChunkIndexIsClampedToTheStoredChunkCount() {
        val file = tempFolder.newFile("staging_clamped.json")
        val writer = PersonalGraphEnrichmentStagingStore(file)
        writer.save(stateOf(chunks = listOf("한 조각"), nextChunkIndex = 99))

        val loaded = requireNotNull(PersonalGraphEnrichmentStagingStore(file).load())

        assertTrue(loaded.nextChunkIndex <= loaded.chunks.size)
    }
}
