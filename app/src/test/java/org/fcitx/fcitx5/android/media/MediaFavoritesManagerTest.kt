/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TDD Test Suite for Media Favorite Bookmarking.
 * Validates add/remove/toggle, search, tag filtering, and capacity limits.
 */
class MediaFavoritesManagerTest {

    private lateinit var favoritesManager: MediaFavoritesManager

    @Before
    fun setUp() {
        favoritesManager = MediaFavoritesManager(maxCapacity = 100)
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
    fun testMassiveFavoritesStressTest() {
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
        }

        assertEquals(100, favoritesManager.getFavorites().size)
    }
}
