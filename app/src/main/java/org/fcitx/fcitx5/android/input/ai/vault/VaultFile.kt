/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.vault

import java.io.File
import java.io.IOException

internal enum class VaultFileCommittedSource {
    BASE,
    BACKUP,
    MISSING
}

internal data class VaultFileVersion(
    val generation: Long,
    val source: VaultFileCommittedSource,
    val length: Long,
    val lastModified: Long
)

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
class VaultFile internal constructor(
    private val file: File,
    private val cipher: VaultCipher,
    private val aad: ByteArray,
    private val storage: VaultFileStorage
) {
    constructor(file: File, cipher: VaultCipher, aad: ByteArray) :
        this(file, cipher, aad, DefaultVaultFileStorage)

    private val canonicalPath = file.canonicalPath
    private val parent = file.parentFile ?: File(".")
    private val tmp = File(parent, "${file.name}.tmp")
    private val backup = File(parent, "${file.name}.bak")

    fun exists(): Boolean = locked {
        storage.exists(file) || storage.exists(backup)
    }

    internal fun <T> withLock(block: () -> T): T = locked(block)

    internal fun version(): VaultFileVersion = locked { versionLocked() }

    /** True when the committed file exists but has no `SGV1` header. */
    fun isLegacyPlaintext(): Boolean = locked {
        val raw = readCommittedRawOrNullLocked() ?: return@locked false
        !hasMagic(raw)
    }

    /**
     * Returns the committed decoded text, or null if neither the base file nor its backup exists.
     * Temporary files are never read as committed data.
     */
    fun readText(): String? = locked { readTextLocked() }

    /**
     * Returns the committed decoded text and, when needed, atomically re-writes it with [cipher].
     * Temporary files are never read as committed data.
     */
    fun readTextAndMigrate(): String? = locked {
        val raw = readCommittedRawOrNullLocked() ?: return@locked null
        val text = decodeRaw(raw)
        if (storedCipherId(raw) != cipher.id) {
            writeTextLocked(text)
        }
        text
    }

    /** Writes encrypted bytes and preserves the prior committed bytes through the commit. */
    fun writeText(text: String) {
        locked { writeTextLocked(text) }
    }

    /**
     * If the committed file is stored with a cipher other than [cipher] — either headerless legacy
     * plaintext or a different (e.g. plain) cipher id in the `SGV1` header — re-writes it with
     * [cipher]. Returns true if migrated.
     */
    fun migrateIfLegacy(): Boolean = locked {
        val raw = readCommittedRawOrNullLocked() ?: return@locked false
        val storedId = storedCipherId(raw)
        if (storedId == cipher.id) return@locked false
        writeTextLocked(decodeRaw(raw))
        true
    }

    /** Deletes temporary and backup state before the base file to prevent backup revival. */
    fun delete(): Boolean = locked {
        VaultFilePathLocks.incrementRevision(canonicalPath)
        val hadBase = storage.exists(file)
        if (!deleteIfPresentLocked(tmp)) return@locked false
        if (!deleteIfPresentLocked(backup)) return@locked false
        hadBase && storage.delete(file)
    }

    private fun readTextLocked(): String? {
        val raw = readCommittedRawOrNullLocked() ?: return null
        return decodeRaw(raw)
    }

    private fun decodeRaw(raw: ByteArray): String {
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

    private fun readCommittedRawOrNullLocked(): ByteArray? = when {
        storage.exists(file) -> storage.readBytes(file)
        storage.exists(backup) -> storage.readBytes(backup)
        else -> null
    }

    private fun versionLocked(): VaultFileVersion {
        val committedFile: File
        val source: VaultFileCommittedSource
        when {
            storage.exists(file) -> {
                committedFile = file
                source = VaultFileCommittedSource.BASE
            }
            storage.exists(backup) -> {
                committedFile = backup
                source = VaultFileCommittedSource.BACKUP
            }
            else -> {
                return VaultFileVersion(
                    generation = VaultFilePathLocks.revision(canonicalPath),
                    source = VaultFileCommittedSource.MISSING,
                    length = 0L,
                    lastModified = 0L
                )
            }
        }
        return VaultFileVersion(
            generation = VaultFilePathLocks.revision(canonicalPath),
            source = source,
            length = storage.length(committedFile),
            lastModified = storage.lastModified(committedFile)
        )
    }

    private fun writeTextLocked(text: String) {
        VaultFilePathLocks.incrementRevision(canonicalPath)
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
        writeBytesLocked(out)
    }

    private fun writeBytesLocked(bytes: ByteArray) {
        restoreBackupOnlyLocked()
        removeStaleBackupLocked()
        storage.writeBytesAndSync(tmp, bytes)

        if (!storage.exists(file)) {
            if (!storage.rename(tmp, file)) {
                throw IOException("Failed to commit vault file: ${file.path}")
            }
            return
        }

        if (!storage.rename(file, backup)) {
            throw IOException("Failed to preserve vault file: ${file.path}")
        }
        val commitFailure = try {
            if (storage.rename(tmp, file)) null else IOException("Failed to commit vault file: ${file.path}")
        } catch (exception: Exception) {
            IOException("Failed to commit vault file: ${file.path}", exception)
        }
        if (commitFailure != null) {
            try {
                if (!storage.rename(backup, file)) {
                    commitFailure.addSuppressed(IOException("Failed to restore vault backup: ${file.path}"))
                }
            } catch (exception: Exception) {
                commitFailure.addSuppressed(exception)
            }
            throw commitFailure
        }
        // A committed base is authoritative even when stale-backup cleanup cannot complete.
        deleteIfPresentLocked(backup)
    }

    private fun restoreBackupOnlyLocked() {
        if (!storage.exists(file) && storage.exists(backup) && !storage.rename(backup, file)) {
            throw IOException("Failed to restore vault backup: ${file.path}")
        }
    }

    private fun removeStaleBackupLocked() {
        if (storage.exists(file) && storage.exists(backup) && !storage.delete(backup)) {
            throw IOException("Failed to remove stale vault backup: ${file.path}")
        }
    }

    private fun deleteIfPresentLocked(target: File): Boolean = try {
        !storage.exists(target) || storage.delete(target)
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun hasMagic(raw: ByteArray): Boolean {
        if (raw.size < MAGIC.size) return false
        for (i in MAGIC.indices) {
            if (raw[i] != MAGIC[i]) return false
        }
        return true
    }

    /** Returns the cipher id recorded in [raw], or null if it has no `SGV1` header. */
    private fun storedCipherId(raw: ByteArray): String? {
        if (!hasMagic(raw)) return null
        val idLength = raw[MAGIC.size].toInt() and 0xFF
        val idStart = MAGIC.size + 1
        return String(raw, idStart, idLength, Charsets.US_ASCII)
    }

    private fun <T> locked(block: () -> T): T =
        VaultFilePathLocks.withLock(canonicalPath, block)

    companion object {
        private val MAGIC = byteArrayOf('S'.code.toByte(), 'G'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())

        fun aadFor(fileName: String): ByteArray = "net.chanpaca.saegeul|$fileName".toByteArray()
    }
}
