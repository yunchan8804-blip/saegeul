/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.text.InputType
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.DynamicPhraseProfileStore
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.SensitivePhrase
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.SensitivePhraseKind
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.SensitivePhraseVault
import org.fcitx.fcitx5.android.input.dynamicphrase.SensitivePhraseAuthenticator
import splitties.dimensions.dp

class SensitivePhraseVaultSettings(private val fragment: Fragment) {
    private val context get() = fragment.requireContext()
    private val store by lazy { DynamicPhraseProfileStore(context) }

    fun open() {
        val request = runCatching(store::beginVaultUnlock).getOrElse {
            showToast(R.string.secret_vault_auth_unavailable)
            return
        }
        SensitivePhraseAuthenticator.authenticate(
            context = context,
            cipher = request.cipher,
            title = context.getString(R.string.secret_vault_unlock),
            subtitle = context.getString(R.string.secret_vault_unlock_settings_summary),
            onSuccess = { cipher ->
                runCatching { store.finishVaultUnlock(request, cipher) }
                    .onSuccess(::showList)
                    .onFailure { showToast(R.string.secret_vault_decrypt_failed) }
            },
            onError = { showToast(R.string.secret_vault_auth_failed) }
        )
    }

    private fun showList(vault: SensitivePhraseVault) {
        val labels = vault.items.map { item ->
            "${item.label} · ${kindLabel(item.kind)}"
        }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.secret_vault_title)
            .setMessage(
                if (vault.items.isEmpty()) R.string.secret_vault_settings_empty
                else R.string.secret_vault_settings_summary
            )
            .setItems(labels) { _, index -> showItemEditor(vault, vault.items[index]) }
            .setPositiveButton(R.string.secret_vault_add) { _, _ -> showItemEditor(vault, null) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showItemEditor(vault: SensitivePhraseVault, existing: SensitivePhrase?) {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = context.dp(20)
            setPadding(horizontal, context.dp(8), horizontal, context.dp(8))
        }
        fun label(text: Int) {
            content.addView(TextView(context).apply { setText(text) }, matchWrapParams())
        }

        label(R.string.secret_vault_kind)
        val kinds = SensitivePhraseKind.entries
        val kindSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                kinds.map(::kindLabel)
            )
            setSelection(kinds.indexOf(existing?.kind ?: SensitivePhraseKind.Account))
        }
        content.addView(kindSpinner, matchWrapParams())

        val labelField = EditText(context).apply {
            setHint(R.string.secret_vault_label_hint)
            setText(existing?.label.orEmpty())
            maxLines = 1
        }
        content.addView(labelField, matchWrapParams())
        val valueField = EditText(context).apply {
            setHint(R.string.secret_vault_value_hint)
            setText(existing?.value.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            minLines = 2
            maxLines = 5
        }
        content.addView(valueField, matchWrapParams())
        val packagesField = EditText(context).apply {
            setHint(R.string.secret_vault_packages_hint)
            setText(existing?.allowedPackages?.joinToString("\n").orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            minLines = 2
            maxLines = 5
        }
        content.addView(packagesField, matchWrapParams())

        val dialog = AlertDialog.Builder(context)
            .setTitle(
                if (existing == null) R.string.secret_vault_add
                else R.string.secret_vault_edit
            )
            .setMessage(R.string.secret_vault_item_security)
            .setView(content)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .apply {
                if (existing != null) setNeutralButton(R.string.delete, null)
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val packages = packagesField.text.toString()
                    .split(Regex("[,\\s]+"))
                    .filter(String::isNotBlank)
                    .toSet()
                val item = SensitivePhrase(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    label = labelField.text.toString(),
                    value = valueField.text.toString(),
                    kind = kinds[kindSpinner.selectedItemPosition],
                    allowedPackages = packages
                )
                runCatching { vault.upsert(item) }
                    .onSuccess { updated ->
                        dialog.dismiss()
                        authenticateWrite(updated)
                    }
                    .onFailure {
                        packagesField.error = context.getString(R.string.secret_vault_invalid_item)
                    }
            }
            existing?.let { item ->
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    dialog.dismiss()
                    authenticateWrite(vault.remove(item.id))
                }
            }
        }
        dialog.show()
    }

    private fun authenticateWrite(vault: SensitivePhraseVault) {
        val request = runCatching(store::beginVaultWrite).getOrElse {
            showToast(R.string.secret_vault_auth_unavailable)
            return
        }
        SensitivePhraseAuthenticator.authenticate(
            context = context,
            cipher = request.cipher,
            title = context.getString(R.string.secret_vault_confirm_change),
            subtitle = context.getString(R.string.secret_vault_confirm_change_summary),
            onSuccess = { authenticatedCipher ->
                runCatching {
                    store.finishVaultWrite(vault, request, authenticatedCipher)
                }
                    .onSuccess {
                        showToast(R.string.secret_vault_saved)
                        showList(vault)
                    }
                    .onFailure { showToast(R.string.secret_vault_save_failed) }
            },
            onError = { showToast(R.string.secret_vault_auth_failed) }
        )
    }

    private fun kindLabel(kind: SensitivePhraseKind): String = context.getString(
        when (kind) {
            SensitivePhraseKind.Account -> R.string.secret_vault_kind_account
            SensitivePhraseKind.Address -> R.string.secret_vault_kind_address
            SensitivePhraseKind.Contact -> R.string.secret_vault_kind_contact
            SensitivePhraseKind.Other -> R.string.secret_vault_kind_other
        }
    )

    private fun showToast(text: Int) =
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    private fun matchWrapParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
