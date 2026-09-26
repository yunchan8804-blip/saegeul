/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for on-device Ego-Graph components:
 * L1 in-memory cache, HippoRAG PPR engine, and DirectSuggestionBridge.
 */
class OnDeviceEgoGraphTest {

    private lateinit var cache: OnDeviceL1GraphCache
    private lateinit var pprEngine: HippoRagPprEngine
    private lateinit var bridge: DirectSuggestionBridge

    @Before
    fun setUp() {
        cache = OnDeviceL1GraphCache()
        pprEngine = HippoRagPprEngine(cache, dampingFactor = 0.85f, maxIterations = 3)
        bridge = DirectSuggestionBridge(pprEngine, cache)
    }

    @Test
    fun testL1GraphCacheWarmupAndQuery() {
        val entities = listOf(
            EntityInfo("회의", "회의", "일정", 1.0f),
            EntityInfo("참석", "참석", "동작", 1.0f),
            EntityInfo("준비", "준비", "동작", 1.0f),
            EntityInfo("자료", "자료", "문서", 1.0f)
        )
        val edges = listOf(
            EdgeInfo("회의", "참석", "동작", 2.0f),
            EdgeInfo("회의", "준비", "준비", 1.5f),
            EdgeInfo("회의", "자료", "참조", 1.0f)
        )

        cache.warmup(entities, edges)

        // Verify 1-hop for src
        val meetingNeighbors = cache.get1Hop("회의")
        assertEquals(3, meetingNeighbors.size)

        // Verify 1-hop for dst (bidirectional index lookup)
        val attendNeighbors = cache.get1Hop("참석")
        assertEquals(1, attendNeighbors.size)
        assertEquals("회의", attendNeighbors[0].src)

        // Verify unindexed entity
        val unknown = cache.get1Hop("없는엔티티")
        assertTrue(unknown.isEmpty())

        // Verify O(1) latency across 1,000 iterations
        val startNanos = System.nanoTime()
        repeat(1000) {
            cache.get1Hop("회의")
        }
        val elapsedNanos = System.nanoTime() - startNanos
        val avgMs = (elapsedNanos / 1000) / 1_000_000.0
        assertTrue("L1 cache 1-hop lookup avg latency must be < 0.05ms, was: $avgMs ms", avgMs < 0.05)
    }

    @Test
    fun testHippoRagPprConvergenceAndRanking() {
        val entities = listOf(
            EntityInfo("회의", "회의", "일정", 1.0f),
            EntityInfo("참석", "참석", "동작", 1.0f),
            EntityInfo("준비", "준비", "동작", 1.0f),
            EntityInfo("자료", "자료", "문서", 1.0f)
        )
        val edges = listOf(
            EdgeInfo("회의", "참석", "동작", 2.0f),
            EdgeInfo("회의", "준비", "준비", 1.5f),
            EdgeInfo("회의", "자료", "참조", 1.0f)
        )
        cache.warmup(entities, edges)

        // Warmup JIT to eliminate class loading bias
        repeat(50) {
            pprEngine.computePpr(seedEntities = setOf("회의"), maxResults = 5)
        }

        val iterations = 100
        val startNanos = System.nanoTime()
        var results: List<Pair<String, Float>> = emptyList()
        repeat(iterations) {
            results = pprEngine.computePpr(seedEntities = setOf("회의"), maxResults = 5)
        }
        val elapsedNanos = System.nanoTime() - startNanos
        val avgMs = (elapsedNanos / iterations) / 1_000_000.0

        assertTrue("HippoRAG PPR execution must complete in < 0.5ms, was: $avgMs ms", avgMs < 0.5)
        assertEquals(3, results.size)

        // Check ranking order based on edge weights (2.0 > 1.5 > 1.0)
        assertEquals("참석", results[0].first)
        assertEquals("준비", results[1].first)
        assertEquals("자료", results[2].first)

        assertTrue(results[0].second > results[1].second)
        assertTrue(results[1].second > results[2].second)
    }

    @Test
    fun testHippoRagPprMultiHopExpansion() {
        val entities = listOf(
            EntityInfo("회의", "회의", "일정", 1.0f),
            EntityInfo("자료", "자료", "문서", 1.0f),
            EntityInfo("보고서", "보고서", "문서", 1.0f)
        )
        val edges = listOf(
            EdgeInfo("회의", "자료", "참조", 2.0f),
            EdgeInfo("자료", "보고서", "상세", 1.5f)
        )
        cache.warmup(entities, edges)

        val results = pprEngine.computePpr(seedEntities = setOf("회의"), maxResults = 5)
        val labels = results.map { it.first }

        assertTrue("1-hop neighbor '자료' should be in results", labels.contains("자료"))
        assertTrue("2-hop neighbor '보고서' should be reached via multi-hop PPR", labels.contains("보고서"))
    }

    @Test
    fun testDirectSuggestionBridge() {
        val entities = listOf(
            EntityInfo("회의", "회의", "일정", 1.0f),
            EntityInfo("참석", "참석", "동작", 1.0f),
            EntityInfo("준비", "준비", "동작", 1.0f),
            EntityInfo("자료", "자료", "문서", 1.0f)
        )
        val edges = listOf(
            EdgeInfo("회의", "참석", "동작", 2.0f),
            EdgeInfo("회의", "준비", "준비", 1.5f),
            EdgeInfo("회의", "자료", "참조", 1.0f)
        )
        cache.warmup(entities, edges)

        // Warmup JIT to eliminate class loading bias
        repeat(50) {
            bridge.suggest("오늘 팀 회의 ")
        }

        val iterations = 100
        val startNanos = System.nanoTime()
        var chips: List<DirectChip> = emptyList()
        repeat(iterations) {
            chips = bridge.suggest("오늘 팀 회의 ")
        }
        val elapsedNanos = System.nanoTime() - startNanos
        val avgMs = (elapsedNanos / iterations) / 1_000_000.0

        assertTrue("Direct suggestion bridge must return chips in < 0.5ms, was: $avgMs ms", avgMs < 0.5)
        assertEquals(3, chips.size)

        assertEquals("참석", chips[0].label)
        assertEquals("동작", chips[0].relation)

        assertEquals("준비", chips[1].label)
        assertEquals("준비", chips[1].relation)

        assertEquals("자료", chips[2].label)
        assertEquals("참조", chips[2].relation)
    }

    @Test
    fun testEmptyAndBlankContextHandling() {
        val chipsEmpty = bridge.suggest("")
        assertTrue(chipsEmpty.isEmpty())

        val chipsBlank = bridge.suggest("     ")
        assertTrue(chipsBlank.isEmpty())

        val chipsUnmatched = bridge.suggest("전혀 다른 문맥입니다")
        assertTrue(chipsUnmatched.isEmpty())
    }

    @Test
    fun testL1GraphCacheDynamicUpdate() {
        cache.putEntity(EntityInfo("점심", "점심", "식사", 1.0f))
        cache.putEntity(EntityInfo("메뉴", "메뉴", "음식", 1.0f))
        cache.putEdge(EdgeInfo("점심", "메뉴", "선택", 1.2f))

        val neighbors = cache.get1Hop("점심")
        assertEquals(1, neighbors.size)
        assertEquals("메뉴", neighbors[0].dst)
        assertEquals("선택", neighbors[0].relation)

        val entity = cache.getEntity("점심")
        assertNotNull(entity)
        assertEquals("식사", entity?.category)
    }
}
