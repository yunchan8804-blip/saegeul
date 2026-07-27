/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.view.View
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ToolbarLayoutPolicy
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui

class ButtonsBarUi(override val ctx: Context, private val theme: Theme) : Ui {

    private class ResponsiveToolbarLayout(context: Context) : HorizontalScrollView(context) {
        // Do not replay [expanded] from this setter. KawaiiBarComponent installs the callback
        // while its lazy IdleUi is still being constructed; an immediate callback would ask for
        // the parent view again and recursively rebuild the IME until it runs out of memory.
        var onNeedsSecondRowChanged: ((Boolean) -> Unit)? = null

        private val itemSize = context.dp(ToolbarLayoutPolicy.TOUCH_TARGET_DP)
        private val content = GridLayout(context)

        var expanded: Boolean = false
            private set

        init {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }

        fun addTool(view: View) {
            content.addView(view)
            updateContentLayout()
        }

        fun setExpanded(value: Boolean) {
            if (expanded == value) return
            expanded = value
            // A row change always returns to the primary tools. In particular, the user must not
            // land at an arbitrary old horizontal offset after collapsing back to one row.
            scrollTo(0, 0)
            updateContentLayout()
            post { scrollTo(0, 0) }
            onNeedsSecondRowChanged?.invoke(value)
        }

        fun needsSecondRow(): Boolean = expanded

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            updateContentLayout()
        }

        private fun updateContentLayout() {
            val rows = ToolbarLayoutPolicy.visibleRows(expanded)
            val columns = ToolbarLayoutPolicy.contentColumns(content.childCount, rows)
            // GridLayout validates its declared counts against the *current* child specs. Grow to
            // a safe envelope before rewriting specs, then shrink to the requested 1x12 or 2x6
            // grid. Reversing that order crashes when a 12-column row becomes six columns.
            content.rowCount = maxOf(
                content.rowCount,
                ToolbarLayoutPolicy.EXPANDED_ROWS
            )
            content.columnCount = maxOf(
                content.columnCount,
                content.childCount.coerceAtLeast(1)
            )
            for (index in 0 until content.childCount) {
                val child = content.getChildAt(index)
                child.layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(index / columns),
                    GridLayout.spec(index % columns)
                ).apply {
                    width = itemSize
                    height = itemSize
                }
            }
            content.rowCount = rows
            content.columnCount = columns.coerceAtLeast(1)
            val targetWidth = columns * itemSize
            val targetHeight = rows * itemSize
            val params = content.layoutParams ?: LayoutParams(targetWidth, targetHeight)
            if (params.width != targetWidth || params.height != targetHeight) {
                params.width = targetWidth
                params.height = targetHeight
                content.layoutParams = params
            }
            content.requestLayout()
        }
    }

    private val responsiveRoot = ResponsiveToolbarLayout(ctx)

    val rowExpansionButton = ToolButton(ctx, R.drawable.ic_baseline_expand_more_24, theme).apply {
        contentDescription = ctx.getString(R.string.expand_toolbar)
        setOnClickListener { toggleExpandedRows() }
    }

    override val root: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        val size = ctx.dp(ToolbarLayoutPolicy.TOUCH_TARGET_DP)
        addView(rowExpansionButton, LinearLayout.LayoutParams(size, size))
        addView(
            responsiveRoot,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        )
    }

    var onNeedsSecondRowChanged: ((Boolean) -> Unit)?
        get() = responsiveRoot.onNeedsSecondRowChanged
        set(value) {
            responsiveRoot.onNeedsSecondRowChanged = value
        }

    fun needsSecondRow(): Boolean = responsiveRoot.needsSecondRow()

    fun collapse() {
        responsiveRoot.setExpanded(false)
        rowExpansionButton.setIcon(R.drawable.ic_baseline_expand_more_24)
        rowExpansionButton.contentDescription = ctx.getString(R.string.expand_toolbar)
    }

    private fun toggleExpandedRows() {
        val expanded = !responsiveRoot.expanded
        responsiveRoot.setExpanded(expanded)
        rowExpansionButton.setIcon(
            if (expanded) R.drawable.ic_baseline_expand_less_24
            else R.drawable.ic_baseline_expand_more_24
        )
        rowExpansionButton.contentDescription = ctx.getString(
            if (expanded) R.string.hide_toolbar else R.string.expand_toolbar
        )
    }

    private fun toolButton(@DrawableRes icon: Int) = ToolButton(ctx, icon, theme).also {
        responsiveRoot.addTool(it)
    }

    val undoButton = toolButton(R.drawable.ic_baseline_undo_24).apply {
        contentDescription = ctx.getString(R.string.undo)
    }

    val redoButton = toolButton(R.drawable.ic_baseline_redo_24).apply {
        contentDescription = ctx.getString(R.string.redo)
    }

    val cursorMoveButton = toolButton(R.drawable.ic_cursor_move).apply {
        contentDescription = ctx.getString(R.string.text_editing)
    }

    val clipboardButton = toolButton(R.drawable.ic_clipboard).apply {
        contentDescription = ctx.getString(R.string.clipboard)
    }

    val quickPhraseButton = toolButton(R.drawable.ic_baseline_format_quote_24).apply {
        contentDescription = ctx.getString(R.string.quickphrase)
    }

    val koreanSearchButton = toolButton(R.drawable.ic_baseline_search_24).apply {
        contentDescription = ctx.getString(R.string.korean_search)
    }

    val typoRecoveryButton = toolButton(R.drawable.ic_baseline_spellcheck_24).apply {
        contentDescription = ctx.getString(R.string.typo_recovery)
    }

    val aiAssistantButton = toolButton(R.drawable.ic_baseline_auto_awesome_24).apply {
        contentDescription = ctx.getString(R.string.ai_assistant_title)
    }

    val precisionDictationButton = toolButton(R.drawable.ic_precision_dictation_24).apply {
        contentDescription = ctx.getString(R.string.voice_provider_settings)
    }

    val ocrButton = toolButton(R.drawable.ic_baseline_text_format_24).apply {
        contentDescription = ctx.getString(R.string.ocr_title)
    }

    val gifButton = toolButton(R.drawable.ic_baseline_image_24).apply {
        contentDescription = ctx.getString(R.string.gif_search)
    }

    val moreButton = toolButton(R.drawable.ic_baseline_more_horiz_24).apply {
        contentDescription = ctx.getString(R.string.status_area)
    }
}
