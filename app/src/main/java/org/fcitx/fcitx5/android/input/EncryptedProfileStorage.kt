/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

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

internal data class EncryptedProfilePayload(
    val iv: ByteArray,
    val payload: ByteArray
)

/** Test seam for encrypted no-backup profiles; production implementations use Android Keystore. */
internal interface EncryptedProfileCipher {
    fun encrypt(plaintext: ByteArray): EncryptedProfilePayload

    fun decrypt(encrypted: EncryptedProfilePayload): ByteArray

    fun clear()
}

/**
 * The per-store part of the on-disk envelope. Every store writes the same layout
 * (magic, IV length, IV, ciphertext length, ciphertext), so existing files stay readable.
 */
internal data class EncryptedProfileFormat(
    val magic: Int,
    val maxPayloadBytes: Int,
    /** Names the stored item in failure messages, e.g. "GIF credential". */
    val label: String
) {
    val maxFileBytes: Long
        get() = maxPayloadBytes + EncryptedProfileStorage.MAX_IV_BYTES + 16L
}

/**
 * Encrypted no-backup profile file shared by every provider credential store.
 * Replacement is file-only and atomic: the previous ciphertext remains recoverable until the
 * replacement is fully synced and renamed, which keeps the contract covered by local JVM tests.
 */
internal class EncryptedProfileStorage(
    private val file: File,
    private val format: EncryptedProfileFormat,
    private val cipher: EncryptedProfileCipher
) {
    /**
     * Returns the decrypted plaintext, or null when nothing is stored. The caller zeroes it.
     * A stored file that is not a valid envelope or fails decryption throws.
     */
    fun load(): ByteArray? {
        val source = readableSource() ?: return null
        require(source.length() in 1..format.maxFileBytes) { "Invalid ${format.label} file size" }
        val bytes = source.readBytes()
        val encrypted = try {
            decode(bytes)
        } finally {
            bytes.fill(0)
        }
        return try {
            cipher.decrypt(encrypted)
        } finally {
            encrypted.payload.fill(0)
        }
    }

    /** Encrypts [plaintext] and replaces the stored file; [plaintext] stays owned by the caller. */
    fun save(plaintext: ByteArray) {
        val encrypted = cipher.encrypt(plaintext)
        try {
            require(encrypted.iv.size in MIN_IV_BYTES..MAX_IV_BYTES) {
                "Invalid encrypted ${format.label} IV"
            }
            require(encrypted.payload.size in 1..format.maxPayloadBytes) {
                "Invalid encrypted ${format.label} size"
            }
            val bytes = encode(encrypted)
            try {
                writeAtomically(bytes)
            } finally {
                bytes.fill(0)
            }
        } finally {
            encrypted.payload.fill(0)
        }
    }

    fun exists(): Boolean = file.isFile || backupFile().isFile

    /** Deletes the file and its Keystore alias; a missing Keystore entry does not block deletion. */
    fun clear() {
        listOf(file, pendingFile(), backupFile()).forEach(File::delete)
        runCatching(cipher::clear)
    }

    private fun decode(bytes: ByteArray): EncryptedProfilePayload =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == format.magic) { "Unexpected ${format.label} format" }
            val ivSize = input.readInt()
            require(ivSize in MIN_IV_BYTES..MAX_IV_BYTES) { "Invalid ${format.label} IV" }
            val iv = ByteArray(ivSize).also(input::readFully)
            val payloadSize = input.readInt()
            require(payloadSize in 1..format.maxPayloadBytes) { "Invalid ${format.label} size" }
            val payload = ByteArray(payloadSize).also(input::readFully)
            require(input.read() == -1) { "Trailing ${format.label} data" }
            EncryptedProfilePayload(iv, payload)
        }

    private fun encode(encrypted: EncryptedProfilePayload): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(format.magic)
                output.writeInt(encrypted.iv.size)
                output.write(encrypted.iv)
                output.writeInt(encrypted.payload.size)
                output.write(encrypted.payload)
            }
            bytes.toByteArray()
        }

    private fun writeAtomically(bytes: ByteArray) {
        readableSource()
        file.parentFile?.mkdirs()
        val pending = pendingFile().apply { delete() }
        val backup = backupFile().apply { delete() }
        try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (file.exists() && !file.renameTo(backup)) {
                throw IllegalStateException("Could not preserve the previous ${format.label}")
            }
            if (!pending.renameTo(file)) {
                if (backup.isFile) backup.renameTo(file)
                throw IllegalStateException("Could not store the ${format.label}")
            }
            backup.delete()
        } catch (error: Throwable) {
            pending.delete()
            if (!file.exists() && backup.isFile) backup.renameTo(file)
            throw error
        }
    }

    private fun readableSource(): File? {
        if (file.isFile) return file
        val backup = backupFile().takeIf(File::isFile) ?: return null
        file.parentFile?.mkdirs()
        return if (backup.renameTo(file)) file else backup
    }

    private fun pendingFile(): File = File(file.parentFile, "${file.name}.new")

    private fun backupFile(): File = File(file.parentFile, "${file.name}.bak")

    internal companion object {
        const val MIN_IV_BYTES = 12
        const val MAX_IV_BYTES = 32
    }
}

/** AES-GCM with a non-exportable Android Keystore key; each store keeps its own alias. */
internal class AndroidKeystoreProfileCipher(private val alias: String) : EncryptedProfileCipher {
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
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun secretKey(): SecretKey {
        val keyStore = keyStore()
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
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
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
