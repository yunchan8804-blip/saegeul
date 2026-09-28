/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.gif.GifProviderCredentialState
import org.fcitx.fcitx5.android.input.gif.GifProviderCredentialStore
import org.fcitx.fcitx5.android.input.gif.GifProviderResolver
import org.fcitx.fcitx5.android.input.gif.GifProviderSelection
import org.fcitx.fcitx5.android.input.gif.GifProviderSelectionStore
import org.fcitx.fcitx5.android.input.gif.GiphyCredentialState
import org.fcitx.fcitx5.android.input.gif.GiphyCustomerIdStore
import org.fcitx.fcitx5.android.input.gif.GiphyProviderConfiguration
import org.fcitx.fcitx5.android.input.gif.GiphyProviderCredentialStore
import org.fcitx.fcitx5.android.utils.addCategory

/** GIF provider category: the explicit source choice and the optional KLIPY and GIPHY keys. */
internal class GifProviderSection(
    private val host: Fragment,
    private val onDataChanged: () -> Unit
) {
    data class Summary(
        val selection: String,
        val klipy: String,
        val clearKlipyVisible: Boolean,
        val giphy: String,
        val clearGiphyVisible: Boolean
    )

    private lateinit var gifSelectionPreference: Preference
    private lateinit var gifProviderPreference: Preference
    private lateinit var clearGifProviderPreference: Preference
    private lateinit var giphyProviderPreference: Preference
    private lateinit var clearGiphyProviderPreference: Preference

    fun addTo(screen: PreferenceScreen) {
        val ctx = host.requireContext()
        screen.addCategory(R.string.gif_provider_settings) {
            gifSelectionPreference = Preference(ctx).apply {
                setTitle(R.string.gif_provider_selection_title)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    showSelectionDialog()
                    true
                }
            }
            addPreference(gifSelectionPreference)
            gifProviderPreference = Preference(ctx).apply {
                setTitle(R.string.gif_klipy_settings)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    showKlipyDialog()
                    true
                }
            }
            addPreference(gifProviderPreference)
            clearGifProviderPreference = Preference(ctx).apply {
                setTitle(R.string.gif_provider_key_remove)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    showRemoveKlipyDialog()
                    true
                }
            }
            addPreference(clearGifProviderPreference)
            giphyProviderPreference = Preference(ctx).apply {
                setTitle(R.string.gif_giphy_settings)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    showGiphyDialog()
                    true
                }
            }
            addPreference(giphyProviderPreference)
            clearGiphyProviderPreference = Preference(ctx).apply {
                setTitle(R.string.gif_giphy_key_remove)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    showRemoveGiphyDialog()
                    true
                }
            }
            addPreference(clearGiphyProviderPreference)
        }
    }

    /** Runs off the main thread; decrypts the stored keys to describe them. */
    fun loadSummary(ctx: Context): Summary {
        val gifProvider = GifProviderResolver.resolve(ctx)
        val gifSelectionSummary = when (gifProvider.selection) {
            GifProviderSelection.Standard -> ctx.getString(R.string.gif_provider_selection_standard)
            GifProviderSelection.Commons -> ctx.getString(R.string.gif_provider_selection_commons)
            GifProviderSelection.Giphy -> ctx.getString(R.string.gif_provider_selection_giphy)
        }
        val gifProviderSummary = when {
            gifProvider.credentialState == GifProviderCredentialState.Unreadable -> {
                ctx.getString(R.string.gif_provider_status_unreadable)
            }
            gifProvider.credentialState == GifProviderCredentialState.Configured -> {
                ctx.getString(R.string.gif_provider_status_klipy)
            }
            else -> ctx.getString(R.string.gif_provider_status_noto)
        }
        val giphyProviderSummary = when (gifProvider.giphyCredentialState) {
            GiphyCredentialState.Missing -> ctx.getString(R.string.gif_giphy_status_missing)
            GiphyCredentialState.KeyOnly -> ctx.getString(R.string.gif_giphy_status_key_only)
            GiphyCredentialState.Unreadable -> ctx.getString(R.string.gif_giphy_status_unreadable)
            GiphyCredentialState.Ready -> ctx.getString(
                if (gifProvider.giphyMediaCachingApproved) {
                    R.string.gif_giphy_status_ready_attach
                } else {
                    R.string.gif_giphy_status_ready_link_only
                }
            )
        }
        return Summary(
            selection = gifSelectionSummary,
            klipy = gifProviderSummary,
            clearKlipyVisible = gifProvider.credentialState != GifProviderCredentialState.Missing,
            giphy = giphyProviderSummary,
            clearGiphyVisible = gifProvider.giphyCredentialState != GiphyCredentialState.Missing
        )
    }

    fun applySummary(summary: Summary) {
        gifSelectionPreference.summary = summary.selection
        gifProviderPreference.summary = summary.klipy
        clearGifProviderPreference.isVisible = summary.clearKlipyVisible
        giphyProviderPreference.summary = summary.giphy
        clearGiphyProviderPreference.isVisible = summary.clearGiphyVisible
    }

    fun showSummaryPlaceholder(summary: String) {
        gifSelectionPreference.summary = summary
        gifProviderPreference.summary = summary
        giphyProviderPreference.summary = summary
    }

    private fun showSelectionDialog() {
        val ctx = host.requireContext()
        val store = GifProviderSelectionStore(ctx)
        val values = GifProviderSelection.entries
        val labels = values.map { selection ->
            when (selection) {
                GifProviderSelection.Standard -> ctx.getString(R.string.gif_provider_selection_standard)
                GifProviderSelection.Commons -> ctx.getString(R.string.gif_provider_selection_commons)
                GifProviderSelection.Giphy -> ctx.getString(R.string.gif_provider_selection_giphy)
            }
        }.toTypedArray()
        var selected = values.indexOf(store.load()).coerceAtLeast(0)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.gif_provider_selection_title)
            .setSingleChoiceItems(labels, selected) { _, index -> selected = index }
            .setPositiveButton(R.string.save) { _, _ ->
                runCatching { store.save(values[selected]) }
                    .onSuccess { onDataChanged() }
                    .onFailure {
                        Toast.makeText(ctx, R.string.gif_provider_selection_failed, Toast.LENGTH_SHORT)
                            .show()
                    }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showKlipyDialog() {
        val ctx = host.requireContext()
        val store = GifProviderCredentialStore(ctx)
        val configured = store.state() == GifProviderCredentialState.Configured
        CredentialInputDialog.show(
            context = ctx,
            title = R.string.gif_klipy_settings,
            securityNote = R.string.gif_provider_security_note,
            field = CredentialFieldSpec(
                hint = R.string.gif_provider_key_hint,
                unchangedHint = R.string.gif_provider_key_unchanged_hint,
                configured = configured
            )
        ) { input ->
            val key = input.enteredKey
            if (key.isEmpty()) {
                if (configured) {
                    input.dismiss()
                } else {
                    input.showKeyError(ctx.getString(R.string.gif_provider_key_required))
                }
                return@show
            }
            runCatching { store.saveKey(key) }
                .onSuccess {
                    input.finish()
                    onDataChanged()
                    Toast.makeText(ctx, R.string.gif_provider_key_saved, Toast.LENGTH_SHORT)
                        .show()
                }
                .onFailure {
                    input.showKeyError(ctx.getString(R.string.gif_provider_key_invalid))
                }
        }
    }

    private fun showGiphyDialog() {
        val ctx = host.requireContext()
        val store = GiphyProviderCredentialStore(ctx)
        val configured = store.load()
        val productionApproved = CheckBox(ctx).apply {
            setText(R.string.gif_giphy_production_approval_confirmation)
            isChecked = configured?.productionApproved == true
        }
        val mediaCachingApproved = CheckBox(ctx).apply {
            setText(R.string.gif_giphy_media_approval_confirmation)
            isChecked = configured?.mediaCachingApproved == true
        }
        CredentialInputDialog.show(
            context = ctx,
            title = R.string.gif_giphy_settings,
            securityNote = R.string.gif_giphy_security_note,
            field = CredentialFieldSpec(
                hint = R.string.gif_giphy_key_hint,
                unchangedHint = R.string.gif_giphy_key_unchanged_hint,
                configured = configured != null
            ),
            extraViews = listOf(productionApproved, mediaCachingApproved)
        ) { input ->
            val key = input.enteredKey.ifEmpty { configured?.apiKey.orEmpty() }
            if (key.isEmpty()) {
                input.showKeyError(ctx.getString(R.string.gif_giphy_key_required))
                return@show
            }
            if (mediaCachingApproved.isChecked && !productionApproved.isChecked) {
                mediaCachingApproved.error = ctx.getString(R.string.gif_giphy_media_requires_production)
                return@show
            }
            runCatching {
                store.save(
                    GiphyProviderConfiguration(
                        apiKey = key,
                        productionApproved = productionApproved.isChecked,
                        mediaCachingApproved = mediaCachingApproved.isChecked
                    )
                )
            }.onSuccess {
                input.finish()
                onDataChanged()
                Toast.makeText(ctx, R.string.gif_giphy_key_saved, Toast.LENGTH_SHORT).show()
            }.onFailure {
                input.showKeyError(ctx.getString(R.string.gif_giphy_key_invalid))
            }
        }
    }

    private fun showRemoveKlipyDialog() {
        val ctx = host.requireContext()
        showDeleteConfirmation(
            ctx,
            R.string.gif_provider_key_remove,
            R.string.gif_provider_key_remove_confirm
        ) {
            GifProviderCredentialStore(ctx).clear()
            onDataChanged()
            Toast.makeText(ctx, R.string.gif_provider_key_removed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRemoveGiphyDialog() {
        val ctx = host.requireContext()
        showDeleteConfirmation(
            ctx,
            R.string.gif_giphy_key_remove,
            R.string.gif_giphy_key_remove_confirm
        ) {
            GiphyProviderCredentialStore(ctx).clear()
            GiphyCustomerIdStore(ctx).clear()
            onDataChanged()
            Toast.makeText(ctx, R.string.gif_giphy_key_removed, Toast.LENGTH_SHORT).show()
        }
    }
}
