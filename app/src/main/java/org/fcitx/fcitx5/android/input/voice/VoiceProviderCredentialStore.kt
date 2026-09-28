/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.fcitx.fcitx5.android.input.AndroidKeystoreProfileCipher
import org.fcitx.fcitx5.android.input.EncryptedProfileCipher
import org.fcitx.fcitx5.android.input.EncryptedProfileFormat
import org.fcitx.fcitx5.android.input.EncryptedProfileStorage
import java.io.File

/** Stores the STT-only API key outside SharedPreferences, Android backup, and user ZIP exports. */
class VoiceProviderCredentialStore internal constructor(
    root: File,
    cipher: EncryptedProfileCipher
) {
    constructor(context: Context) : this(
        context.noBackupFilesDir,
        AndroidKeystoreProfileCipher(KEY_ALIAS)
    )

    private val storage = EncryptedProfileStorage(File(root, RELATIVE_PATH), FORMAT, cipher)

    fun load(): VoiceProviderProfile? {
        if (!storage.exists()) return null
        return runCatching {
            val plaintext = storage.load() ?: return null
            try {
                decode(plaintext).validate()
            } finally {
                plaintext.fill(0)
            }
        }.getOrNull()
    }

    fun save(profile: VoiceProviderProfile) {
        val plaintext = encode(profile.validate())
        try {
            storage.save(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    fun clear() {
        storage.clear()
    }

    fun hasStoredProfile(): Boolean = storage.exists()

    private fun encode(profile: VoiceProviderProfile): ByteArray = buildJsonObject {
        put("apiKey", profile.apiKey)
        put("transcriptionModel", profile.transcriptionModel)
        put("realtimeTranscriptionModel", profile.realtimeTranscriptionModel)
        put("diarizationModel", profile.diarizationModel)
        put("baseUrl", profile.baseUrl)
    }.toString().toByteArray(Charsets.UTF_8)

    private fun decode(bytes: ByteArray): VoiceProviderProfile {
        val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        return VoiceProviderProfile(
            apiKey = json.string("apiKey"),
            transcriptionModel = json.string(
                "transcriptionModel",
                VoiceTranscriptionModel.Accurate.id
            ),
            realtimeTranscriptionModel = json.string(
                "realtimeTranscriptionModel",
                VoiceRealtimeTranscriptionModel.Streaming.id
            ),
            diarizationModel = json.string(
                "diarizationModel",
                VoiceProviderProfile.DIARIZATION_MODEL
            ),
            baseUrl = json.string("baseUrl", VoiceProviderProfile.OPENAI_BASE_URL)
        )
    }

    private fun kotlinx.serialization.json.JsonObject.string(
        name: String,
        default: String = ""
    ): String = get(name)?.jsonPrimitive?.contentOrNull ?: default

    companion object {
        const val RELATIVE_PATH = "voice/provider.bin"
        internal const val KEY_ALIAS = "fcitx.voice.provider.v1"
        private val FORMAT = EncryptedProfileFormat(
            magic = 0x56505231, // VPR1
            maxPayloadBytes = 16 * 1024,
            label = "voice provider profile"
        )
    }
}
