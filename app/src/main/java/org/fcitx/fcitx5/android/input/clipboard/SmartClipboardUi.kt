/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.graphics.Typeface
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
import splitties.dimensions.dp

class SmartClipboardUi(
    private val context: Context,
    private val theme: Theme
) {
    val root = ScrollView(context).apply {
        isFillViewport = true
        setBackgroundColor(theme.barColor)
    }

    var onInsert: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null

    private val title = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_EMPHASIS
        typeface = Typeface.DEFAULT_BOLD
    }
    private val notice = TextView(context).apply {
        setText(R.string.smart_clipboard_local_notice)
        setTextColor(theme.altKeyTextColor)
        textSize = PanelStyle.TEXT_CAPTION
        setPadding(0, context.dp(PanelStyle.GAP_S_DP), 0, context.dp(PanelStyle.GAP_S_DP))
    }
    private val output = TextView(context).apply {
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
    private val detail = TextView(context).apply {
        setTextColor(theme.altKeyTextColor)
        textSize = PanelStyle.TEXT_CAPTION
        setPadding(0, context.dp(PanelStyle.GAP_S_DP), 0, context.dp(PanelStyle.GAP_S_DP))
    }
    private val status = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_BODY
        gravity = Gravity.CENTER
        setPadding(context.dp(PanelStyle.CARD_PADDING_H_DP))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = View.GONE
    }
    private val insertButton = context.panelButton(theme, PanelButtonKind.Primary).apply {
        setText(R.string.smart_clipboard_insert)
        setOnClickListener {
            isEnabled = false
            onInsert?.invoke()
        }
    }
    private val backButton = context.panelButton(theme, PanelButtonKind.Secondary).apply {
        setText(R.string.smart_clipboard_back)
        setOnClickListener { onBack?.invoke() }
    }
    private val actionRow = LinearLayout(context).apply {
        addView(insertButton, LinearLayout.LayoutParams(0, context.dp(PanelStyle.BUTTON_HEIGHT_DP), 1f))
        addView(backButton, LinearLayout.LayoutParams(0, context.dp(PanelStyle.BUTTON_HEIGHT_DP), 1f).apply {
            marginStart = context.dp(PanelStyle.GAP_M_DP)
        })
    }
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            context.dp(PanelStyle.PANEL_PADDING_H_DP),
            context.dp(PanelStyle.PANEL_PADDING_V_DP),
            context.dp(PanelStyle.PANEL_PADDING_H_DP),
            context.dp(PanelStyle.PANEL_PADDING_V_DP)
        )
        addView(title, matchWrap())
        addView(notice, matchWrap())
        addView(output, matchWrap())
        addView(detail, matchWrap())
        addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        addView(actionRow, matchWrap().apply { topMargin = context.dp(PanelStyle.GAP_M_DP) })
    }

    init {
        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    fun showPreview(preview: SmartClipboardPreview) {
        title.setText(preview.action.labelRes())
        output.text = preview.output
        output.visibility = View.VISIBLE
        notice.visibility = View.VISIBLE
        detail.text = if (preview.action == SmartClipboardAction.MaskPersonalData) {
            context.getString(
                R.string.smart_clipboard_mask_count,
                preview.maskCandidates.size
            )
        } else {
            context.getString(R.string.smart_clipboard_source_count, preview.sourceCount)
        }
        detail.visibility = View.VISIBLE
        status.visibility = View.GONE
        actionRow.visibility = View.VISIBLE
        insertButton.visibility = View.VISIBLE
        insertButton.isEnabled = true
        backButton.visibility = View.VISIBLE
    }

    fun showMessage(message: String, allowBack: Boolean, isError: Boolean = false) {
        title.setText(R.string.smart_clipboard)
        output.visibility = View.GONE
        detail.visibility = View.GONE
        notice.visibility = View.GONE
        status.text = message
        status.setTextColor(if (isError) PanelStyle.errorTextColor(theme) else theme.keyTextColor)
        status.visibility = View.VISIBLE
        insertButton.visibility = View.GONE
        backButton.visibility = if (allowBack) View.VISIBLE else View.GONE
        actionRow.visibility = if (allowBack) View.VISIBLE else View.GONE
    }

    fun showError(message: String) {
        status.text = message
        status.setTextColor(PanelStyle.errorTextColor(theme))
        status.visibility = View.VISIBLE
        insertButton.isEnabled = true
    }

    private fun SmartClipboardAction.labelRes(): Int = when (this) {
        SmartClipboardAction.PlainText -> R.string.smart_clipboard_plain_text
        SmartClipboardAction.Combine -> R.string.smart_clipboard_combine
        SmartClipboardAction.PhoneNumber -> R.string.smart_clipboard_phone
        SmartClipboardAction.AccountNumber -> R.string.smart_clipboard_account
        SmartClipboardAction.MaskPersonalData -> R.string.smart_clipboard_mask
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )
}
