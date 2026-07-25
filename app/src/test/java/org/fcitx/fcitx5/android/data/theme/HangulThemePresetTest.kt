/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class HangulThemePresetTest {

    @Test
    fun presetsHaveExpectedDayNightModes() {
        assertFalse(ThemePreset.HanjiLight.isDark)
        assertTrue(ThemePreset.DancheongDark.isDark)
    }

    @Test
    fun denseHangulLegendsMeetContrastTarget() {
        listOf(ThemePreset.HanjiLight, ThemePreset.DancheongDark).forEach { theme ->
            assertTrue(theme.name, contrast(theme.keyTextColor, theme.keyBackgroundColor) >= 7.0)
            assertTrue(theme.name, contrast(theme.accentKeyTextColor, theme.accentKeyBackgroundColor) >= 4.5)
        }
    }

    private fun contrast(foreground: Int, background: Int): Double {
        val a = luminance(foreground) + 0.05
        val b = luminance(background) + 0.05
        return max(a, b) / min(a, b)
    }

    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.04045) normalized / 12.92
            else ((normalized + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color shr 16 and 0xff) +
            0.7152 * channel(color shr 8 and 0xff) +
            0.0722 * channel(color and 0xff)
    }
}
