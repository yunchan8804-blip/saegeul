/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.typo

import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must

class TypoRecoveryWindow : InputWindow.ExtendedInputWindow<TypoRecoveryWindow>() {
    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()
    private lateinit var ui: TypoRecoveryUi
    private var snapshot: TypoRecoverySnapshot? = null
    private var applied: AppliedReplacement? = null

    override val title: String by lazy { context.getString(R.string.typo_recovery) }

    override fun onCreateView(): View {
        ui = TypoRecoveryUi(context, theme).apply {
            onProposal = ::applyProposal
            onUndo = ::undo
            onBack = { windowManager.attachWindow(KeyboardWindow) }
        }
        return ui.root
    }

    override fun onAttached() {
        if (!service.allowsTextInspectionFeatures()) {
            ui.showMessage(context.getString(R.string.typo_recovery_private_disabled), true)
            return
        }
        snapshot = service.captureTypoRecoverySnapshot()
        snapshot?.let(ui::showPreview)
            ?: ui.showMessage(context.getString(R.string.typo_recovery_no_text))
    }

    override fun onDetached() = Unit

    private fun applyProposal(proposal: TypoRecoveryProposal) {
        val current = snapshot ?: return
        val original = current.chunk.original
        if (!service.replaceTypoRecoveryText(current.editor, original, proposal.replacement)) {
            ui.showMessage(context.getString(R.string.typo_recovery_editor_changed), true)
            return
        }
        applied = AppliedReplacement(current.editor, original, proposal.replacement)
        ui.showApplied(original, proposal.replacement)
    }

    private fun undo() {
        val current = applied ?: return
        if (!service.replaceTypoRecoveryText(current.editor, current.replacement, current.original)) {
            ui.showMessage(context.getString(R.string.typo_recovery_editor_changed), true)
            return
        }
        applied = null
        ui.showMessage(context.getString(R.string.typo_recovery_undone))
    }

    private data class AppliedReplacement(
        val editor: TypoRecoveryEditorTarget,
        val original: String,
        val replacement: String
    )
}
