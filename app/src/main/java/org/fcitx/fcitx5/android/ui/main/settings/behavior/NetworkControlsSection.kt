/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.text.format.Formatter
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.addCategory

/** Network controls category: the offline switch and optional sentence pack downloads. */
internal class NetworkControlsSection(private val host: Fragment) {
    private lateinit var offlineModeSwitch: SwitchPreferenceCompat
    private lateinit var sentencePackPreference: Preference

    fun addTo(screen: PreferenceScreen) {
        val ctx = host.requireContext()
        val prefs = AppPrefs.getInstance()
        screen.addCategory(R.string.privacy_network_controls) {
            offlineModeSwitch = SwitchPreferenceCompat(ctx).apply {
                key = "privacy_offline_mode"
                setTitle(R.string.offline_mode)
                setSummary(R.string.offline_mode_summary)
                isPersistent = false
                isChecked = prefs.advanced.offlineMode.getValue()
                setOnPreferenceChangeListener { _, value ->
                    val offline = value as Boolean
                    prefs.advanced.offlineMode.setValue(offline)
                    if (offline) {
                        FcitxApplication.getInstance().sentencePacks.cancelDownload()
                    }
                    true
                }
            }
            addPreference(offlineModeSwitch)
            sentencePackPreference = Preference(ctx).apply {
                setTitle(R.string.sentence_packs_title)
                setSummary(R.string.sentence_packs_default_summary)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    SentencePackDialog.show(
                        context = ctx,
                        lifecycleOwner = host.viewLifecycleOwner,
                        repository = FcitxApplication.getInstance().sentencePacks,
                        isOfflineMode = { prefs.advanced.offlineMode.getValue() }
                    )
                    true
                }
            }
            addPreference(sentencePackPreference)
        }
    }

    /** Mirrors an offline-mode change made on another screen. */
    fun showOfflineMode(offline: Boolean) {
        offlineModeSwitch.isChecked = offline
    }

    fun observeSentencePackSummary() {
        val app = FcitxApplication.getInstance()
        app.sentencePacks.prepare()
        val owner = host.viewLifecycleOwner
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.sentencePacks.status.collect { status ->
                    val ctx = host.requireContext()
                    sentencePackPreference.summary = when {
                        status.isDownloading -> ctx.getString(
                            R.string.sentence_packs_downloading_summary,
                            ctx.getString(
                                R.string.sentence_packs_progress,
                                Formatter.formatFileSize(ctx, status.downloadedBytes),
                                Formatter.formatFileSize(ctx, status.totalBytes.coerceAtLeast(1L))
                            )
                        )
                        status.installedCount > 0 -> ctx.getString(
                            R.string.sentence_packs_installed_summary,
                            status.builtinCount,
                            status.installedCount
                        )
                        status.error != null -> ctx.getString(R.string.sentence_packs_failed_summary)
                        else -> ctx.getString(R.string.sentence_packs_default_summary)
                    }
                }
            }
        }
    }
}
