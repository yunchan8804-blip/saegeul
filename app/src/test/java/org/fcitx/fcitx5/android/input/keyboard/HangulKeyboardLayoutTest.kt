/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HangulKeyboardLayoutTest {

    @Test
    fun fullSurfaceContainsEveryPrintableUsKeyboardPosition() {
        val expected = "`1234567890-=qwertyuiop[]asdfghjkl;'\\zxcvbnm,./".toSet()
        val actual = HangulKeyboard.Layout.flatten()
            .filterIsInstance<HangulPositionKey>()
            .map { it.character }
            .toSet()

        assertEquals(expected, actual)
        assertEquals(expected.size, HangulKeyboard.Layout.flatten().count { it is HangulPositionKey })
    }

    @Test
    fun everyFullSurfaceLayoutUsesAPinnedLibhangulTable() {
        assertEquals(
            "a34aef73378c0992316861bbf13fc914ee7577d9",
            HangulKeyboardTables.SourceRevision
        )
        HangulKeyLegends.fullSurfaceLayouts.forEach {
            val table = HangulKeyboardTables.byLayout[it]
            assertTrue("Missing 128-entry table for $it", table?.size == 128)
        }
    }

    @Test
    fun normalAndShiftActionsCoverEverySurfacePosition() {
        val keys = HangulKeyboard.Layout.flatten().filterIsInstance<HangulPositionKey>()
        HangulKeyLegends.fullSurfaceLayouts.forEach { layout ->
            keys.forEach { key ->
                val normal = HangulKeyLegends.actionCharacter(key.character, false)
                val shifted = HangulKeyLegends.actionCharacter(key.character, true)
                assertTrue(normal.code in HangulKeyboardTables.byLayout.getValue(layout).indices)
                assertTrue(shifted.code in HangulKeyboardTables.byLayout.getValue(layout).indices)
            }
        }
    }
}
