/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dynamicphrase

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.setPadding
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.SensitivePhrase
import org.fcitx.fcitx5.android.data.theme.Theme

class SensitivePhraseUi(private val context: Context, private val theme: Theme) {
    val root = ScrollView(context).apply {
        setBackgroundColor(theme.barColor)
        isFillViewport = true
    }

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }
    private val status = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = 14f
        setPadding(0, dp(8), 0, dp(8))
    }
    private val items = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val preview = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(12))
        visibility = View.GONE
    }
    private val unlockButton = actionButton(R.string.secret_vault_unlock).apply {
        setOnClickListener { onUnlock?.invoke() }
    }
    private val insertButton = actionButton(R.string.secret_vault_insert).apply {
        visibility = View.GONE
        setOnClickListener { onInsert?.invoke() }
    }
    private val backButton = Button(context).apply {
        isAllCaps = false
        setText(R.string.back_to_keyboard)
        setOnClickListener { onBack?.invoke() }
    }
    private val actionRow = LinearLayout(context).apply {
        gravity = Gravity.END
        addView(unlockButton, rowButtonParams())
        addView(insertButton, rowButtonParams())
        addView(backButton, rowButtonParams())
    }

    var onUnlock: (() -> Unit)? = null
    var onInsert: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null
    var onSelect: ((SensitivePhrase) -> Unit)? = null

    init {
        column.addView(status)
        column.addView(items)
        column.addView(preview, blockParams())
        column.addView(actionRow, blockParams())
        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    fun showLocked(authenticationAvailable: Boolean) {
        clearSensitiveContent()
        status.setText(
            if (authenticationAvailable) R.string.secret_vault_locked
            else R.string.secret_vault_auth_unavailable
        )
        status.setTextColor(theme.keyTextColor)
        unlockButton.visibility = if (authenticationAvailable) View.VISIBLE else View.GONE
    }

    fun showItems(phrases: List<SensitivePhrase>) {
        clearSensitiveContent()
        status.setText(
            if (phrases.isEmpty()) R.string.secret_vault_no_allowed_items
            else R.string.secret_vault_choose_item
        )
        status.setTextColor(theme.keyTextColor)
        unlockButton.visibility = View.GONE
        items.removeAllViews()
        phrases.forEach { phrase ->
            items.addView(Button(context).apply {
                isAllCaps = false
                text = phrase.label
                setOnClickListener { onSelect?.invoke(phrase) }
            }, blockParams())
        }
    }

    fun showPreview(phrase: SensitivePhrase) {
        status.text = context.getString(R.string.secret_vault_preview_label, phrase.label)
        status.setTextColor(theme.keyTextColor)
        items.removeAllViews()
        preview.text = phrase.value
        preview.visibility = View.VISIBLE
        unlockButton.visibility = View.GONE
        insertButton.visibility = View.VISIBLE
        insertButton.isEnabled = true
        insertButton.alpha = 1f
    }

    fun showError(message: CharSequence) {
        clearSensitiveContent()
        status.text = message
        status.setTextColor(Color.rgb(220, 85, 85))
        unlockButton.visibility = View.GONE
    }

    /** Drops every UI-held reference to a decrypted phrase before changing state. */
    fun clearSensitiveContent() {
        items.removeAllViews()
        preview.text = ""
        preview.visibility = View.GONE
        insertButton.visibility = View.GONE
        insertButton.isEnabled = false
        insertButton.alpha = 0.35f
    }

    private fun actionButton(text: Int) = Button(context).apply {
        isAllCaps = false
        setText(text)
        setTextColor(theme.genericActiveForegroundColor)
        backgroundTintList = ColorStateList.valueOf(theme.genericActiveBackgroundColor)
    }

    private fun blockParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(6) }

    private fun rowButtonParams() = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        marginStart = dp(6)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
