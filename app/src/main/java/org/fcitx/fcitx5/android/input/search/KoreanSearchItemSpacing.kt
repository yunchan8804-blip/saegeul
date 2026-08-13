/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.search

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.input.panel.PanelStyle
import splitties.dimensions.dp

class KoreanSearchItemSpacing(context: Context) : RecyclerView.ItemDecoration() {
    private val spacing = context.dp(PanelStyle.GAP_M_DP)

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        // Skip the last item so the list padding stays symmetric top and bottom.
        val position = parent.getChildAdapterPosition(view)
        outRect.bottom = if (position == RecyclerView.NO_POSITION || position == state.itemCount - 1) {
            0
        } else {
            spacing
        }
    }
}
