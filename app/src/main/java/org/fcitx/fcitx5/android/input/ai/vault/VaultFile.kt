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
 * called.
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
     * Legacy plaintext files are returned as-is (UTF-8); encrypted files are decrypted with
     * [cipher]. Decryption failures propagate as-is.
     */
    fun readText(): String? {
        val raw = readRawOrNull() ?: return null
        return if (hasMagic(raw)) {
            val idLength = raw[MAGIC.size].toInt() and 0xFF
            val blobStart = MAGIC.size + 1 + idLength
            val blob = raw.copyOfRange(blobStart, raw.size)
            String(cipher.decrypt(blob, aad), Charsets.UTF_8)
        } else {
            String(raw, Charsets.UTF_8)
        }
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

    /** If the file is legacy plaintext, re-writes it in the encrypted format. Returns true if migrated. */
    fun migrateIfLegacy(): Boolean {
        if (!isLegacyPlaintext()) return false
        val raw = readRawOrNull() ?: return false
        writeText(String(raw, Charsets.UTF_8))
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
