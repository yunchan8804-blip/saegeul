/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dynamicphrase

import android.os.CancellationSignal
import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.DynamicPhraseProfileStore
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.SensitivePhrase
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.SensitivePhraseCommitGate
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.SensitivePhrasePolicy
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.SensitivePhraseVault
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must

class SensitivePhraseWindow(
    private val editor: DynamicPhraseEditorTarget
) : InputWindow.ExtendedInputWindow<SensitivePhraseWindow>() {
    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()
    private val store by lazy { DynamicPhraseProfileStore(context) }
    private lateinit var ui: SensitivePhraseUi
    private var cancellation: CancellationSignal? = null
    private var selectedId: String? = null
    private var commitGate = SensitivePhraseCommitGate()

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
        if (!service.allowsTextInspectionFeatures()) {
            SensitivePhraseSession.lock()
            ui.showError(context.getString(R.string.secret_vault_private_blocked))
            return
        }
        SensitivePhraseSession.vaultFor(editor.packageName)?.let(::showVault)
            ?: ui.showLocked(SensitivePhraseAuthenticator.isAvailable(context))
    }

    override fun onDetached() {
        cancellation?.cancel()
        cancellation = null
        selectedId = null
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
        cancellation = SensitivePhraseAuthenticator.authenticate(
            context = context,
            cipher = request.cipher,
            title = context.getString(R.string.secret_vault_unlock),
            subtitle = context.getString(R.string.secret_vault_unlock_summary),
            onSuccess = { cipher ->
                runCatching { store.finishVaultUnlock(request, cipher) }
                    .onSuccess { vault ->
                        SensitivePhraseSession.unlockFor(editor.packageName, vault)
                        showVault(vault)
                    }
                    .onFailure {
                        SensitivePhraseSession.lock()
                        ui.showError(context.getString(R.string.secret_vault_decrypt_failed))
                    }
            },
            onError = {
                SensitivePhraseSession.lock()
                ui.showError(context.getString(R.string.secret_vault_auth_failed))
            }
        )
    }

    private fun showVault(vault: SensitivePhraseVault) {
        val allowed = vault.items.filter { item ->
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
        selectedId = item.id
        commitGate = SensitivePhraseCommitGate()
        ui.showPreview(item)
    }

    private fun insert() {
        val id = selectedId ?: return
        val inserted = commitGate.commitOnce {
            val privateEditor = !service.allowsTextInspectionFeatures()
            val item = SensitivePhraseSession.vaultFor(editor.packageName)
                ?.items?.firstOrNull { it.id == id }
            if (item == null || !SensitivePhrasePolicy.canExpose(
                    item,
                    editor.packageName,
                    unlockedForPackage = true,
                    privateEditor = privateEditor
                ) || !service.matchesCurrentEditor(
                    editor.packageName,
                    editor.fieldId,
                    editor.inputType,
                    editor.selectionStart,
                    editor.selectionEnd
                )
            ) {
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
}
