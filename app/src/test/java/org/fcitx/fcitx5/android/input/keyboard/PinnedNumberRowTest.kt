/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedNumberRowTest {

    @Test
    fun `row carries all ten digits in typing order`() {
        val digits = PinnedNumberRow.Keys.filterIsInstance<PinnedNumberKey>().map { it.digit }
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), digits)
    }

    @Test
    fun `height grows by one row when the number row is pinned`() {
        // Four letter rows become five, so 30% of the screen becomes 37%.
        assertEquals(37, PinnedNumberRow.scaleHeightPercent(30, 90, enabled = true))
    }

    @Test
    fun `height is untouched when the number row is off`() {
        assertEquals(30, PinnedNumberRow.scaleHeightPercent(30, 90, enabled = false))
    }

    @Test
    fun `scaled height never exceeds what the height preference allows`() {
        assertEquals(90, PinnedNumberRow.scaleHeightPercent(80, 90, enabled = true))
    }

    @Test
    fun `pinned row shifts the split boundary of every letter row down by one`() {
        // Bottom row of TextKeyboard: 6 keys splitting after the language key.
        assertEquals(3, TextKeyboardSplitPolicy.boundaryIndex(rowIndex = 3, keyCount = 6))
        assertEquals(
            3,
            TextKeyboardSplitPolicy.boundaryIndex(
                rowIndex = 4,
                keyCount = 6,
                numberRowOffset = 1
            )
        )
    }

    @Test
    fun `pinned row splits between its own fifth and sixth digit`() {
        assertEquals(
            PinnedNumberRow.SPLIT_BOUNDARY,
            TextKeyboardSplitPolicy.boundaryIndex(
                rowIndex = 0,
                keyCount = PinnedNumberRow.Keys.size,
                numberRowOffset = 1
            )
        )
    }
}
