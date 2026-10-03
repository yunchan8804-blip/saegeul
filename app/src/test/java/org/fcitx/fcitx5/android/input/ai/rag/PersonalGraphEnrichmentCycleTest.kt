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
        assertFalse(
            PersonalGraphEnrichmentCycle.shouldStart(
                hasExistingGraph = false, newSentenceCount = 9, msSinceLastBuild = 0L, manual = false
            )
        )
        assertTrue(
            PersonalGraphEnrichmentCycle.shouldStart(
                hasExistingGraph = false, newSentenceCount = 10, msSinceLastBuild = 0L, manual = false
            )
        )
    }

    @Test
    fun subsequentCycleNeedsBothTenSentencesAndOneHour() {
        val justUnderInterval = PersonalGraphEnrichmentCycle.MIN_INTERVAL_MS - 1
        val exactlyInterval = PersonalGraphEnrichmentCycle.MIN_INTERVAL_MS

        assertFalse(
            PersonalGraphEnrichmentCycle.shouldStart(
                hasExistingGraph = true, newSentenceCount = 10, msSinceLastBuild = justUnderInterval, manual = false
            )
        )
        assertFalse(
            PersonalGraphEnrichmentCycle.shouldStart(
                hasExistingGraph = true, newSentenceCount = 9, msSinceLastBuild = exactlyInterval, manual = false
            )
        )
        assertTrue(
            PersonalGraphEnrichmentCycle.shouldStart(
                hasExistingGraph = true, newSentenceCount = 10, msSinceLastBuild = exactlyInterval, manual = false
            )
        )
    }

    @Test
    fun manualBypassesBothThresholdsRegardlessOfGraphHistory() {
        assertTrue(
            PersonalGraphEnrichmentCycle.shouldStart(
                hasExistingGraph = false, newSentenceCount = 0, msSinceLastBuild = 0L, manual = true
            )
        )
        assertTrue(
            PersonalGraphEnrichmentCycle.shouldStart(
                hasExistingGraph = true, newSentenceCount = 0, msSinceLastBuild = 0L, manual = true
            )
        )
        assertTrue(
            PersonalGraphEnrichmentCycle.shouldStart(
                hasExistingGraph = true, newSentenceCount = 9, msSinceLastBuild = 1L, manual = true
            )
        )
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
            PersonalGraphEnrichmentCycle.shouldStart(true, sizeDifference, now - graphBuiltMs, manual = false)
        )

        val newSentences = PersonalGraphEnrichmentCycle.newSentenceCount(true, vault, graphBuiltMs)
        assertEquals(PersonalGraphEnrichmentCycle.MIN_NEW_SENTENCES, newSentences)
        assertTrue(
            PersonalGraphEnrichmentCycle.shouldStart(true, newSentences, now - graphBuiltMs, manual = false)
        )
    }

    @Test
    fun withoutAnExistingGraphEveryStoredSentenceIsNew() {
        val vault = PersonalSentenceVault(clock = { 1_000L })
        repeat(12) { assertTrue(vault.record(sentence(it), "com.android.chrome")) }

        assertEquals(12, PersonalGraphEnrichmentCycle.newSentenceCount(false, vault, graphBuiltMs = 0L))
    }

    private fun sentence(index: Int): String = "하루 ${(0xAC00 + index).toChar()}다 기록"

    private companion object {
        const val VAULT_CAPACITY = 3000
    }
}
