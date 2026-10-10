/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The enrichment cursor ([PersonalGraphStore.GraphStats.enrichedThroughMs]) as the worker uses it:
 * export the oldest unprocessed sentences, move the cursor to the newest exported one when the cycle
 * ends, and never lose sentences that arrive or are left over meanwhile.
 */
class GraphEnrichmentCursorTest {

    private fun sentence(index: Int): String = "하루 ${(0xAC00 + index).toChar()}다 기록"

    private fun graphBuiltAt(builtMs: Long): PersonalGraphStore =
        PersonalGraphStore().also { it.replaceGraph(emptyList(), emptyList(), emptyList(), builtMs) }

    @Test
    fun aBacklogLargerThanOneCycleIsWorkedOffOldestFirstAndTheRestStaysNew() {
        var now = 1_000L
        val vault = PersonalSentenceVault(clock = { now })
        repeat(130) {
            now = 1_000L + it
            assertTrue(vault.record(sentence(it), "com.android.chrome"))
        }
        val store = graphBuiltAt(500L)
        val cursor = store.stats().enrichedThroughMs
        assertEquals(130, PersonalGraphEnrichmentCycle.newSentenceCount(vault, cursor))

        val exported = vault.exportSince(cursor, PersonalGraphEnrichmentCycle.MAX_INCREMENTAL_SENTENCES)

        assertEquals((0 until 120).map { sentence(it) }, exported.map { it.text })
        val throughMs = exported.maxOf { it.lastSeenMs }
        assertEquals(1_119L, throughMs)
        assertTrue(store.advanceEnrichedThrough(throughMs, store.generation()))

        val nextCursor = store.stats().enrichedThroughMs
        assertEquals(1_119L, nextCursor)
        assertEquals(10, PersonalGraphEnrichmentCycle.newSentenceCount(vault, nextCursor))
        assertEquals((120 until 130).map { sentence(it) }, vault.exportSince(nextCursor, 120).map { it.text })
    }

    @Test
    fun sentencesTypedWhileACycleRunsAreStillNewAfterItsChunksMovedTheBuiltTime() {
        var now = 1_000L
        val vault = PersonalSentenceVault(clock = { now })
        repeat(15) {
            now = 1_000L + it
            vault.record(sentence(it), "com.android.chrome")
        }
        val store = graphBuiltAt(500L)
        val exported = vault.exportSince(store.stats().enrichedThroughMs, 120)
        val throughMs = exported.maxOf { it.lastSeenMs }

        // While the cycle runs, five more sentences are typed and a merged chunk stamps a later built time.
        repeat(5) {
            now = 1_100L + it
            vault.record(sentence(15 + it), "com.android.chrome")
        }
        store.replaceGraph(emptyList(), emptyList(), emptyList(), builtMs = 1_200L)
        store.advanceEnrichedThrough(throughMs, store.generation())

        val stats = store.stats()
        assertEquals(1_200L, stats.builtMs)
        assertEquals(5, PersonalGraphEnrichmentCycle.newSentenceCount(vault, stats.enrichedThroughMs))
        assertEquals(
            "counting from the built time would have lost them",
            0,
            vault.countSince(stats.builtMs)
        )
    }

    @Test
    fun withoutAGraphTheFirstCycleAlsoTakesTheOldest120AndLeavesTheRestNew() {
        var now = 1_000L
        val vault = PersonalSentenceVault(clock = { now })
        repeat(130) {
            now = 1_000L + it
            assertTrue(vault.record(sentence(it), "com.android.chrome"))
        }
        val store = PersonalGraphStore()
        val stats = store.stats()
        assertEquals(0L, stats.builtMs)
        assertEquals(0L, stats.enrichedThroughMs)
        assertEquals(130, PersonalGraphEnrichmentCycle.newSentenceCount(vault, stats.enrichedThroughMs))

        val exported = vault.exportSince(stats.enrichedThroughMs, PersonalGraphEnrichmentCycle.MAX_INCREMENTAL_SENTENCES)

        assertEquals((0 until 120).map { sentence(it) }, exported.map { it.text })
        assertTrue(store.advanceEnrichedThrough(exported.maxOf { it.lastSeenMs }, store.generation()))
        val nextCursor = store.stats().enrichedThroughMs
        assertEquals(1_119L, nextCursor)
        assertEquals(10, PersonalGraphEnrichmentCycle.newSentenceCount(vault, nextCursor))
        assertEquals((120 until 130).map { sentence(it) }, vault.exportSince(nextCursor, 120).map { it.text })
    }

    @Test
    fun aMigratedCursorOfZeroReprocessesTheWholeVaultOldestFirstUntilItIsWorkedOff() {
        var now = 1_000L
        val vault = PersonalSentenceVault(clock = { now })
        repeat(250) {
            now = 1_000L + it
            assertTrue(vault.record(sentence(it), "com.android.chrome"))
        }
        val store = graphBuiltAt(900L)

        var cursor = store.stats().enrichedThroughMs
        var cycles = 0
        val seen = mutableListOf<String>()
        while (PersonalGraphEnrichmentCycle.newSentenceCount(vault, cursor) > 0) {
            val exported = vault.exportSince(cursor, PersonalGraphEnrichmentCycle.MAX_INCREMENTAL_SENTENCES)
            seen += exported.map { it.text }
            store.advanceEnrichedThrough(exported.maxOf { it.lastSeenMs }, store.generation())
            cursor = store.stats().enrichedThroughMs
            cycles++
        }

        assertEquals(3, cycles)
        assertEquals((0 until 250).map { sentence(it) }, seen)
    }
}
