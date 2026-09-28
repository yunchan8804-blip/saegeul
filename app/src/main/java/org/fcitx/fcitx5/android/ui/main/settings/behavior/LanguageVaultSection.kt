/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaInstantSync
import org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaPersistenceException
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAiSupport
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelInstaller
import org.fcitx.fcitx5.android.ui.main.ai.TypingDnaDashboardActivity
import org.fcitx.fcitx5.android.ui.main.ai.install.GemmaInstallUiState
import org.fcitx.fcitx5.android.ui.main.ai.install.GemmaModelActivity
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference

/** Language vault category: the on-device model, automatic suggestions, and Typing DNA data. */
internal class LanguageVaultSection(
    private val host: Fragment,
    private val onDataChanged: () -> Unit
) {
    private lateinit var gemmaModelPreference: Preference
    private lateinit var notificationPermissionPreference: Preference
    private lateinit var typingDnaPreference: Preference
    private lateinit var typingDnaSyncPreference: Preference
    private lateinit var clearTypingDnaPreference: Preference
    private var typingDnaSyncJob: Job? = null

    fun addTo(screen: PreferenceScreen) {
        val ctx = host.requireContext()
        val prefs = AppPrefs.getInstance()
        screen.addCategory(R.string.privacy_ai_vault_category) {
            gemmaModelPreference = Preference(ctx).apply {
                setTitle(R.string.privacy_ai_gemma_model_title)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    ctx.startActivity(Intent(ctx, GemmaModelActivity::class.java))
                    true
                }
            }
            addPreference(gemmaModelPreference)
            addPreference(
                title = R.string.privacy_ai_vault_dashboard_title,
                summary = R.string.privacy_ai_vault_dashboard_summary,
                onClick = {
                    ctx.startActivity(Intent(ctx, TypingDnaDashboardActivity::class.java))
                }
            )
            addPreference(SwitchPreferenceCompat(ctx).apply {
                key = "automatic_ondevice_suggestions_opt_in"
                setTitle(R.string.gemma_automatic_enable)
                isPersistent = true
                setDefaultValue(true)
                isChecked = prefs.internal.automaticOnDeviceSuggestionsOptIn.getValue()
                if (OnDeviceAiSupport.isSupported) {
                    setSummary(R.string.gemma_automatic_enable_description)
                } else {
                    isEnabled = false
                    setSummary(R.string.privacy_ai_automatic_release_summary)
                }
            })
            addPreference(SwitchPreferenceCompat(ctx).apply {
                key = "automatic_ondevice_suggestions_use_gpu"
                setTitle(R.string.privacy_ai_gpu_acceleration_title)
                setSummary(R.string.privacy_ai_gpu_acceleration_summary)
                isPersistent = true
                setDefaultValue(true)
                isChecked = prefs.internal.automaticOnDeviceSuggestionsUseGpu.getValue()
                isEnabled = OnDeviceAiSupport.isSupported
            })
            addPreference(SwitchPreferenceCompat(ctx).apply {
                key = "background_progress_notifications"
                setTitle(R.string.privacy_ai_background_notifications_title)
                setSummary(R.string.privacy_ai_background_notifications_summary)
                isPersistent = true
                setDefaultValue(true)
                isChecked = prefs.internal.backgroundProgressNotifications.getValue()
            })
            addPreference(SwitchPreferenceCompat(ctx).apply {
                key = "collection_feedback_in_keyboard"
                setTitle(R.string.privacy_ai_collection_feedback_title)
                setSummary(R.string.privacy_ai_collection_feedback_summary)
                isPersistent = true
                setDefaultValue(true)
                isChecked = prefs.internal.collectionFeedbackInKeyboard.getValue()
            })
            notificationPermissionPreference = Preference(ctx).apply {
                setTitle(R.string.privacy_ai_open_notification_settings_title)
                setSummary(R.string.privacy_ai_open_notification_settings_summary)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    })
                    true
                }
            }
            addPreference(notificationPermissionPreference)
            typingDnaPreference = Preference(ctx).apply {
                title = ctx.getString(R.string.privacy_ai_typing_dna_report_title)
                icon = ctx.themedPreferenceIcon(R.drawable.ic_baseline_auto_awesome_24)
                isSelectable = false
            }
            addPreference(typingDnaPreference)
            typingDnaSyncPreference = Preference(ctx).apply {
                title = ctx.getString(R.string.privacy_ai_typing_dna_sync_title)
                summary = ctx.getString(R.string.privacy_ai_typing_dna_sync_summary)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    startTypingDnaSync()
                    true
                }
            }
            addPreference(typingDnaSyncPreference)
            clearTypingDnaPreference = Preference(ctx).apply {
                title = ctx.getString(R.string.privacy_ai_typing_dna_clear_title)
                summary = ctx.getString(R.string.privacy_ai_typing_dna_clear_summary)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    showClearTypingDnaDialog(ctx)
                    true
                }
            }
            addPreference(clearTypingDnaPreference)
            refreshSyncBusy()
            updateNotificationPermissionVisibility()
        }
    }

    /** Summary line: the shared install-status title, plus its detail (e.g. live download %) when there is one. */
    fun observeGemmaModelSummary() {
        val owner = host.viewLifecycleOwner
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                GemmaModelInstaller.state(host.requireContext().applicationContext)
                    .collect { state ->
                        val ctx = host.requireContext()
                        val uiState = GemmaInstallUiState.from(state)
                        val title = ctx.getString(uiState.titleRes)
                        val detail = uiState.detailRes?.let { ctx.getString(it, *uiState.detailArgs.toTypedArray()) }
                        gemmaModelPreference.summary = if (detail != null) "$title · $detail" else title
                    }
            }
        }
    }

    fun updateNotificationPermissionVisibility() {
        val ctx = host.requireContext()
        notificationPermissionPreference.isVisible =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Re-applies the sync controls' busy state, e.g. after the screen returns while a sync runs. */
    fun refreshSyncBusy() {
        setTypingDnaSyncBusy(typingDnaSyncJob?.isActive == true)
    }

    /** Runs off the main thread; reads the learned-data stats only. */
    fun loadSummary(ctx: Context): String {
        val typingDnaStats = FcitxApplication.getInstance().typingDnaRepository.getStats()
        return if (!typingDnaStats.hasLearnedData) {
            ctx.getString(R.string.privacy_ai_typing_dna_no_data_summary)
        } else {
            ctx.getString(
                R.string.privacy_ai_typing_dna_stats_summary,
                typingDnaStats.level,
                typingDnaStats.levelTitle,
                typingDnaStats.totalSentences,
                typingDnaStats.bigramsCount,
                typingDnaStats.endingsCount
            )
        }
    }

    fun applySummary(summary: String) {
        typingDnaPreference.summary = summary
    }

    fun showSummaryPlaceholder(summary: String) {
        typingDnaPreference.summary = summary
    }

    private fun showClearTypingDnaDialog(ctx: Context) {
        showDeleteConfirmation(
            ctx,
            R.string.privacy_ai_typing_dna_clear_dialog_title,
            R.string.privacy_ai_typing_dna_clear_dialog_message
        ) {
            val app = FcitxApplication.getInstance()
            app.typingDnaRepository.clear()
            val stagingPurged = try {
                app.typingDnaVault.purge()
                true
            } catch (_: TypingDnaPersistenceException) {
                false
            }
            app.personalNgramModel.clear()
            app.personalSentenceVault.clear()
            FcitxInputMethodService.activeInstance?.recentSentSentences?.clear()
            onDataChanged()
            val resultMessage = if (stagingPurged) {
                R.string.privacy_ai_typing_dna_cleared_toast
            } else {
                R.string.privacy_ai_typing_dna_clear_failed_toast
            }
            Toast.makeText(ctx, resultMessage, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startTypingDnaSync() {
        if (typingDnaSyncJob?.isActive == true) return
        val ctx = host.requireContext().applicationContext
        val app = FcitxApplication.getInstance()
        val ime = FcitxInputMethodService.activeInstance
        setTypingDnaSyncBusy(true)
        val syncJob = host.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                val totalSentences = if (ime != null) {
                    ime.triggerInstantTypingDnaSyncAsync()
                    withContext(Dispatchers.IO) {
                        app.typingDnaRepository.getSummary().totalSentences
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        TypingDnaInstantSync.persistOnly(
                            app.typingDnaVault,
                            app.typingDnaRepository,
                            sentenceStoreFile = java.io.File(ctx.filesDir, "personalized_sentences.json"),
                            cipher = app.vaultCipher
                        )
                        app.typingDnaRepository.getSummary().totalSentences
                    }
                }
                if (host.canUpdatePreferenceView()) {
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.privacy_ai_typing_dna_sync_done_toast, totalSentences),
                        Toast.LENGTH_SHORT
                    ).show()
                    onDataChanged()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w("SaegeulAI", "typing DNA sync failed: ${error.javaClass.simpleName}")
                if (host.canUpdatePreferenceView()) {
                    Toast.makeText(ctx, R.string.privacy_ai_typing_dna_sync_failed, Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (typingDnaSyncJob === coroutineContext[Job]) {
                    typingDnaSyncJob = null
                    if (host.canUpdatePreferenceView()) setTypingDnaSyncBusy(false)
                }
            }
        }
        typingDnaSyncJob = syncJob
        syncJob.start()
    }

    private fun setTypingDnaSyncBusy(busy: Boolean) {
        typingDnaSyncPreference.isEnabled = !busy
        clearTypingDnaPreference.isEnabled = !busy
        typingDnaSyncPreference.summary = host.getString(
            if (busy) R.string.privacy_ai_typing_dna_sync_busy
            else R.string.privacy_ai_typing_dna_sync_summary
        )
    }
}
