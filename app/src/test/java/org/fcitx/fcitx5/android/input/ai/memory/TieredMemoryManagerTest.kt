/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TieredMemoryManagerTest {

    // =========================================================================
    // 1. TPO Context Encoder Tests
    // =========================================================================

    @Test
    fun testTpoToneResolution() {
        val encoder = TpoContextEncoder()

        // Formal Business
        assertEquals(PersonaTone.FORMAL_BUSINESS, encoder.resolveTone("com.slack"))
        assertEquals(PersonaTone.FORMAL_BUSINESS, encoder.resolveTone("com.slack.android"))
        assertEquals(PersonaTone.FORMAL_BUSINESS, encoder.resolveTone("com.google.android.gm"))
        assertEquals(PersonaTone.FORMAL_BUSINESS, encoder.resolveTone("com.microsoft.teams"))

        // Casual Chat
        assertEquals(PersonaTone.CASUAL_CHAT, encoder.resolveTone("com.kakao.talk"))
        assertEquals(PersonaTone.CASUAL_CHAT, encoder.resolveTone("com.instagram.android"))
        assertEquals(PersonaTone.CASUAL_CHAT, encoder.resolveTone("com.facebook.orca"))

        // Concise Search / Default
        assertEquals(PersonaTone.CONCISE_SEARCH, encoder.resolveTone("com.android.chrome"))
        assertEquals(PersonaTone.CONCISE_SEARCH, encoder.resolveTone("com.nhn.android.search"))
        assertEquals(PersonaTone.CONCISE_SEARCH, encoder.resolveTone("com.example.unknownapp"))
    }

    @Test
    fun testTpoTimeOfDayResolution() {
        val zoneId = ZoneId.of("UTC")
        val encoder = TpoContextEncoder(defaultZoneId = zoneId)

        fun epochForHour(hour: Int, minute: Int = 0): Long {
            return ZonedDateTime.of(2026, 9, 17, hour, minute, 0, 0, zoneId).toInstant().toEpochMilli()
        }

        // Morning: 06:00 ~ 11:59
        assertEquals(TimeOfDay.MORNING, encoder.resolveTimeOfDay(epochForHour(6, 0), zoneId))
        assertEquals(TimeOfDay.MORNING, encoder.resolveTimeOfDay(epochForHour(9, 30), zoneId))
        assertEquals(TimeOfDay.MORNING, encoder.resolveTimeOfDay(epochForHour(11, 59), zoneId))

        // Afternoon: 12:00 ~ 17:59
        assertEquals(TimeOfDay.AFTERNOON, encoder.resolveTimeOfDay(epochForHour(12, 0), zoneId))
        assertEquals(TimeOfDay.AFTERNOON, encoder.resolveTimeOfDay(epochForHour(15, 30), zoneId))
        assertEquals(TimeOfDay.AFTERNOON, encoder.resolveTimeOfDay(epochForHour(17, 59), zoneId))

        // Evening: 18:00 ~ 22:59
        assertEquals(TimeOfDay.EVENING, encoder.resolveTimeOfDay(epochForHour(18, 0), zoneId))
        assertEquals(TimeOfDay.EVENING, encoder.resolveTimeOfDay(epochForHour(21, 0), zoneId))
        assertEquals(TimeOfDay.EVENING, encoder.resolveTimeOfDay(epochForHour(22, 59), zoneId))

        // Night: 23:00 ~ 05:59
        assertEquals(TimeOfDay.NIGHT, encoder.resolveTimeOfDay(epochForHour(23, 0), zoneId))
        assertEquals(TimeOfDay.NIGHT, encoder.resolveTimeOfDay(epochForHour(2, 0), zoneId))
        assertEquals(TimeOfDay.NIGHT, encoder.resolveTimeOfDay(epochForHour(5, 59), zoneId))
    }

    @Test
    fun testTpoFullEncoding() {
        val zoneId = ZoneId.of("Asia/Seoul")
        val encoder = TpoContextEncoder(defaultZoneId = zoneId)
        val testEpoch = ZonedDateTime.of(2026, 9, 17, 14, 30, 0, 0, zoneId).toInstant().toEpochMilli()

        val context = encoder.encode("com.slack", testEpoch)
        assertEquals(PersonaTone.FORMAL_BUSINESS, context.tone)
        assertEquals(TimeOfDay.AFTERNOON, context.timeOfDay)
        assertEquals("com.slack", context.packageName)
    }

    // =========================================================================
    // 2. TieredMemoryManager Tests (L1, L2, L3, Memory Limit)
    // =========================================================================

    @Test
    fun testL1ActiveBufferLifecycle() {
        val manager = TieredMemoryManager()
        assertEquals("", manager.getL1Buffer())

        manager.updateL1Buffer("안녕하세요")
        assertEquals("안녕하세요", manager.getL1Buffer())

        manager.clearL1Buffer()
        assertEquals("", manager.getL1Buffer())
    }

    @Test
    fun testL2SessionUtterancesFifoCapacity() {
        val manager = TieredMemoryManager()
        assertTrue(manager.getSessionUtterances().isEmpty())

        // Add 5 sentences
        for (i in 1..5) {
            manager.addSessionUtterance("문장 $i")
        }
        val session1 = manager.getSessionUtterances()
        assertEquals(5, session1.size)
        assertEquals("문장 1", session1.first())
        assertEquals("문장 5", session1.last())

        // Add 6th sentence -> FIFO eviction of "문장 1"
        manager.addSessionUtterance("문장 6")
        val session2 = manager.getSessionUtterances()
        assertEquals(5, session2.size)
        assertEquals("문장 2", session2.first())
        assertEquals("문장 6", session2.last())

        // Clear session
        manager.clearSession()
        assertTrue(manager.getSessionUtterances().isEmpty())
        assertEquals("", manager.getL1Buffer())
    }

    @Test
    fun testL3EpisodicContextLruCapacity() {
        val manager = TieredMemoryManager()
        assertTrue(manager.getRecentEpisodes().isEmpty())

        // Add 25 episodes
        for (i in 1..25) {
            manager.addEpisode("Topic $i", "Summary of episode $i", timestamp = 1000L + i)
        }

        val recent = manager.getRecentEpisodes()
        // Max capacity 20
        assertEquals(20, recent.size)
        // Most recent should be Topic 25
        assertEquals("Topic 25", recent.first().topic)
        // Oldest retained should be Topic 6 (1..5 were evicted)
        assertEquals("Topic 6", recent.last().topic)

        manager.clearEpisodes()
        assertTrue(manager.getRecentEpisodes().isEmpty())
    }

    @Test
    fun testMemoryFootprintSafetyUnder35Mb() {
        val manager = TieredMemoryManager()

        // Empty state baseline overhead
        val emptyBytes = manager.getEstimatedMemoryBytes()
        assertTrue(emptyBytes >= 0L)
        assertTrue(emptyBytes < TieredMemoryManager.MAX_MEMORY_LIMIT_BYTES)

        // Populate all tiers
        manager.updateL1Buffer("새글 모바일 인공지능 버퍼 입력 스트림 테스트")
        for (i in 1..TieredMemoryManager.MAX_L2_UTTERANCES) {
            manager.addSessionUtterance("세션 발화 큐 $i 번째 테스트 문장입니다.")
        }
        for (i in 1..TieredMemoryManager.MAX_L3_EPISODES) {
            manager.addEpisode("에피소드 토픽 $i", "에피소드 요약문 $i : 사용자가 나눈 대화 기록")
        }
        manager.updateL4Profile("tone_preference", "formal")
        manager.updateL4Profile("domain_focus", "software_engineering")

        val usedBytes = manager.getEstimatedMemoryBytes()
        assertTrue(usedBytes > emptyBytes)
        // Strictly below 35 MB (36,700,160 bytes)
        assertTrue("Estimated bytes ($usedBytes) must be strictly less than 35MB",
            usedBytes < TieredMemoryManager.MAX_MEMORY_LIMIT_BYTES)

        val limit35Mb = 35L * 1024 * 1024
        assertEquals(limit35Mb, TieredMemoryManager.MAX_MEMORY_LIMIT_BYTES)
    }
}
