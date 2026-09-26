/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaegeulThemePresetTest {

    @Test
    fun saegeulIvoryIsLightAndSaegeulNavyIsDark() {
        assertFalse(ThemePreset.SaegeulIvory.isDark)
        assertTrue(ThemePreset.SaegeulNavy.isDark)
    }

    @Test
    fun saegeulPresetsMeetMinimumContrast() {
        for (theme in listOf(ThemePreset.SaegeulIvory, ThemePreset.SaegeulNavy)) {
            val custom = theme.deriveCustomNoBackground(theme.name)
            val issues = ThemeContrast.findIssues(custom)
            assertTrue("${theme.name}: $issues", issues.isEmpty())
            assertTrue(theme.name, ThemeContrast.ratio(theme.keyTextColor, theme.keyBackgroundColor) >= 4.5)
            assertTrue(theme.name, ThemeContrast.ratio(theme.altKeyTextColor, theme.altKeyBackgroundColor) >= 4.5)
            assertTrue(theme.name, ThemeContrast.ratio(theme.accentKeyTextColor, theme.accentKeyBackgroundColor) >= 4.5)
            assertTrue(theme.name, ThemeContrast.ratio(theme.candidateTextColor, theme.barColor) >= 4.5)
        }
    }

    // ThemeManager.BuiltinThemes / DefaultTheme are not exercised here: ThemeManager's
    // object initializer reaches ThemeFilesManager -> appContext -> FcitxApplication,
    // which is unavailable in a plain JVM unit test (no Robolectric in this module).
    // Their order/default value were verified by source review instead.
}
