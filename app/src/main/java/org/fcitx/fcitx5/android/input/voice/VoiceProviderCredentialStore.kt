/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.fcitx.fcitx5.android.input.EncryptedProfileCipher
import org.fcitx.fcitx5.android.input.EncryptedProfilePayload
import org.fcitx.fcitx5.android.input.EncryptedProfileStorage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the STT-only API key outside SharedPreferences, Android backup, and user ZIP exports. */
class VoiceProviderCredentialStore internal constructor(
    root: File,
    private val cipher: EncryptedProfileCipher
) {
    constructor(context: Context) : this(
        context.noBackupFilesDir,
        AndroidKeystoreVoiceProviderCipher()
    )

    private val file = File(root, RELATIVE_PATH)
    private val storage = EncryptedProfileStorage(file)

    fun load(): VoiceProviderProfile? {
        if (!storage.exists()) return null
        return runCatching {
            val bytes = storage.read(MAX_FILE_BYTES) ?: return null
            val encrypted = try {
                DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    require(input.readInt() == MAGIC) {
                        "Unexpected voice provider profile format"
                    }
                    val ivSize = input.readInt()
                    require(ivSize in MIN_IV_BYTES..MAX_IV_BYTES) {
                        "Invalid voice provider profile IV"
                    }
                    val iv = ByteArray(ivSize).also(input::readFully)
                    val payloadSize = input.readInt()
                    require(payloadSize in 1..MAX_PAYLOAD_BYTES) {
                        "Invalid voice provider profile size"
                    }
                    val payload = ByteArray(payloadSize).also(input::readFully)
                    require(input.read() == -1) { "Trailing voice provider profile data" }
                    EncryptedProfilePayload(iv, payload)
                }
            } finally {
                bytes.fill(0)
            }
            try {
                val plaintext = cipher.decrypt(encrypted)
                try {
                    decode(plaintext).validate()
                } finally {
                    plaintext.fill(0)
                }
            } finally {
                encrypted.payload.fill(0)
            }
        }.getOrNull()
    }

    fun save(profile: VoiceProviderProfile) {
        val validated = profile.validate()
        val plaintext = encode(validated)
        val encrypted = try {
            cipher.encrypt(plaintext)
        } finally {
            plaintext.fill(0)
        }
        require(encrypted.iv.size in MIN_IV_BYTES..MAX_IV_BYTES) {
            "Invalid encrypted voice provider profile IV"
        }
        require(encrypted.payload.size in 1..MAX_PAYLOAD_BYTES) {
            "Invalid encrypted voice provider profile size"
        }
        val bytes = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(encrypted.iv.size)
                output.write(encrypted.iv)
                output.writeInt(encrypted.payload.size)
                output.write(encrypted.payload)
            }
            bytes.toByteArray()
        }
        try {
            storage.write(bytes)
        } finally {
            bytes.fill(0)
            encrypted.payload.fill(0)
        }
    }

    fun clear() {
        storage.delete()
        runCatching(cipher::clear)
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
        private const val MAGIC = 0x56505231 // VPR1
        private const val MIN_IV_BYTES = 12
        private const val MAX_IV_BYTES = 32
        private const val MAX_PAYLOAD_BYTES = 16 * 1024
        private const val MAX_FILE_BYTES = MAX_PAYLOAD_BYTES + MAX_IV_BYTES + 16L
    }
}

private class AndroidKeystoreVoiceProviderCipher : EncryptedProfileCipher {
    override fun encrypt(plaintext: ByteArray): EncryptedProfilePayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return EncryptedProfilePayload(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(encrypted: EncryptedProfilePayload): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, encrypted.iv))
        return cipher.doFinal(encrypted.payload)
    }

    override fun clear() {
        val keyStore = keyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun secretKey(): SecretKey {
        val keyStore = keyStore()
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = VoiceProviderCredentialStore.KEY_ALIAS
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
