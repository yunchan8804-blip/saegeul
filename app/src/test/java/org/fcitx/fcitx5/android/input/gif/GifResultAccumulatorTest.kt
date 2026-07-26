/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import org.junit.Assert.assertEquals
import org.junit.Test

class GifResultAccumulatorTest {
    @Test
    fun klipyPagesStayIsolatedAndRemoveIdOrMediaDuplicatesInStableOrder() {
        val existing = listOf(result("klipy", 1, "a.gif"))
        val incoming = listOf(
            result("klipy", 1, "new.gif"),
            result("klipy", 2, "a.gif"),
            result("giphy", 3, "giphy.gif"),
            result("klipy", 4, "fresh.gif")
        )

        val additions = GifResultAccumulator.additions(existing, incoming)

        assertEquals(listOf(4L), additions.map(GifResult::id))
    }

    @Test
    fun giphyOrderAndDuplicatesArePreservedButOtherProvidersNeverEnterGrid() {
        val existing = listOf(result("giphy", 1, "a.gif"))
        val incoming = listOf(
            result("giphy", 1, "a.gif"),
            result("klipy", 2, "foreign.gif"),
            result("giphy", 3, "c.gif")
        )

        val additions = GifResultAccumulator.additions(existing, incoming)

        assertEquals(listOf(1L, 3L), additions.map(GifResult::id))
    }

    private fun result(providerId: String, id: Long, media: String) = GifResult(
        providerId = providerId,
        id = id,
        title = "result-$id",
        description = "",
        thumbnailUrl = "https://example.test/$media.webp",
        mediaUrl = "https://example.test/$media",
        canonicalUrl = "https://example.test/$id",
        mimeType = "image/gif",
        byteSize = 1,
        width = 1,
        height = 1,
        license = GifLicense("test", null, "test", "test", false),
        safe = true
    )
}
