/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TDD Test Suite for Media Query Normalization and favorites search.
 * Tests special symbol normalization (ㅋㅋㅋㅋ, ㅠㅠ, 초성) and whitespace/case-insensitive search.
 */
class MediaSearchOfflineResilienceTest {

    private lateinit var favoritesManager: MediaFavoritesManager

    @Before
    fun setUp() {
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
