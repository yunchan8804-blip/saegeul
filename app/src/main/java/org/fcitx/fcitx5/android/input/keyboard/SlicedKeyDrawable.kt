/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.min

/**
 * High-performance sliced (9-Patch) Key Drawable supporting custom slice insets and corner preservation.
 */
class SlicedKeyDrawable(
    private val bitmap: Bitmap,
    private val leftSlice: Int,
    private val topSlice: Int,
    private val rightSlice: Int,
    private val bottomSlice: Int,
    private val repeatMode: String = "stretch"
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()

    override fun draw(canvas: Canvas) {
        val bWidth = bitmap.width
        val bHeight = bitmap.height
        if (bWidth <= 0 || bHeight <= 0) return

        val b = bounds
        val dWidth = b.width()
        val dHeight = b.height()
        if (dWidth <= 0 || dHeight <= 0) return

        val sL = min(leftSlice, bWidth / 2)
        val sT = min(topSlice, bHeight / 2)
        val sR = min(rightSlice, bWidth / 2)
        val sB = min(bottomSlice, bHeight / 2)

        val dL = min(sL, dWidth / 2)
        val dT = min(sT, dHeight / 2)
        val dR = min(sR, dWidth / 2)
        val dB = min(sB, dHeight / 2)

        // 3x3 source X slices
        val srcX = intArrayOf(0, sL, bWidth - sR, bWidth)
        // 3x3 source Y slices
        val srcY = intArrayOf(0, sT, bHeight - sB, bHeight)

        // 3x3 dest X slices
        val dstX = intArrayOf(b.left, b.left + dL, b.right - dR, b.right)
        // 3x3 dest Y slices
        val dstY = intArrayOf(b.top, b.top + dT, b.bottom - dB, b.bottom)

        for (row in 0 until 3) {
            for (col in 0 until 3) {
                srcRect.set(srcX[col], srcY[row], srcX[col + 1], srcY[row + 1])
                dstRect.set(dstX[col], dstY[row], dstX[col + 1], dstY[row + 1])

                if (srcRect.width() > 0 && srcRect.height() > 0 &&
                    dstRect.width() > 0 && dstRect.height() > 0
                ) {
                    canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
                }
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int =
        if (bitmap.hasAlpha() || paint.alpha < 255) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE
}
