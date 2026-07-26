/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The state exposed to settings never contains the credential itself. */
enum class GifProviderCredentialState {
    Missing,
    Configured,
    Unreadable
}

/**
 * Stores the optional KLIPY credential outside SharedPreferences and every backup/export path.
 * The file contains only AES-GCM ciphertext; its key remains non-exportable in Android Keystore.
 */
class GifProviderCredentialStore internal constructor(
    private val file: File,
    private val cipher: GifCredentialCipher
) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, RELATIVE_PATH),
        AndroidKeystoreGifCredentialCipher()
    )

    fun loadKey(): String? {
        val source = readableSource() ?: return null
        return runCatching {
            require(source.length() in 1..MAX_FILE_BYTES) { "Invalid GIF credential file size" }
            val encrypted = DataInputStream(ByteArrayInputStream(source.readBytes())).use { input ->
                require(input.readInt() == MAGIC) { "Unexpected GIF credential format" }
                val ivSize = input.readInt()
                require(ivSize in MIN_IV_BYTES..MAX_IV_BYTES) { "Invalid GIF credential IV" }
                val iv = ByteArray(ivSize).also(input::readFully)
                val payloadSize = input.readInt()
                require(payloadSize in 1..MAX_ENCRYPTED_BYTES) {
                    "Invalid GIF credential payload"
                }
                val payload = ByteArray(payloadSize).also(input::readFully)
                require(input.read() == -1) { "Trailing GIF credential data" }
                GifEncryptedPayload(iv, payload)
            }
            cipher.decrypt(encrypted)
                .toString(Charsets.UTF_8)
                .trim()
                .takeIf { it.length in 1..MAX_KEY_CHARACTERS }
        }.getOrNull()
    }

    fun state(): GifProviderCredentialState = when {
        !hasStoredPayload() -> GifProviderCredentialState.Missing
        loadKey() != null -> GifProviderCredentialState.Configured
        else -> GifProviderCredentialState.Unreadable
    }

    fun saveKey(apiKey: String) {
        val normalized = apiKey.trim()
        require(normalized.length in 1..MAX_KEY_CHARACTERS) { "Enter a valid KLIPY API key" }
        val encrypted = cipher.encrypt(normalized.toByteArray(Charsets.UTF_8))
        require(encrypted.iv.size in MIN_IV_BYTES..MAX_IV_BYTES) {
            "Invalid encrypted credential IV"
        }
        require(encrypted.payload.size in 1..MAX_ENCRYPTED_BYTES) {
            "Invalid encrypted credential payload"
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
        writeAtomically(bytes)
    }

    fun clear() {
        listOf(file, pendingFile(), backupFile()).forEach(File::delete)
        runCatching(cipher::clear)
    }

    private fun hasStoredPayload(): Boolean = file.isFile || backupFile().isFile

    private fun readableSource(): File? {
        if (file.isFile) return file
        val backup = backupFile().takeIf(File::isFile) ?: return null
        file.parentFile?.mkdirs()
        return if (backup.renameTo(file)) file else backup
    }

    /**
     * Same-directory replace keeps the old ciphertext recoverable until the new file is complete.
     * This is intentionally file-only so the persistence contract remains covered by local JVM tests.
     */
    private fun writeAtomically(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val pending = pendingFile().apply { delete() }
        val backup = backupFile().apply { delete() }
        try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (file.exists() && !file.renameTo(backup)) {
                throw IllegalStateException("Could not preserve the previous GIF credential")
            }
            if (!pending.renameTo(file)) {
                if (backup.isFile) backup.renameTo(file)
                throw IllegalStateException("Could not store the GIF credential")
            }
            backup.delete()
        } catch (error: Throwable) {
            pending.delete()
            if (!file.exists() && backup.isFile) backup.renameTo(file)
            throw error
        }
    }

    private fun pendingFile(): File = File(file.parentFile, "${file.name}.new")

    private fun backupFile(): File = File(file.parentFile, "${file.name}.bak")

    private companion object {
        const val RELATIVE_PATH = "gif/provider.bin"
        const val MAGIC = 0x47494631 // GIF1
        const val MIN_IV_BYTES = 12
        const val MAX_IV_BYTES = 32
        const val MAX_KEY_CHARACTERS = 512
        const val MAX_ENCRYPTED_BYTES = 4 * 1024
        const val MAX_FILE_BYTES = MAX_ENCRYPTED_BYTES + MAX_IV_BYTES + 16L
    }
}

internal data class GifEncryptedPayload(
    val iv: ByteArray,
    val payload: ByteArray
)

internal interface GifCredentialCipher {
    fun encrypt(plaintext: ByteArray): GifEncryptedPayload

    fun decrypt(encrypted: GifEncryptedPayload): ByteArray

    fun clear()
}

private class AndroidKeystoreGifCredentialCipher : GifCredentialCipher {
    override fun encrypt(plaintext: ByteArray): GifEncryptedPayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return GifEncryptedPayload(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(encrypted: GifEncryptedPayload): ByteArray {
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
        const val KEY_ALIAS = "fcitx.gif.klipy.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
