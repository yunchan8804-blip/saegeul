/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.backup.BackupSummary
import org.fcitx.fcitx5.android.data.backup.ExportResult
import org.fcitx.fcitx5.android.data.backup.ImportResult
import org.fcitx.fcitx5.android.data.backup.InspectResult
import org.fcitx.fcitx5.android.data.backup.VaultBackup
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.utils.AppUtil
import splitties.dimensions.dp
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * User-facing export/import screen for [VaultBackup]. Follows a "plain view, app theme,
 * no toolbar" convention rather than a dedicated theme/layout.
 */
class VaultBackupActivity : AppCompatActivity() {

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private lateinit var lastExportLabel: TextView
    private lateinit var exportButton: Button
    private lateinit var importButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var progressLabel: TextView

    private var pendingExportPassword: CharArray? = null
    private var pendingImportUri: Uri? = null
    private var pendingImportPassword: CharArray? = null

    private val createBackupDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val password = pendingExportPassword
        pendingExportPassword = null
        if (uri == null || password == null) return@registerForActivityResult
        runExport(uri, password)
    }

    private val openBackupDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        pendingImportUri = uri
        showImportPasswordDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.vault_backup_title)
        if ((applicationContext as? FcitxApplication)?.isDirectBootMode == true) {
            buildLockedContentView()
        } else {
            buildContentView()
        }
    }

    override fun onDestroy() {
        pendingExportPassword?.fill('\u0000')
        pendingImportPassword?.fill('\u0000')
        super.onDestroy()
    }

    private fun buildLockedContentView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
        }
        root.addView(TextView(this).apply {
            setText(R.string.vault_backup_locked_direct_boot)
            textSize = 16f
        })
        setContentView(root)
    }

    private fun buildContentView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
        }
        root.addView(TextView(this).apply {
            setText(R.string.vault_backup_title)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            setText(R.string.vault_backup_summary)
            textSize = 16f
            setPadding(0, dp(8), 0, dp(20))
        })

        exportButton = Button(this).apply {
            setText(R.string.vault_backup_export_button)
            setOnClickListener { showExportPasswordDialog() }
        }
        root.addView(exportButton)

        importButton = Button(this).apply {
            setText(R.string.vault_backup_import_button)
            setPadding(0, dp(8), 0, 0)
            setOnClickListener { openBackupDocument.launch(arrayOf("*/*")) }
        }
        root.addView(importButton)

        progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(
            progress,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
        )
        progressLabel = TextView(this).apply {
            visibility = View.GONE
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(progressLabel)

        lastExportLabel = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(20), 0, 0)
        }
        root.addView(lastExportLabel)
        refreshLastExportLabel()

        root.addView(TextView(this).apply {
            setText(R.string.vault_backup_cloud_coming_soon)
            textSize = 13f
            isEnabled = false
            alpha = 0.5f
            setPadding(0, dp(24), 0, 0)
        })

        setContentView(root)
    }

    private fun refreshLastExportLabel() {
        val lastExportAt = prefs.getLong(KEY_LAST_EXPORT_AT, 0L)
        lastExportLabel.text = if (lastExportAt <= 0L) {
            getString(R.string.vault_backup_last_export_none)
        } else {
            getString(R.string.vault_backup_last_export_at, formatTimestamp(lastExportAt))
        }
    }

    private fun formatTimestamp(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

    // --- Export ---

    private fun showExportPasswordDialog() {
        val passwordField = EditText(this).apply {
            hint = getString(R.string.vault_backup_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val confirmField = EditText(this).apply {
            hint = getString(R.string.vault_backup_password_confirm_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val warning = TextView(this).apply {
            setText(R.string.vault_backup_password_warning)
            setPadding(0, dp(8), 0, 0)
            alpha = 0.7f
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(passwordField)
            addView(confirmField)
            addView(warning)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.vault_backup_password_title)
            .setView(container)
            .setPositiveButton(R.string.vault_backup_confirm_button, null)
            .setNegativeButton(R.string.vault_backup_cancel_button, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = passwordField.text.toString()
                val confirm = confirmField.text.toString()
                when {
                    password.length < MIN_PASSWORD_LENGTH ->
                        passwordField.error = getString(R.string.vault_backup_password_too_short)
                    password != confirm ->
                        confirmField.error = getString(R.string.vault_backup_password_mismatch)
                    else -> {
                        pendingExportPassword = password.toCharArray()
                        passwordField.text.clear()
                        confirmField.text.clear()
                        dialog.dismiss()
                        val fileName = getString(
                            R.string.vault_backup_export_file_name,
                            SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
                        )
                        createBackupDocument.launch(fileName)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun runExport(uri: Uri, password: CharArray) {
        showProgress(R.string.vault_backup_export_in_progress)
        lifecycleScope.launch {
            val result = try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    VaultBackup.export(applicationContext, out, password)
                } ?: ExportResult.Failure("NO_OUTPUT_STREAM")
            } catch (e: Exception) {
                Timber.w(e, "Vault backup export failed")
                ExportResult.Failure("UNEXPECTED")
            }
            hideProgress()
            when (result) {
                is ExportResult.Success -> {
                    prefs.edit().putLong(KEY_LAST_EXPORT_AT, System.currentTimeMillis()).apply()
                    refreshLastExportLabel()
                    toast(getString(R.string.vault_backup_export_done, result.summary.personalSentenceCount))
                }
                is ExportResult.Failure -> toast(
                    if (result.code == "TOO_LARGE") {
                        getString(R.string.vault_backup_export_failed_too_large)
                    } else {
                        getString(R.string.vault_backup_export_failed)
                    }
                )
            }
        }
    }

    // --- Import ---

    private fun showImportPasswordDialog() {
        val passwordField = EditText(this).apply {
            hint = getString(R.string.vault_backup_import_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val inputContainer = FrameLayout(this).apply {
            setPadding(dp(20), 0, dp(20), 0)
            addView(
                passwordField,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.vault_backup_import_password_title)
            .setView(inputContainer)
            .setPositiveButton(R.string.vault_backup_confirm_button, null)
            .setNegativeButton(R.string.vault_backup_cancel_button, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = passwordField.text.toString().toCharArray()
                passwordField.text.clear()
                dialog.dismiss()
                inspectPendingImport(password)
            }
        }
        dialog.show()
    }

    private fun inspectPendingImport(password: CharArray) {
        val uri = pendingImportUri ?: return
        showProgress(R.string.vault_backup_checking)
        lifecycleScope.launch {
            val result = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    VaultBackup.inspect(input, password)
                } ?: InspectResult.NotABackup
            } catch (e: Exception) {
                Timber.w(e, "Vault backup inspect failed")
                InspectResult.Corrupted
            }
            hideProgress()
            when (result) {
                is InspectResult.Valid -> showImportConfirmation(result.summary)
                InspectResult.WrongPassword -> toast(getString(R.string.vault_backup_error_wrong_password))
                InspectResult.Corrupted -> toast(getString(R.string.vault_backup_error_corrupted))
                InspectResult.NewerVersion -> toast(getString(R.string.vault_backup_error_newer_version))
                InspectResult.NotABackup -> toast(getString(R.string.vault_backup_error_not_a_backup))
            }
        }
    }

    private fun showImportConfirmation(summary: BackupSummary) {
        val createdAt = formatTimestamp(summary.createdAtMs)
        val message = if (summary.skippedDerivedEntries.isEmpty()) {
            getString(
                R.string.vault_backup_import_summary,
                createdAt,
                summary.personalSentenceCount,
                summary.publicMaterialCount
            )
        } else {
            getString(
                R.string.vault_backup_import_summary_with_skipped,
                createdAt,
                summary.personalSentenceCount,
                summary.publicMaterialCount,
                summary.skippedDerivedEntries.size
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.vault_backup_import_button)
            .setMessage(message)
            .setPositiveButton(R.string.vault_backup_replace_button) { _, _ -> promptImportPasswordAgainAndRun() }
            .setNegativeButton(R.string.vault_backup_cancel_button) { _, _ -> pendingImportUri = null }
            .show()
    }

    /**
     * [VaultBackup.inspect] already consumed the input stream and the [CharArray] password it was
     * given (both are one-shot), so committing the import needs a fresh stream and password. The
     * inspect step above already told the user this is the right file/password; asking again here
     * (rather than caching the first password) keeps this activity from holding a decrypted-vault
     * password in memory any longer than each single use needs it for.
     */
    private fun promptImportPasswordAgainAndRun() {
        val passwordField = EditText(this).apply {
            hint = getString(R.string.vault_backup_import_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val inputContainer = FrameLayout(this).apply {
            setPadding(dp(20), 0, dp(20), 0)
            addView(
                passwordField,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.vault_backup_import_password_title)
            .setView(inputContainer)
            .setPositiveButton(R.string.vault_backup_replace_button, null)
            .setNegativeButton(R.string.vault_backup_cancel_button) { _, _ -> pendingImportUri = null }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = passwordField.text.toString().toCharArray()
                passwordField.text.clear()
                dialog.dismiss()
                runImport(password)
            }
        }
        dialog.show()
    }

    private fun runImport(password: CharArray) {
        val uri = pendingImportUri ?: return
        pendingImportUri = null
        showProgress(R.string.vault_backup_importing)
        lifecycleScope.launch {
            val result = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    VaultBackup.import(applicationContext, input, password)
                } ?: ImportResult.Failure("NO_INPUT_STREAM")
            } catch (e: Exception) {
                Timber.w(e, "Vault backup import failed")
                ImportResult.Failure("UNEXPECTED")
            }
            when (result) {
                is ImportResult.Success -> {
                    // Deliberately no hideProgress()/UI restore here: the process is about to be
                    // killed and relaunched (see scheduleRestartAndExit) so freshly-imported data
                    // replaces every in-memory store, including any already loaded by this process.
                    progressLabel.setText(R.string.vault_backup_import_done)
                    scheduleRestartAndExit()
                }
                is ImportResult.Failure -> {
                    hideProgress()
                    toast(getString(R.string.vault_backup_error_generic))
                }
                ImportResult.WrongPassword -> {
                    hideProgress()
                    toast(getString(R.string.vault_backup_error_wrong_password))
                }
                ImportResult.Corrupted -> {
                    hideProgress()
                    toast(getString(R.string.vault_backup_error_corrupted))
                }
                ImportResult.NewerVersion -> {
                    hideProgress()
                    toast(getString(R.string.vault_backup_error_newer_version))
                }
                ImportResult.NotABackup -> {
                    hideProgress()
                    toast(getString(R.string.vault_backup_error_not_a_backup))
                }
            }
        }
    }

    /**
     * Restarts the whole app process so every in-memory store (this process may already have
     * some loaded, e.g. from earlier keyboard use in this session) reloads from the files
     * [VaultBackup.import] just replaced, instead of a live singleton later flushing its stale
     * cached state back over them. Schedules the relaunch with [AlarmManager] (survives the
     * process dying) and then kills the process outright with [AppUtil.exit] — a hard
     * `exitProcess(0)`, not a graceful shutdown, so nothing gets a chance to write during exit.
     */
    private fun scheduleRestartAndExit() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            RESTART_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC,
            System.currentTimeMillis() + RESTART_DELAY_MS,
            pendingIntent
        )
        AppUtil.exit()
    }

    private fun showProgress(@StringRes labelRes: Int) {
        exportButton.isEnabled = false
        importButton.isEnabled = false
        progress.visibility = View.VISIBLE
        progressLabel.visibility = View.VISIBLE
        progressLabel.setText(labelRes)
    }

    private fun hideProgress() {
        exportButton.isEnabled = true
        importButton.isEnabled = true
        progress.visibility = View.GONE
        progressLabel.visibility = View.GONE
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val PREFS_NAME = "vault_backup"
        private const val KEY_LAST_EXPORT_AT = "last_export_at"
        private const val MIN_PASSWORD_LENGTH = 8
        private const val RESTART_REQUEST_CODE = 0x5647 // "VG"
        private const val RESTART_DELAY_MS = 700L
    }
}
