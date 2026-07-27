/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
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
class VoiceProviderCredentialStore(context: Context) {
    private val file = File(context.noBackupFilesDir, RELATIVE_PATH)
    private val atomicFile = AtomicFile(file)

    fun load(): VoiceProviderProfile? {
        if (!file.isFile) return null
        return runCatching {
            val encrypted = DataInputStream(ByteArrayInputStream(atomicFile.readFully())).use { input ->
                require(input.readInt() == MAGIC) { "Unexpected voice provider profile format" }
                val ivSize = input.readInt()
                require(ivSize in 12..32) { "Invalid voice provider profile IV" }
                val iv = ByteArray(ivSize).also(input::readFully)
                val payloadSize = input.readInt()
                require(payloadSize in 1..MAX_PAYLOAD_BYTES) { "Invalid voice provider profile size" }
                EncryptedPayload(iv, ByteArray(payloadSize).also(input::readFully))
            }
            val plaintext = decrypt(encrypted)
            try {
                decode(plaintext).validate()
            } finally {
                plaintext.fill(0)
                encrypted.payload.fill(0)
            }
        }.getOrNull()
    }

    fun save(profile: VoiceProviderProfile) {
        val validated = profile.validate()
        file.parentFile?.mkdirs()
        val plaintext = encode(validated)
        val encrypted = try {
            encrypt(plaintext)
        } finally {
            plaintext.fill(0)
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
        val stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        } finally {
            bytes.fill(0)
            encrypted.payload.fill(0)
        }
    }

    fun clear() = atomicFile.delete()

    fun hasStoredProfile(): Boolean = file.isFile

    private fun encode(profile: VoiceProviderProfile): ByteArray = JSONObject()
        .put("apiKey", profile.apiKey)
        .put("transcriptionModel", profile.transcriptionModel)
        .put("realtimeTranscriptionModel", profile.realtimeTranscriptionModel)
        .put("diarizationModel", profile.diarizationModel)
        .put("baseUrl", profile.baseUrl)
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun decode(bytes: ByteArray): VoiceProviderProfile {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        return VoiceProviderProfile(
            apiKey = json.optString("apiKey"),
            transcriptionModel = json.optString(
                "transcriptionModel",
                VoiceTranscriptionModel.Accurate.id
            ),
            realtimeTranscriptionModel = json.optString(
                "realtimeTranscriptionModel",
                VoiceRealtimeTranscriptionModel.Streaming.id
            ),
            diarizationModel = json.optString(
                "diarizationModel",
                VoiceProviderProfile.DIARIZATION_MODEL
            ),
            baseUrl = json.optString("baseUrl", VoiceProviderProfile.OPENAI_BASE_URL)
        )
    }

    private fun encrypt(plaintext: ByteArray): EncryptedPayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return EncryptedPayload(cipher.iv, cipher.doFinal(plaintext))
    }

    private fun decrypt(encrypted: EncryptedPayload): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, encrypted.iv))
        return cipher.doFinal(encrypted.payload)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
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

    private data class EncryptedPayload(val iv: ByteArray, val payload: ByteArray)

    companion object {
        const val RELATIVE_PATH = "voice/provider.bin"
        private const val MAGIC = 0x56505231 // VPR1
        private const val MAX_PAYLOAD_BYTES = 16 * 1024
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "fcitx.voice.provider.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
