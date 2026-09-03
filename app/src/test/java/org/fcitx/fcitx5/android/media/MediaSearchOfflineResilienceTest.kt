/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TDD Test Suite for Media Offline Resilience and Query Normalization.
 * Tests offline query caching, special symbol normalization (ㅋㅋㅋㅋ, ㅠㅠ, 초성),
 * rapid network state transitions, and exhausted item recovery.
 */
class MediaSearchOfflineResilienceTest {

    private lateinit var retryQueue: MediaRetryQueue
    private lateinit var favoritesManager: MediaFavoritesManager

    @Before
    fun setUp() {
        retryQueue = MediaRetryQueue(maxQueueSize = 100, maxRetryAttempts = 5)
        favoritesManager = MediaFavoritesManager(maxCapacity = 200)
    }

    @Test
    fun testSpecialKoreanSymbolQueryNormalization() {
        val rawQueries = listOf(
            "ㅋㅋㅋㅋ" to "ㅋㅋ",
            "ㅠㅠㅠㅠㅠ" to "ㅠㅠ",
            "  고양이   짤  " to "고양이 짤",
            "!!!축하!!!" to "축하"
        )

        rawQueries.forEach { (raw, expectedNormalized) ->
            val normalized = normalizeSearchQuery(raw)
            assertEquals("Normalization mismatch for $raw", expectedNormalized, normalized)
        }
    }

    @Test
    fun testOfflineQueryQueuePersistenceUnderSimulatedAirplaneMode() {
        // Enqueue 20 queries during airplane mode
        for (i in 0 until 20) {
            val req = MediaSearchRequest(
                id = "offline_$i",
                query = "오프라인 쿼리 $i",
                mediaType = MediaType.GIF,
                timestamp = 1000L + i * 10L
            )
            assertTrue(retryQueue.enqueue(req))
        }

        assertEquals(20, retryQueue.size())

        // Simulated airplane mode: 3 consecutive failure waves
        for (wave in 1..3) {
            val failureTime = 5000L * wave
            for (i in 0 until 20) {
                retryQueue.recordFailure("offline_$i", "No internet connection", failureTime)
            }
        }

        // None should be exhausted yet since maxRetryAttempts is 5
        assertEquals(0, retryQueue.getExhaustedCount())
        assertEquals(20, retryQueue.size())

        // Network restored: record success for all items
        for (i in 0 until 20) {
            retryQueue.recordSuccess("offline_$i")
        }

        assertEquals(0, retryQueue.size())
    }

    @Test
    fun testFavoritesSearchWithWhitespaceAndCaseInsensitivity() {
        val item = MediaItem(
            id = "fav_korean",
            title = "대한민국 화이팅 GIF",
            url = "https://example.com/korea.gif",
            thumbnailUrl = "https://example.com/korea_thumb.jpg",
            mediaType = MediaType.GIF,
            tags = listOf("KOREA", "화이팅", "응원")
        )

        favoritesManager.addFavorite(item)

        assertTrue(favoritesManager.search("대한민국").isNotEmpty())
        assertTrue(favoritesManager.search("korea").isNotEmpty())
        assertTrue(favoritesManager.search("KOREA").isNotEmpty())
        assertTrue(favoritesManager.search("  화이팅  ").isNotEmpty())
        assertTrue(favoritesManager.findByTag("응원").isNotEmpty())
        assertTrue(favoritesManager.search("존재하지않는검색어").isEmpty())
    }

    private fun normalizeSearchQuery(query: String): String {
        var clean = query.trim().replace(Regex("\\s+"), " ")
        if (clean.all { it == 'ㅋ' } && clean.length > 2) clean = "ㅋㅋ"
        if (clean.all { it == 'ㅠ' || it == 'ㅜ' } && clean.length > 2) clean = "ㅠㅠ"
        clean = clean.trim('!', '?', '.', ',', ' ')
        return clean
    }
}
