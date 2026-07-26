/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dynamicphrase

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
import androidx.core.view.setPadding
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.DynamicPhraseResolution
import org.fcitx.fcitx5.android.data.theme.Theme

class DynamicPhraseUi(private val context: Context, private val theme: Theme) {
    val root = ScrollView(context).apply {
        setBackgroundColor(theme.barColor)
        isFillViewport = true
    }

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }
    private val templateLabel = label(R.string.dynamic_phrase_template)
    private val templateText = valueText()
    private val previewLabel = label(R.string.dynamic_phrase_preview)
    private val previewText = valueText().apply { typeface = Typeface.DEFAULT_BOLD }
    private val issues = TextView(context).apply {
        setTextColor(Color.rgb(220, 85, 85))
        textSize = 13f
        setPadding(0, dp(8), 0, dp(8))
        visibility = View.GONE
    }
    private val status = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(theme.keyTextColor)
        textSize = 14f
        setPadding(0, dp(8), 0, dp(8))
        visibility = View.GONE
    }
    private val insertButton = Button(context).apply {
        isAllCaps = false
        text = context.getString(R.string.dynamic_phrase_insert)
        setTextColor(theme.genericActiveForegroundColor)
        backgroundTintList = ColorStateList.valueOf(theme.genericActiveBackgroundColor)
        setOnClickListener { onInsert?.invoke() }
    }
    private val backButton = Button(context).apply {
        isAllCaps = false
        text = context.getString(R.string.back_to_keyboard)
        setOnClickListener { onBack?.invoke() }
    }
    private val actionRow = LinearLayout(context).apply {
        gravity = Gravity.END
        addView(insertButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        addView(backButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(8)
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
        ).apply { topMargin = dp(10) })
        column.addView(previewText, blockParams())
        column.addView(issues)
        column.addView(status)
        column.addView(actionRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    fun show(template: String, resolution: DynamicPhraseResolution, issueMessages: List<String>) {
        templateText.text = template
        previewText.text = resolution.text
        issues.text = issueMessages.joinToString("\n") { "• $it" }
        issues.visibility = if (issueMessages.isEmpty()) View.GONE else View.VISIBLE
        status.visibility = View.GONE
        insertButton.isEnabled = resolution.canInsert
        insertButton.alpha = if (resolution.canInsert) 1f else 0.35f
    }

    fun showError(message: String) {
        status.text = message
        status.setTextColor(Color.rgb(220, 85, 85))
        status.visibility = View.VISIBLE
        insertButton.isEnabled = false
        insertButton.alpha = 0.35f
    }

    private fun label(text: Int) = TextView(context).apply {
        setText(text)
        setTextColor(theme.altKeyTextColor)
        textSize = 12f
    }

    private fun valueText() = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = 17f
        setPadding(dp(12))
        background = GradientDrawable().apply {
            setColor(theme.altKeyBackgroundColor)
            cornerRadius = dp(12).toFloat()
        }
    }

    private fun blockParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(4) }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
