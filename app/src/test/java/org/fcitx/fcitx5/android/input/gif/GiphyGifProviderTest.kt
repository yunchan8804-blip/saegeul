/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GiphyGifProviderTest {
    @Test
    fun koreanSearchUsesExactQuerySafeRatingLocaleAndPaging() {
        val url = provider().buildApiUrl("축하 짤", limit = 99, page = 2)

        assertTrue(url.startsWith("https://api.giphy.com/v1/gifs/search?"))
        assertTrue(url.contains("api_key=unit-test-key"))
        assertTrue(url.contains("limit=24"))
        assertTrue(url.contains("offset=24"))
        assertTrue(url.contains("rating=g"))
        assertTrue(url.contains("country_code=KR"))
        assertTrue(url.contains("lang=ko"))
        assertTrue(url.contains("q=%EC%B6%95%ED%95%98%20%EC%A7%A4"))
    }

    @Test
    fun blankQueryUsesTrendingWithoutSearchParameters() {
        val url = provider().buildApiUrl("", limit = 12, page = 3)

        assertTrue(url.contains("/trending?"))
        assertTrue(url.contains("limit=12"))
        assertTrue(url.contains("offset=24"))
        assertFalse(url.contains("&q="))
        assertFalse(url.contains("&lang="))
    }

    @Test
    fun responseOrderCanonicalMediaAttributionAndAnalyticsArePreserved() {
        val page = provider(mediaCachingApproved = true).parsePageResponse(SAFE_RESPONSE)

        assertTrue(page.hasNext)
        assertEquals(listOf("First reaction", "Second reaction"), page.items.map(GifResult::title))
        assertTrue(page.items.all { it.providerId == GiphyGifProvider.PROVIDER_ID })
        val first = page.items.first()
        assertEquals("https://giphy.com/gifs/first-abc", first.canonicalUrl)
        assertEquals("https://media.giphy.com/media/abc/giphy.gif", first.mediaUrl)
        assertEquals("https://media.giphy.com/media/abc/100w.webp", first.thumbnailUrl)
        assertEquals("Powered by GIPHY", first.license.credit)
        assertTrue(first.attachmentDownloadAllowed)
        assertEquals("https://giphy-analytics.giphy.com/simple_analytics?event=GIF_SEARCH", first.analytics?.onLoadUrl)
    }

    @Test
    fun mediaCopyIsDisabledWithoutSeparateApproval() {
        val first = provider(mediaCachingApproved = false)
            .parsePageResponse(SAFE_RESPONSE)
            .items.first()

        assertFalse(first.attachmentDownloadAllowed)
    }

    @Test
    fun responseWithHigherRatingFailsWholePageClosed() {
        val unsafe = SAFE_RESPONSE.replaceFirst("\"rating\":\"g\"", "\"rating\":\"pg-13\"")
        assertTrue(unsafe.contains("\"rating\":\"pg-13\""))

        try {
            provider().parsePageResponse(unsafe)
            fail("Expected a fail-closed response")
        } catch (error: GifNetworkException) {
            assertTrue(error.message.orEmpty().contains("safe rating"))
            assertFalse(error.message.orEmpty().contains("unit-test-key"))
        }
    }

    @Test
    fun pageWithUnofficialAnalyticsFailsClosedWithoutFilteringOrReordering() {
        val response = SAFE_RESPONSE.replace(
            "https://giphy-analytics.giphy.com/simple_analytics?event=GIF_SEARCH",
            "https://tracker.example.test/collect"
        )

        try {
            provider().parsePageResponse(response)
            fail("Expected a fail-closed page")
        } catch (error: GifNetworkException) {
            assertTrue(error.message.orEmpty().contains("invalid or incomplete"))
        }
    }

    private fun provider(mediaCachingApproved: Boolean = false) =
        GiphyGifProvider("unit-test-key", mediaCachingApproved)

    private companion object {
        val SAFE_RESPONSE = """
            {
              "meta":{"status":200},
              "pagination":{"count":2,"offset":0,"total_count":3},
              "data":[
                {
                  "type":"gif","id":"abc","title":"First reaction","rating":"g",
                  "url":"https://giphy.com/gifs/first-abc",
                  "images":{
                    "original":{"url":"https://media.giphy.com/media/abc/giphy.gif","width":"480","height":"270","size":"12345"},
                    "fixed_width_small":{"webp":"https://media.giphy.com/media/abc/100w.webp"}
                  },
                  "analytics":{
                    "onload":{"url":"https://giphy-analytics.giphy.com/simple_analytics?event=GIF_SEARCH"},
                    "onclick":{"url":"https://giphy-analytics.giphy.com/simple_analytics?event=GIF_CLICK"},
                    "onsent":{"url":"https://giphy-analytics.giphy.com/simple_analytics?event=GIF_SENT"}
                  }
                },
                {
                  "type":"gif","id":"def","title":"Second reaction","rating":"g",
                  "url":"https://giphy.com/gifs/second-def",
                  "images":{
                    "original":{"url":"https://media.giphy.com/media/def/giphy.gif","width":"320","height":"180","size":"4567"},
                    "fixed_width":{"url":"https://media.giphy.com/media/def/200w.gif"}
                  },
                  "analytics":{
                    "onload":{"url":"https://giphy-analytics.giphy.com/a?event=load"},
                    "onclick":{"url":"https://giphy-analytics.giphy.com/a?event=click"},
                    "onsent":{"url":"https://giphy-analytics.giphy.com/a?event=send"}
                  }
                }
              ]
            }
        """.trimIndent()
    }
}
