/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import org.fcitx.fcitx5.android.R
import splitties.dimensions.dp

/** What the key field shows; a configured key swaps in the "leave empty to keep it" hint. */
internal data class CredentialFieldSpec(
    @StringRes val hint: Int,
    @StringRes val unchangedHint: Int,
    val configured: Boolean
)

/**
 * The one provider key dialog: a password field that never feeds personalized learning,
 * optional provider controls below it, and a save button that stays reachable over the keyboard.
 * The field is cleared whenever the dialog closes so the key does not outlive it.
 */
internal class CredentialInputDialog private constructor(
    context: Context,
    @StringRes title: Int,
    @StringRes securityNote: Int,
    field: CredentialFieldSpec,
    extraViews: List<View>,
    private val onSave: (CredentialInputDialog) -> Unit
) {
    private val keyInput = EditText(context).apply {
        setHint(if (field.configured) field.unchangedHint else field.hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        maxLines = 1
        isSaveEnabled = false
    }

    private val dialog: AlertDialog = run {
        val horizontal = context.dp(20)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontal, context.dp(8), horizontal, context.dp(8))
            addView(keyInput)
            extraViews.forEach(::addView)
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(securityNote)
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    /** The typed key without surrounding spaces; empty when the field was left blank. */
    val enteredKey: String
        get() = keyInput.text.toString().trim()

    fun showKeyError(message: CharSequence) {
        keyInput.error = message
    }

    /** Clears the key and closes the dialog after a successful save. */
    fun finish() {
        keyInput.text?.clear()
        dialog.dismiss()
    }

    fun dismiss() {
        dialog.dismiss()
    }

    private fun show() {
        dialog.setOnShowListener {
            // Keep the save button reachable while the soft keyboard is up.
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onSave(this) }
        }
        dialog.setOnDismissListener { keyInput.text?.clear() }
        dialog.show()
    }

    companion object {
        /** [onSave] runs on every save tap and decides whether to [finish] or show an error. */
        fun show(
            context: Context,
            @StringRes title: Int,
            @StringRes securityNote: Int,
            field: CredentialFieldSpec,
            extraViews: List<View> = emptyList(),
            onSave: (CredentialInputDialog) -> Unit
        ) {
            CredentialInputDialog(context, title, securityNote, field, extraViews, onSave).show()
        }
    }
}
