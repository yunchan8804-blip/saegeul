/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase.dynamic

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

/** Stores personal phrase variables outside Android backup and user-data ZIP exports. */
class DynamicPhraseProfileStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "dynamic-phrase/profile.bin")
    private val atomicFile = AtomicFile(file)

    fun load(): DynamicPhraseProfile {
        if (!file.isFile) return DynamicPhraseProfile()
        return runCatching {
            val encrypted = DataInputStream(ByteArrayInputStream(atomicFile.readFully())).use { input ->
                require(input.readInt() == MAGIC) { "Unexpected dynamic phrase profile format" }
                val ivSize = input.readInt()
                require(ivSize in 12..32) { "Invalid dynamic phrase profile IV" }
                val iv = ByteArray(ivSize).also(input::readFully)
                val payloadSize = input.readInt()
                require(payloadSize in 1..MAX_PAYLOAD_BYTES) { "Invalid dynamic phrase profile size" }
                val payload = ByteArray(payloadSize).also(input::readFully)
                EncryptedPayload(iv, payload)
            }
            decode(decrypt(encrypted)).normalized()
        }.getOrDefault(DynamicPhraseProfile())
    }

    fun save(profile: DynamicPhraseProfile) {
        val normalized = profile.normalized()
        if (normalized.isEmpty) {
            atomicFile.delete()
            return
        }
        file.parentFile?.mkdirs()
        val encrypted = encrypt(encode(normalized))
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
        var stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
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

    private fun encode(profile: DynamicPhraseProfile): ByteArray = JSONObject()
        .put("name", profile.name)
        .put("phone", profile.phone)
        .put("address", profile.address)
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun decode(bytes: ByteArray): DynamicPhraseProfile {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        return DynamicPhraseProfile(
            name = json.optString("name"),
            phone = json.optString("phone"),
            address = json.optString("address")
        )
    }

    private data class EncryptedPayload(val iv: ByteArray, val payload: ByteArray)

    private companion object {
        const val MAGIC = 0x44595031 // DYP1
        const val MAX_PAYLOAD_BYTES = 64 * 1024
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "fcitx.dynamic_phrase.profile.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
