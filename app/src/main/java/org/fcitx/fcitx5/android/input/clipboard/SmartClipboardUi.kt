/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.setPadding
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme

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
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val notice = TextView(context).apply {
        setText(R.string.smart_clipboard_local_notice)
        setTextColor(theme.altKeyTextColor)
        textSize = 11f
        setPadding(0, dp(4), 0, dp(6))
    }
    private val output = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = 16f
        setPadding(dp(10))
        background = rounded(theme.altKeyBackgroundColor, dp(10))
    }
    private val detail = TextView(context).apply {
        setTextColor(theme.altKeyTextColor)
        textSize = 11f
        setPadding(0, dp(5), 0, dp(5))
    }
    private val status = TextView(context).apply {
        setTextColor(Color.rgb(220, 85, 85))
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(12))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = View.GONE
    }
    private val insertButton = button(R.string.smart_clipboard_insert, active = true).apply {
        setOnClickListener {
            isEnabled = false
            alpha = 0.45f
            onInsert?.invoke()
        }
    }
    private val backButton = button(R.string.smart_clipboard_back, active = false).apply {
        setOnClickListener { onBack?.invoke() }
    }
    private val actionRow = LinearLayout(context).apply {
        addView(insertButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        addView(backButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginStart = dp(6)
        })
    }
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(title, matchWrap())
        addView(notice, matchWrap())
        addView(output, matchWrap())
        addView(detail, matchWrap())
        addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        addView(actionRow, matchWrap().apply { topMargin = dp(6) })
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
        insertButton.alpha = 1f
        backButton.visibility = View.VISIBLE
    }

    fun showMessage(message: String, allowBack: Boolean) {
        title.setText(R.string.smart_clipboard)
        output.visibility = View.GONE
        detail.visibility = View.GONE
        notice.visibility = View.GONE
        status.text = message
        status.visibility = View.VISIBLE
        insertButton.visibility = View.GONE
        backButton.visibility = if (allowBack) View.VISIBLE else View.GONE
        actionRow.visibility = if (allowBack) View.VISIBLE else View.GONE
        status.announceForAccessibility(message)
    }

    fun showError(message: String) {
        status.text = message
        status.visibility = View.VISIBLE
        insertButton.isEnabled = true
        insertButton.alpha = 1f
        status.announceForAccessibility(message)
    }

    private fun SmartClipboardAction.labelRes(): Int = when (this) {
        SmartClipboardAction.PlainText -> R.string.smart_clipboard_plain_text
        SmartClipboardAction.Combine -> R.string.smart_clipboard_combine
        SmartClipboardAction.PhoneNumber -> R.string.smart_clipboard_phone
        SmartClipboardAction.AccountNumber -> R.string.smart_clipboard_account
        SmartClipboardAction.MaskPersonalData -> R.string.smart_clipboard_mask
    }

    private fun button(@StringRes textRes: Int, active: Boolean) = Button(context).apply {
        isAllCaps = false
        setText(textRes)
        textSize = 13f
        minHeight = 0
        minimumHeight = 0
        setTextColor(if (active) theme.genericActiveForegroundColor else theme.keyTextColor)
        backgroundTintList = ColorStateList.valueOf(
            if (active) theme.genericActiveBackgroundColor else theme.keyBackgroundColor
        )
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
