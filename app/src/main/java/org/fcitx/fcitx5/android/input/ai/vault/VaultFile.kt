/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.vault

import java.io.File
import java.io.IOException

/**
 * A single personalization vault file (n-gram model, typo correction pairs, language fingerprint,
 * personal sentences, ...) encrypted at rest with [cipher].
 *
 * File layout: magic `SGV1` (4 bytes) + cipher id length (1 byte) + cipher id (ASCII) + cipher
 * blob. A file with no `SGV1` header is treated as legacy plaintext (UTF-8) written before
 * encryption was introduced, so existing on-disk data keeps loading until [migrateIfLegacy] is
 * called. [readText] always decrypts with the cipher recorded in the header (falling back to
 * [cipher] or [PlainVaultCipher]), so a file written by an older, less-encrypted version of
 * [cipher]'s store keeps loading correctly even before it is migrated.
 */
class VaultFile(
    private val file: File,
    private val cipher: VaultCipher,
    private val aad: ByteArray
) {

    fun exists(): Boolean = file.exists()

    /** True when the file exists but has no `SGV1` header, i.e. pre-encryption plaintext. */
    fun isLegacyPlaintext(): Boolean {
        val raw = readRawOrNull() ?: return false
        return !hasMagic(raw)
    }

    /**
     * Returns the file's decoded text, or null if it does not exist.
     * Legacy plaintext files (no `SGV1` header) are returned as-is (UTF-8). Encrypted files are
     * decrypted with the cipher recorded in their header — [cipher] if it matches, [PlainVaultCipher]
     * if the file predates encryption support for its store, or a thrown
     * [java.security.GeneralSecurityException] for an unrecognized id. Decryption failures
     * otherwise propagate as-is.
     */
    fun readText(): String? {
        val raw = readRawOrNull() ?: return null
        if (!hasMagic(raw)) return String(raw, Charsets.UTF_8)
        val idLength = raw[MAGIC.size].toInt() and 0xFF
        val idStart = MAGIC.size + 1
        val storedId = String(raw, idStart, idLength, Charsets.US_ASCII)
        val blob = raw.copyOfRange(idStart + idLength, raw.size)
        val readCipher = when (storedId) {
            cipher.id -> cipher
            PlainVaultCipher.id -> PlainVaultCipher
            else -> throw java.security.GeneralSecurityException("Unknown vault cipher id: $storedId")
        }
        return String(readCipher.decrypt(blob, aad), Charsets.UTF_8)
    }

    /** Always writes in the encrypted format, replacing the file atomically via a `.tmp` file. */
    fun writeText(text: String) {
        val blob = cipher.encrypt(text.toByteArray(Charsets.UTF_8), aad)
        val idBytes = cipher.id.toByteArray(Charsets.US_ASCII)
        require(idBytes.size <= 0xFF) { "Vault cipher id too long: ${cipher.id}" }

        val out = ByteArray(MAGIC.size + 1 + idBytes.size + blob.size)
        var offset = 0
        System.arraycopy(MAGIC, 0, out, offset, MAGIC.size)
        offset += MAGIC.size
        out[offset] = idBytes.size.toByte()
        offset += 1
        System.arraycopy(idBytes, 0, out, offset, idBytes.size)
        offset += idBytes.size
        System.arraycopy(blob, 0, out, offset, blob.size)

        writeAtomically(out)
    }

    /**
     * If the file is stored with a cipher other than [cipher] — either headerless legacy
     * plaintext or a different (e.g. plain) cipher id in the `SGV1` header — re-writes it with
     * [cipher]. Returns true if migrated.
     */
    fun migrateIfLegacy(): Boolean {
        val raw = readRawOrNull() ?: return false
        val storedId = storedCipherId(raw)
        if (storedId == cipher.id) return false
        val text = readText() ?: return false
        writeText(text)
        return true
    }

    fun delete(): Boolean = file.delete()

    private fun readRawOrNull(): ByteArray? = if (file.exists()) file.readBytes() else null

    private fun hasMagic(raw: ByteArray): Boolean {
        if (raw.size < MAGIC.size) return false
        for (i in MAGIC.indices) {
            if (raw[i] != MAGIC[i]) return false
        }
        return true
    }

    /** Returns the cipher id recorded in [raw]'s header, or null if it has no `SGV1` header. */
    private fun storedCipherId(raw: ByteArray): String? {
        if (!hasMagic(raw)) return null
        val idLength = raw[MAGIC.size].toInt() and 0xFF
        val idStart = MAGIC.size + 1
        return String(raw, idStart, idLength, Charsets.US_ASCII)
    }

    private fun writeAtomically(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(bytes)
        if (file.exists()) {
            file.delete()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Failed to replace vault file: ${file.path}")
        }
    }

    companion object {
        private val MAGIC = byteArrayOf('S'.code.toByte(), 'G'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())

        fun aadFor(fileName: String): ByteArray = "net.chanpaca.saegeul|$fileName".toByteArray()
    }
}
