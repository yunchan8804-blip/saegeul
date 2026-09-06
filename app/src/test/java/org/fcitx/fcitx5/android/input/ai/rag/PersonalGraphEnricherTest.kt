/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PersonalGraphEnricher]: no-data short-circuit, successful single-chunk
 * enrichment, total parse failure leaving the graph untouched, and partial-success merging
 * across chunks.
 */
class PersonalGraphEnricherTest {

    private val validGraphJson =
        """{"nodes":[{"id":"회의","tags":["업무"],"w":3.0},{"id":"참석","tags":["업무"],"w":2.0}],"edges":[{"a":"회의","b":"참석","w":0.8}],"topics":[{"id":"t0","label":"업무","members":["회의","참석"]}]}"""

    @Test
    fun enrichReturnsNoDataForEmptyVault() = runBlocking {
        val vault = PersonalSentenceVault(clock = { 1000L })
        val store = PersonalGraphStore()
        val enricher = PersonalGraphEnricher(vault, store)

        val result = enricher.enrich(generate = { _, _ -> listOf(validGraphJson) })

        assertFalse(result.ok)
        assertEquals("no_data", result.reason)
        assertEquals(0, result.nodes)
        assertEquals(0, result.edges)
        assertEquals(0, result.topics)
        assertEquals(0, store.stats().nodes)
    }

    @Test
    fun enrichPopulatesGraphOnSuccessfulSingleChunk() = runBlocking {
        val vault = PersonalSentenceVault(clock = { 1000L })
        vault.record("오늘 회의 참석하겠습니다", "com.android.chrome")
        val store = PersonalGraphStore(clock = { 55555L })
        val enricher = PersonalGraphEnricher(vault, store, clock = { 55555L })

        val result = enricher.enrich(generate = { _, _ -> listOf(validGraphJson) })

        assertTrue(result.ok)
        assertEquals("ok", result.reason)
        assertEquals(2, result.nodes)
        assertEquals(1, result.edges)
        assertEquals(1, result.topics)

        val stats = store.stats()
        assertEquals(2, stats.nodes)
        assertEquals(1, stats.edges)
        assertEquals(1, stats.topics)
        assertEquals(55555L, stats.builtMs)

        val boost = store.proximityBoost(setOf("회의"), setOf("참석"))
        assertTrue(boost > 1.0f)
    }

    @Test
    fun enrichPassesInstructionAndChunkToGenerate() = runBlocking {
        val vault = PersonalSentenceVault(clock = { 1000L })
        vault.record("오늘 회의 참석하겠습니다", "com.android.chrome")
        val store = PersonalGraphStore()
        val enricher = PersonalGraphEnricher(vault, store)

        var capturedInstruction: String? = null
        var capturedInput: String? = null
        enricher.enrich(generate = { instruction, input ->
            capturedInstruction = instruction
            capturedInput = input
            listOf(validGraphJson)
        })

        assertEquals(PersonalGraphEnricher.ENRICHMENT_INSTRUCTION, capturedInstruction)
        assertTrue(capturedInput!!.contains("오늘 회의 참석하겠습니다"))
    }

    @Test
    fun enrichReturnsParseFailedAndLeavesExistingGraphUntouchedWhenAllChunksFail() = runBlocking {
        val vault = PersonalSentenceVault(clock = { 1000L })
        vault.record("오늘 회의 참석하겠습니다", "com.android.chrome")
        val store = PersonalGraphStore()
        store.replaceGraph(
            nodes = listOf(PersonalGraphStore.Node("기존", emptyList(), 1.0f)),
            edges = emptyList(),
            topics = emptyList(),
            builtMs = 42L
        )
        val enricher = PersonalGraphEnricher(vault, store)

        val result = enricher.enrich(generate = { _, _ -> listOf("이것은 JSON이 아닙니다") })

        assertFalse(result.ok)
        assertEquals("parse_failed", result.reason)
        assertEquals(0, result.nodes)
        assertEquals(0, result.edges)
        assertEquals(0, result.topics)

        // Existing graph is left alone - not replaced, not cleared.
        val stats = store.stats()
        assertEquals(1, stats.nodes)
        assertEquals(42L, stats.builtMs)
    }

