/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorGeneratorTest {

    companion object {
        private const val SEED_L = 0.55
        private const val LOW_CHROMA = 0.035
        private const val HIGH_CHROMA = 0.08
        private val HUES = (0 until 360 step 30).map { it.toDouble() }
    }

    @Test
    fun generatedThemesAcrossHueAndSaturationMatrixHaveNoContrastIssues() {
        for (hue in HUES) {
            for (chroma in listOf(LOW_CHROMA, HIGH_CHROMA)) {
                for (isDark in listOf(false, true)) {
                    val seed = Oklch.oklchToColor(Oklch.OklchColor(SEED_L, chroma, hue))
                    val theme = ThemeColorGenerator.generate(seed, isDark, "Seed$hue$chroma$isDark")
                    val issues = ThemeContrast.findIssues(theme)
                    assertTrue(
                        "hue=$hue chroma=$chroma isDark=$isDark issues=$issues",
                        issues.isEmpty()
                    )
                }
            }
        }
    }

    @Test
    fun generatedThemesAreAllOpaqueValidSrgb() {
        for (hue in HUES) {
            for (chroma in listOf(LOW_CHROMA, HIGH_CHROMA)) {
                for (isDark in listOf(false, true)) {
                    val seed = Oklch.oklchToColor(Oklch.OklchColor(SEED_L, chroma, hue))
                    val theme = ThemeColorGenerator.generate(seed, isDark, "Seed")
                    val opaqueFields = listOf(
                        theme.backgroundColor, theme.barColor, theme.keyboardColor,
                        theme.keyBackgroundColor, theme.keyTextColor, theme.candidateTextColor,
                        theme.candidateLabelColor, theme.candidateCommentColor,
                        theme.altKeyBackgroundColor, theme.altKeyTextColor,
                        theme.accentKeyBackgroundColor, theme.accentKeyTextColor,
                        theme.keyShadowColor, theme.popupBackgroundColor, theme.popupTextColor,
                        theme.spaceBarColor, theme.dividerColor, theme.clipboardEntryColor,
                        theme.genericActiveBackgroundColor, theme.genericActiveForegroundColor
                    )
                    for (color in opaqueFields) {
                        assertEquals("expected fully opaque color 0x${Integer.toHexString(color)}", 0xFF, alphaOf(color))
                    }
                    // keyPressHighlightColor is intentionally translucent, but must still be valid.
                    assertTrue(alphaOf(theme.keyPressHighlightColor) in 0..255)
                }
            }
        }
    }

    @Test
    fun accentHueStaysWithin15DegreesOfSeedForChromaticSeeds() {
        for (hue in HUES) {
            for (chroma in listOf(LOW_CHROMA, HIGH_CHROMA)) {
                for (isDark in listOf(false, true)) {
                    val seed = Oklch.oklchToColor(Oklch.OklchColor(SEED_L, chroma, hue))
                    val seedHue = Oklch.colorToOklch(seed).h
                    val theme = ThemeColorGenerator.generate(seed, isDark, "Seed")
                    val accentHue = Oklch.colorToOklch(theme.accentKeyBackgroundColor).h
                    val delta = circularHueDelta(seedHue, accentHue)
                    assertTrue(
                        "hue=$hue chroma=$chroma isDark=$isDark seedHue=$seedHue accentHue=$accentHue delta=$delta",
                        delta <= 15.0
                    )
                }
            }
        }
    }

    @Test
    fun effectFieldsAreAlwaysNull() {
        val seed = Oklch.oklchToColor(Oklch.OklchColor(SEED_L, HIGH_CHROMA, 200.0))
        val theme = ThemeColorGenerator.generate(seed, isDark = true, name = "Seed")
        assertEquals(null, theme.lightingEffect)
        assertEquals(null, theme.particleEffect)
        assertEquals(null, theme.keyGlowEffect)
        assertEquals(null, theme.keyOverrides)
        assertEquals(null, theme.globalKeyStyle)
        assertEquals(null, theme.backgroundImage)
    }

    private fun alphaOf(color: Int) = (color ushr 24) and 0xFF

    private fun circularHueDelta(a: Double, b: Double): Double {
        var diff = Math.abs(a - b) % 360.0
        if (diff > 180.0) diff = 360.0 - diff
        return diff
    }
}
