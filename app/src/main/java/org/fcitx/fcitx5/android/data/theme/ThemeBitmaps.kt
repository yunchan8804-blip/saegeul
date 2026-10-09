/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Bounded decoding of theme background images. */
object ThemeBitmaps {

    /** Longest side, in pixels, a theme image is decoded at. Larger images are subsampled. */
    const val MAX_DECODE_LONG_SIDE = 4096

    /** Longest side, in pixels, an imported theme image may declare. */
    const val MAX_IMPORT_SIDE = 16384

    /** The power-of-two `inSampleSize` that brings the longer side down to [maxLongSide]. */
    fun sampleSizeFor(width: Int, height: Int, maxLongSide: Int = MAX_DECODE_LONG_SIDE): Int {
        require(maxLongSide > 0) { "maxLongSide must be positive: $maxLongSide" }
        val longSide = maxOf(width, height)
        var sampleSize = 1
        while ((longSide + sampleSize - 1) / sampleSize > maxLongSide) {
            sampleSize *= 2
        }
        return sampleSize
    }

    fun isImportableSize(width: Int, height: Int): Boolean =
        width in 1..MAX_IMPORT_SIDE && height in 1..MAX_IMPORT_SIDE

    /** Width and height from the image header, or null when the file is not a decodable image. */
    fun readBounds(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return options.outWidth to options.outHeight
    }

    fun isImportable(file: File): Boolean {
        val (width, height) = readBounds(file) ?: return false
        return isImportableSize(width, height)
    }

    fun decodeFile(file: File, maxLongSide: Int = MAX_DECODE_LONG_SIDE): Bitmap? {
        val (width, height) = readBounds(file) ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(width, height, maxLongSide)
        }
        return file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
    }
}