    @Test
    fun enrichReturnsPartialWhenOnlyOneOfTwoChunksParses() = runBlocking {
        val vault = PersonalSentenceVault(clock = { 1000L })
        vault.record("오늘 회의 참석하겠습니다", "com.android.chrome")
        vault.record("내일 판교에서 봐요", "com.android.chrome")
        val store = PersonalGraphStore()
        val enricher = PersonalGraphEnricher(vault, store)

        var callCount = 0
        // maxCharsPerChunk = 1 forces each of the two sentences into its own chunk, since the
        // first sentence in a chunk is always accepted regardless of the limit.
        val result = enricher.enrich(
            generate = { _, _ ->
                callCount++
                if (callCount == 1) listOf(validGraphJson) else listOf("깨진 JSON")
            },
            maxCharsPerChunk = 1
        )

        assertEquals(2, callCount)
        assertTrue(result.ok)
        assertEquals("partial", result.reason)
        assertEquals(2, result.nodes)
        assertEquals(1, result.edges)
        assertEquals(1, result.topics)
    }

    @Test
    fun enrichMergesNodeWeightsAndTagsAcrossChunksWithSameId() = runBlocking {
        val vault = PersonalSentenceVault(clock = { 1000L })
        vault.record("오늘 회의 참석하겠습니다", "com.android.chrome")
        vault.record("내일 판교에서 봐요", "com.android.chrome")
        val store = PersonalGraphStore()
        val enricher = PersonalGraphEnricher(vault, store)

        val secondJson =
            """{"nodes":[{"id":"회의","tags":["일정"],"w":2.0}],"edges":[{"a":"참석","b":"회의","w":0.5}],"topics":[{"id":"t0","label":"업무","members":["참석"]}]}"""
        var callCount = 0
        val result = enricher.enrich(
            generate = { _, _ ->
                callCount++
                if (callCount == 1) listOf(validGraphJson) else listOf(secondJson)
            },
            maxCharsPerChunk = 1
        )

        assertTrue(result.ok)
        assertEquals("ok", result.reason)
        // "회의" node merged from both chunks (weight 3.0 + 2.0), "참석" only from the first chunk,
        // one edge normalized both ways merged into one, and the "업무" topic merged members from
        // both chunks.
        assertEquals(2, result.nodes)
        assertEquals(1, result.edges)
        assertEquals(1, result.topics)
    }

    @Test
    fun enrichParsesResponseWrappedInMarkdownJsonFence() = runBlocking {
        val vault = PersonalSentenceVault(clock = { 1000L })
        vault.record("오늘 회의 참석하겠습니다", "com.android.chrome")
        val store = PersonalGraphStore()
        val enricher = PersonalGraphEnricher(vault, store)

        val fenced = "```json\n$validGraphJson\n```"
        val result = enricher.enrich(generate = { _, _ -> listOf(fenced) })

        assertTrue(result.ok)
        assertEquals("ok", result.reason)
        assertEquals(2, result.nodes)
        assertEquals(1, result.edges)
        assertEquals(1, result.topics)
    }

    @Test
    fun enrichParsesResponseWithSurroundingProse() = runBlocking {
        val vault = PersonalSentenceVault(clock = { 1000L })
        vault.record("오늘 회의 참석하겠습니다", "com.android.chrome")
        val store = PersonalGraphStore()
        val enricher = PersonalGraphEnricher(vault, store)

        val withProse = "다음은 결과입니다: $validGraphJson 이상입니다."
        val result = enricher.enrich(generate = { _, _ -> listOf(withProse) })

        assertTrue(result.ok)
        assertEquals("ok", result.reason)
        assertEquals(2, result.nodes)
        assertEquals(1, result.edges)
        assertEquals(1, result.topics)
    }

    @Test
    fun enrichReturnsParseFailedForPlainTextWithNoJsonObjectAtAll() = runBlocking {
        val vault = PersonalSentenceVault(clock = { 1000L })
        vault.record("오늘 회의 참석하겠습니다", "com.android.chrome")
        val store = PersonalGraphStore()
        val enricher = PersonalGraphEnricher(vault, store)

        val result = enricher.enrich(generate = { _, _ -> listOf("죄송하지만 그래프를 만들 수 없습니다.") })

        assertFalse(result.ok)
        assertEquals("parse_failed", result.reason)
    }
}
