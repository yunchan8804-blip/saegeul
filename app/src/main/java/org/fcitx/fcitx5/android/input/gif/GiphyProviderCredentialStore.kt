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

data class GiphyProviderConfiguration(
    val apiKey: String,
    /** User confirmation that this exact key completed GIPHY's production upgrade review. */
    val productionApproved: Boolean,
    /** Separate written approval is required before storing a copy of GIPHY media. */
    val mediaCachingApproved: Boolean
)

enum class GiphyCredentialState { Missing, KeyOnly, Ready, Unreadable }

/** GIPHY key and approval assertions are encrypted outside every backup/export path. */
class GiphyProviderCredentialStore internal constructor(
    private val file: File,
    private val cipher: GifCredentialCipher
) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, RELATIVE_PATH),
        AndroidKeystoreGiphyCredentialCipher()
    )

    fun load(): GiphyProviderConfiguration? {
        val source = readableSource() ?: return null
        return runCatching {
            require(source.length() in 1..MAX_FILE_BYTES)
            val encrypted = DataInputStream(ByteArrayInputStream(source.readBytes())).use { input ->
                require(input.readInt() == MAGIC)
                val ivSize = input.readInt()
                require(ivSize in MIN_IV_BYTES..MAX_IV_BYTES)
                val iv = ByteArray(ivSize).also(input::readFully)
                val payloadSize = input.readInt()
                require(payloadSize in 1..MAX_ENCRYPTED_BYTES)
                val payload = ByteArray(payloadSize).also(input::readFully)
                require(input.read() == -1)
                GifEncryptedPayload(iv, payload)
            }
            val plaintext = cipher.decrypt(encrypted)
            try {
                decode(plaintext)
            } finally {
                plaintext.fill(0)
            }
        }.getOrNull()
    }

    fun state(): GiphyCredentialState {
        if (!hasStoredPayload()) return GiphyCredentialState.Missing
        val configuration = load() ?: return GiphyCredentialState.Unreadable
        return if (configuration.productionApproved) {
            GiphyCredentialState.Ready
        } else {
            GiphyCredentialState.KeyOnly
        }
    }

    fun save(configuration: GiphyProviderConfiguration) {
        val normalized = configuration.copy(apiKey = configuration.apiKey.trim())
        require(normalized.apiKey.length in 1..MAX_KEY_CHARACTERS) {
            "Enter a valid GIPHY API key"
        }
        require(!normalized.mediaCachingApproved || normalized.productionApproved) {
            "Media caching approval requires production approval"
        }
        val plaintext = encode(normalized)
        val encrypted = try {
            cipher.encrypt(plaintext)
        } finally {
            plaintext.fill(0)
        }
        require(encrypted.iv.size in MIN_IV_BYTES..MAX_IV_BYTES)
        require(encrypted.payload.size in 1..MAX_ENCRYPTED_BYTES)
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

    private fun encode(configuration: GiphyProviderConfiguration): ByteArray {
        val key = configuration.apiKey.toByteArray(Charsets.UTF_8)
        return try {
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                output.writeInt(PAYLOAD_VERSION)
                output.writeInt(key.size)
                output.write(key)
                output.writeBoolean(configuration.productionApproved)
                output.writeBoolean(configuration.mediaCachingApproved)
                }
                bytes.toByteArray()
            }
        } finally {
            key.fill(0)
        }
    }

    private fun decode(bytes: ByteArray): GiphyProviderConfiguration =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == PAYLOAD_VERSION)
            val keySize = input.readInt()
            require(keySize in 1..MAX_KEY_BYTES)
            val keyBytes = ByteArray(keySize).also(input::readFully)
            try {
                val key = keyBytes.toString(Charsets.UTF_8).trim()
                require(key.length in 1..MAX_KEY_CHARACTERS)
                val productionApproved = input.readBoolean()
                val mediaCachingApproved = input.readBoolean()
                require(!mediaCachingApproved || productionApproved)
                require(input.read() == -1)
                GiphyProviderConfiguration(key, productionApproved, mediaCachingApproved)
            } finally {
                keyBytes.fill(0)
            }
        }

    private fun hasStoredPayload(): Boolean = file.isFile || backupFile().isFile

    private fun readableSource(): File? {
        if (file.isFile) return file
        val backup = backupFile().takeIf(File::isFile) ?: return null
        file.parentFile?.mkdirs()
        return if (backup.renameTo(file)) file else backup
    }

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
                throw IllegalStateException("Could not preserve the previous GIPHY credential")
            }
            if (!pending.renameTo(file)) {
                if (backup.isFile) backup.renameTo(file)
                throw IllegalStateException("Could not store the GIPHY credential")
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
        const val RELATIVE_PATH = "gif/giphy-provider.bin"
        const val MAGIC = 0x47495031 // GIP1
        const val PAYLOAD_VERSION = 1
        const val MIN_IV_BYTES = 12
        const val MAX_IV_BYTES = 32
        const val MAX_KEY_CHARACTERS = 512
        const val MAX_KEY_BYTES = MAX_KEY_CHARACTERS * 4
        const val MAX_ENCRYPTED_BYTES = 4 * 1024
        const val MAX_FILE_BYTES = MAX_ENCRYPTED_BYTES + MAX_IV_BYTES + 16L
    }
}

private class AndroidKeystoreGiphyCredentialCipher : GifCredentialCipher {
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
        const val KEY_ALIAS = "fcitx.gif.giphy.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/**
 * A single explicit source per GIF grid. The resolver never merges provider results.
 *
 * `Standard` keeps the existing KLIPY-or-Noto behavior. `Commons` is keyless open media;
 * `Giphy` remains approval-gated.
 */
enum class GifProviderSelection { Standard, Commons, Giphy }

/** Explicit provider choice; no key presence may silently switch a GIPHY-selected grid. */
class GifProviderSelectionStore internal constructor(private val file: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, RELATIVE_PATH))

    fun load(): GifProviderSelection = runCatching {
        file.takeIf(File::isFile)?.readText()?.trim()?.let(GifProviderSelection::valueOf)
    }.getOrNull() ?: GifProviderSelection.Standard

    fun save(selection: GifProviderSelection) {
        file.parentFile?.mkdirs()
        val pending = File(file.parentFile, "${file.name}.new").apply { delete() }
        val backup = File(file.parentFile, "${file.name}.bak").apply { delete() }
        try {
            FileOutputStream(pending).use { output ->
                output.write(selection.name.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (file.exists() && !file.renameTo(backup)) {
                throw IllegalStateException("Could not preserve GIF provider selection")
            }
            if (!pending.renameTo(file)) {
                if (backup.isFile) backup.renameTo(file)
                throw IllegalStateException("Could not store GIF provider selection")
            }
            backup.delete()
        } catch (error: Throwable) {
            pending.delete()
            if (!file.exists() && backup.isFile) backup.renameTo(file)
            throw error
        }
    }

    private companion object {
        const val RELATIVE_PATH = "gif/provider-selection"
    }
}
