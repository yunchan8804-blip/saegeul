/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * design.md R2/R3: every Saegeul-made theme keeps each glyph/background pair it draws at
 * WCAG 4.5:1 or better. Upstream-derived themes (Material, Pixel, Nord, ...) are not covered.
 */
class SaegeulOwnedThemeContrastTest {

    private class ContrastPair(val label: String, val fg: (Theme) -> Int, val bg: (Theme) -> Int)

    private val textPairs = listOf(
        ContrastPair("key text / key", { it.keyTextColor }, { it.keyBackgroundColor }),
        ContrastPair("alt key text / alt key", { it.altKeyTextColor }, { it.altKeyBackgroundColor }),
        ContrastPair("alt key hint / key", { it.altKeyTextColor }, { it.keyBackgroundColor }),
        ContrastPair("accent key text / accent key", { it.accentKeyTextColor }, { it.accentKeyBackgroundColor }),
        ContrastPair("candidate text / bar", { it.candidateTextColor }, { it.barColor }),
        ContrastPair("candidate comment / bar", { it.candidateCommentColor }, { it.barColor }),
        ContrastPair("candidate label / bar", { it.candidateLabelColor }, { it.barColor }),
        ContrastPair("space legend / space bar", { it.keyTextColor }, { it.spaceBarColor }),
        ContrastPair("active chip text / chip", { it.genericActiveForegroundColor }, { it.genericActiveBackgroundColor }),
        ContrastPair("popup text / popup", { it.popupTextColor }, { it.popupBackgroundColor })
    )

    private val shopNames = listOf("SilverMistLight", "PineDark", "RosyDuskDark", "CeladonJadeLight")

    private val saegeulOwned: List<Theme.Builtin> = listOf(
        ThemePreset.HanjiLight,
        ThemePreset.BaegjaLight,
        ThemePreset.MidnightOLED
    ) + shopNames.map { name ->
        checkNotNull(ThemeShopCatalog.find(name)) { "shop theme $name is missing" }.theme
    }

    @Test
    fun sevenSaegeulThemesAreChecked() {
        assertEquals(7, saegeulOwned.size)
    }

    @Test
    fun everyTextPairMeetsNormalTextContrast() {
        for (theme in saegeulOwned) {
            for (pair in textPairs) {
                val ratio = ThemeContrast.ratio(pair.fg(theme), pair.bg(theme))
                assertTrue("${theme.name} ${pair.label}: $ratio", ratio >= 4.5)
            }
        }
    }

    @Test
    fun checkerFindsNoIssueOnSaegeulThemes() {
        for (theme in saegeulOwned) {
            val issues = ThemeContrast.findIssues(theme.deriveCustomNoBackground(theme.name))
            assertTrue("${theme.name}: $issues", issues.isEmpty())
        }
    }

    @Test
    fun checkerNowSeesCandidateCommentPair() {
        val faint = ThemePreset.SaegeulIvory.deriveCustomNoBackground("Faint").copy(
            candidateCommentColor = 0xFFB8B8B8.toInt()
        )
        val issues = ThemeContrast.findIssues(faint)
        assertTrue(issues.any { it.role == ThemeContrast.Role.CandidateComment })
    }

    @Test
    fun checkerNowSeesSpaceBarActiveChipAndPopupPairs() {
        val broken = ThemePreset.SaegeulIvory.deriveCustomNoBackground("Broken").copy(
            spaceBarColor = 0xFF101827.toInt(),
            genericActiveForegroundColor = 0xFFEFE9DC.toInt(),
            genericActiveBackgroundColor = 0xFFF5F0E4.toInt(),
            popupTextColor = 0xFFEEEEEE.toInt(),
            popupBackgroundColor = 0xFFFFFFFF.toInt()
        )
        val roles = ThemeContrast.findIssues(broken).map { it.role }.toSet()
        assertTrue(roles.toString(), ThemeContrast.Role.SpaceBar in roles)
        assertTrue(roles.toString(), ThemeContrast.Role.GenericActive in roles)
        assertTrue(roles.toString(), ThemeContrast.Role.Popup in roles)
    }

    @Test
    fun autoFixResolvesTheNewPairsAndKeepsBackgrounds() {
        val broken = ThemePreset.SaegeulIvory.deriveCustomNoBackground("Broken").copy(
            candidateCommentColor = 0xFFB8B8B8.toInt(),
            candidateLabelColor = 0xFFC8C8C8.toInt(),
            spaceBarColor = 0xFFE9E1D2.toInt(),
            keyTextColor = 0xFFD8D0C0.toInt(),
            genericActiveForegroundColor = 0xFFEFE9DC.toInt(),
            genericActiveBackgroundColor = 0xFFF5F0E4.toInt(),
            popupTextColor = 0xFFEEEEEE.toInt()
        )

        val fixed = ThemeContrast.autoFix(broken)

        val issues = ThemeContrast.findIssues(fixed)
        assertTrue(issues.toString(), issues.isEmpty())
        assertEquals(broken.spaceBarColor, fixed.spaceBarColor)
        assertEquals(broken.genericActiveBackgroundColor, fixed.genericActiveBackgroundColor)
        assertEquals(broken.popupBackgroundColor, fixed.popupBackgroundColor)
        assertEquals(broken.barColor, fixed.barColor)
    }
}
