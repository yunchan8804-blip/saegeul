/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.junit.Assert.assertEquals
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
    fun collapsedToolsUseOneScrollableRowUntilExplicitlyExpanded() {
        assertEquals(12, ToolbarLayoutPolicy.contentColumns(itemCount = 12, visibleRows = 1))
        assertEquals(576, ToolbarLayoutPolicy.contentWidthDp(itemCount = 12, visibleRows = 1))
        assertEquals(6, ToolbarLayoutPolicy.contentColumns(itemCount = 12, visibleRows = 2))
        assertEquals(288, ToolbarLayoutPolicy.contentWidthDp(itemCount = 12, visibleRows = 2))
    }

    @Test
    fun fixedExpansionControlPlusTwelveToolsUsesExactlyTwoRows() {
        assertEquals(624, ToolbarLayoutPolicy.totalWidthDp(toolItemCount = 12, visibleRows = 1))
        assertEquals(336, ToolbarLayoutPolicy.totalWidthDp(toolItemCount = 12, visibleRows = 2))
    }

}
