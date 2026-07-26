/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotoAnimatedEmojiProviderTest {
    private val provider = NotoAnimatedEmojiProvider()
    private val catalog = provider.parseCatalog(SAMPLE_CATALOG)

    @Test
    fun parsesValidCatalogEntriesAndRejectsMalformedCodepoints() {
        assertEquals(3, catalog.size)
        assertEquals("partying face", catalog.first().tags.first())
    }

    @Test
    fun koreanReactionQueryMatchesLocalEnglishMetadata() {
        val results = provider.searchCatalog(catalog, "축하")

        assertEquals(2, results.size)
        assertTrue(results.any { it.mediaUrl.contains("/1f973/512.gif") })
        assertTrue(results.any { it.mediaUrl.contains("/1f44f/512.gif") })
    }

    @Test
    fun separatesAnimatedMediaPreviewAndCanonicalAttribution() {
        val result = provider.searchCatalog(catalog, "파티").single()

        assertTrue(result.thumbnailUrl.endsWith("/512.webp"))
        assertTrue(result.mediaUrl.endsWith("/512.gif"))
        assertFalse(result.canonicalUrl == result.mediaUrl)
        assertEquals("image/gif", result.mimeType)
        assertEquals(0L, result.byteSize)
        assertEquals("CC BY 4.0", result.license.name)
        assertEquals("Google", result.license.author)
        assertTrue(result.license.attributionRequired)
    }

    @Test
    fun unknownDeclaredSizeIsAcceptedButOversizedMetadataIsRejected() {
        assertTrue(GifCache.isDeclaredSizeAllowed(GifCache.UNKNOWN_SIZE))
        assertTrue(GifCache.isDeclaredSizeAllowed(GifCache.MAX_BYTES))
        assertFalse(GifCache.isDeclaredSizeAllowed(GifCache.MAX_BYTES + 1))
        assertFalse(GifCache.isDeclaredSizeAllowed(-1))
    }

    companion object {
        private val SAMPLE_CATALOG = """
            {"icons":[
              {"codepoint":"1f973","popularity":90,"tags":[":partying-face:"],"categories":["Smileys & Emotion"]},
              {"codepoint":"1f44f","popularity":80,"tags":[":clapping-hands:"],"categories":["People & Body"]},
              {"codepoint":"1f62d","popularity":70,"tags":[":loudly-crying-face:"],"categories":["Smileys & Emotion"]},
              {"codepoint":"not-a-codepoint","popularity":100,"tags":[":bad:"],"categories":[]}
            ]}
        """.trimIndent()
    }
}
