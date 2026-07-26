/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.ai.AiProviderCredentialStore
import org.fcitx.fcitx5.android.input.ai.EffectiveAiProfile
import org.fcitx.fcitx5.android.input.ai.AiProviderKind
import org.fcitx.fcitx5.android.input.ai.AiProviderProfile
import org.fcitx.fcitx5.android.input.ai.AiProviderResolver
import org.fcitx.fcitx5.android.input.ai.AiUsageStore
import org.fcitx.fcitx5.android.input.gif.GifCache
import org.fcitx.fcitx5.android.input.gif.GifProviderCredentialState
import org.fcitx.fcitx5.android.input.gif.GifProviderCredentialStore
import org.fcitx.fcitx5.android.input.gif.GifProviderKind
import org.fcitx.fcitx5.android.input.gif.GifProviderResolver
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import splitties.dimensions.dp

/** User-visible controls for network input, BYOK credentials, and local traces. */
class PrivacyAiSettingsFragment : PaddingPreferenceFragment() {
    private lateinit var providerPreference: Preference
    private lateinit var usagePreference: Preference
    private lateinit var gifProviderPreference: Preference
    private lateinit var clearGifProviderPreference: Preference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        val prefs = AppPrefs.getInstance()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addCategory(R.string.privacy_network_controls) {
                addPreference(SwitchPreferenceCompat(ctx).apply {
                    key = "privacy_offline_mode"
                    setTitle(R.string.offline_mode)
                    setSummary(R.string.offline_mode_summary)
                    isPersistent = false
                    isChecked = prefs.advanced.offlineMode.getValue()
                    setOnPreferenceChangeListener { _, value ->
                        prefs.advanced.offlineMode.setValue(value as Boolean)
                        true
                    }
                })
            }
            addCategory(R.string.ai_provider_settings) {
                providerPreference = Preference(ctx).apply {
                    setTitle(R.string.ai_provider_settings)
                    setIcon(R.drawable.ic_baseline_auto_awesome_24)
                    setOnPreferenceClickListener {
                        showProviderDialog()
                        true
                    }
                }
                addPreference(providerPreference)
                addPreference(R.string.ai_clear_custom_provider, onClick = {
                    AiProviderCredentialStore(ctx).clear()
                    refreshSummaries()
                })
            }
            addCategory(R.string.gif_provider_settings) {
                gifProviderPreference = Preference(ctx).apply {
                    setTitle(R.string.gif_provider_settings)
                    setOnPreferenceClickListener {
                        showGifProviderDialog()
                        true
                    }
                }
                addPreference(gifProviderPreference)
                clearGifProviderPreference = Preference(ctx).apply {
                    setTitle(R.string.gif_provider_key_remove)
                    setOnPreferenceClickListener {
                        showRemoveGifProviderDialog()
                        true
                    }
                }
                addPreference(clearGifProviderPreference)
            }
            addCategory(R.string.privacy_local_data) {
                usagePreference = Preference(ctx).apply {
                    setTitle(R.string.ai_usage_title)
                    isSelectable = false
                }
                addPreference(usagePreference)
                addPreference(R.string.ai_usage_clear, onClick = {
                    AiUsageStore(ctx).clear()
                    refreshSummaries()
                })
                addPreference(R.string.gif_cache_clear, onClick = {
                    GifCache(ctx).clear()
                    Toast.makeText(ctx, R.string.gif_cache_cleared, Toast.LENGTH_SHORT).show()
                })
            }
            addCategory(R.string.privacy_guarantees) {
                addPreference(
                    R.string.privacy_guarantees,
                    R.string.privacy_guarantees_summary
                )
            }
        }
        refreshSummaries()
    }

    override fun onResume() {
        super.onResume()
        if (::providerPreference.isInitialized) refreshSummaries()
    }

    private fun refreshSummaries() {
        val ctx = requireContext()
        val effective = AiProviderResolver.resolve(ctx)
        providerPreference.summary = effective.profile?.let { profile ->
            val source = when (effective.source) {
                EffectiveAiProfile.Source.Custom -> getString(R.string.ai_provider_source_custom)
                EffectiveAiProfile.Source.BundledDebug -> getString(R.string.ai_provider_source_bundled)
                EffectiveAiProfile.Source.Missing -> getString(R.string.ai_provider_source_missing)
            }
            getString(
                R.string.ai_provider_configured_summary,
                profile.displayName,
                profile.baseUrl,
                source
            )
        } ?: getString(R.string.ai_not_configured)
        val usage = AiUsageStore(ctx).snapshot()
        usagePreference.summary = getString(
            R.string.ai_usage_summary,
            usage.totalRequests,
            usage.successfulRequests,
            usage.failedRequests,
            usage.inputCharacters
        )
        val gifProvider = GifProviderResolver.resolve(ctx)
        gifProviderPreference.summary = when {
            gifProvider.credentialState == GifProviderCredentialState.Unreadable -> {
                getString(R.string.gif_provider_status_unreadable)
            }
            gifProvider.kind == GifProviderKind.Klipy -> {
                getString(R.string.gif_provider_status_klipy)
            }
            else -> getString(R.string.gif_provider_status_noto)
        }
        clearGifProviderPreference.isEnabled =
            gifProvider.credentialState != GifProviderCredentialState.Missing
    }

    private fun showProviderDialog() {
        val ctx = requireContext()
        val store = AiProviderCredentialStore(ctx)
        val custom = store.load()
        val effective = custom ?: AiProviderResolver.resolve(ctx).profile
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = ctx.dp(20)
            setPadding(horizontal, ctx.dp(8), horizontal, 0)
        }
        fun field(hint: Int, value: String, type: Int = InputType.TYPE_CLASS_TEXT) =
            EditText(ctx).apply {
                setHint(hint)
                setText(value)
                inputType = type
                maxLines = 1
                container.addView(
                    this,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }

        val name = field(R.string.ai_provider_name_hint, effective?.displayName.orEmpty())
        val baseUrl = field(
            R.string.ai_provider_url_hint,
            effective?.baseUrl ?: AiProviderProfile.OPENAI_BASE_URL,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        val apiKey = field(
            R.string.ai_provider_key_hint,
            "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        ).apply {
            hint = if (custom == null) {
                getString(R.string.ai_provider_key_hint)
            } else {
                getString(R.string.ai_provider_key_unchanged_hint)
            }
        }
        val fast = field(R.string.ai_fast_model_hint, effective?.fastModel ?: "gpt-5.6-luna")
        val balanced = field(
            R.string.ai_balanced_model_hint,
            effective?.balancedModel ?: "gpt-5.6-terra"
        )
        val quality = field(R.string.ai_quality_model_hint, effective?.qualityModel ?: "gpt-5.6-sol")

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.ai_provider_settings)
            .setMessage(R.string.ai_provider_security_note)
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = apiKey.text.toString().trim().ifEmpty { custom?.apiKey.orEmpty() }
                val profile = AiProviderProfile(
                    kind = if (baseUrl.text.toString().trimEnd('/') == AiProviderProfile.OPENAI_BASE_URL) {
                        AiProviderKind.OpenAI
                    } else {
                        AiProviderKind.OpenAICompatible
                    },
                    displayName = name.text.toString(),
                    baseUrl = baseUrl.text.toString(),
                    apiKey = key,
                    fastModel = fast.text.toString(),
                    balancedModel = balanced.text.toString(),
                    qualityModel = quality.text.toString()
                )
                runCatching { store.save(profile) }
                    .onSuccess {
                        dialog.dismiss()
                        refreshSummaries()
                    }
                    .onFailure { error ->
                        apiKey.error = error.message ?: getString(R.string.ai_provider_invalid)
                    }
            }
        }
        dialog.show()
    }

    private fun showGifProviderDialog() {
        val ctx = requireContext()
        val store = GifProviderCredentialStore(ctx)
        val configured = store.state() == GifProviderCredentialState.Configured
        val apiKey = EditText(ctx).apply {
            setHint(
                if (configured) {
                    R.string.gif_provider_key_unchanged_hint
                } else {
                    R.string.gif_provider_key_hint
                }
            )
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            maxLines = 1
            isSaveEnabled = false
        }
        val horizontal = ctx.dp(20)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontal, ctx.dp(8), horizontal, 0)
            addView(
                apiKey,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.gif_provider_settings)
            .setMessage(R.string.gif_provider_security_note)
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = apiKey.text.toString().trim()
                if (key.isEmpty()) {
                    if (configured) {
                        dialog.dismiss()
                    } else {
                        apiKey.error = getString(R.string.gif_provider_key_required)
                    }
                    return@setOnClickListener
                }
                runCatching { store.saveKey(key) }
                    .onSuccess {
                        apiKey.text?.clear()
                        dialog.dismiss()
                        refreshSummaries()
                        Toast.makeText(ctx, R.string.gif_provider_key_saved, Toast.LENGTH_SHORT)
                            .show()
                    }
                    .onFailure {
                        apiKey.error = getString(R.string.gif_provider_key_invalid)
                    }
            }
        }
        dialog.setOnDismissListener { apiKey.text?.clear() }
        dialog.show()
    }

    private fun showRemoveGifProviderDialog() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.gif_provider_key_remove)
            .setMessage(R.string.gif_provider_key_remove_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                GifProviderCredentialStore(ctx).clear()
                refreshSummaries()
                Toast.makeText(ctx, R.string.gif_provider_key_removed, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
