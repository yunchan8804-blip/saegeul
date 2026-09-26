/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Context
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import org.fcitx.fcitx5.android.input.ai.graph.DirectChip
import org.fcitx.fcitx5.android.input.ai.graph.DirectSuggestionBridge
import org.fcitx.fcitx5.android.input.ai.graph.EdgeInfo
import org.fcitx.fcitx5.android.input.ai.graph.EntityInfo
import org.fcitx.fcitx5.android.input.ai.graph.HippoRagPprEngine
import org.fcitx.fcitx5.android.input.ai.graph.OnDeviceEgoGraphDatabase
import org.fcitx.fcitx5.android.input.ai.graph.OnDeviceL1GraphCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class EgoGraphE2eDeviceTest {

    private lateinit var context: Context
    private lateinit var database: OnDeviceEgoGraphDatabase
    private val testDbName = "test_saegeul_ego_graph.db"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(testDbName)
        database = OnDeviceEgoGraphDatabase(context, testDbName)
        database.clear()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(testDbName)
    }

    @Test
    fun testEgoGraphDatabaseCrudAndWal() {
        // Verify WAL is enabled on database
        val db = database.writableDatabase
        assertTrue("Database WAL must be enabled", db.isWriteAheadLoggingEnabled)

        // 1. Upsert Entities
        database.upsertEntity(id = "회의", label = "회의", category = "일정", weight = 1.0f)
        database.upsertEntity(id = "참석", label = "참석", category = "동작", weight = 1.0f)
        database.upsertEntity(id = "준비", label = "준비", category = "동작", weight = 1.0f)
        database.upsertEntity(id = "자료", label = "자료", category = "문서", weight = 1.0f)

        val allEntities = database.getAllEntities()
        assertEquals(4, allEntities.size)
        val meetingEntity = allEntities.find { it.id == "회의" }
        assertNotNull(meetingEntity)
        assertEquals("일정", meetingEntity?.category)

        // 2. Upsert Edges
        database.upsertEdge(src = "회의", dst = "참석", relation = "동작", weight = 2.0f)
        database.upsertEdge(src = "회의", dst = "준비", relation = "준비", weight = 1.5f)
        database.upsertEdge(src = "회의", dst = "자료", relation = "참조", weight = 1.0f)

        val allEdges = database.getAllEdges()
        assertEquals(3, allEdges.size)

        // Frequency increment verification on re-upsert
        database.upsertEdge(src = "회의", dst = "참석", relation = "동작", weight = 2.5f)
        val updatedEdges = database.getAllEdges()
        assertEquals(3, updatedEdges.size)
        val attendEdge = updatedEdges.find { it.src == "회의" && it.dst == "참석" }
        assertNotNull(attendEdge)
        assertEquals(2, attendEdge?.frequency)
        assertEquals(2.5f, attendEdge?.weight)

        // 3. 1-hop neighbor queries
        val meetingNeighbors = database.get1HopNeighbors("회의")
        assertEquals(3, meetingNeighbors.size)

        val attendNeighbors = database.get1HopNeighbors("참석")
        assertEquals(1, attendNeighbors.size)
        assertEquals("회의", attendNeighbors[0].src)

        // 4. Clear verification
        database.clear()
        assertTrue(database.getAllEntities().isEmpty())
        assertTrue(database.getAllEdges().isEmpty())
    }

    @Test
    fun testL1GraphCacheLatency() {
        val cache = OnDeviceL1GraphCache()
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

        // Warmup JIT
        repeat(100) { cache.get1Hop("회의") }

        // Measure 1,000 iterations
        val startNanos = System.nanoTime()
        repeat(1000) {
            val neighbors = cache.get1Hop("회의")
            assertEquals(3, neighbors.size)
        }
        val totalElapsedNanos = System.nanoTime() - startNanos
        val avgLatencyMs = (totalElapsedNanos / 1000) / 1_000_000.0

        assertTrue(
            "L1 cache 1-hop lookup avg latency must be < 0.1ms, actual: $avgLatencyMs ms",
            avgLatencyMs < 0.1
        )
    }

    @Test
    fun testHippoRagPprConvergenceAndRanking() {
        val cache = OnDeviceL1GraphCache()
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
        val pprEngine = HippoRagPprEngine(cache, dampingFactor = 0.85f, maxIterations = 3)

        // Warmup JIT
        repeat(20) { pprEngine.computePpr(seedEntities = setOf("회의"), maxResults = 5) }

        val iterations = 50
        val startNanos = System.nanoTime()
        var results = emptyList<Pair<String, Float>>()
        repeat(iterations) {
            results = pprEngine.computePpr(seedEntities = setOf("회의"), maxResults = 5)
        }
        val avgElapsedMs = ((System.nanoTime() - startNanos) / iterations) / 1_000_000.0

        assertTrue("HippoRAG PPR execution must converge in < 2.0ms on emulator, actual: $avgElapsedMs ms", avgElapsedMs < 2.0)
        assertEquals(3, results.size)

        // Verify ranking order by relevance score: 참석 > 준비 > 자료
        val labels = results.map { it.first }
        assertEquals(listOf("참석", "준비", "자료"), labels)
        assertTrue(results[0].second > results[1].second)
        assertTrue(results[1].second > results[2].second)
    }

    @Test
    fun testDirectSuggestionBridge() {
        val cache = OnDeviceL1GraphCache()
        database.upsertEntity("회의", "회의", "일정", 1.0f)
        database.upsertEntity("참석", "참석", "동작", 1.0f)
        database.upsertEntity("준비", "준비", "동작", 1.0f)
        database.upsertEntity("자료", "자료", "문서", 1.0f)
        database.upsertEdge("회의", "참석", "동작", 2.0f)
        database.upsertEdge("회의", "준비", "준비", 1.5f)
        database.upsertEdge("회의", "자료", "참조", 1.0f)

        // Warmup L1 cache from database
        cache.warmup(database.getAllEntities(), database.getAllEdges())

        val pprEngine = HippoRagPprEngine(cache)
        val bridge = DirectSuggestionBridge(pprEngine, cache)

        // Warmup JIT
        repeat(20) { bridge.suggest("내일 오전 회의 ", maxChips = 3) }

        val iterations = 50
        val startNanos = System.nanoTime()
        var chips = emptyList<DirectChip>()
        repeat(iterations) {
            chips = bridge.suggest("내일 오전 회의 ", maxChips = 3)
        }
        val avgElapsedMs = ((System.nanoTime() - startNanos) / iterations) / 1_000_000.0

        assertTrue("Direct suggestion bridge must yield chips instantaneously (< 3.0ms on emulator), actual: $avgElapsedMs ms", avgElapsedMs < 3.0)
        assertEquals(3, chips.size)

        assertEquals("참석", chips[0].label)
        assertEquals("동작", chips[0].relation)

        assertEquals("준비", chips[1].label)
        assertEquals("준비", chips[1].relation)

        assertEquals("자료", chips[2].label)
        assertEquals("참조", chips[2].relation)
    }
}
