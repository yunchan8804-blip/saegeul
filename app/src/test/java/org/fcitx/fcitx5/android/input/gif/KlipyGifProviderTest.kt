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

class KlipyGifProviderTest {
    private val provider = KlipyGifProvider("unit-test-key")

    @Test
    fun koreanSearchUrlUsesUtf8LocaleAndCapsPageSize() {
        val url = provider.buildApiUrl("축하 짤", 99)

        assertTrue(url.startsWith("https://api.klipy.com/api/v1/unit-test-key/gifs/search?"))
        assertTrue(url.contains("page=1"))
        assertTrue(url.contains("per_page=24"))
        assertTrue(url.contains("locale=ko"))
        assertTrue(url.contains("q=%EC%B6%95%ED%95%98%20%EC%A7%A4"))
    }

    @Test
    fun blankQueryUsesLocalizedTrendingEndpoint() {
        val url = provider.buildApiUrl("   ", 12)

        assertTrue(url.contains("/gifs/trending?"))
        assertTrue(url.contains("per_page=12"))
        assertTrue(url.contains("locale=ko"))
        assertFalse(url.contains("q="))
    }

    @Test
    fun parsesOnlyGifsWithMediumGifAndSmallWebp() {
        val result = provider.parseResponse(MIXED_RESPONSE).single()

        assertEquals("klipy", result.providerId)
        assertEquals("Funny reaction", result.title)
        assertEquals("https://cdn.klipy.test/funny-md.gif", result.mediaUrl)
        assertEquals("https://cdn.klipy.test/funny-sm.webp", result.thumbnailUrl)
        assertEquals("https://klipy.com/gifs/funny-reaction-42", result.canonicalUrl)
        assertEquals("image/gif", result.mimeType)
        assertEquals(123_456L, result.byteSize)
        assertEquals(480, result.width)
        assertEquals(270, result.height)
        assertEquals("Powered by KLIPY", result.license.author)
        assertEquals("Powered by KLIPY", result.license.credit)
        assertTrue(result.license.attributionRequired)
    }

    @Test
    fun fallsBackAcrossSmallAndMediumRenditions() {
        val result = provider.parseResponse(SMALL_GIF_MEDIUM_WEBP_RESPONSE).single()

        assertEquals("https://cdn.klipy.test/only-sm.gif", result.mediaUrl)
        assertEquals("https://cdn.klipy.test/only-md.webp", result.thumbnailUrl)
        assertEquals(240, result.width)
        assertEquals(135, result.height)
    }

    @Test
    fun excludesEntriesWithoutHttpsGifMedia() {
        assertTrue(provider.parseResponse(INSECURE_MEDIA_RESPONSE).isEmpty())
    }

    @Test
    fun rejectedOrMalformedResponsesReturnSanitizedErrors() {
        listOf(
            """{"result":false,"message":"bad key secret-value"}""",
            """{"result":true,"data":{}}""",
            "not-json"
        ).forEach { body ->
            try {
                provider.parseResponse(body)
                fail("Expected GifNetworkException")
            } catch (error: GifNetworkException) {
                assertFalse(error.message.orEmpty().contains("unit-test-key"))
                assertFalse(error.message.orEmpty().contains("secret-value"))
            }
        }
    }

    @Test
    fun apiKeyIsRequiredWithoutEchoingInput() {
        try {
            KlipyGifProvider("  ")
            fail("Expected IllegalArgumentException")
        } catch (error: IllegalArgumentException) {
            assertFalse(error.message.orEmpty().contains("unit-test-key"))
        }
    }

    companion object {
        private val MIXED_RESPONSE = """
            {
              "result": true,
              "data": {
                "data": [
                  {
                    "slug": "funny-reaction-42",
                    "title": "Funny reaction",
                    "type": "gif",
                    "file": {
                      "md": {
                        "gif": {"url":"https://cdn.klipy.test/funny-md.gif","width":480,"height":270,"size":123456},
                        "webp": {"url":"https://cdn.klipy.test/funny-md.webp","width":480,"height":270,"size":45678}
                      },
                      "sm": {
                        "gif": {"url":"https://cdn.klipy.test/funny-sm.gif","width":240,"height":135,"size":65432},
                        "webp": {"url":"https://cdn.klipy.test/funny-sm.webp","width":240,"height":135,"size":23456}
                      }
                    }
                  },
                  {
                    "slug": "not-a-gif",
                    "title": "Sticker",
                    "type": "sticker",
                    "file": {"md":{"gif":{"url":"https://cdn.klipy.test/sticker.gif"}}}
                  },
                  {"type":"ad","content":"<div>Ad</div>"},
                  {"slug":"clip","type":"clip","file":{"md":{"gif":{"url":"https://cdn.klipy.test/clip.gif"}}}}
                ]
              }
            }
        """.trimIndent()

        private val SMALL_GIF_MEDIUM_WEBP_RESPONSE = """
            {"result":true,"data":{"data":[{
              "slug":"fallback","title":"Fallback","type":"gif","file":{
                "md":{"webp":{"url":"https://cdn.klipy.test/only-md.webp","width":480,"height":270}},
                "sm":{"gif":{"url":"https://cdn.klipy.test/only-sm.gif","width":240,"height":135,"size":1000}}
              }
            }]}}
        """.trimIndent()

        private val INSECURE_MEDIA_RESPONSE = """
            {"result":true,"data":{"data":[{
              "slug":"insecure","title":"Insecure","type":"gif","file":{
                "md":{"gif":{"url":"http://cdn.klipy.test/insecure.gif","width":100,"height":100,"size":100}}
              }
            }]}}
        """.trimIndent()
    }
}
