/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.MainActivity
import splitties.resources.styledColor

/** User-visible controls for network input, BYOK credentials, and local traces. */
class PrivacyAiSettingsFragment : PaddingPreferenceFragment() {
    private lateinit var languageVault: LanguageVaultSection
    private lateinit var networkControls: NetworkControlsSection
    private lateinit var voiceProvider: VoiceProviderSection
    private lateinit var gifProvider: GifProviderSection

    private var summaryRefreshJob: Job? = null
    private var summaryGeneration = 0L
    private var hasSummarySnapshot = false
    private var privacySettingsResumed = false

    // sync the offline-mode switch when `advanced.offlineMode` changes from another screen
    // (e.g. Advanced Settings) while this fragment isn't the visible one.
    @androidx.annotation.Keep
    private val offlineModeChangeListener =
        ManagedPreference.OnChangeListener<Boolean> { _, v ->
            if (privacySettingsResumed) return@OnChangeListener
            if (::networkControls.isInitialized) networkControls.showOfflineMode(v)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.getInstance().advanced.offlineMode.registerOnChangeListener(offlineModeChangeListener)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        languageVault = LanguageVaultSection(this, ::refreshSummaries)
        networkControls = NetworkControlsSection(this)
        voiceProvider = VoiceProviderSection(this, ::refreshSummaries)
        gifProvider = GifProviderSection(this, ::refreshSummaries)
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            languageVault.addTo(this)
            networkControls.addTo(this)
            voiceProvider.addTo(this)
            gifProvider.addTo(this)
            LocalDataSection.addTo(this)
            PrivacyGuaranteesSection.addTo(this)
        }
        refreshSummaries()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        networkControls.observeSentencePackSummary()
        languageVault.observeGemmaModelSummary()
    }

    override fun onResume() {
        super.onResume()
        privacySettingsResumed = true
        if (::languageVault.isInitialized) {
            languageVault.refreshSyncBusy()
            refreshSummaries()
            languageVault.updateNotificationPermissionVisibility()
        }
        val intent = requireActivity().intent
        val action = intent.getStringExtra(MainActivity.EXTRA_PRIVACY_AI_ACTION)
        if (action != MainActivity.PRIVACY_AI_ACTION_VOICE_SETUP) return
        intent.removeExtra(MainActivity.EXTRA_PRIVACY_AI_ACTION)
        view?.post {
            if (!isAdded) return@post
            voiceProvider.showProviderDialog()
        }
    }

    override fun onPause() {
        super.onPause()
        privacySettingsResumed = false
    }

    override fun onDestroyView() {
        summaryGeneration++
        summaryRefreshJob?.cancel()
        super.onDestroyView()
    }

    override fun onDestroy() {
        AppPrefs.getInstance().advanced.offlineMode.unregisterOnChangeListener(offlineModeChangeListener)
        super.onDestroy()
    }

    private fun refreshSummaries() {
        if (!::voiceProvider.isInitialized) return
        val configuration = android.content.res.Configuration(requireContext().resources.configuration)
        val ctx = requireContext().applicationContext.createConfigurationContext(configuration)
        val generation = ++summaryGeneration
        summaryRefreshJob?.cancel()
        if (!hasSummarySnapshot && view != null) {
            showSummaryPlaceholder(getString(R.string.privacy_ai_summary_loading))
        }
        summaryRefreshJob = lifecycleScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { createSummarySnapshot(ctx) }
                if (!canApplySummarySnapshot(generation)) return@launch
                applySummarySnapshot(snapshot)
                hasSummarySnapshot = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w("SaegeulAI", "privacy AI summaries failed: ${error.javaClass.simpleName}")
                if (canApplySummarySnapshot(generation) && !hasSummarySnapshot) {
                    showSummaryPlaceholder(getString(R.string.privacy_ai_summary_load_failed))
                }
            }
        }
    }

    private fun createSummarySnapshot(ctx: Context) = PrivacyAiSummarySnapshot(
        voice = voiceProvider.loadSummary(ctx),
        gif = gifProvider.loadSummary(ctx),
        typingDna = languageVault.loadSummary(ctx)
    )

    private fun applySummarySnapshot(snapshot: PrivacyAiSummarySnapshot) {
        voiceProvider.applySummary(snapshot.voice)
        gifProvider.applySummary(snapshot.gif)
        languageVault.applySummary(snapshot.typingDna)
    }

    /** Shows the loading or failure line in every summary that waits on the snapshot. */
    private fun showSummaryPlaceholder(summary: String) {
        voiceProvider.showSummaryPlaceholder(summary)
        gifProvider.showSummaryPlaceholder(summary)
        languageVault.showSummaryPlaceholder(summary)
    }

    private fun canApplySummarySnapshot(generation: Long): Boolean =
        generation == summaryGeneration && canUpdatePreferenceView()

    private data class PrivacyAiSummarySnapshot(
        val voice: VoiceProviderSection.Summary,
        val gif: GifProviderSection.Summary,
        val typingDna: String
    )
}

internal fun Fragment.canUpdatePreferenceView(): Boolean = isAdded && view != null

/** Preference icon tinted like the surrounding controls; mutated so the tint stays local. */
internal fun Context.themedPreferenceIcon(@DrawableRes resource: Int): Drawable? =
    AppCompatResources.getDrawable(this, resource)?.mutate()?.apply {
        setTint(styledColor(android.R.attr.colorControlNormal))
    }
