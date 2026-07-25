/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GifSelectionStateTest {
    @Test
    fun sameCardClosesAndDifferentCardMovesOverlay() {
        val state = GifSelectionState()
        assertEquals(1L, state.tap(1L))
        assertNull(state.tap(1L))
        assertEquals(1L, state.tap(1L))
        assertEquals(2L, state.tap(2L))
    }
}
