/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.dashboard

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.ai.DashboardSecurity

/** Storage card: where the data lives, on-demand detail, backup, and full reset. */
internal class StorageCardSection(
    private val activity: Activity,
    onClearConfirmed: () -> Unit
) {
    private val clearButton: MaterialButton = activity.findViewById(R.id.btn_clear_dna)

    /** The last key-storage detail read with the dashboard snapshot, shown in the info dialog. */
    var security: DashboardSecurity? = null

    init {
        activity.findViewById<ImageButton>(R.id.btn_storage_info).setOnClickListener { showStorageInfoDialog() }
        activity.findViewById<View>(R.id.row_storage_backup).setOnClickListener { openVaultBackup() }
        clearButton.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle(R.string.vault_storage_clear_button)
                .setMessage(R.string.vault_storage_clear_dialog_message)
                .setPositiveButton(R.string.delete) { _, _ ->
                    onClearConfirmed()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    fun setClearEnabled(enabled: Boolean) {
        clearButton.isEnabled = enabled
    }

    private fun showStorageInfoDialog() {
        val security = security
        val keyText = when {
            security == null -> null
            security.isStrongBoxBacked -> activity.getString(R.string.vault_storage_dialog_key_strongbox)
            security.isHardwareBacked -> activity.getString(R.string.vault_storage_dialog_key_tee)
            else -> activity.getString(R.string.vault_storage_dialog_key_software)
        }
        val message = listOfNotNull(keyText, activity.getString(R.string.vault_storage_dialog_body)).joinToString("\n\n")
        AlertDialog.Builder(activity)
            .setTitle(R.string.vault_storage_dialog_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openVaultBackup() {
        val intent = Intent().setClassName(
            activity.packageName, "org.fcitx.fcitx5.android.ui.main.ai.backup.VaultBackupActivity"
        )
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // 백업 화면이 없는 빌드(일부 플레이버)에서는 안내만 하고 조용히 넘어간다.
            Toast.makeText(activity, R.string.vault_storage_backup_unavailable, Toast.LENGTH_SHORT).show()
        }
    }
}
