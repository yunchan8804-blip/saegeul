/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphEnrichmentStagingStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the chunk attempt limit: a chunk the engine keeps failing on (an exception, or a
 * native crash that kills the process mid-chunk) is skipped after [MAX_CHUNK_ATTEMPTS] starts
 * instead of being retried by every later run.
 */
class GemmaGraphEnrichmentWorkerChunkAttemptsTest {

    private fun state(nextChunkIndex: Int = 1, currentChunkAttempts: Int = 0) =
        PersonalGraphEnrichmentStagingStore.State(
            cycleStartedMs = 1L,
            sourceSentenceCountAtStart = 0,
            chunks = listOf("가", "나", "다"),
            nextChunkIndex = nextChunkIndex,
            successCount = 1,
            failCount = 0,
            currentChunkAttempts = currentChunkAttempts
        )

    @Test
    fun aChunkIsStartedAgainUntilTheLimitIsReached() {
        assertFalse(isChunkAttemptLimitReached(0))
        assertFalse(isChunkAttemptLimitReached(MAX_CHUNK_ATTEMPTS - 1))
        assertTrue(isChunkAttemptLimitReached(MAX_CHUNK_ATTEMPTS))
        assertTrue(isChunkAttemptLimitReached(MAX_CHUNK_ATTEMPTS + 1))
    }

    @Test
    fun skippingAChunkCountsItFailedPassesItAndResetsTheAttempts() {
        val skipped = state(nextChunkIndex = 1, currentChunkAttempts = MAX_CHUNK_ATTEMPTS).withChunkSkipped()

        assertEquals(2, skipped.nextChunkIndex)
        assertEquals(1, skipped.failCount)
        assertEquals(1, skipped.successCount)
        assertEquals(1, skipped.skippedChunkCount)
        assertEquals(0, skipped.currentChunkAttempts)
    }

    @Test
    fun skippingCountsAcrossTheCycle() {
        val twice = state(nextChunkIndex = 0, currentChunkAttempts = MAX_CHUNK_ATTEMPTS)
            .withChunkSkipped()
            .copy(currentChunkAttempts = MAX_CHUNK_ATTEMPTS)
            .withChunkSkipped()

        assertEquals(2, twice.nextChunkIndex)
        assertEquals(2, twice.failCount)
        assertEquals(2, twice.skippedChunkCount)
    }
}
