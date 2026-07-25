/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GifFileInspectorTest {
    @Test
    fun distinguishesStaticAndAnimatedGifBlocks() {
        val staticGif = Base64.getDecoder().decode(STATIC_GIF)
        val animatedGif = staticGif.copyOf(staticGif.size + SECOND_FRAME.size).apply {
            staticGif.copyInto(this)
            // Replace the trailer with a second valid 1x1 image block and a new trailer.
            SECOND_FRAME.copyInto(this, staticGif.size - 1)
        }
        assertFalse(GifFileInspector.isAnimated(staticGif))
        assertTrue(GifFileInspector.isAnimated(animatedGif))
    }

    @Test
    fun rejectsNonGifBytes() {
        assertFalse(GifFileInspector.isAnimated("not a gif".toByteArray()))
    }

    companion object {
        // Transparent 1x1 GIF89a.
        private const val STATIC_GIF = "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
        private val SECOND_FRAME = byteArrayOf(
            0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0,
            2, 2, 0x44, 0x01, 0, 0x3b
        )
    }
}
