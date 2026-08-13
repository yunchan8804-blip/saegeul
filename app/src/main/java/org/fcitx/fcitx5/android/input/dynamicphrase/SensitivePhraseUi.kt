/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dynamicphrase

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.SensitivePhrase
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.panel.PanelButtonKind
import org.fcitx.fcitx5.android.input.panel.PanelRecovery
import org.fcitx.fcitx5.android.input.panel.PanelStyle
import org.fcitx.fcitx5.android.input.panel.panelButton
import org.fcitx.fcitx5.android.input.panel.panelSurface
import splitties.dimensions.dp

class SensitivePhraseUi(private val context: Context, private val theme: Theme) {
    val root = ScrollView(context).apply {
        setBackgroundColor(theme.barColor)
        isFillViewport = true
    }

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            context.dp(PanelStyle.PANEL_PADDING_H_DP),
            context.dp(PanelStyle.PANEL_PADDING_V_DP),
            context.dp(PanelStyle.PANEL_PADDING_H_DP),
            context.dp(PanelStyle.PANEL_PADDING_V_DP)
        )
    }
    private val status = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_BODY
        setPadding(0, context.dp(PanelStyle.GAP_M_DP), 0, context.dp(PanelStyle.GAP_M_DP))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val items = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val preview = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_EMPHASIS
        typeface = Typeface.DEFAULT_BOLD
        setPadding(
            context.dp(PanelStyle.CARD_PADDING_H_DP),
            context.dp(PanelStyle.CARD_PADDING_V_DP),
            context.dp(PanelStyle.CARD_PADDING_H_DP),
            context.dp(PanelStyle.CARD_PADDING_V_DP)
        )
        background = context.panelSurface(theme.altKeyBackgroundColor)
        visibility = View.GONE
    }
    private val unlockButton = actionButton(R.string.secret_vault_unlock).apply {
        setOnClickListener { onUnlock?.invoke() }
    }
    private val insertButton = actionButton(R.string.secret_vault_insert).apply {
        visibility = View.GONE
        setOnClickListener { onInsert?.invoke() }
    }
    // Shown when the vault has nothing to offer here: the fix lives in phrase settings.
    private var recovery: PanelRecovery? = null

    private val recoveryButton = context.panelButton(theme, PanelButtonKind.Secondary).apply {
        visibility = View.GONE
        setOnClickListener { recovery?.run?.invoke() }
    }
    private val backButton = context.panelButton(theme, PanelButtonKind.Secondary).apply {
        setText(R.string.back_to_keyboard)
        setOnClickListener { onBack?.invoke() }
    }
    private val actionRow = LinearLayout(context).apply {
        gravity = Gravity.END
        addView(unlockButton, rowButtonParams(first = true))
        addView(insertButton, rowButtonParams(first = false))
        addView(backButton, rowButtonParams(first = false))
    }

    /** Whether the device can retry authentication; kept so errors keep the retry path. */
    private var authenticationAvailable = false

    var onUnlock: (() -> Unit)? = null
    var onInsert: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null
    var onSelect: ((SensitivePhrase) -> Unit)? = null

    init {
        column.addView(status)
        column.addView(items)
        column.addView(recoveryButton, blockParams())
        column.addView(preview, blockParams())
        column.addView(actionRow, blockParams())
        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    fun showLocked(authenticationAvailable: Boolean) {
        this.authenticationAvailable = authenticationAvailable
        clearSensitiveContent()
        status.setText(
            if (authenticationAvailable) R.string.secret_vault_locked
            else R.string.secret_vault_auth_unavailable
        )
        status.setTextColor(theme.keyTextColor)
        unlockButton.visibility = if (authenticationAvailable) View.VISIBLE else View.GONE
    }

    /** [emptyRecovery] is offered only when there is nothing allowed here to insert. */
    fun showItems(phrases: List<SensitivePhrase>, emptyRecovery: PanelRecovery? = null) {
        clearSensitiveContent()
        status.setText(
            if (phrases.isEmpty()) R.string.secret_vault_no_allowed_items
            else R.string.secret_vault_choose_item
        )
        setRecovery(if (phrases.isEmpty()) emptyRecovery else null)
        status.setTextColor(theme.keyTextColor)
        unlockButton.visibility = View.GONE
        items.removeAllViews()
        phrases.forEach { phrase ->
            items.addView(context.panelButton(theme, PanelButtonKind.Secondary).apply {
                text = phrase.label
                setOnClickListener { onSelect?.invoke(phrase) }
            }, itemParams())
        }
    }

    fun showPreview(phrase: SensitivePhrase) {
        setRecovery(null)
        status.text = context.getString(R.string.secret_vault_preview_label, phrase.label)
        status.setTextColor(theme.keyTextColor)
        items.removeAllViews()
        preview.text = phrase.value
        preview.visibility = View.VISIBLE
        unlockButton.visibility = View.GONE
        insertButton.visibility = View.VISIBLE
        insertButton.isEnabled = true
    }

    fun showError(message: CharSequence) {
        clearSensitiveContent()
        status.text = message
        status.setTextColor(PanelStyle.errorTextColor(theme))
        // Keep the unlock button as the retry path when authentication can be retried.
        unlockButton.visibility = if (authenticationAvailable) View.VISIBLE else View.GONE
    }

    private fun setRecovery(recovery: PanelRecovery?) {
        this.recovery = recovery
        recovery?.let { recoveryButton.setText(it.labelRes) }
        recoveryButton.visibility = if (recovery == null) View.GONE else View.VISIBLE
    }

    /** Drops every UI-held reference to a decrypted phrase before changing state. */
    fun clearSensitiveContent() {
        setRecovery(null)
        items.removeAllViews()
        preview.text = ""
        preview.visibility = View.GONE
        insertButton.visibility = View.GONE
        insertButton.isEnabled = false
    }

    private fun actionButton(text: Int) =
        context.panelButton(theme, PanelButtonKind.Primary).apply { setText(text) }

    private fun blockParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = context.dp(PanelStyle.GAP_S_DP) }

    private fun itemParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        context.dp(PanelStyle.BUTTON_HEIGHT_DP)
    ).apply { topMargin = context.dp(PanelStyle.GAP_S_DP) }

    private fun rowButtonParams(first: Boolean) =
        LinearLayout.LayoutParams(0, context.dp(PanelStyle.BUTTON_HEIGHT_DP), 1f).apply {
            if (!first) marginStart = context.dp(PanelStyle.GAP_S_DP)
        }
}
