/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.typo

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
import org.fcitx.fcitx5.android.data.theme.Theme

class TypoRecoveryUi(private val context: Context, private val theme: Theme) {
    val root = ScrollView(context).apply {
        setBackgroundColor(theme.barColor)
        isFillViewport = true
    }

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(14))
    }
    private val sourceLabel = TextView(context).apply {
        text = context.getString(R.string.typo_recovery_source)
        setTextColor(theme.altKeyTextColor)
        textSize = 12f
    }
    private val sourceText = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(context.dp(12))
        background = rounded(theme.altKeyBackgroundColor, context.dp(12).toFloat())
    }
    private val notice = TextView(context).apply {
        text = context.getString(R.string.typo_recovery_local_notice)
        setTextColor(theme.altKeyTextColor)
        textSize = 11f
        setPadding(0, context.dp(8), 0, context.dp(8))
    }
    private val candidates = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val status = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(theme.keyTextColor)
        textSize = 15f
        setPadding(context.dp(16))
        visibility = View.GONE
    }
    private val undoButton = Button(context).apply {
        isAllCaps = false
        text = context.getString(R.string.typo_recovery_undo)
        visibility = View.GONE
        setOnClickListener { onUndo?.invoke() }
    }
    private val backButton = Button(context).apply {
        isAllCaps = false
        text = context.getString(R.string.typo_recovery_back)
        setOnClickListener { onBack?.invoke() }
    }
    private val actionRow = LinearLayout(context).apply {
        gravity = Gravity.END
        addView(undoButton, LinearLayout.LayoutParams(0, context.dp(48), 1f))
        addView(backButton, LinearLayout.LayoutParams(0, context.dp(48), 1f).apply {
            marginStart = context.dp(8)
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
        ).apply { topMargin = context.dp(4) })
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
        ).apply { topMargin = context.dp(8) })
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
            candidates.addView(Button(context).apply {
                isAllCaps = false
                text = "$direction\n${proposal.replacement}"
                textSize = 15f
                setTextColor(theme.genericActiveForegroundColor)
                backgroundTintList = ColorStateList.valueOf(theme.genericActiveBackgroundColor)
                setOnClickListener { onProposal?.invoke(proposal) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(62)
            ).apply { bottomMargin = context.dp(7) })
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
        status.setTextColor(if (isError) Color.rgb(220, 85, 85) else theme.keyTextColor)
        status.visibility = View.VISIBLE
        undoButton.visibility = View.GONE
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
