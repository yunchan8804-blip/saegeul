/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dynamicphrase

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.DynamicPhraseResolution
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.panel.PanelButtonKind
import org.fcitx.fcitx5.android.input.panel.PanelRecovery
import org.fcitx.fcitx5.android.input.panel.PanelStyle
import org.fcitx.fcitx5.android.input.panel.panelButton
import org.fcitx.fcitx5.android.input.panel.panelSurface
import splitties.dimensions.dp

class DynamicPhraseUi(private val context: Context, private val theme: Theme) {
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
    private val templateLabel = label(R.string.dynamic_phrase_template)
    private val templateText = valueText()
    private val previewLabel = label(R.string.dynamic_phrase_preview)
    private val previewText = valueText().apply { typeface = Typeface.DEFAULT_BOLD }
    private val issues = TextView(context).apply {
        setTextColor(PanelStyle.errorTextColor(theme))
        textSize = PanelStyle.TEXT_BODY
        setPadding(0, context.dp(PanelStyle.GAP_M_DP), 0, context.dp(PanelStyle.GAP_M_DP))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = View.GONE
    }
    private val status = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_BODY
        setPadding(0, context.dp(PanelStyle.GAP_M_DP), 0, context.dp(PanelStyle.GAP_M_DP))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = View.GONE
    }
    // Unresolved variables are fixed in phrase settings, not by retrying the insert.
    private var recovery: PanelRecovery? = null

    private val recoveryButton = context.panelButton(theme, PanelButtonKind.Secondary).apply {
        visibility = View.GONE
        setOnClickListener { recovery?.run?.invoke() }
    }
    private val insertButton = context.panelButton(theme, PanelButtonKind.Primary).apply {
        text = context.getString(R.string.dynamic_phrase_insert)
        setOnClickListener { onInsert?.invoke() }
    }
    private val backButton = context.panelButton(theme, PanelButtonKind.Secondary).apply {
        text = context.getString(R.string.back_to_keyboard)
        setOnClickListener { onBack?.invoke() }
    }
    private val actionRow = LinearLayout(context).apply {
        gravity = Gravity.END
        addView(insertButton, LinearLayout.LayoutParams(0, context.dp(PanelStyle.BUTTON_HEIGHT_DP), 1f))
        addView(backButton, LinearLayout.LayoutParams(0, context.dp(PanelStyle.BUTTON_HEIGHT_DP), 1f).apply {
            marginStart = context.dp(PanelStyle.GAP_M_DP)
        })
    }

    var onInsert: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null

    init {
        column.addView(templateLabel)
        column.addView(templateText, blockParams())
        column.addView(previewLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(PanelStyle.GAP_M_DP) })
        column.addView(previewText, blockParams())
        column.addView(issues)
        column.addView(recoveryButton, blockParams())
        column.addView(status)
        column.addView(actionRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(PanelStyle.GAP_M_DP) })
        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    /** [issueRecovery] rides along with the issue list, pointing at where those values live. */
    fun show(
        template: String,
        resolution: DynamicPhraseResolution,
        issueMessages: List<String>,
        issueRecovery: PanelRecovery? = null
    ) {
        templateText.text = template
        previewText.text = resolution.text
        issues.text = issueMessages.joinToString("\n") { "• $it" }
        issues.visibility = if (issueMessages.isEmpty()) View.GONE else View.VISIBLE
        setRecovery(if (issueMessages.isEmpty()) null else issueRecovery)
        status.visibility = View.GONE
        insertButton.isEnabled = resolution.canInsert
    }

    private fun setRecovery(recovery: PanelRecovery?) {
        this.recovery = recovery
        recovery?.let { recoveryButton.setText(it.labelRes) }
        recoveryButton.visibility = if (recovery == null) View.GONE else View.VISIBLE
    }

    fun showError(message: String) {
        status.text = message
        status.setTextColor(PanelStyle.errorTextColor(theme))
        status.visibility = View.VISIBLE
        insertButton.isEnabled = false
    }

    private fun label(text: Int) = TextView(context).apply {
        setText(text)
        setTextColor(theme.altKeyTextColor)
        textSize = PanelStyle.TEXT_CAPTION
    }

    private fun valueText() = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_EMPHASIS
        setPadding(
            context.dp(PanelStyle.CARD_PADDING_H_DP),
            context.dp(PanelStyle.CARD_PADDING_V_DP),
            context.dp(PanelStyle.CARD_PADDING_H_DP),
            context.dp(PanelStyle.CARD_PADDING_V_DP)
        )
        background = context.panelSurface(theme.altKeyBackgroundColor)
    }

    private fun blockParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = context.dp(PanelStyle.GAP_S_DP) }
}
