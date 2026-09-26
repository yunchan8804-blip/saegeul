/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OklchTest {

    @Test
    fun sRgbToOklchRoundTripWithinOneChannelStep() {
        val samples = intArrayOf(
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(),
            0xFF0000FF.toInt(), 0xFF808080.toInt(), 0xFFEFE9DC.toInt(), 0xFF101827.toInt(),
            0xFF55D6A6.toInt(), 0xFF176B50.toInt(), 0xFFFFF9ED.toInt(), 0xFF1E2A3C.toInt()
        )
        for (color in samples) {
            val oklch = Oklch.colorToOklch(color)
            val roundTripped = Oklch.oklchToColor(oklch)
            assertChannelWithinOne("red", (color ushr 16) and 0xFF, (roundTripped ushr 16) and 0xFF, color)
            assertChannelWithinOne("green", (color ushr 8) and 0xFF, (roundTripped ushr 8) and 0xFF, color)
            assertChannelWithinOne("blue", color and 0xFF, roundTripped and 0xFF, color)
        }
    }

    @Test
    fun oklabToOklchRoundTrip() {
        val lab = Oklch.Oklab(L = 0.6, a = 0.05, b = -0.03)
        val lch = Oklch.oklabToOklch(lab)
        val back = Oklch.oklchToOklab(lch)
        assertTrue(abs(lab.L - back.L) < 1e-9)
        assertTrue(abs(lab.a - back.a) < 1e-9)
        assertTrue(abs(lab.b - back.b) < 1e-9)
    }

    @Test
    fun blackAndWhiteHaveExpectedLightness() {
        val black = Oklch.colorToOklch(0xFF000000.toInt())
        val white = Oklch.colorToOklch(0xFFFFFFFF.toInt())
        assertTrue(black.L < 0.01)
        assertTrue(white.L > 0.99)
    }

    @Test
    fun grayscaleColorsHaveNearZeroChroma() {
        val gray = Oklch.colorToOklch(0xFF808080.toInt())
        assertTrue(gray.C < 0.005)
    }

    private fun assertChannelWithinOne(label: String, original: Int, roundTripped: Int, color: Int) {
        val diff = abs(original - roundTripped)
        assertTrue(
            "channel $label diff=$diff for color=${Integer.toHexString(color)} ($original vs $roundTripped)",
            diff <= 1
        )
    }
}
