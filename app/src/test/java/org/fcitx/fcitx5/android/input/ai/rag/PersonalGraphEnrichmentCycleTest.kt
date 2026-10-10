/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalGraphEnrichmentCycleTest {

    @Test
    fun firstEverCycleOnlyNeedsThirtySentences() {
        assertFalse(startsAutomatically(hasExistingGraph = false, newSentenceCount = 9, msSinceLastBuild = 0L))
        assertTrue(startsAutomatically(hasExistingGraph = false, newSentenceCount = 10, msSinceLastBuild = 0L))
    }

    @Test
    fun subsequentCycleNeedsBothTenSentencesAndOneHour() {
        val justUnderInterval = PersonalGraphEnrichmentCycle.MIN_INTERVAL_MS - 1
        val exactlyInterval = PersonalGraphEnrichmentCycle.MIN_INTERVAL_MS

        assertFalse(startsAutomatically(hasExistingGraph = true, newSentenceCount = 10, msSinceLastBuild = justUnderInterval))
        assertFalse(startsAutomatically(hasExistingGraph = true, newSentenceCount = 9, msSinceLastBuild = exactlyInterval))
        assertTrue(startsAutomatically(hasExistingGraph = true, newSentenceCount = 10, msSinceLastBuild = exactlyInterval))
    }

    @Test
    fun aVaultAtItsCapacityStillStartsAnAutomaticCycleForSentencesNewSinceTheGraph() {
        var now = 1_000L
        val vault = PersonalSentenceVault(clock = { now })
        repeat(VAULT_CAPACITY) { assertTrue(vault.record(sentence(it), "com.android.chrome")) }
        val graphBuiltMs = 1_500L
        val graphSourceSentenceCount = vault.stats().sentences
        now = graphBuiltMs + PersonalGraphEnrichmentCycle.MIN_INTERVAL_MS
        repeat(PersonalGraphEnrichmentCycle.MIN_NEW_SENTENCES) {
            assertTrue(vault.record(sentence(VAULT_CAPACITY + it), "com.android.chrome"))
        }

        assertEquals(VAULT_CAPACITY, vault.stats().sentences)
        val sizeDifference = (vault.stats().sentences - graphSourceSentenceCount).coerceAtLeast(0)
        assertFalse(
            "a size difference can never see new sentences once the vault is full",
            startsAutomatically(true, sizeDifference, now - graphBuiltMs)
        )

        val newSentences = PersonalGraphEnrichmentCycle.newSentenceCount(vault, graphBuiltMs)
        assertEquals(PersonalGraphEnrichmentCycle.MIN_NEW_SENTENCES, newSentences)
        assertTrue(
            startsAutomatically(true, newSentences, now - graphBuiltMs)
        )
    }

    @Test
    fun aBacklogOfOneCycleOrMoreStartsWithoutWaitingForTheInterval() {
        val backlog = PersonalGraphEnrichmentCycle.MAX_INCREMENTAL_SENTENCES
        assertTrue(startsAutomatically(hasExistingGraph = true, newSentenceCount = backlog, msSinceLastBuild = 0L))
        assertFalse(startsAutomatically(hasExistingGraph = true, newSentenceCount = backlog - 1, msSinceLastBuild = 0L))
        assertEquals(
            GraphEnrichmentSkipReason.TOO_SOON,
            PersonalGraphEnrichmentCycle.automaticSkipReason(true, backlog - 1, msSinceLastBuild = 0L)
        )
    }

    @Test
    fun theMinimumSentenceCountStillAppliesToABacklogDecision() {
        assertEquals(
            GraphEnrichmentSkipReason.NOT_ENOUGH_NEW_SENTENCES,
            PersonalGraphEnrichmentCycle.automaticSkipReason(
                true, PersonalGraphEnrichmentCycle.MIN_NEW_SENTENCES - 1, msSinceLastBuild = Long.MAX_VALUE
            )
        )
        assertEquals(
            GraphEnrichmentSkipReason.NONE,
            PersonalGraphEnrichmentCycle.automaticSkipReason(
                true, PersonalGraphEnrichmentCycle.MIN_NEW_SENTENCES, msSinceLastBuild = PersonalGraphEnrichmentCycle.MIN_INTERVAL_MS
            )
        )
    }

    @Test
    fun withoutAnExistingGraphEveryStoredSentenceIsNew() {
        val vault = PersonalSentenceVault(clock = { 1_000L })
        repeat(12) { assertTrue(vault.record(sentence(it), "com.android.chrome")) }

        assertEquals(12, PersonalGraphEnrichmentCycle.newSentenceCount(vault, enrichedThroughMs = 0L))
    }

    private fun startsAutomatically(
        hasExistingGraph: Boolean,
        newSentenceCount: Int,
        msSinceLastBuild: Long
    ): Boolean = PersonalGraphEnrichmentCycle.automaticSkipReason(
        hasExistingGraph, newSentenceCount, msSinceLastBuild
    ) == GraphEnrichmentSkipReason.NONE

    private fun sentence(index: Int): String = "하루 ${(0xAC00 + index).toChar()}다 기록"

    private companion object {
        const val VAULT_CAPACITY = 3000
    }
}
