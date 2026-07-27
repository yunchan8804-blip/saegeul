/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

/** Layout contract shared by the IME toolbar and its host view. */
internal object ToolbarLayoutPolicy {
    const val TOUCH_TARGET_DP = 48
    const val COLLAPSED_ROWS = 1
    const val EXPANDED_ROWS = 2

    fun visibleRows(needsSecondRow: Boolean): Int =
        if (needsSecondRow) EXPANDED_ROWS else COLLAPSED_ROWS

    fun heightDp(needsSecondRow: Boolean): Int =
        TOUCH_TARGET_DP * visibleRows(needsSecondRow)

    fun needsSecondRow(availableWidth: Int, itemSize: Int, itemCount: Int): Boolean =
        availableWidth < itemSize * itemCount

    fun supportsSecondRow(measuredHeight: Int, rowHeight: Int): Boolean =
        measuredHeight >= rowHeight * EXPANDED_ROWS
}
