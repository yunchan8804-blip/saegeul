/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import androidx.annotation.ColorInt

fun radiusDrawable(
    r: Float, @ColorInt
    color: Int = Color.WHITE
): Drawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = r
}

fun insetRadiusDrawable(
    hInset: Int,
    vInset: Int,
    r: Float = 0f,
    @ColorInt color: Int = Color.WHITE
): Drawable = InsetDrawable(
    radiusDrawable(r, color),
    hInset, vInset, hInset, vInset
)

fun insetOvalDrawable(
    hInset: Int,
    vInset: Int,
    @ColorInt color: Int = Color.WHITE
): Drawable = InsetDrawable(
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    },
    hInset, vInset, hInset, vInset
)

fun shadowedKeyBackgroundDrawable(
    @ColorInt bkgColor: Int,
    @ColorInt shadowColor: Int,
    radius: Float,
    shadowWidth: Int,
    hMargin: Int,
    vMargin: Int
): Drawable = LayerDrawable(
    arrayOf(
        radiusDrawable(radius, shadowColor),
        radiusDrawable(radius, bkgColor),
    )
).apply {
    setLayerInset(0, hMargin, vMargin, hMargin, vMargin - shadowWidth)
    setLayerInset(1, hMargin, vMargin, hMargin, vMargin)
}

fun borderedKeyBackgroundDrawable(
    @ColorInt bkgColor: Int,
    @ColorInt shadowColor: Int,
    radius: Float,
    strokeWidth: Int,
    hMargin: Int,
    vMargin: Int
): Drawable = LayerDrawable(
    arrayOf(
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(bkgColor)
            setStroke(strokeWidth, shadowColor)
        }
    )
).apply {
    setLayerInset(0, hMargin, vMargin, hMargin, vMargin)
}

fun glowingKeyBackgroundDrawable(
    @ColorInt bkgColor: Int,
    @ColorInt glowColor: Int,
    radius: Float,
    glowWidth: Int,
    hMargin: Int,
    vMargin: Int
): Drawable {
    val glowAlpha = Color.argb(
        (Color.alpha(glowColor) * 0.45f).toInt(),
        Color.red(glowColor),
        Color.green(glowColor),
        Color.blue(glowColor)
    )
    val outerGlowAlpha = Color.argb(
        (Color.alpha(glowColor) * 0.20f).toInt(),
        Color.red(glowColor),
        Color.green(glowColor),
        Color.blue(glowColor)
    )
    return LayerDrawable(
        arrayOf(
            // Outer halo
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius + glowWidth * 1.5f
                setColor(outerGlowAlpha)
            },
            // Inner halo
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius + glowWidth
                setColor(glowAlpha)
                setStroke(glowWidth, glowColor)
            },
            // Core key body
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(bkgColor)
            }
        )
    ).apply {
        setLayerInset(0, maxOf(0, hMargin - glowWidth * 2), maxOf(0, vMargin - glowWidth * 2), maxOf(0, hMargin - glowWidth * 2), maxOf(0, vMargin - glowWidth * 2))
        setLayerInset(1, maxOf(0, hMargin - glowWidth), maxOf(0, vMargin - glowWidth), maxOf(0, hMargin - glowWidth), maxOf(0, vMargin - glowWidth))
        setLayerInset(2, hMargin, vMargin, hMargin, vMargin)
    }
}

