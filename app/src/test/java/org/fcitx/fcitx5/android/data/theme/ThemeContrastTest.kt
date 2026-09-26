/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {

    @Test
    fun blackOnWhiteIs21To1() {
        val ratio = ThemeContrast.ratio(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(21.0, ratio, 0.01)
    }

    @Test
    fun sameColorIs1To1() {
        val ratio = ThemeContrast.ratio(0xFF3366CC.toInt(), 0xFF3366CC.toInt())
        assertEquals(1.0, ratio, 1e-9)
    }

    @Test
    fun knownPairMatchesExpectedRatio() {
        // #101827 ink on #EFE9DC ivory background (SaegeulIvory key colors).
        val ratio = ThemeContrast.ratio(0xFF101827.toInt(), 0xFFEFE9DC.toInt())
        assertTrue("ratio was $ratio", ratio in 13.0..17.0)
    }

    @Test
    fun semiTransparentForegroundIsCompositedOverBackground() {
        // 50% black over white should land between full black (21.0) and no contrast (1.0).
        val translucentBlack = (0x80 shl 24) or 0x000000
        val ratio = ThemeContrast.ratio(translucentBlack, 0xFFFFFFFF.toInt())
        assertTrue("ratio was $ratio", ratio in 3.5..5.5)
    }

    @Test
    fun findIssuesEmptyForSaegeulIvory() {
        val theme = ThemePreset.SaegeulIvory.deriveCustomNoBackground("SaegeulIvory")
        assertTrue(ThemeContrast.findIssues(theme).isEmpty())
    }

    @Test
    fun findIssuesEmptyForSaegeulNavy() {
        val theme = ThemePreset.SaegeulNavy.deriveCustomNoBackground("SaegeulNavy")
        assertTrue(ThemeContrast.findIssues(theme).isEmpty())
    }

    @Test
    fun findIssuesDetectsLowContrastKeyPair() {
        val theme = ThemePreset.SaegeulIvory.deriveCustomNoBackground("Broken").copy(
            keyTextColor = 0xFFE0E0E0.toInt(),
            keyBackgroundColor = 0xFFF5F5F5.toInt()
        )
        val issues = theme.let { ThemeContrast.findIssues(it) }
        assertTrue(issues.any { it.role == ThemeContrast.Role.Key })
    }

    @Test
    fun findIssuesDetectsKeyOverrideWithCustomBackgroundOnly() {
        val theme = ThemePreset.SaegeulIvory.deriveCustomNoBackground("Overridden").copy(
            keyOverrides = mapOf(
                "a" to Theme.Custom.KeyCustomStyle(keyBackgroundColor = 0xFF101020.toInt())
            )
        )
        val issues = ThemeContrast.findIssues(theme)
        assertTrue(issues.any { it.role == ThemeContrast.Role.KeyOverride && it.keyOverrideKey == "a" })
    }

    @Test
    fun findIssuesIgnoresKeyOverrideWithoutColorCustomization() {
        val theme = ThemePreset.SaegeulIvory.deriveCustomNoBackground("Uncustomized").copy(
            keyOverrides = mapOf(
                "a" to Theme.Custom.KeyCustomStyle(cornerRadius = 8f)
            )
        )
        val issues = ThemeContrast.findIssues(theme)
        assertTrue(issues.none { it.keyOverrideKey == "a" })
    }

    @Test
    fun autoFixResolvesAllIssuesAndPreservesUnrelatedFields() {
        val broken = ThemePreset.SaegeulIvory.deriveCustomNoBackground("Broken").copy(
            keyTextColor = 0xFFE0E0E0.toInt(),
            keyBackgroundColor = 0xFFF5F5F5.toInt(),
            altKeyTextColor = 0xFFF0F0F0.toInt(),
            altKeyBackgroundColor = 0xFFFAFAFA.toInt(),
            lightingEffect = Theme.Custom.LightingEffectDef(mode = "rgb_wave"),
            keyOverrides = mapOf(
                "a" to Theme.Custom.KeyCustomStyle(keyBackgroundColor = 0xFF101020.toInt())
            )
        )

        val fixed = ThemeContrast.autoFix(broken)

        assertTrue(ThemeContrast.findIssues(fixed).isEmpty())
        assertEquals(broken.keyBackgroundColor, fixed.keyBackgroundColor)
        assertEquals(broken.altKeyBackgroundColor, fixed.altKeyBackgroundColor)
        assertEquals(broken.lightingEffect, fixed.lightingEffect)
        assertEquals(broken.backgroundImage, fixed.backgroundImage)
        assertEquals(
            broken.keyOverrides!!["a"]!!.keyBackgroundColor,
            fixed.keyOverrides!!["a"]!!.keyBackgroundColor
        )
    }

    @Test
    fun autoFixIsNoOpWhenNoIssues() {
        val theme = ThemePreset.SaegeulIvory.deriveCustomNoBackground("SaegeulIvory")
        val fixed = ThemeContrast.autoFix(theme)
        assertEquals(theme, fixed)
    }
}
