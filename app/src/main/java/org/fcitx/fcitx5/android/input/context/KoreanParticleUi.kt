/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.context

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp

class KoreanParticleUi(
    private val context: Context,
    private val theme: Theme
) {
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
        setBackgroundColor(theme.barColor)
    }

    private val message = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(theme.keyTextColor)
        textSize = 14f
    }

    private val firstRow = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private val secondRow = LinearLayout(context).apply { gravity = Gravity.CENTER }

    var onSuggestion: ((KoreanParticleSuggestion) -> Unit)? = null

    init {
        root.addView(message, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            0.8f
        ))
        root.addView(firstRow, rowParams())
        root.addView(secondRow, rowParams())
    }

    fun showSuggestions(suggestions: List<KoreanParticleSuggestion>) {
        message.setText(R.string.korean_particle_prompt)
        firstRow.visibility = View.VISIBLE
        secondRow.visibility = View.VISIBLE
        firstRow.removeAllViews()
        secondRow.removeAllViews()
        suggestions.forEachIndexed { index, suggestion ->
            val row = if (index < 3) firstRow else secondRow
            row.addView(Button(context).apply {
                text = suggestion.text
                isAllCaps = false
                setTextColor(theme.keyTextColor)
                textSize = 16f
                minWidth = 0
                minimumWidth = 0
                background = GradientDrawable().apply {
                    cornerRadius = context.dp(10).toFloat()
                    setColor(theme.altKeyBackgroundColor)
                }
                setOnClickListener { onSuggestion?.invoke(suggestion) }
            }, LinearLayout.LayoutParams(0, context.dp(46), 1f).apply {
                val gap = context.dp(3)
                setMargins(gap, gap, gap, gap)
            })
        }
    }

    fun showMessage(text: CharSequence) {
        message.text = text
        firstRow.visibility = View.GONE
        secondRow.visibility = View.GONE
    }

    fun setLocked(locked: Boolean) {
        sequenceOf(firstRow, secondRow).forEach { row ->
            repeat(row.childCount) { index -> row.getChildAt(index).isEnabled = !locked }
        }
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        0,
        1f
    )
}
