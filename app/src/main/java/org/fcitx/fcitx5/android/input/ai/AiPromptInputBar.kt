/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme

/** Prompt strip shown above the app's existing keyboard while input is captured internally. */
class AiPromptInputBar(
    context: Context,
    private val theme: Theme
) : LinearLayout(context) {
    var onCancel: (() -> Unit)? = null
    var onSubmit: (() -> Unit)? = null

    private val prompt = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(theme.keyTextColor)
        textSize = 14f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.START
        setPadding(dp(12), 0, dp(10), 0)
        background = rounded(theme.altKeyBackgroundColor, dp(10))
    }

    private val cancel = compactButton(android.R.string.cancel, active = false).apply {
        setOnClickListener { onCancel?.invoke() }
    }

    private val submit = compactButton(R.string.ai_direct_prompt_run, active = true).apply {
        setOnClickListener { onSubmit?.invoke() }
    }

    init {
        gravity = Gravity.CENTER_VERTICAL
        orientation = HORIZONTAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setBackgroundColor(theme.barColor)
        visibility = View.GONE
        addView(prompt, LayoutParams(0, dp(44), 1f))
        addView(cancel, LayoutParams(dp(58), dp(44)).apply { marginStart = dp(6) })
        addView(submit, LayoutParams(dp(66), dp(44)).apply { marginStart = dp(4) })
    }

    fun render(committed: String, preedit: String) {
        val combined = committed + preedit
        prompt.text = if (combined.isBlank()) {
            context.getString(R.string.ai_direct_prompt_hint)
        } else {
            SpannableString(combined).apply {
                if (preedit.isNotEmpty()) {
                    setSpan(
                        ForegroundColorSpan(theme.genericActiveBackgroundColor),
                        committed.length,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }
        prompt.alpha = if (combined.isBlank()) 0.65f else 1f
        submit.isEnabled = combined.isNotBlank()
        submit.alpha = if (submit.isEnabled) 1f else 0.45f
        contentDescription = context.getString(R.string.ai_direct_prompt_hint) + ": " + combined
    }

    private fun compactButton(textRes: Int, active: Boolean) = Button(context).apply {
        isAllCaps = false
        setText(textRes)
        textSize = 12f
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(if (active) theme.genericActiveForegroundColor else theme.keyTextColor)
        backgroundTintList = ColorStateList.valueOf(
            if (active) theme.genericActiveBackgroundColor else theme.keyBackgroundColor
        )
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
