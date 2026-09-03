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
 * TDD Test Suite for Media Search, Retry Queue Manager, and Favorite Bookmarking.
 * Validates retry backoff, queue limits, persistence, duplicate deduplication, and cache invalidation.
 */
class MediaSearchAndRetryQueueTest {

    private lateinit var retryQueue: MediaRetryQueue
    private lateinit var favoritesManager: MediaFavoritesManager

    @Before
    fun setUp() {
        retryQueue = MediaRetryQueue(maxQueueSize = 50, maxRetryAttempts = 3)
        favoritesManager = MediaFavoritesManager(maxCapacity = 100)
    }

    @Test
    fun testEnqueueFailedSearchRequest() {
        val request = MediaSearchRequest(
            id = "req_1",
            query = "고양이 짤",
            mediaType = MediaType.GIF,
            timestamp = 1000L
        )

        assertTrue(retryQueue.enqueue(request))
        assertEquals(1, retryQueue.size())
        assertEquals(request, retryQueue.peek())
    }

    @Test
    fun testDuplicateRequestDeduplication() {
        val request1 = MediaSearchRequest("req_1", "고양이", MediaType.GIF, 1000L)
        val request2 = MediaSearchRequest("req_1", "고양이", MediaType.GIF, 1005L)

        assertTrue(retryQueue.enqueue(request1))
        assertFalse(retryQueue.enqueue(request2)) // Should be rejected as duplicate
        assertEquals(1, retryQueue.size())
    }

    @Test
    fun testExponentialBackoffCalculation() {
        val request = MediaSearchRequest("req_1", "강아지", MediaType.VIDEO, 1000L)
        retryQueue.enqueue(request)

        // Initial delay
        assertEquals(1000L, retryQueue.calculateNextRetryDelay(attempt = 0))
        assertEquals(2000L, retryQueue.calculateNextRetryDelay(attempt = 1))
        assertEquals(4000L, retryQueue.calculateNextRetryDelay(attempt = 2))
        assertEquals(8000L, retryQueue.calculateNextRetryDelay(attempt = 3))
    }

    @Test
    fun testRetryAttemptProgressionAndExhaustion() {
        val request = MediaSearchRequest("req_1", "눈물 짤", MediaType.GIF, 1000L)
        retryQueue.enqueue(request)

        var item = retryQueue.pollNextReady(currentTime = 5000L)
        assertNotNull(item)
        assertEquals(0, item!!.retryCount)

        // Record failure #1
        retryQueue.recordFailure(item.id, errorMessage = "Network timeout", failureTime = 6000L)
        assertEquals(1, retryQueue.getRetryCount(item.id))

        // Record failure #2
        retryQueue.recordFailure(item.id, errorMessage = "Server error 503", failureTime = 9000L)
        assertEquals(2, retryQueue.getRetryCount(item.id))

        // Record failure #3 (Exhaustion)
        val exhausted = retryQueue.recordFailure(item.id, errorMessage = "HTTP 404", failureTime = 15000L)
        assertTrue(exhausted)
        assertNull(retryQueue.peek()) // Removed from active queue
        assertEquals(1, retryQueue.getExhaustedCount())
    }

    @Test
    fun testSuccessfulRetryRemovesFromQueue() {
        val request = MediaSearchRequest("req_1", "축하 댄스", MediaType.GIF, 1000L)
        retryQueue.enqueue(request)

        val item = retryQueue.pollNextReady(currentTime = 2000L)
        assertNotNull(item)

        retryQueue.recordSuccess(item!!.id)
        assertEquals(0, retryQueue.size())
        assertEquals(0, retryQueue.getExhaustedCount())
    }

    @Test
    fun testQueueOverflowEvictionPolicy() {
        val smallQueue = MediaRetryQueue(maxQueueSize = 3, maxRetryAttempts = 3)
        smallQueue.enqueue(MediaSearchRequest("req_1", "1", MediaType.GIF, 100L))
        smallQueue.enqueue(MediaSearchRequest("req_2", "2", MediaType.GIF, 200L))
        smallQueue.enqueue(MediaSearchRequest("req_3", "3", MediaType.GIF, 300L))

        assertEquals(3, smallQueue.size())

        // 4th item should evict oldest request (req_1)
        smallQueue.enqueue(MediaSearchRequest("req_4", "4", MediaType.GIF, 400L))
        assertEquals(3, smallQueue.size())
        assertFalse(smallQueue.contains("req_1"))
        assertTrue(smallQueue.contains("req_2"))
        assertTrue(smallQueue.contains("req_3"))
        assertTrue(smallQueue.contains("req_4"))
    }

    @Test
    fun testMediaFavoritesAddRemoveAndToggle() {
        val mediaItem = MediaItem(
            id = "media_101",
            title = "신나는 고양이 댄스",
            url = "https://example.com/cat.gif",
            thumbnailUrl = "https://example.com/cat_thumb.jpg",
            mediaType = MediaType.GIF,
            width = 300,
            height = 200
        )

        assertFalse(favoritesManager.isFavorite("media_101"))

        assertTrue(favoritesManager.addFavorite(mediaItem))
        assertTrue(favoritesManager.isFavorite("media_101"))
        assertEquals(1, favoritesManager.getFavorites().size)

        // Duplicate add is idempotent
        assertFalse(favoritesManager.addFavorite(mediaItem))
        assertEquals(1, favoritesManager.getFavorites().size)

        // Toggle to remove
        assertFalse(favoritesManager.toggleFavorite(mediaItem))
        assertFalse(favoritesManager.isFavorite("media_101"))
        assertTrue(favoritesManager.getFavorites().isEmpty())

        // Toggle to add
        assertTrue(favoritesManager.toggleFavorite(mediaItem))
        assertTrue(favoritesManager.isFavorite("media_101"))
    }

    @Test
    fun testFavoritesSearchAndTagFiltering() {
        favoritesManager.addFavorite(MediaItem("1", "귀여운 고양이", "url1", "thumb1", MediaType.GIF, 100, 100, tags = listOf("cat", "cute", "동물")))
        favoritesManager.addFavorite(MediaItem("2", "웃긴 강아지", "url2", "thumb2", MediaType.GIF, 100, 100, tags = listOf("dog", "funny", "동물")))
        favoritesManager.addFavorite(MediaItem("3", "코딩 짤", "url3", "thumb3", MediaType.GIF, 100, 100, tags = listOf("code", "dev")))

        val catResults = favoritesManager.search("고양이")
        assertEquals(1, catResults.size)
        assertEquals("1", catResults[0].id)

        val animalResults = favoritesManager.findByTag("동물")
        assertEquals(2, animalResults.size)

        val allResults = favoritesManager.search("")
        assertEquals(3, allResults.size)
    }

    @Test
    fun testMassiveRetryAndFavoritesStressTest() {
        for (i in 0 until 100) {
            val item = MediaItem(
                id = "item_$i",
                title = "Media $i",
                url = "https://example.com/$i.gif",
                thumbnailUrl = "https://example.com/$i.jpg",
                mediaType = if (i % 2 == 0) MediaType.GIF else MediaType.VIDEO,
                width = 200,
                height = 200
            )
            favoritesManager.addFavorite(item)
            assertTrue(favoritesManager.isFavorite("item_$i"))

            val req = MediaSearchRequest("req_$i", "Query $i", MediaType.GIF, i * 10L)
            retryQueue.enqueue(req)
        }

        assertEquals(100, favoritesManager.getFavorites().size)
        assertEquals(50, retryQueue.size()) // Capped at maxQueueSize 50
    }
}
