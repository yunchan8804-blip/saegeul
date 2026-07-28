/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dynamicphrase

import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.DynamicPhraseProfileStore
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.SensitivePhrase
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.SensitivePhraseCommitGate
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.SensitivePhrasePolicy
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must

class SensitivePhraseWindow(
    private val editor: DynamicPhraseEditorTarget,
    authResume: SensitivePhraseAuthResumeResult? = null
) : InputWindow.ExtendedInputWindow<SensitivePhraseWindow>() {
    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()
    private val store by lazy { DynamicPhraseProfileStore(context) }
    private lateinit var ui: SensitivePhraseUi
    private var pendingAuthResume = authResume
    private var selectedId: String? = null
    private var commitGate = SensitivePhraseCommitGate()
    private val sessionInvalidationListener = ::onSessionInvalidated
    private var sessionListenerRegistered = false

    override val title: String by lazy { context.getString(R.string.secret_vault_title) }

    override fun onCreateView(): View {
        ui = SensitivePhraseUi(context, theme).apply {
            onUnlock = ::unlock
            onSelect = ::preview
            onInsert = ::insert
            onBack = { windowManager.attachWindow(KeyboardWindow) }
        }
        return ui.root
    }

    override fun onAttached() {
        if (!sessionListenerRegistered) {
            SensitivePhraseSession.addInvalidationListener(sessionInvalidationListener)
            sessionListenerRegistered = true
        }
        if (!service.allowsTextInspectionFeatures()) {
            pendingAuthResume = null
            SensitivePhraseSession.lock()
            ui.showError(context.getString(R.string.secret_vault_private_blocked))
            return
        }
        pendingAuthResume?.let { resume ->
            pendingAuthResume = null
            if (!service.matchesCurrentEditor(
                    editor.packageName,
                    editor.fieldId,
                    editor.inputType,
                    editor.selectionStart,
                    editor.selectionEnd
                )
            ) {
                SensitivePhraseSession.lock()
                ui.showError(context.getString(R.string.secret_vault_editor_changed))
                return
            }
            resume.vault?.let { vault ->
                SensitivePhraseSession.unlockFor(editor, vault)
                showVault()
            } ?: run {
                SensitivePhraseSession.lock()
                ui.showError(context.getString(R.string.secret_vault_auth_failed))
            }
            return
        }
        if (!service.matchesCurrentEditor(
                editor.packageName,
                editor.fieldId,
                editor.inputType,
                editor.selectionStart,
                editor.selectionEnd
            )
        ) {
            SensitivePhraseSession.lock()
            ui.showError(context.getString(R.string.secret_vault_editor_changed))
            return
        }
        SensitivePhraseSession.vaultFor(editor)?.let { showVault() }
            ?: ui.showLocked(SensitivePhraseAuthenticator.isAvailable(context))
    }

    override fun onDetached() {
        if (sessionListenerRegistered) {
            SensitivePhraseSession.removeInvalidationListener(sessionInvalidationListener)
            sessionListenerRegistered = false
        }
        pendingAuthResume = null
        selectedId = null
        if (::ui.isInitialized) ui.clearSensitiveContent()
    }

    private fun unlock() {
        if (!service.allowsTextInspectionFeatures() ||
            !service.matchesCurrentEditor(
                editor.packageName,
                editor.fieldId,
                editor.inputType,
                editor.selectionStart,
                editor.selectionEnd
            )
        ) {
            ui.showError(context.getString(R.string.secret_vault_editor_changed))
            return
        }
        val request = runCatching(store::beginVaultUnlock).getOrElse {
            ui.showError(context.getString(R.string.secret_vault_auth_unavailable))
            return
        }
        service.prepareForSettingsActivity()
        if (SensitivePhraseAuthCoordinator.request(context, editor, request) == null) {
            SensitivePhraseSession.lock()
            ui.showError(context.getString(R.string.secret_vault_auth_unavailable))
        }
    }

    private fun showVault() {
        val activeVault = SensitivePhraseSession.vaultFor(editor) ?: run {
            ui.showLocked(SensitivePhraseAuthenticator.isAvailable(context))
            return
        }
        val allowed = activeVault.items.filter { item ->
            SensitivePhrasePolicy.canExpose(
                item = item,
                packageName = editor.packageName,
                unlockedForPackage = true,
                privateEditor = !service.allowsTextInspectionFeatures()
            )
        }
        ui.showItems(allowed)
    }

    private fun preview(item: SensitivePhrase) {
        val activeItem = currentAllowedItem(item.id) ?: run {
            selectedId = null
            ui.showError(context.getString(R.string.secret_vault_editor_changed))
            return
        }
        selectedId = activeItem.id
        commitGate = SensitivePhraseCommitGate()
        ui.showPreview(activeItem)
    }

    private fun insert() {
        val id = selectedId ?: return
        val inserted = commitGate.commitOnce {
            val item = currentAllowedItem(id)
            if (item == null) {
                ui.showError(context.getString(R.string.secret_vault_editor_changed))
                return@commitOnce false
            }
            service.commitText(item.value, 1).also { committed ->
                if (!committed) {
                    ui.showError(context.getString(R.string.secret_vault_insert_failed))
                }
            }
        }
        if (inserted) windowManager.attachWindow(KeyboardWindow)
    }

    private fun currentAllowedItem(id: String): SensitivePhrase? {
        if (!service.allowsTextInspectionFeatures() || !service.matchesCurrentEditor(
                editor.packageName,
                editor.fieldId,
                editor.inputType,
                editor.selectionStart,
                editor.selectionEnd
            )
        ) {
            SensitivePhraseSession.lock()
            return null
        }
        return SensitivePhraseSession.vaultFor(editor)
            ?.items
            ?.firstOrNull { item ->
                item.id == id && SensitivePhrasePolicy.canExpose(
                    item = item,
                    packageName = editor.packageName,
                    unlockedForPackage = true,
                    privateEditor = false
                )
            }
    }

    private fun onSessionInvalidated() {
        if (!sessionListenerRegistered) return
        selectedId = null
        pendingAuthResume = null
        if (!::ui.isInitialized) return
        if (!service.allowsTextInspectionFeatures()) {
            ui.showError(context.getString(R.string.secret_vault_private_blocked))
        } else {
            ui.showLocked(SensitivePhraseAuthenticator.isAvailable(context))
        }
    }
}
