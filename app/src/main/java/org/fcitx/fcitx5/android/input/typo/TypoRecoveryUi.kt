/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.typo

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.setPadding
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.panel.PanelButtonKind
import org.fcitx.fcitx5.android.input.panel.PanelStyle
import org.fcitx.fcitx5.android.input.panel.panelButton
import org.fcitx.fcitx5.android.input.panel.panelSurface
import org.fcitx.fcitx5.android.utils.alpha
import splitties.dimensions.dp

class TypoRecoveryUi(private val context: Context, private val theme: Theme) {
    val root = ScrollView(context).apply {
        setBackgroundColor(theme.barColor)
        isFillViewport = true
    }

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            context.dp(PanelStyle.PANEL_PADDING_H_DP),
            context.dp(PanelStyle.PANEL_PADDING_V_DP),
            context.dp(PanelStyle.PANEL_PADDING_H_DP),
            context.dp(PanelStyle.PANEL_PADDING_V_DP)
        )
    }
    private val sourceLabel = TextView(context).apply {
        text = context.getString(R.string.typo_recovery_source)
        setTextColor(theme.altKeyTextColor)
        textSize = PanelStyle.TEXT_CAPTION
    }
    private val sourceText = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_TITLE
        typeface = Typeface.DEFAULT_BOLD
        setPadding(
            context.dp(PanelStyle.CARD_PADDING_H_DP),
            context.dp(PanelStyle.CARD_PADDING_V_DP),
            context.dp(PanelStyle.CARD_PADDING_H_DP),
            context.dp(PanelStyle.CARD_PADDING_V_DP)
        )
        background = context.panelSurface(theme.altKeyBackgroundColor)
    }
    private val notice = TextView(context).apply {
        text = context.getString(R.string.typo_recovery_local_notice)
        setTextColor(theme.altKeyTextColor)
        textSize = PanelStyle.TEXT_CAPTION
        setPadding(0, context.dp(PanelStyle.GAP_M_DP), 0, context.dp(PanelStyle.GAP_M_DP))
    }
    private val candidates = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val status = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_EMPHASIS
        setPadding(context.dp(PanelStyle.CARD_PADDING_H_DP))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = View.GONE
    }
    private val undoButton = context.panelButton(theme, PanelButtonKind.Secondary).apply {
        text = context.getString(R.string.typo_recovery_undo)
        visibility = View.GONE
        setOnClickListener { onUndo?.invoke() }
    }
    private val backButton = context.panelButton(theme, PanelButtonKind.Secondary).apply {
        text = context.getString(R.string.typo_recovery_back)
        setOnClickListener { onBack?.invoke() }
    }
    private val actionRow = LinearLayout(context).apply {
        gravity = Gravity.END
        addView(undoButton, LinearLayout.LayoutParams(0, context.dp(PanelStyle.BUTTON_HEIGHT_DP), 1f))
        addView(backButton, LinearLayout.LayoutParams(0, context.dp(PanelStyle.BUTTON_HEIGHT_DP), 1f).apply {
            marginStart = context.dp(PanelStyle.GAP_M_DP)
        })
    }

    var onProposal: ((TypoRecoveryProposal) -> Unit)? = null
    var onUndo: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null

    init {
        column.addView(sourceLabel)
        column.addView(sourceText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(PanelStyle.GAP_S_DP) })
        column.addView(notice)
        column.addView(candidates)
        column.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        column.addView(actionRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(PanelStyle.GAP_M_DP) })
        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    fun showPreview(snapshot: TypoRecoverySnapshot) {
        sourceLabel.visibility = View.VISIBLE
        sourceText.visibility = View.VISIBLE
        notice.visibility = View.VISIBLE
        sourceText.text = snapshot.chunk.original
        candidates.removeAllViews()
        snapshot.proposals.forEach { proposal ->
            val direction = context.getString(
                when (proposal.direction) {
                    TypoRecoveryDirection.EnglishToHangul -> R.string.typo_recovery_en_to_ko
                    TypoRecoveryDirection.HangulToEnglish -> R.string.typo_recovery_ko_to_en
                }
            )
            candidates.addView(
                context.panelButton(
                    theme, PanelButtonKind.Primary, textSize = PanelStyle.TEXT_EMPHASIS
                ).apply {
                    text = candidateLabel(direction, proposal.replacement)
                    setOnClickListener { onProposal?.invoke(proposal) }
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    // Two-line content: caption direction plus emphasis replacement, on the 4dp grid
                    context.dp(64)
                ).apply { bottomMargin = context.dp(PanelStyle.GAP_M_DP) }
            )
        }
        status.visibility = View.GONE
        undoButton.visibility = View.GONE
    }

    fun showApplied(original: String, replacement: String) {
        candidates.removeAllViews()
        sourceText.text = replacement
        status.text = context.getString(R.string.typo_recovery_applied, original, replacement)
        status.setTextColor(theme.keyTextColor)
        status.visibility = View.VISIBLE
        undoButton.visibility = View.VISIBLE
    }

    fun showMessage(message: String, isError: Boolean = false) {
        candidates.removeAllViews()
        sourceLabel.visibility = View.GONE
        sourceText.visibility = View.GONE
        notice.visibility = View.GONE
        status.text = message
        status.setTextColor(if (isError) PanelStyle.errorTextColor(theme) else theme.keyTextColor)
        status.visibility = View.VISIBLE
        undoButton.visibility = View.GONE
    }

    /** Direction label rides above the replacement, smaller and dimmer than the result. */
    private fun candidateLabel(direction: String, replacement: String) =
        SpannableString("$direction\n$replacement").apply {
            val directionEnd = direction.length
            setSpan(
                RelativeSizeSpan(PanelStyle.TEXT_CAPTION / PanelStyle.TEXT_EMPHASIS),
                0, directionEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                ForegroundColorSpan(theme.accentKeyTextColor.alpha(0.7f)),
                0, directionEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                StyleSpan(Typeface.BOLD),
                directionEnd + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
}
