/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.daemon

import org.fcitx.fcitx5.android.input.ai.daemon.cache.PrecomputedKvCacheManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrecomputedKvCacheManagerTest {

    private lateinit var cacheManager: PrecomputedKvCacheManager

    @Before
    fun setUp() {
        cacheManager = PrecomputedKvCacheManager(maxSlots = 8)
    }

    @Test
    fun testPutAndMatchPrefix() {
        val prompt = "안녕하세요. 새글 AI 시스템 프롬프트입니다."
        val slot = cacheManager.putSlot(prompt)

        assertNotNull(slot)
        assertEquals(prompt, slot.promptText)
        assertEquals(1, cacheManager.size())

        val matched = cacheManager.matchPrefix("$prompt 추가 사용자 질의문장")
        assertNotNull(matched)
        assertEquals(prompt, matched?.promptText)
    }

    @Test
    fun testLongestPrefixMatch() {
        val shortPrefix = "새글 키보드"
        val longPrefix = "새글 키보드는 대한민국에서 가장 빠르고 정확한"

        cacheManager.putSlot(shortPrefix)
        cacheManager.putSlot(longPrefix)

        val fullPrompt = "$longPrefix 혁신적인 온디바이스 입력기입니다."
        val matched = cacheManager.matchPrefix(fullPrompt)

        assertNotNull(matched)
        assertEquals(longPrefix, matched?.promptText)
    }

    @Test
    fun testLruEviction() {
        val smallManager = PrecomputedKvCacheManager(maxSlots = 3)

        val s1 = smallManager.putSlot("프롬프트 1", slotId = "s1")
        Thread.sleep(5)
        val s2 = smallManager.putSlot("프롬프트 2", slotId = "s2")
        Thread.sleep(5)
        val s3 = smallManager.putSlot("프롬프트 3", slotId = "s3")

        assertEquals(3, smallManager.size())

        // Access s1 to refresh its LRU timestamp
        Thread.sleep(5)
        smallManager.matchPrefix("프롬프트 1 질의")

        // Now s2 should be the oldest. Inserting s4 should evict s2.
        Thread.sleep(5)
        val s4 = smallManager.putSlot("프롬프트 4", slotId = "s4")

        assertEquals(3, smallManager.size())
        assertNotNull(smallManager.matchPrefix("프롬프트 1 질의"))
        assertNotNull(smallManager.matchPrefix("프롬프트 3 질의"))
        assertNotNull(smallManager.matchPrefix("프롬프트 4 질의"))
        assertNull(smallManager.matchPrefix("프롬프트 2 질의"))
    }

    @Test
    fun testPrefixMatchLatencySubPointOneMs() {
        val prompt = "시스템 페르소나 설정: 정중하고 정확한 한국어 작문 도우미"
        cacheManager.putSlot(prompt)

        val query = "$prompt 사용자가 지금 메시지를 작성 중입니다."

        // Warm up JIT
        repeat(100) {
            cacheManager.matchPrefix(query)
        }

        val iterations = 1000
        val startNs = System.nanoTime()
        repeat(iterations) {
            val matched = cacheManager.matchPrefix(query)
            assertNotNull(matched)
        }
        val elapsedNs = System.nanoTime() - startNs
        val avgNs = elapsedNs.toDouble() / iterations
        val avgMs = avgNs / 1_000_000.0

        println("PrecomputedKvCacheManager average lookup latency: ${avgMs}ms (${avgNs}ns)")
        assertTrue("Prefix match latency must be < 0.1ms (was ${avgMs}ms)", avgMs < 0.1)
    }

    @Test
    fun testClearAndSize() {
        cacheManager.putSlot("프롬프트 1")
        cacheManager.putSlot("프롬프트 2")
        assertEquals(2, cacheManager.size())

        cacheManager.clear()
        assertEquals(0, cacheManager.size())
        assertNull(cacheManager.matchPrefix("프롬프트 1"))
    }
}
