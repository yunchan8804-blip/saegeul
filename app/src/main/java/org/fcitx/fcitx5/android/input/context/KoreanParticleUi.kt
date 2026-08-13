/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.context

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.panel.PanelStyle
import org.fcitx.fcitx5.android.input.panel.pressablePanelSurface
import splitties.dimensions.dp

class KoreanParticleUi(
    private val context: Context,
    private val theme: Theme
) {
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(
            context.dp(PanelStyle.PANEL_PADDING_H_DP),
            context.dp(PanelStyle.PANEL_PADDING_V_DP),
            context.dp(PanelStyle.PANEL_PADDING_H_DP),
            context.dp(PanelStyle.PANEL_PADDING_V_DP)
        )
        setBackgroundColor(theme.barColor)
    }

    private val message = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_BODY
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
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
        secondRow.visibility = if (suggestions.size > 3) View.VISIBLE else View.GONE
        firstRow.removeAllViews()
        secondRow.removeAllViews()
        suggestions.forEachIndexed { index, suggestion ->
            val row = if (index < 3) firstRow else secondRow
            row.addView(Button(context).apply {
                text = suggestion.text
                isAllCaps = false
                setTextColor(theme.keyTextColor)
                textSize = PanelStyle.TEXT_EMPHASIS
                minWidth = 0
                minimumWidth = 0
                background = context.pressablePanelSurface(theme, theme.altKeyBackgroundColor)
                setOnClickListener { onSuggestion?.invoke(suggestion) }
            }, LinearLayout.LayoutParams(0, context.dp(PanelStyle.BUTTON_HEIGHT_DP), 1f).apply {
                // Horizontal gaps only; the vertical rhythm comes from rowParams so
                // stacked rows do not double their margins.
                val gap = context.dp(PanelStyle.GAP_S_DP)
                marginStart = gap
                marginEnd = gap
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
            repeat(row.childCount) { index ->
                row.getChildAt(index).apply {
                    isEnabled = !locked
                    alpha = if (locked) PanelStyle.DISABLED_ALPHA else 1f
                }
            }
        }
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        0,
        1f
    ).apply { topMargin = context.dp(PanelStyle.GAP_S_DP) }
}
