/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

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

/** Prompt strip shown above the existing Fcitx keyboard while text is captured internally. */
class InternalPromptInputBar(
    context: Context,
    private val theme: Theme
) : LinearLayout(context) {
    var onCancel: (() -> Unit)? = null
    var onSubmit: (() -> Unit)? = null

    private var spec = InternalPromptSpecs.Ai
    private var submitPending = false
    private var hasInput = false

    /**
     * An AI request transforms the captured editor text, so the strip says that before taking an
     * instruction. GIF search deliberately stays a compact standalone search field.
     */
    private val aiContextLabel = TextView(context).apply {
        setText(R.string.ai_direct_prompt_context)
        setTextColor(theme.keyTextColor)
        alpha = AI_CONTEXT_ALPHA
        textSize = 10f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private val aiContext = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            setText(R.string.ai_direct_prompt_title)
            setTextColor(theme.keyTextColor)
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(AI_CONTEXT_HEIGHT_DP)))
        addView(aiContextLabel, LayoutParams(0, dp(AI_CONTEXT_HEIGHT_DP), 1f).apply {
            marginStart = dp(6)
        })
    }

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

    private val submit = compactButton(spec.submitRes, active = true).apply {
        setOnClickListener { onSubmit?.invoke() }
    }

    private val promptRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = HORIZONTAL
        addView(prompt, LayoutParams(0, dp(GIF_CONTROL_HEIGHT_DP), 1f))
        addView(cancel, LayoutParams(dp(58), dp(GIF_CONTROL_HEIGHT_DP)).apply {
            marginStart = dp(6)
        })
        addView(submit, LayoutParams(dp(66), dp(GIF_CONTROL_HEIGHT_DP)).apply {
            marginStart = dp(4)
        })
    }

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setBackgroundColor(theme.barColor)
        visibility = View.GONE
        addView(aiContext, LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(AI_CONTEXT_HEIGHT_DP)))
        addView(promptRow, LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(GIF_CONTROL_HEIGHT_DP)))
    }

    fun configure(spec: InternalPromptSpec, contextLabel: CharSequence? = null) {
        this.spec = spec
        val isAi = spec.feature == InternalPromptFeature.Ai
        aiContext.visibility = if (isAi) View.VISIBLE else View.GONE
        aiContextLabel.text = contextLabel ?: context.getString(R.string.ai_direct_prompt_context)
        updateControlHeight(if (isAi) AI_CONTROL_HEIGHT_DP else GIF_CONTROL_HEIGHT_DP)
        submit.setText(spec.submitRes)
        render(committed = "", preedit = "")
    }

    /** Height changes only for the AI header; GIF search keeps its compact strip. */
    val preferredHeightPx: Int
        get() = dp(
            if (spec.feature == InternalPromptFeature.Ai) AI_PROMPT_HEIGHT_DP else GIF_PROMPT_HEIGHT_DP
        )

    fun render(committed: String, preedit: String) {
        val combined = committed + preedit
        hasInput = combined.isNotBlank()
        prompt.text = if (combined.isBlank()) {
            SpannableString(context.getString(spec.hintRes) + CARET).apply {
                setSpan(
                    ForegroundColorSpan(theme.genericActiveBackgroundColor),
                    length - CARET.length,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        } else {
            SpannableString(combined + CARET).apply {
                if (preedit.isNotEmpty()) {
                    setSpan(
                        ForegroundColorSpan(theme.genericActiveBackgroundColor),
                        committed.length,
                        combined.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                // Internal prompt capture intentionally supports only the end cursor. Keep the
                // caret visible so this does not look like a read-only status label.
                setSpan(
                    ForegroundColorSpan(theme.genericActiveBackgroundColor),
                    combined.length,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        prompt.alpha = if (combined.isBlank()) EMPTY_PROMPT_ALPHA else 1f
        submit.isEnabled = !submitPending && (spec.allowBlankSubmission || hasInput)
        submit.alpha = if (submit.isEnabled) 1f else 0.45f
        contentDescription = context.getString(spec.hintRes) + ": " + combined
    }

    /** Locks prompt actions while its FIFO submit fence and reset drain are settling. */
    fun setSubmitPending(pending: Boolean) {
        submitPending = pending
        cancel.isEnabled = !pending
        cancel.alpha = if (cancel.isEnabled) 1f else 0.45f
        submit.isEnabled = !pending && (spec.allowBlankSubmission || hasInput)
        submit.alpha = if (submit.isEnabled) 1f else 0.45f
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

    private fun updateControlHeight(heightDp: Int) {
        val height = dp(heightDp)
        promptRow.layoutParams.height = height
        prompt.layoutParams.height = height
        cancel.layoutParams.height = height
        submit.layoutParams.height = height
        promptRow.requestLayout()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val AI_CONTEXT_HEIGHT_DP = 16
        const val AI_CONTROL_HEIGHT_DP = 40
        const val GIF_CONTROL_HEIGHT_DP = 44
        const val AI_PROMPT_HEIGHT_DP = 68
        const val GIF_PROMPT_HEIGHT_DP = 56
        const val AI_CONTEXT_ALPHA = 0.72f
        const val EMPTY_PROMPT_ALPHA = 0.65f
        const val CARET = "\u200A│"
    }
}
