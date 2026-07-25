/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WikimediaCommonsGifProviderTest {
    private val provider = WikimediaCommonsGifProvider()

    @Test
    fun koreanQueryAddsReactionExpansionAndExactGifMimeFilter() {
        val expression = provider.buildSearchExpression("축하")
        assertTrue(expression.contains("happy icon animated"))
        assertTrue(!expression.contains("축하"))
        assertTrue(expression.contains("filemime:\"image/gif\""))
    }

    @Test
    fun parsesCanonicalAndMediaUrlsSeparately() {
        val result = provider.parseResponse(VALID_RESPONSE).single()
        assertEquals("https://commons.wikimedia.org/wiki/File:Wave.gif", result.canonicalUrl)
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/a/a1/Wave.gif", result.mediaUrl)
        assertEquals("Alice", result.license.author)
        assertEquals("CC BY-SA 4.0", result.license.name)
    }

    @Test
    fun excludesWrongMimeAndRestrictedEntries() {
        assertTrue(provider.parseResponse(REJECTED_RESPONSE).isEmpty())
    }

    companion object {
        private val VALID_RESPONSE = """
            {"query":{"pages":[{"pageid":42,"title":"File:Wave.gif",
            "canonicalurl":"https://commons.wikimedia.org/wiki/File:Wave.gif",
            "imageinfo":[{"size":12345,"width":200,"height":100,"mime":"image/gif",
            "url":"https://upload.wikimedia.org/wikipedia/commons/a/a1/Wave.gif",
            "thumburl":"https://upload.wikimedia.org/thumb/Wave.gif",
            "extmetadata":{"LicenseShortName":{"value":"CC BY-SA 4.0"},
            "LicenseUrl":{"value":"https://creativecommons.org/licenses/by-sa/4.0"},
            "Artist":{"value":"<b>Alice</b>"},"Credit":{"value":"Own work"},
            "AttributionRequired":{"value":"true"},"Restrictions":{"value":""},
            "UsageTerms":{"value":"Creative Commons"},
            "ImageDescription":{"value":"A friendly wave"}}}] }]}}
        """.trimIndent()

        private val REJECTED_RESPONSE = """
            {"query":{"pages":[{"pageid":43,"title":"File:Bad.gif",
            "canonicalurl":"https://commons.wikimedia.org/wiki/File:Bad.gif",
            "imageinfo":[{"size":123,"mime":"image/gif","url":"https://example.test/Bad.gif",
            "thumburl":"https://example.test/Bad.gif","extmetadata":{
            "LicenseShortName":{"value":"CC BY 4.0"},"Artist":{"value":"Bob"},
            "Credit":{"value":"Do not use"},"Restrictions":{"value":""}}}]}]}}
        """.trimIndent()
    }
}
