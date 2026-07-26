/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarLayoutPolicyTest {
    @Test
    fun collapsedToolbarUsesOneAccessibleRow() {
        assertEquals(1, ToolbarLayoutPolicy.visibleRows(needsSecondRow = false))
        assertEquals(48, ToolbarLayoutPolicy.heightDp(needsSecondRow = false))
        assertTrue(ToolbarLayoutPolicy.TOUCH_TARGET_DP >= 48)
    }

    @Test
    fun expandedToolbarUsesExactlyTwoRows() {
        assertEquals(2, ToolbarLayoutPolicy.visibleRows(needsSecondRow = true))
        assertEquals(96, ToolbarLayoutPolicy.heightDp(needsSecondRow = true))
    }

    @Test
    fun narrowPhoneWrapsWhileUnfoldedWidthStaysOnOneRow() {
        assertTrue(ToolbarLayoutPolicy.needsSecondRow(315, 48, 12))
        assertTrue(ToolbarLayoutPolicy.needsSecondRow(334, 48, 12))
        assertFalse(ToolbarLayoutPolicy.needsSecondRow(729, 48, 12))
    }
}
