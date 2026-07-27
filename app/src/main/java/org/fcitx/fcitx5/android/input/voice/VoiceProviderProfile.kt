/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import org.fcitx.fcitx5.android.input.ai.AiEndpointPolicy

enum class VoiceProviderMode {
    DeviceDictation,
    OpenAiRealtime,
    OpenAiApi
}

internal data class VoiceModeChangePlan(
    val modeToPersist: VoiceProviderMode?,
    val credentialMode: VoiceProviderMode?
)

internal object VoiceProviderModeSelectionPolicy {
    /**
     * An online mode is not active until its dedicated STT credential is available. This keeps a
     * cancelled setup dialog from silently replacing working device dictation with a broken mode.
     */
    fun plan(selectedMode: VoiceProviderMode, hasCredential: Boolean): VoiceModeChangePlan =
        when {
            selectedMode == VoiceProviderMode.DeviceDictation || hasCredential -> {
                VoiceModeChangePlan(modeToPersist = selectedMode, credentialMode = null)
            }
            else -> VoiceModeChangePlan(modeToPersist = null, credentialMode = selectedMode)
        }

    /** Resolve the mode atomically with a successful STT credential save. */
    fun afterCredentialSaved(
        currentMode: VoiceProviderMode,
        requestedMode: VoiceProviderMode?
    ): VoiceProviderMode = requestedMode
        ?.takeIf { it != VoiceProviderMode.DeviceDictation }
        ?: currentMode.takeIf { it != VoiceProviderMode.DeviceDictation }
        ?: VoiceProviderMode.OpenAiApi
}

object VoiceProviderPolicy {
    fun allowsSelectedMode(mode: VoiceProviderMode, allowsNetworkInput: Boolean): Boolean =
        mode == VoiceProviderMode.DeviceDictation || allowsNetworkInput
}

enum class VoiceTranscriptionModel(val id: String) {
    Accurate("gpt-4o-transcribe"),
    Efficient("gpt-4o-mini-transcribe");

    companion object {
        fun fromId(value: String): VoiceTranscriptionModel? = entries.firstOrNull { it.id == value }
    }
}

enum class VoiceRealtimeTranscriptionModel(val id: String) {
    Streaming("gpt-realtime-whisper");

    companion object {
        fun fromId(value: String): VoiceRealtimeTranscriptionModel? =
            entries.firstOrNull { it.id == value }
    }
}

/** Dedicated STT credential. It is deliberately separate from the writing-LLM profile. */
data class VoiceProviderProfile(
    val apiKey: String,
    val transcriptionModel: String = VoiceTranscriptionModel.Accurate.id,
    val realtimeTranscriptionModel: String = VoiceRealtimeTranscriptionModel.Streaming.id,
    val diarizationModel: String = DIARIZATION_MODEL,
    val baseUrl: String = OPENAI_BASE_URL
) {
    fun normalized(): VoiceProviderProfile = copy(
        apiKey = apiKey.trim(),
        transcriptionModel = transcriptionModel.trim(),
        realtimeTranscriptionModel = realtimeTranscriptionModel.trim(),
        diarizationModel = diarizationModel.trim(),
        baseUrl = baseUrl.trim().trimEnd('/')
    )

    fun validate(): VoiceProviderProfile {
        val profile = normalized()
        require(profile.baseUrl == OPENAI_BASE_URL) {
            "Voice transcription must use the official OpenAI API"
        }
        AiEndpointPolicy.requireHttps(profile.baseUrl, "Voice provider URL")
        require(profile.apiKey.isNotEmpty()) { "STT API key is empty" }
        require(profile.apiKey.length <= MAX_API_KEY_CHARACTERS) { "STT API key is too long" }
        require(profile.apiKey.none(Char::isISOControl)) { "STT API key contains control characters" }
        require(VoiceTranscriptionModel.fromId(profile.transcriptionModel) != null) {
            "Unsupported transcription model"
        }
        require(
            VoiceRealtimeTranscriptionModel.fromId(profile.realtimeTranscriptionModel) != null
        ) {
            "Unsupported realtime transcription model"
        }
        require(profile.diarizationModel == DIARIZATION_MODEL) {
            "Unsupported diarization model"
        }
        return profile
    }

    val endpoint: String
        get() = "${normalized().baseUrl}/audio/transcriptions"

    val realtimeEndpoint: String
        get() = "wss://api.openai.com/v1/realtime?model=${normalized().realtimeTranscriptionModel}"

    val isConfigured: Boolean
        get() = runCatching(::validate).isSuccess

    companion object {
        const val OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val DIARIZATION_MODEL = "gpt-4o-transcribe-diarize"
        private const val MAX_API_KEY_CHARACTERS = 4_096
    }
}

class VoiceProviderModeStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): VoiceProviderMode = runCatching {
        VoiceProviderMode.valueOf(
            preferences.getString(KEY_MODE, null) ?: VoiceProviderMode.DeviceDictation.name
        )
    }.getOrDefault(VoiceProviderMode.DeviceDictation)

    fun save(mode: VoiceProviderMode) {
        check(preferences.edit().putString(KEY_MODE, mode.name).commit()) {
            "Could not save voice provider mode"
        }
    }

    private companion object {
        const val PREFERENCES = "voice-provider-settings"
        const val KEY_MODE = "mode"
    }
}

data class EffectiveVoiceProvider(
    val mode: VoiceProviderMode,
    val profile: VoiceProviderProfile?
)

object VoiceProviderResolver {
    fun resolve(context: Context, allowsCredentialAccess: Boolean): EffectiveVoiceProvider = resolve(
        mode = VoiceProviderModeStore(context).load(),
        allowsCredentialAccess = allowsCredentialAccess,
        loadCredential = { VoiceProviderCredentialStore(context).load() }
    )

    internal fun resolve(
        mode: VoiceProviderMode,
        allowsCredentialAccess: Boolean,
        loadCredential: () -> VoiceProviderProfile?
    ): EffectiveVoiceProvider {
        val profile = if (allowsCredentialAccess && mode != VoiceProviderMode.DeviceDictation) {
            loadCredential()?.takeIf(VoiceProviderProfile::isConfigured)
        } else {
            null
        }
        return EffectiveVoiceProvider(mode, profile)
    }
}
