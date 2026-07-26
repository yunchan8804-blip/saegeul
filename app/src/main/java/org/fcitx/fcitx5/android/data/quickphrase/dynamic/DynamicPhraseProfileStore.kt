/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase.dynamic

import android.content.Context
import android.os.Build
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
    private val vaultFile = File(context.noBackupFilesDir, VAULT_RELATIVE_PATH)
    private val vaultAtomicFile = AtomicFile(vaultFile)

    fun load(): DynamicPhraseProfile {
        if (!file.isFile) return DynamicPhraseProfile()
        return runCatching {
            decode(decrypt(readEncrypted(atomicFile, MAGIC), secretKey())).normalized()
        }.getOrDefault(DynamicPhraseProfile())
    }

    fun save(profile: DynamicPhraseProfile) {
        val normalized = profile.normalized()
        if (normalized.isEmpty) {
            atomicFile.delete()
            return
        }
        file.parentFile?.mkdirs()
        writeEncrypted(atomicFile, MAGIC, encrypt(encode(normalized), secretKey()))
    }

    fun hasVault(): Boolean = vaultFile.isFile && vaultFile.length() in 1..MAX_ENCRYPTED_FILE_BYTES

    /** Creates the CryptoObject operation that must be passed to BiometricPrompt. */
    fun beginVaultUnlock(): VaultUnlockRequest {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw IllegalArgumentException("Sensitive phrase vault requires Android 11 or newer")
        }
        if (!hasVault()) {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, vaultSecretKey())
            }
            return VaultUnlockRequest(cipher, null)
        }
        val encrypted = readEncrypted(vaultAtomicFile, VAULT_MAGIC)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                vaultSecretKey(),
                GCMParameterSpec(128, encrypted.iv)
            )
        }
        return VaultUnlockRequest(cipher, encrypted.payload)
    }

    /** Completes only with the exact cipher authenticated by BiometricPrompt. */
    fun finishVaultUnlock(
        request: VaultUnlockRequest,
        authenticatedCipher: Cipher
    ): SensitivePhraseVault {
        require(authenticatedCipher === request.cipher) { "Unexpected vault cipher" }
        val encrypted = request.encryptedPayload
        if (encrypted == null) {
            authenticatedCipher.doFinal(ByteArray(0)).fill(0)
            return SensitivePhraseVault()
        }
        val plaintext = authenticatedCipher.doFinal(encrypted)
        return try {
            decodeSensitivePhraseVault(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    /** Creates an authenticated encryption operation for an explicit vault change. */
    fun beginVaultWrite(): VaultWriteRequest {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw IllegalArgumentException("Sensitive phrase vault requires Android 11 or newer")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, vaultSecretKey())
        }
        return VaultWriteRequest(cipher)
    }

    /** Writes only ciphertext produced by the BiometricPrompt-authenticated cipher. */
    fun finishVaultWrite(
        vault: SensitivePhraseVault,
        request: VaultWriteRequest,
        authenticatedCipher: Cipher
    ) {
        require(authenticatedCipher === request.cipher) { "Unexpected vault write cipher" }
        val normalized = vault.normalized()
        if (normalized.items.isEmpty()) {
            // The caller still authenticates beginVaultWrite() before reaching this method.
            authenticatedCipher.doFinal(ByteArray(0)).fill(0)
            vaultAtomicFile.delete()
            return
        }
        vaultFile.parentFile?.mkdirs()
        val plaintext = encodeSensitivePhraseVault(normalized)
        try {
            val payload = authenticatedCipher.doFinal(plaintext)
            writeEncrypted(
                vaultAtomicFile,
                VAULT_MAGIC,
                EncryptedPayload(authenticatedCipher.iv, payload)
            )
        } finally {
            plaintext.fill(0)
        }
    }

    private fun encrypt(plaintext: ByteArray, key: SecretKey): EncryptedPayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return EncryptedPayload(cipher.iv, cipher.doFinal(plaintext))
    }

    private fun decrypt(encrypted: EncryptedPayload, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.iv))
        return cipher.doFinal(encrypted.payload)
    }

    private fun readEncrypted(atomicFile: AtomicFile, expectedMagic: Int): EncryptedPayload =
        DataInputStream(ByteArrayInputStream(atomicFile.readFully())).use { input ->
            require(input.readInt() == expectedMagic) { "Unexpected encrypted phrase format" }
            val ivSize = input.readInt()
            require(ivSize in 12..32) { "Invalid encrypted phrase IV" }
            val iv = ByteArray(ivSize).also(input::readFully)
            val payloadSize = input.readInt()
            require(payloadSize in 1..MAX_PAYLOAD_BYTES) { "Invalid encrypted phrase size" }
            val payload = ByteArray(payloadSize).also(input::readFully)
            require(input.available() == 0) { "Trailing encrypted phrase data" }
            EncryptedPayload(iv, payload)
        }

    private fun writeEncrypted(
        atomicFile: AtomicFile,
        magic: Int,
        encrypted: EncryptedPayload
    ) {
        val bytes = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(magic)
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
        }
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

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun vaultSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(VAULT_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    VAULT_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or
                            KeyProperties.AUTH_DEVICE_CREDENTIAL
                    )
                    .setInvalidatedByBiometricEnrollment(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun encode(profile: DynamicPhraseProfile): ByteArray = JSONObject()
        .put("name", profile.name)
        .put("phone", profile.phone)
        .put("address", profile.address)
        .put("email", profile.email)
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun decode(bytes: ByteArray): DynamicPhraseProfile {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        return DynamicPhraseProfile(
            name = json.optString("name"),
            phone = json.optString("phone"),
            address = json.optString("address"),
            email = json.optString("email")
        )
    }

    private data class EncryptedPayload(val iv: ByteArray, val payload: ByteArray)

    private companion object {
        const val MAGIC = 0x44595031 // DYP1
        const val VAULT_MAGIC = 0x44535631 // DSV1
        const val MAX_PAYLOAD_BYTES = 128 * 1024
        const val MAX_ENCRYPTED_FILE_BYTES = 128 * 1024L
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "fcitx.dynamic_phrase.profile.v1"
        const val VAULT_KEY_ALIAS = "fcitx.dynamic_phrase.vault.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    data class VaultUnlockRequest internal constructor(
        val cipher: Cipher,
        internal val encryptedPayload: ByteArray?
    )

    data class VaultWriteRequest internal constructor(val cipher: Cipher)
}

internal const val VAULT_RELATIVE_PATH = "dynamic-phrase/vault.bin"
