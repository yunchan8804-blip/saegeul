/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardFrameTest {

    @Test
    fun `portrait with one-hand off keeps the existing symmetric user padding`() {
        val insets = KeyboardFrame.compute(
            windowWidthPx = 1080,
            density = 1f,
            isLandscape = false,
            userSidePaddingPx = 20,
            oneHandMode = OneHandMode.Off,
            isSplitActive = false
        )
        assertEquals(20, insets.startPx)
        assertEquals(20, insets.endPx)
    }

    @Test
    fun `portrait one-hand right docks the keyboard at 84 percent width to the right`() {
        val insets = KeyboardFrame.compute(
            windowWidthPx = 1000,
            density = 1f,
            isLandscape = false,
            userSidePaddingPx = 0,
            oneHandMode = OneHandMode.Right,
            isSplitActive = false
        )
        // available 1000 * 0.84 = 840 keyboard width, 160 empty space on the left
        assertEquals(160, insets.startPx)
        assertEquals(0, insets.endPx)
    }

    @Test
    fun `portrait one-hand left docks the keyboard at 84 percent width to the left`() {
        val insets = KeyboardFrame.compute(
            windowWidthPx = 1000,
            density = 1f,
            isLandscape = false,
            userSidePaddingPx = 0,
            oneHandMode = OneHandMode.Left,
            isSplitActive = false
        )
        assertEquals(0, insets.startPx)
        assertEquals(160, insets.endPx)
    }

    @Test
    fun `one-hand mode keeps the user's outer side padding on both sides`() {
        val insets = KeyboardFrame.compute(
            windowWidthPx = 1000,
            density = 1f,
            isLandscape = false,
            userSidePaddingPx = 20,
            oneHandMode = OneHandMode.Right,
            isSplitActive = false
        )
        // available = 1000 - 40 = 960; keyboard width = 960 * 0.84 = 806.4 -> 806; empty = 154
        assertEquals(20 + 154, insets.startPx)
        assertEquals(20, insets.endPx)
    }

    @Test
    fun `landscape 1080dp width with one-hand off centers a 640dp keyboard`() {
        val insets = KeyboardFrame.compute(
            windowWidthPx = 1080,
            density = 1f,
            isLandscape = true,
            userSidePaddingPx = 0,
            oneHandMode = OneHandMode.Off,
            isSplitActive = false
        )
        // available 1080 > max 640; extra = (1080 - 640) / 2 = 220 on each side
        assertEquals(220, insets.startPx)
        assertEquals(220, insets.endPx)
    }

    @Test
    fun `landscape user side padding already under the max width wins over centering`() {
        val insets = KeyboardFrame.compute(
            windowWidthPx = 1200,
            density = 1f,
            isLandscape = true,
            userSidePaddingPx = 300,
            oneHandMode = OneHandMode.Off,
            isSplitActive = false
        )
        // available = 1200 - 600 = 600 <= max 640; user padding alone is kept, no extra centering
        assertEquals(300, insets.startPx)
        assertEquals(300, insets.endPx)
    }

    @Test
    fun `split keyboard active ignores one-hand mode entirely`() {
        val insets = KeyboardFrame.compute(
            windowWidthPx = 1000,
            density = 1f,
            isLandscape = true,
            userSidePaddingPx = 20,
            oneHandMode = OneHandMode.Right,
            isSplitActive = true
        )
        assertEquals(20, insets.startPx)
        assertEquals(20, insets.endPx)
    }

    @Test
    fun `key text scale factor is a percentage coerced to the 80 to 140 range`() {
        assertEquals(1.0f, KeyTextScale.factor(100))
        assertEquals(0.8f, KeyTextScale.factor(80))
        assertEquals(1.4f, KeyTextScale.factor(140))
        assertEquals(1.4f, KeyTextScale.factor(200))
        assertEquals(0.8f, KeyTextScale.factor(10))
    }

    @Test
    fun `key text scale multiplies the base dp size`() {
        assertEquals(19.6f, KeyTextScale.scale(14f, 140), 0.001f)
        assertEquals(11.2f, KeyTextScale.scale(14f, 80), 0.001f)
        assertEquals(14f, KeyTextScale.scale(14f, 100), 0.001f)
    }

    @Test
    fun heightFloorLiftsShortLandscapeCoverScreen() {
        // Fold6 cover screen in landscape: 968px tall at 2.25 density, user height 25% + number row.
        val percentPx = 968 * 31 / 100
        val floored = KeyboardHeightFloor.apply(percentPx, 2.25f, isLandscape = true, rows = 5)
        assertEquals((5 * 36 * 2.25f).toInt(), floored)
    }

    @Test
    fun heightFloorKeepsTallerUserHeight() {
        val percentPx = 2400 * 30 / 100
        assertEquals(percentPx, KeyboardHeightFloor.apply(percentPx, 2.625f, isLandscape = false, rows = 4))
    }

    @Test
    fun heightFloorRowCountFollowsNumberRow() {
        assertEquals(4, KeyboardHeightFloor.rowCount(false))
        assertEquals(5, KeyboardHeightFloor.rowCount(true))
    }
}
