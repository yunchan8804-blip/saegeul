/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GifMimeSupportTest {
    @Test
    fun exactAndWildcardImageTypesSupportGif() {
        assertTrue(GifMimeSupport.supportsGif(arrayOf("image/gif")))
        assertTrue(GifMimeSupport.supportsGif(arrayOf("image/*")))
        assertTrue(GifMimeSupport.supportsGif(arrayOf("*/*")))
    }

    @Test
    fun textAndOtherImageTypesDoNotSupportGif() {
        assertFalse(GifMimeSupport.supportsGif(emptyArray()))
        assertFalse(GifMimeSupport.supportsGif(arrayOf("text/plain", "image/png")))
    }
}
