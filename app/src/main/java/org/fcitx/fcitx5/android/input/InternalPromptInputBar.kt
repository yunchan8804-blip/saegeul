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

    private var spec = InternalPromptSpecs.gifSearch(1)
    private var submitPending = false
    private var hasInput = false

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
        addView(promptRow, LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(PanelStyle.BUTTON_HEIGHT_DP)
        ))
    }

    fun configure(spec: InternalPromptSpec) {
        this.spec = spec
        submit.setText(spec.submitRes)
        render(committed = "", preedit = "")
    }

    val preferredHeightPx: Int
        get() = dp(GIF_PROMPT_HEIGHT_DP)

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
        const val MIN_CONTROL_WIDTH_DP = 64
        // Strip height is the sum of its parts: controls (48) + vertical padding (8 + 8) = 64
        const val GIF_PROMPT_HEIGHT_DP =
            PanelStyle.BUTTON_HEIGHT_DP + 2 * PanelStyle.PANEL_PADDING_V_DP
        const val CARET = " │"
    }
}
