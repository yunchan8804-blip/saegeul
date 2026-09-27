/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeColorApplyTest {

    private fun decoratedTarget(): Theme.Custom {
        val base = ThemePreset.HanjiLight.deriveCustomNoBackground("decorated")
        return base.copy(
            backgroundImage = Theme.Custom.CustomBackground(
                croppedFilePath = "/tmp/cropped.png",
                srcFilePath = "/tmp/src.png",
                brightness = 55,
                cropRect = null,
                cropRotation = 90
            ),
            keyOverrides = mapOf(
                "button_space" to Theme.Custom.KeyCustomStyle(keyBackgroundColor = 0xFF112233.toInt())
            ),
            globalKeyStyle = Theme.Custom.KeyCustomStyle(cornerRadius = 12f),
            lightingEffect = Theme.Custom.LightingEffectDef(mode = "rgb_wave"),
            particleEffect = Theme.Custom.ParticleEffectDef(type = "star_sparkle"),
            keyGlowEffect = Theme.Custom.KeyGlowDef(enabled = true, glowColor = 0xFF00FFFF.toInt())
        )
    }

    @Test
    fun replaceColorsCopiesAllTwentyOneColorFieldsAndIsDark() {
        val target = decoratedTarget()
        val result = ThemeColorApply.replaceColors(target, ThemePreset.SaegeulNavy)

        assertEquals(ThemePreset.SaegeulNavy.isDark, result.isDark)
        assertEquals(ThemePreset.SaegeulNavy.backgroundColor, result.backgroundColor)
        assertEquals(ThemePreset.SaegeulNavy.barColor, result.barColor)
        assertEquals(ThemePreset.SaegeulNavy.keyboardColor, result.keyboardColor)
        assertEquals(ThemePreset.SaegeulNavy.keyBackgroundColor, result.keyBackgroundColor)
        assertEquals(ThemePreset.SaegeulNavy.keyTextColor, result.keyTextColor)
        assertEquals(ThemePreset.SaegeulNavy.candidateTextColor, result.candidateTextColor)
        assertEquals(ThemePreset.SaegeulNavy.candidateLabelColor, result.candidateLabelColor)
        assertEquals(ThemePreset.SaegeulNavy.candidateCommentColor, result.candidateCommentColor)
        assertEquals(ThemePreset.SaegeulNavy.altKeyBackgroundColor, result.altKeyBackgroundColor)
        assertEquals(ThemePreset.SaegeulNavy.altKeyTextColor, result.altKeyTextColor)
        assertEquals(ThemePreset.SaegeulNavy.accentKeyBackgroundColor, result.accentKeyBackgroundColor)
        assertEquals(ThemePreset.SaegeulNavy.accentKeyTextColor, result.accentKeyTextColor)
        assertEquals(ThemePreset.SaegeulNavy.keyPressHighlightColor, result.keyPressHighlightColor)
        assertEquals(ThemePreset.SaegeulNavy.keyShadowColor, result.keyShadowColor)
        assertEquals(ThemePreset.SaegeulNavy.popupBackgroundColor, result.popupBackgroundColor)
        assertEquals(ThemePreset.SaegeulNavy.popupTextColor, result.popupTextColor)
        assertEquals(ThemePreset.SaegeulNavy.spaceBarColor, result.spaceBarColor)
        assertEquals(ThemePreset.SaegeulNavy.dividerColor, result.dividerColor)
        assertEquals(ThemePreset.SaegeulNavy.clipboardEntryColor, result.clipboardEntryColor)
        assertEquals(ThemePreset.SaegeulNavy.genericActiveBackgroundColor, result.genericActiveBackgroundColor)
        assertEquals(ThemePreset.SaegeulNavy.genericActiveForegroundColor, result.genericActiveForegroundColor)
    }

    @Test
    fun replaceColorsPreservesEffectsKeyOverridesGlobalStyleAndBackgroundImage() {
        val target = decoratedTarget()
        val result = ThemeColorApply.replaceColors(target, ThemePreset.SaegeulNavy)

        assertEquals(target.backgroundImage, result.backgroundImage)
        assertEquals(target.keyOverrides, result.keyOverrides)
        assertEquals(target.globalKeyStyle, result.globalKeyStyle)
        assertEquals(target.lightingEffect, result.lightingEffect)
        assertEquals(target.particleEffect, result.particleEffect)
        assertEquals(target.keyGlowEffect, result.keyGlowEffect)
        assertEquals(target.name, result.name)

        assertNotNull(result.backgroundImage)
        assertNotNull(result.keyOverrides)
        assertNotNull(result.globalKeyStyle)
        assertNotNull(result.lightingEffect)
        assertNotNull(result.particleEffect)
        assertNotNull(result.keyGlowEffect)
    }

    @Test
    fun replaceColorsFromPlainThemeLeavesUndecoratedTargetWithoutEffects() {
        val target = ThemePreset.SaegeulIvory.deriveCustomNoBackground("plain")
        val result = ThemeColorApply.replaceColors(target, ThemePreset.MidnightOLED)

        assertNull(result.backgroundImage)
        assertNull(result.keyOverrides)
        assertNull(result.globalKeyStyle)
        assertNull(result.lightingEffect)
        assertNull(result.particleEffect)
        assertNull(result.keyGlowEffect)
        assertEquals(ThemePreset.MidnightOLED.keyTextColor, result.keyTextColor)
    }
}
