/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.install

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaInstallState
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelFiles
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelInstaller
import org.fcitx.fcitx5.android.utils.AppUtil

/**
 * The product "새글 AI" model-management screen: live install status (via the same
 * [GemmaInstallStatusView] the vault card and onboarding page use), storage/model/version info,
 * the Gemma terms and LiteRT-LM license, deleting an installed model, and importing one from a
 * file. Single implementation for every build variant - see [org.fcitx.fcitx5.android.ui.main.ai.GemmaModelManagementLauncher].
 */
class GemmaModelActivity : AppCompatActivity() {

    private lateinit var statusView: GemmaInstallStatusView
    private lateinit var deleteButton: MaterialButton
    private lateinit var advancedHeader: View
    private lateinit var advancedBody: View
    private lateinit var advancedToggle: TextView
    private lateinit var importStatus: TextView
    private var advancedExpanded = false

    private val openModelDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(::importModel) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gemma_model)

        findViewById<MaterialToolbar>(R.id.gemma_model_toolbar).setNavigationOnClickListener { finish() }

        statusView = GemmaInstallStatusView.bind(findViewById(R.id.gemma_model_status))
        statusView.onAction = { button -> GemmaInstallFlow.handle(this, button) }

        findViewById<TextView>(R.id.gemma_model_info_size_value).text =
            formatGemmaInstallBytes(GemmaModelFiles.MODEL_BYTES)
        findViewById<TextView>(R.id.gemma_model_info_version_value).text =
            GemmaModelFiles.MODEL_COMMIT.take(7)

        findViewById<View>(R.id.gemma_model_terms_row).setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(GEMMA_TERMS_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        findViewById<View>(R.id.gemma_model_license_row).setOnClickListener {
            AppUtil.launchMainToLicenseList(this)
        }

        deleteButton = findViewById(R.id.gemma_model_delete_button)
        deleteButton.setOnClickListener { confirmDelete() }

        advancedHeader = findViewById(R.id.gemma_model_advanced_header)
        advancedBody = findViewById(R.id.gemma_model_advanced_body)
        advancedToggle = findViewById(R.id.gemma_model_advanced_toggle)
        advancedHeader.setOnClickListener { toggleAdvanced() }
        importStatus = findViewById(R.id.gemma_model_import_status)
        findViewById<View>(R.id.gemma_model_import_button).setOnClickListener {
            openModelDocument.launch(arrayOf("*/*"))
        }

        observeInstallState()
    }

    private fun observeInstallState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                GemmaModelInstaller.state(applicationContext).collect { state ->
                    statusView.render(this@GemmaModelActivity, GemmaInstallUiState.from(state))
                    deleteButton.visibility = if (state is GemmaInstallState.Installed) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun toggleAdvanced() {
        advancedExpanded = !advancedExpanded
        advancedBody.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
        advancedToggle.setText(
            if (advancedExpanded) R.string.vault_dev_section_collapse else R.string.vault_dev_section_expand
        )
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.gemma_model_delete_dialog_title)
            .setMessage(R.string.gemma_model_delete_dialog_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                GemmaModelInstaller.deleteModel(applicationContext)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importModel(uri: Uri) {
        importStatus.visibility = View.VISIBLE
        importStatus.setText(R.string.gemma_model_import_progress)
        lifecycleScope.launch {
            val result = try {
                GemmaModelInstaller.importFrom(applicationContext, uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
            importStatus.visibility = View.GONE
            if (result.isSuccess) {
                Toast.makeText(this@GemmaModelActivity, R.string.gemma_model_import_succeeded, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@GemmaModelActivity, R.string.gemma_model_import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private companion object {
        const val GEMMA_TERMS_URL = "https://ai.google.dev/gemma/terms"
    }
}
