/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.panel

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp

/** Vertical content column with the standard panel padding. */
internal fun Context.panelColumn(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(
        dp(PanelStyle.PANEL_PADDING_H_DP),
        dp(PanelStyle.PANEL_PADDING_V_DP),
        dp(PanelStyle.PANEL_PADDING_H_DP),
        dp(PanelStyle.PANEL_PADDING_V_DP)
    )
}

/** Scrolling window root on the bar color that stretches [content] to the full panel height. */
internal fun Context.panelScrollRoot(theme: Theme, content: View): ScrollView = ScrollView(this).apply {
    setBackgroundColor(theme.barColor)
    isFillViewport = true
    addView(content, FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
    ))
}

/** Centered status line whose changes accessibility services announce politely. */
internal fun Context.panelStatusText(theme: Theme): TextView = TextView(this).apply {
    setTextColor(theme.keyTextColor)
    textSize = PanelStyle.TEXT_BODY
    gravity = Gravity.CENTER
    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
}

/** Indeterminate spinner in the committing-action color, hidden until work starts. */
internal fun Context.panelProgress(theme: Theme): ProgressBar = ProgressBar(this).apply {
    isIndeterminate = true
    indeterminateTintList = ColorStateList.valueOf(theme.accentKeyBackgroundColor)
    visibility = View.GONE
}

/**
 * Adds [status] over a centered [progress], then [preview], which takes three times the
 * status share of the free height once it is shown.
 */
internal fun LinearLayout.addStatusWithPreview(status: View, progress: View, preview: View) {
    addView(status, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        0,
        1f
    ))
    addView(progress, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        bottomMargin = dp(PanelStyle.GAP_S_DP)
    })
    addView(preview, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        0,
        3f
    ))
}

/** Two equal-width buttons side by side, [start] first. */
internal fun Context.panelButtonPair(start: View, end: View): LinearLayout = LinearLayout(this).apply {
    gravity = Gravity.CENTER
    addView(start, LinearLayout.LayoutParams(0, dp(PanelStyle.BUTTON_HEIGHT_DP), 1f).apply {
        marginEnd = dp(PanelStyle.GAP_S_DP)
    })
    addView(end, LinearLayout.LayoutParams(0, dp(PanelStyle.BUTTON_HEIGHT_DP), 1f).apply {
        marginStart = dp(PanelStyle.GAP_S_DP)
    })
}

/** Unchecked row for picking one preview item; [onCheckedChange] runs on every toggle. */
internal fun Context.panelCheckRow(
    theme: Theme,
    text: CharSequence,
    onCheckedChange: (Boolean) -> Unit
): CheckBox = CheckBox(this).apply {
    isChecked = false
    buttonTintList = ColorStateList.valueOf(theme.accentKeyBackgroundColor)
    setTextColor(theme.keyTextColor)
    textSize = PanelStyle.TEXT_BODY
    this.text = text
    setPadding(
        dp(PanelStyle.GAP_M_DP), dp(PanelStyle.GAP_S_DP),
        dp(PanelStyle.GAP_M_DP), dp(PanelStyle.GAP_S_DP)
    )
    background = context.panelSurface(theme.keyBackgroundColor)
    setOnCheckedChangeListener { _, checked -> onCheckedChange(checked) }
}

/** Tappable query field of a search panel; the panel sets the query text. */
internal fun Context.panelQueryField(theme: Theme, onClick: () -> Unit): TextView = TextView(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    setTextColor(theme.keyTextColor)
    textSize = PanelStyle.TEXT_EMPHASIS
    setPadding(dp(PanelStyle.CARD_PADDING_H_DP), 0, dp(PanelStyle.GAP_M_DP), 0)
    background = context.pressablePanelSurface(theme, theme.altKeyBackgroundColor)
    setOnClickListener { onClick() }
}

/**
 * Results area of a search panel: [results] under a full-size [statusOverlay], with the
 * transient [actionStatus] banner along the top edge.
 */
internal fun Context.panelResultsFrame(
    results: View,
    statusOverlay: View,
    actionStatus: View
): FrameLayout = FrameLayout(this).apply {
    addView(results, FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
    ))
    addView(statusOverlay, FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
    ))
    addView(actionStatus, FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.TOP
    ))
}

/**
 * Shows the button under [label] running [action] on tap. A button shown with [enabled] false
 * keeps [action] for when the panel enables it later.
 */
internal fun Button.showPanelAction(@StringRes label: Int, enabled: Boolean = true, action: () -> Unit) {
    isEnabled = enabled
    setText(label)
    setOnClickListener { action() }
}

/** Disables the button under [label] and drops its tap action. */
internal fun Button.showDisabledPanelAction(@StringRes label: Int) {
    isEnabled = false
    setText(label)
    setOnClickListener(null)
}

/**
 * Primary slot of a panel that can be blocked: [action] under [label] while [available].
 * Otherwise [recovery] takes the slot so the panel points at the setting that unblocks it,
 * and without one [label] stays disabled.
 */
internal fun Button.showPanelActionOrRecovery(
    available: Boolean,
    @StringRes label: Int,
    recovery: PanelRecovery?,
    action: () -> Unit
) {
    when {
        available -> showPanelAction(label, action = action)
        recovery != null -> showPanelAction(recovery.labelRes) { recovery.run() }
        else -> showDisabledPanelAction(label)
    }
}
