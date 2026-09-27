/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ThemeDisplayNamesTest {

    // Mirrors ThemeManager.BuiltinThemes (the actual list cannot be referenced from a plain
    // JVM unit test: ThemeManager's object initializer reaches ThemeFilesManager -> appContext,
    // which is unavailable without Robolectric — see SaegeulThemePresetTest for the same note).
    private val builtinThemes = listOf(
        ThemePreset.SaegeulIvory,
        ThemePreset.SaegeulNavy,
        ThemePreset.HanjiLight,
        ThemePreset.DancheongDark,
        ThemePreset.BaegjaLight,
        ThemePreset.CheongjaDark,
        ThemePreset.MidnightOLED,
        ThemePreset.SeoulMistGlass,
        ThemePreset.MaterialLight,
        ThemePreset.MaterialDark,
        ThemePreset.PixelLight,
        ThemePreset.PixelDark,
        ThemePreset.NordLight,
        ThemePreset.NordDark,
        ThemePreset.DeepBlue,
        ThemePreset.Monokai,
        ThemePreset.AMOLEDBlack,
    )

    @Test
    fun everyBuiltinThemeHasADisplayNameResource() {
        builtinThemes.forEach { theme ->
            val res = ThemeDisplayNames.resolveBuiltinNameRes(theme.name)
            assertNotNull("no display name mapped for builtin theme ${theme.name}", res)
        }
    }

    @Test
    fun mappingHasNoDuplicateResourceIds() {
        val ids = ThemeDisplayNames.builtinNameRes.values
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun unknownNameIsNotMapped() {
        assertNotNull(ThemeDisplayNames.builtinNameRes) // sanity: map itself exists
        assert(ThemeDisplayNames.resolveBuiltinNameRes("NotARealTheme") == null)
    }
}
