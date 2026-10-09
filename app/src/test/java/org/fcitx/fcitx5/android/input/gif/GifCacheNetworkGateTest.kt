/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.gif

import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GifCacheNetworkGateTest {
    private lateinit var directory: File
    private var transportCalls = 0

    @Before
    fun setUp() {
        directory = File.createTempFile("gif-cache", "").apply {
            delete()
            mkdirs()
        }
        transportCalls = 0
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun blockedNetworkMakesNoDownloadRequest() = runBlocking {
        val cache = GifCache(directory) { _ ->
            transportCalls++
            animatedGif()
        }

        assertNull(cache.getOrDownload(result(), allowsNetwork = false))
        assertEquals(0, transportCalls)
    }

    @Test
    fun openNetworkDownloadsOnce() = runBlocking {
        val cache = GifCache(directory) { _ ->
            transportCalls++
            animatedGif()
        }

        assertNotNull(cache.getOrDownload(result(), allowsNetwork = true))
        assertEquals(1, transportCalls)
    }

    @Test
    fun cachedCopyIsServedWithoutNetwork() = runBlocking {
        val cache = GifCache(directory) { _ ->
            transportCalls++
            animatedGif()
        }
        val downloaded = cache.getOrDownload(result(), allowsNetwork = true)

        val reused = cache.getOrDownload(result(), allowsNetwork = false)

        assertEquals(downloaded, reused)
        assertEquals(1, transportCalls)
    }

    private fun result() = GifResult(
        providerId = "test",
        id = 1,
        title = "t",
        description = "",
        thumbnailUrl = "https://example.test/t.webp",
        mediaUrl = "https://example.test/m.gif",
        canonicalUrl = "https://example.test/1",
        mimeType = "image/gif",
        byteSize = 1,
        width = 1,
        height = 1,
        license = GifLicense("test", null, "test", "test", false),
        safe = true
    )

    private fun animatedGif(): ByteArray {
        val staticGif = Base64.getDecoder().decode(STATIC_GIF)
        return staticGif.copyOf(staticGif.size + SECOND_FRAME.size).apply {
            staticGif.copyInto(this)
            SECOND_FRAME.copyInto(this, staticGif.size - 1)
        }
    }

    private companion object {
        const val STATIC_GIF = "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
        val SECOND_FRAME = byteArrayOf(
            0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0,
            2, 2, 0x44, 0x01, 0, 0x3b
        )
    }
}
