/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutMemoryTest {
    @Test
    fun `number surface remains the remembered question-123 destination`() {
        assertTrue(KeyboardLayoutMemory.shouldRememberAsSymbolLayout(NumberKeyboard.Name))
    }

    @Test
    fun `returning to any text surface does not overwrite the symbol destination`() {
        assertFalse(KeyboardLayoutMemory.shouldRememberAsSymbolLayout(TextKeyboard.Name))
        assertFalse(KeyboardLayoutMemory.shouldRememberAsSymbolLayout(HangulKeyboard.Name))
        MobileHangulLayout.entries
            .filterNot { it == MobileHangulLayout.Physical }
            .forEach { layout ->
                assertFalse(
                    KeyboardLayoutMemory.shouldRememberAsSymbolLayout(
                        MobileHangulKeyboard.name(layout)
                    )
                )
            }
    }

    @Test
    fun `legacy text destinations are repaired before question-123 switching`() {
        val symbol = "Symbol"
        assertEquals(
            NumberKeyboard.Name,
            KeyboardLayoutMemory.sanitizeStoredTarget(TextKeyboard.Name, symbol)
        )
        assertEquals(
            NumberKeyboard.Name,
            KeyboardLayoutMemory.sanitizeStoredTarget(HangulKeyboard.Name, symbol)
        )
        MobileHangulLayout.entries
            .filterNot { it == MobileHangulLayout.Physical }
            .forEach { layout ->
                assertEquals(
                    NumberKeyboard.Name,
                    KeyboardLayoutMemory.sanitizeStoredTarget(
                        MobileHangulKeyboard.name(layout),
                        symbol
                    )
                )
            }
    }

    @Test
    fun `valid number and symbol destinations survive migration`() {
        val symbol = "Symbol"
        assertEquals(
            NumberKeyboard.Name,
            KeyboardLayoutMemory.sanitizeStoredTarget(NumberKeyboard.Name, symbol)
        )
        assertEquals(symbol, KeyboardLayoutMemory.sanitizeStoredTarget(symbol, symbol))
    }
}
