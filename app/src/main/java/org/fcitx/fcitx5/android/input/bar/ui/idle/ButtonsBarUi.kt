/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import androidx.annotation.DrawableRes
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ToolbarLayoutPolicy
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui

class ButtonsBarUi(override val ctx: Context, private val theme: Theme) : Ui {

    private class ResponsiveToolbarLayout(context: Context) : FlexboxLayout(context) {
        var onNeedsSecondRowChanged: ((Boolean) -> Unit)? = null
            set(value) {
                field = value
                // The toolbar can already be measured before KawaiiBarComponent installs this
                // callback. Replay the current decision so the host is not left one row high
                // with the wrapped AI/voice/OCR/GIF buttons clipped below it.
                if (value != null && width > 0) {
                    val needsSecondRow = calculateNeedsSecondRow(width)
                    lastNeedsSecondRow = needsSecondRow
                    value(needsSecondRow)
                }
            }

        private var lastNeedsSecondRow: Boolean? = null

        private fun calculateNeedsSecondRow(width: Int): Boolean =
            ToolbarLayoutPolicy.needsSecondRow(
                availableWidth = width,
                itemSize = context.dp(ToolbarLayoutPolicy.TOUCH_TARGET_DP),
                itemCount = childCount
            )

        fun needsSecondRow(): Boolean =
            if (width > 0) calculateNeedsSecondRow(width) else lastNeedsSecondRow ?: false

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            if (width <= 0) return
            val needsSecondRow = calculateNeedsSecondRow(width)
            if (lastNeedsSecondRow == needsSecondRow) return
            lastNeedsSecondRow = needsSecondRow
            onNeedsSecondRowChanged?.invoke(needsSecondRow)
        }
    }

    private val responsiveRoot = ResponsiveToolbarLayout(ctx).apply {
        alignItems = AlignItems.CENTER
        flexWrap = FlexWrap.WRAP
        justifyContent = JustifyContent.SPACE_AROUND
    }

    override val root: FlexboxLayout
        get() = responsiveRoot

    var onNeedsSecondRowChanged: ((Boolean) -> Unit)?
        get() = responsiveRoot.onNeedsSecondRowChanged
        set(value) {
            responsiveRoot.onNeedsSecondRowChanged = value
        }

    fun needsSecondRow(): Boolean = responsiveRoot.needsSecondRow()

    private fun toolButton(@DrawableRes icon: Int) = ToolButton(ctx, icon, theme).also {
        val size = ctx.dp(ToolbarLayoutPolicy.TOUCH_TARGET_DP)
        root.addView(it, FlexboxLayout.LayoutParams(size, size).apply {
            // Wrapping, not shrinking, is the responsive behavior. Keeping this at zero also
            // makes the 48 dp touch-target contract measurable on compact screens.
            flexShrink = 0f
        })
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
