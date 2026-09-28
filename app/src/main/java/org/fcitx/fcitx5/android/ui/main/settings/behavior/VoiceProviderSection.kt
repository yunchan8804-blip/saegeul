/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.voice.VoiceProviderCredentialStore
import org.fcitx.fcitx5.android.input.voice.VoiceProviderMode
import org.fcitx.fcitx5.android.input.voice.VoiceProviderModeSelectionPolicy
import org.fcitx.fcitx5.android.input.voice.VoiceProviderModeStore
import org.fcitx.fcitx5.android.input.voice.VoiceProviderPolicy
import org.fcitx.fcitx5.android.input.voice.VoiceProviderProfile
import org.fcitx.fcitx5.android.input.voice.VoiceTranscriptionModel
import org.fcitx.fcitx5.android.utils.addCategory

/** Voice dictation category: the dictation mode and the optional OpenAI transcription key. */
internal class VoiceProviderSection(
    private val host: Fragment,
    private val onDataChanged: () -> Unit
) {
    data class Summary(
        val mode: String,
        val provider: String,
        val clearProviderVisible: Boolean
    )

    private lateinit var voiceModePreference: Preference
    private lateinit var voiceProviderPreference: Preference
    private lateinit var clearVoiceProviderPreference: Preference

    fun addTo(screen: PreferenceScreen) {
        val ctx = host.requireContext()
        screen.addCategory(R.string.voice_provider_settings) {
            voiceModePreference = Preference(ctx).apply {
                setTitle(R.string.voice_provider_mode_title)
                icon = ctx.themedPreferenceIcon(R.drawable.ic_baseline_keyboard_voice_24)
                setOnPreferenceClickListener {
                    showVoiceModeDialog()
                    true
                }
            }
            addPreference(voiceModePreference)
            voiceProviderPreference = Preference(ctx).apply {
                setTitle(R.string.voice_openai_api_settings)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    showProviderDialog()
                    true
                }
            }
            addPreference(voiceProviderPreference)
            clearVoiceProviderPreference = Preference(ctx).apply {
                setTitle(R.string.voice_provider_key_remove)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    showRemoveProviderDialog()
                    true
                }
            }
            addPreference(clearVoiceProviderPreference)
        }
    }

    /** Runs off the main thread; decrypts the stored profile to describe it. */
    fun loadSummary(ctx: Context): Summary {
        val voiceMode = VoiceProviderModeStore(ctx).load()
        val voiceStore = VoiceProviderCredentialStore(ctx)
        val voiceProfile = voiceStore.load()
        val voiceModeSummary = ctx.getString(
            when (voiceMode) {
                VoiceProviderMode.DeviceDictation -> R.string.voice_provider_mode_device_summary
                VoiceProviderMode.OpenAiRealtime ->
                    R.string.voice_provider_mode_openai_realtime_summary
                VoiceProviderMode.OpenAiApi -> R.string.voice_provider_mode_openai_summary
            }
        )
        val voiceProviderSummary = when {
            voiceProfile != null && !VoiceProviderPolicy.requiresCredential(voiceMode) -> ctx.getString(
                R.string.voice_provider_status_optional_configured,
                ctx.getString(
                    R.string.voice_models_configured,
                    voiceModelName(ctx, voiceProfile.transcriptionModel),
                    voiceProfile.realtimeTranscriptionModel
                )
            )
            voiceProfile != null -> ctx.getString(
                R.string.voice_provider_configured_summary,
                ctx.getString(
                    R.string.voice_models_configured,
                    voiceModelName(ctx, voiceProfile.transcriptionModel),
                    voiceProfile.realtimeTranscriptionModel
                )
            )
            voiceStore.hasStoredProfile() -> ctx.getString(R.string.voice_provider_status_unreadable)
            !VoiceProviderPolicy.requiresCredential(voiceMode) ->
                ctx.getString(R.string.voice_provider_status_optional_missing)
            else -> ctx.getString(R.string.voice_provider_status_missing)
        }
        return Summary(
            mode = voiceModeSummary,
            provider = voiceProviderSummary,
            clearProviderVisible = voiceStore.hasStoredProfile()
        )
    }

    fun applySummary(summary: Summary) {
        voiceModePreference.summary = summary.mode
        voiceProviderPreference.summary = summary.provider
        clearVoiceProviderPreference.isVisible = summary.clearProviderVisible
    }

    fun showSummaryPlaceholder(summary: String) {
        voiceModePreference.summary = summary
        voiceProviderPreference.summary = summary
    }

    fun showProviderDialog(pendingMode: VoiceProviderMode? = null) {
        val ctx = host.requireContext()
        val store = VoiceProviderCredentialStore(ctx)
        val configured = store.load()
        val accurate = RadioButton(ctx).apply {
            id = View.generateViewId()
            setText(R.string.voice_model_accurate)
        }
        val efficient = RadioButton(ctx).apply {
            id = View.generateViewId()
            setText(R.string.voice_model_efficient)
        }
        val models = RadioGroup(ctx).apply {
            orientation = RadioGroup.VERTICAL
            addView(accurate)
            addView(efficient)
            check(
                if (configured?.transcriptionModel == VoiceTranscriptionModel.Efficient.id) {
                    efficient.id
                } else {
                    accurate.id
                }
            )
        }
        CredentialInputDialog.show(
            context = ctx,
            title = R.string.voice_openai_api_settings,
            securityNote = R.string.voice_provider_security_note,
            field = CredentialFieldSpec(
                hint = R.string.voice_provider_key_hint,
                unchangedHint = R.string.voice_provider_key_unchanged_hint,
                configured = configured != null
            ),
            extraViews = listOf(models)
        ) { input ->
            val key = input.enteredKey.ifEmpty { configured?.apiKey.orEmpty() }
            val model = if (models.checkedRadioButtonId == efficient.id) {
                VoiceTranscriptionModel.Efficient.id
            } else {
                VoiceTranscriptionModel.Accurate.id
            }
            val profile = VoiceProviderProfile(apiKey = key, transcriptionModel = model)
            val validated = runCatching(profile::validate)
                .onFailure { error ->
                    input.showKeyError(error.message ?: ctx.getString(R.string.voice_provider_invalid))
                }
                .getOrNull() ?: return@show
            runCatching {
                store.save(validated)
                val selectedMode = VoiceProviderModeSelectionPolicy.afterCredentialSaved(
                    currentMode = VoiceProviderModeStore(ctx).load(),
                    requestedMode = pendingMode
                )
                VoiceProviderModeStore(ctx).save(selectedMode)
            }.onSuccess {
                input.finish()
                onDataChanged()
                Toast.makeText(ctx, R.string.voice_provider_saved, Toast.LENGTH_SHORT).show()
            }.onFailure {
                input.showKeyError(ctx.getString(R.string.voice_provider_save_failed))
            }
        }
    }

    private fun showVoiceModeDialog() {
        val ctx = host.requireContext()
        val store = VoiceProviderModeStore(ctx)
        val values = VoiceProviderMode.entries
        val labels = arrayOf(
            ctx.getString(R.string.voice_provider_mode_device),
            ctx.getString(R.string.voice_provider_mode_openai_realtime),
            ctx.getString(R.string.voice_provider_mode_openai)
        )
        var selected = values.indexOf(store.load()).coerceAtLeast(0)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.voice_provider_mode_title)
            .setSingleChoiceItems(labels, selected) { _, index -> selected = index }
            .setPositiveButton(R.string.save) { _, _ ->
                val mode = values[selected]
                val plan = VoiceProviderModeSelectionPolicy.plan(
                    selectedMode = mode,
                    hasCredential = VoiceProviderCredentialStore(ctx).load() != null
                )
                runCatching { plan.modeToPersist?.let(store::save) }
                    .onSuccess {
                        onDataChanged()
                        plan.credentialMode?.let { pendingMode ->
                            host.view?.post { showProviderDialog(pendingMode) }
                        }
                    }
                    .onFailure {
                        Toast.makeText(ctx, R.string.voice_provider_save_failed, Toast.LENGTH_SHORT)
                            .show()
                    }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRemoveProviderDialog() {
        val ctx = host.requireContext()
        showDeleteConfirmation(
            ctx,
            R.string.voice_provider_key_remove,
            R.string.voice_provider_key_remove_confirm
        ) {
            VoiceProviderCredentialStore(ctx).clear()
            VoiceProviderModeStore(ctx).save(VoiceProviderMode.DeviceDictation)
            onDataChanged()
            Toast.makeText(ctx, R.string.voice_provider_removed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun voiceModelName(ctx: Context, model: String): String = ctx.getString(
        if (model == VoiceTranscriptionModel.Efficient.id) {
            R.string.voice_model_efficient
        } else {
            R.string.voice_model_accurate
        }
    )
}
