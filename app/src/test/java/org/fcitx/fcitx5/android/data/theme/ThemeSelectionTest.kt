/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSelectionTest {

    private val free = listOf(ThemePreset.SaegeulIvory, ThemePreset.SaegeulNavy)
    private val shop = ThemeShopCatalog.allThemes()
    private val all: List<Theme> = free + shop

    private val ownsNothing: (String) -> Boolean = { false }

    @Test
    fun shopThemesNotBoughtAreLeftOut() {
        val result = selectableThemes(all, ownsNothing)
        assertEquals(free, result)
        shop.forEach { assertFalse(it.name, result.contains(it)) }
    }

    @Test
    fun boughtShopThemesAndFreeThemesAreKept() {
        val bought = shop.first().name
        val result = selectableThemes(all) { it == bought }
        assertEquals(free + shop.first(), result)
    }

    @Test
    fun ownershipIsOnlyAskedForShopThemes() {
        val asked = mutableListOf<String>()
        selectableThemes(all) { asked += it; false }
        assertEquals(shop.map { it.name }, asked)
    }

    @Test
    fun savedNameOfAnUnboughtShopThemeResolvesToNothing() {
        shop.forEach {
            assertNull(it.name, resolveSelectableTheme(it.name, all, ownsNothing))
        }
    }

    @Test
    fun unboughtShopThemeFallsBackToTheDefault() {
        val default = ThemePreset.SaegeulNavy
        val resolved = resolveSelectableTheme(shop.first().name, all, ownsNothing) ?: default
        assertSame(default, resolved)
    }

    @Test
    fun savedNameOfABoughtShopThemeResolves() {
        val bought = shop.first()
        assertSame(bought, resolveSelectableTheme(bought.name, all) { it == bought.name })
    }

    @Test
    fun savedNameOfAFreeThemeResolvesWithoutAskingOwnership() {
        val resolved = resolveSelectableTheme(ThemePreset.SaegeulIvory.name, all) {
            throw AssertionError("ownership must not be asked for $it")
        }
        assertSame(ThemePreset.SaegeulIvory, resolved)
    }

    @Test
    fun unknownNameResolvesToNothing() {
        assertNull(resolveSelectableTheme("NoSuchTheme", all) { true })
        assertTrue(shop.isNotEmpty())
    }
}
