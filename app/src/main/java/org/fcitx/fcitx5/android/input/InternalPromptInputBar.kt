/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.panel.PanelButtonKind
import org.fcitx.fcitx5.android.input.panel.PanelStyle
import org.fcitx.fcitx5.android.input.panel.panelButton
import org.fcitx.fcitx5.android.input.panel.panelSurface
import splitties.dimensions.dp

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
        alpha = PanelStyle.HINT_ALPHA
        textSize = PanelStyle.TEXT_CAPTION
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private val aiContext = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            setText(R.string.ai_direct_prompt_title)
            setTextColor(theme.keyTextColor)
            textSize = PanelStyle.TEXT_CAPTION
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(AI_CONTEXT_HEIGHT_DP)))
        addView(aiContextLabel, LayoutParams(0, dp(AI_CONTEXT_HEIGHT_DP), 1f).apply {
            marginStart = dp(PanelStyle.GAP_S_DP)
        })
    }

    private val prompt = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_BODY
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.START
        setPadding(dp(PanelStyle.CARD_PADDING_H_DP), 0, dp(PanelStyle.CARD_PADDING_H_DP), 0)
        background = context.panelSurface(theme.altKeyBackgroundColor)
    }

    private val cancel = context.panelButton(theme, PanelButtonKind.Secondary).apply {
        setText(android.R.string.cancel)
        minWidth = dp(MIN_CONTROL_WIDTH_DP)
        setOnClickListener { onCancel?.invoke() }
    }

    private val submit = context.panelButton(theme, PanelButtonKind.Primary).apply {
        setText(spec.submitRes)
        minWidth = dp(MIN_CONTROL_WIDTH_DP)
        setOnClickListener { onSubmit?.invoke() }
    }

    private val promptRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = HORIZONTAL
        addView(prompt, LayoutParams(0, dp(PanelStyle.BUTTON_HEIGHT_DP), 1f))
        addView(cancel, LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(PanelStyle.BUTTON_HEIGHT_DP)
        ).apply { marginStart = dp(PanelStyle.GAP_S_DP) })
        addView(submit, LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(PanelStyle.BUTTON_HEIGHT_DP)
        ).apply { marginStart = dp(PanelStyle.GAP_S_DP) })
    }

    init {
        orientation = VERTICAL
        setPadding(
            dp(PanelStyle.GAP_M_DP),
            dp(PanelStyle.PANEL_PADDING_V_DP),
            dp(PanelStyle.GAP_M_DP),
            dp(PanelStyle.PANEL_PADDING_V_DP)
        )
        setBackgroundColor(theme.barColor)
        visibility = View.GONE
        addView(aiContext, LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(AI_CONTEXT_HEIGHT_DP)))
        addView(promptRow, LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(PanelStyle.BUTTON_HEIGHT_DP)
        ))
    }

    fun configure(spec: InternalPromptSpec, contextLabel: CharSequence? = null) {
        this.spec = spec
        val isAi = spec.feature == InternalPromptFeature.Ai
        aiContext.visibility = if (isAi) View.VISIBLE else View.GONE
        aiContextLabel.text = contextLabel ?: context.getString(R.string.ai_direct_prompt_context)
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
                    ForegroundColorSpan(theme.accentKeyBackgroundColor),
                    length - CARET.length,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        } else {
            SpannableString(combined + CARET).apply {
                if (preedit.isNotEmpty()) {
                    setSpan(
                        ForegroundColorSpan(theme.accentKeyBackgroundColor),
                        committed.length,
                        combined.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                // Internal prompt capture intentionally supports only the end cursor. Keep the
                // caret visible so this does not look like a read-only status label.
                setSpan(
                    ForegroundColorSpan(theme.accentKeyBackgroundColor),
                    combined.length,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        prompt.alpha = if (combined.isBlank()) PanelStyle.HINT_ALPHA else 1f
        submit.isEnabled = !submitPending && (spec.allowBlankSubmission || hasInput)
        contentDescription = context.getString(spec.hintRes) + ": " + combined
    }

    /** Locks prompt actions while its FIFO submit fence and reset drain are settling. */
    fun setSubmitPending(pending: Boolean) {
        submitPending = pending
        cancel.isEnabled = !pending
        submit.isEnabled = !pending && (spec.allowBlankSubmission || hasInput)
    }

    private companion object {
        const val AI_CONTEXT_HEIGHT_DP = 16
        const val MIN_CONTROL_WIDTH_DP = 64
        // Strip heights are the sum of their parts:
        // GIF = controls (48) + vertical padding (8 + 8) = 64
        // AI  = context row (16) + GIF strip (64) = 80
        const val GIF_PROMPT_HEIGHT_DP =
            PanelStyle.BUTTON_HEIGHT_DP + 2 * PanelStyle.PANEL_PADDING_V_DP
        const val AI_PROMPT_HEIGHT_DP = AI_CONTEXT_HEIGHT_DP + GIF_PROMPT_HEIGHT_DP
        const val CARET = "\u200A│"
    }
}
