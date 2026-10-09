/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ThemeFileRulesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun ordinaryThemeNamesPass() {
        assertTrue(ThemeFileRules.isSafeThemeName("3f2b8c1e-5d4a-4e6f-9a7b-0c1d2e3f4a5b"))
        assertTrue(ThemeFileRules.isSafeThemeName("MonetLight#ff415f91"))
        assertTrue(ThemeFileRules.isSafeThemeName("내 테마"))
        assertTrue(ThemeFileRules.isSafeThemeName("a".repeat(ThemeFileRules.MAX_THEME_NAME_LENGTH)))
    }

    @Test
    fun pathLikeThemeNamesAreRejected() {
        assertFalse(ThemeFileRules.isSafeThemeName(""))
        assertFalse(ThemeFileRules.isSafeThemeName("   "))
        assertFalse(ThemeFileRules.isSafeThemeName("../escape"))
        assertFalse(ThemeFileRules.isSafeThemeName(".."))
        assertFalse(ThemeFileRules.isSafeThemeName("a..b"))
        assertFalse(ThemeFileRules.isSafeThemeName("a/b"))
        assertFalse(ThemeFileRules.isSafeThemeName("a\\b"))
        assertFalse(ThemeFileRules.isSafeThemeName("a\u0000b"))
        assertFalse(ThemeFileRules.isSafeThemeName("a\nb"))
        assertFalse(ThemeFileRules.isSafeThemeName("a".repeat(ThemeFileRules.MAX_THEME_NAME_LENGTH + 1)))
    }

    @Test
    fun builtinShopAndMonetNamesAreReserved() {
        val builtin = listOf(ThemePreset.SaegeulIvory.name, ThemePreset.MaterialDark.name)
        assertTrue(ThemeFileRules.isReservedThemeName(ThemePreset.SaegeulIvory.name, builtin))
        ThemeShopCatalog.shopThemes.forEach {
            assertTrue(it.theme.name, ThemeFileRules.isReservedThemeName(it.theme.name, builtin))
        }
        assertTrue(ThemeFileRules.isReservedThemeName("MonetLight", builtin))
        assertTrue(ThemeFileRules.isReservedThemeName("MonetDark", builtin))
        assertEquals("MonetLight", Theme.Monet.nameOf(isDark = false))
        assertEquals("MonetDark", Theme.Monet.nameOf(isDark = true))
    }

    @Test
    fun customThemeNamesAreNotReserved() {
        val builtin = listOf(ThemePreset.SaegeulIvory.name)
        assertFalse(ThemeFileRules.isReservedThemeName("3f2b8c1e-5d4a-4e6f-9a7b-0c1d2e3f4a5b", builtin))
        assertFalse(ThemeFileRules.isReservedThemeName("MonetLight#ff415f91", builtin))
    }

    @Test
    fun imagePathKeepsOnlyTheFileName() {
        assertEquals("x.png", ThemeFileRules.backgroundImageFileName("../../x.png"))
        assertEquals("x.png", ThemeFileRules.backgroundImageFileName("/data/data/app/x.png"))
        assertEquals("x.png", ThemeFileRules.backgroundImageFileName("..\\..\\x.png"))
        assertEquals("id-cropped.png", ThemeFileRules.backgroundImageFileName("id-cropped.png"))
        assertNull(ThemeFileRules.backgroundImageFileName(""))
        assertNull(ThemeFileRules.backgroundImageFileName("dir/"))
        assertNull(ThemeFileRules.backgroundImageFileName(".."))
        assertNull(ThemeFileRules.backgroundImageFileName("a/\u0000"))
    }

    @Test
    fun escapingImagePathLandsInsideTheThemeDirectory() {
        val dir = tempFolder.newFolder("theme")
        val name = ThemeFileRules.backgroundImageFileName("../../x.png")!!
        val resolved = ThemeFileRules.resolveInside(dir, name)
        assertNotNull(resolved)
        assertEquals(File(dir.canonicalFile, "x.png"), resolved!!.canonicalFile)
    }

    @Test
    fun resolveInsideRejectsPathsLeavingTheDirectory() {
        val dir = tempFolder.newFolder("theme")
        assertNull(ThemeFileRules.resolveInside(dir, "../outside.json"))
        assertNull(ThemeFileRules.resolveInside(dir, "sub/../../outside.json"))
        assertNull(ThemeFileRules.resolveInside(dir, ".."))
        assertNull(ThemeFileRules.resolveInside(dir, ""))
        assertNotNull(ThemeFileRules.resolveInside(dir, "inside.json"))
    }

    @Test
    fun isInsideDoesNotAcceptSiblingWithSharedPrefix() {
        val dir = tempFolder.newFolder("theme")
        val sibling = tempFolder.newFolder("theme-other")
        assertFalse(ThemeFileRules.isInside(dir, File(sibling, "x.png")))
        assertFalse(ThemeFileRules.isInside(dir, dir))
        assertTrue(ThemeFileRules.isInside(dir, File(dir, "x.png")))
    }

    @Test
    fun srcExtensionIsAppendedOnceToAFreshName() {
        val file = File("/theme", "abc-src")
        assertEquals(File("/theme", "abc-src.png"), ThemeFileRules.srcFileWithExtension(file, "png"))
    }

    @Test
    fun srcExtensionReplacesTheOneAlreadyThere() {
        assertEquals(
            File("/theme", "abc-src.jpg"),
            ThemeFileRules.srcFileWithExtension(File("/theme", "abc-src.png"), "jpg")
        )
        assertEquals(
            File("/theme", "abc-src.png"),
            ThemeFileRules.srcFileWithExtension(File("/theme", "abc-src.png"), "png")
        )
    }

    @Test
    fun srcExtensionHealsAnAlreadyDoubledName() {
        assertEquals(
            File("/theme", "abc-src.jpg"),
            ThemeFileRules.srcFileWithExtension(File("/theme", "abc-src.png.jpg"), "jpg")
        )
    }

    @Test
    fun srcExtensionIsKeptWhenTheMimeTypeHasNone() {
        val file = File("/theme", "abc-src.png")
        assertEquals(file, ThemeFileRules.srcFileWithExtension(file, null))
        assertEquals(file, ThemeFileRules.srcFileWithExtension(file, ""))
    }

    @Test
    fun srcExtensionWithoutMarkerIsNotDoubled() {
        assertEquals(
            File("/theme", "photo.png"),
            ThemeFileRules.srcFileWithExtension(File("/theme", "photo.png"), "png")
        )
        assertEquals(
            File("/theme", "photo.jpg"),
            ThemeFileRules.srcFileWithExtension(File("/theme", "photo"), "jpg")
        )
    }

    @Test
    fun exportFileNameRemovesCharactersFilesCannotHave() {
        assertEquals("내 테마", ThemeFileRules.exportFileName("내 테마"))
        assertEquals("ab", ThemeFileRules.exportFileName("a/b"))
        assertEquals("abcdefghi", ThemeFileRules.exportFileName("a/b\\c:d*e?f\"g<h>i|"))
        assertEquals("a b", ThemeFileRules.exportFileName("a\u0000 b\n"))
    }

    @Test
    fun exportFileNameFallsBackWhenNothingIsLeft() {
        assertEquals("theme", ThemeFileRules.exportFileName(""))
        assertEquals("theme", ThemeFileRules.exportFileName("  "))
        assertEquals("theme", ThemeFileRules.exportFileName("/\\:*?\"<>|"))
        assertEquals("theme", ThemeFileRules.exportFileName("..."))
    }

    @Test
    fun exportFileNameIsShortened() {
        assertTrue(ThemeFileRules.exportFileName("a".repeat(500)).length <= 64)
    }
}
