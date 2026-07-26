/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dynamicphrase

import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.DynamicPhraseIssue
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.DynamicPhraseIssueReason
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.DynamicPhraseProfileStore
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.DynamicPhraseResolution
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.DynamicPhraseTemplate
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.DynamicPhraseValues
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.DynamicPhraseVariable
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import java.time.ZonedDateTime

data class DynamicPhraseEditorTarget(
    val packageName: String,
    val fieldId: Int,
    val inputType: Int,
    val selectionStart: Int,
    val selectionEnd: Int
)

class DynamicPhraseWindow(
    private val template: String,
    private val editor: DynamicPhraseEditorTarget
) : InputWindow.ExtendedInputWindow<DynamicPhraseWindow>() {
    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()
    private lateinit var ui: DynamicPhraseUi
    private var resolution: DynamicPhraseResolution? = null

    override val title: String by lazy { context.getString(R.string.dynamic_phrase) }

    override fun onCreateView(): View {
        ui = DynamicPhraseUi(context, theme).apply {
            onInsert = ::insert
            onBack = { windowManager.attachWindow(KeyboardWindow) }
        }
        return ui.root
    }

    override fun onAttached() {
        val privateEditor = !service.allowsTextInspectionFeatures()
        val clipboard = ClipboardManager.lastEntry
        resolution = DynamicPhraseTemplate.expand(
            template,
            DynamicPhraseValues(
                now = ZonedDateTime.now(),
                profile = DynamicPhraseProfileStore(context).load(),
                clipboardText = clipboard?.takeUnless { it.sensitive }?.text,
                clipboardSensitive = clipboard?.sensitive == true,
                privateEditor = privateEditor
            )
        ).also { result ->
            ui.show(template, result, result.issues.map(::issueMessage))
        }
    }

    override fun onDetached() = Unit

    private fun insert() {
        val result = resolution?.takeIf { it.canInsert } ?: return
        if (!service.matchesCurrentEditor(
                editor.packageName,
                editor.fieldId,
                editor.inputType,
                editor.selectionStart,
                editor.selectionEnd
            )
        ) {
            ui.showError(context.getString(R.string.dynamic_phrase_editor_changed))
            return
        }
        if (!service.commitText(result.text, 1)) {
            ui.showError(context.getString(R.string.dynamic_phrase_insert_failed))
            return
        }
        windowManager.attachWindow(KeyboardWindow)
    }

    private fun issueMessage(issue: DynamicPhraseIssue): String {
        val variable = context.getString(
            when (issue.variable) {
                DynamicPhraseVariable.Date -> R.string.dynamic_phrase_variable_date
                DynamicPhraseVariable.Time -> R.string.dynamic_phrase_variable_time
                DynamicPhraseVariable.Name -> R.string.dynamic_phrase_variable_name
                DynamicPhraseVariable.Phone -> R.string.dynamic_phrase_variable_phone
                DynamicPhraseVariable.Email -> R.string.dynamic_phrase_variable_email
                DynamicPhraseVariable.Address -> R.string.dynamic_phrase_variable_address
                DynamicPhraseVariable.Clipboard -> R.string.dynamic_phrase_variable_clipboard
            }
        )
        return context.getString(
            when (issue.reason) {
                DynamicPhraseIssueReason.MissingValue -> R.string.dynamic_phrase_missing_value
                DynamicPhraseIssueReason.PrivateEditor -> R.string.dynamic_phrase_private_blocked
                DynamicPhraseIssueReason.SensitiveClipboard -> R.string.dynamic_phrase_sensitive_clipboard
            },
            variable
        )
    }
}
