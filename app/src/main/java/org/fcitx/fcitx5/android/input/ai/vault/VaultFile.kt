/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.vault

import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

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
 * Process-wide read-block state of vault files, keyed by canonical path. A path is blocked after
 * a failed read of an existing file and stays blocked until a later read succeeds or the file is
 * preserved or gone. After the first failure the next read may retry at once, in case the error was
 * transient; after that the cipher that failed is not asked again until [retryIntervalMs] has
 * passed, so a broken file is not decrypted again on every keystroke. A different cipher (another
 * key) may always try at once.
 */
internal object VaultReadBlocks {
    const val MAX_READ_FAILURES = 3
    private const val DEFAULT_RETRY_INTERVAL_MS = 5L * 60 * 1000

    @Volatile
    var retryIntervalMs: Long = DEFAULT_RETRY_INTERVAL_MS

    @Volatile
    var clock: () -> Long = System::currentTimeMillis

    private class Block(val retryAtMs: Long, val failedCipher: VaultCipher)

    private val blocks = ConcurrentHashMap<String, Block>()

    fun block(canonicalPath: String, failedCipher: VaultCipher, consecutiveFailures: Int) {
        val delayMs = if (consecutiveFailures <= 1) 0L else retryIntervalMs
        blocks[canonicalPath] = Block(clock() + delayMs, failedCipher)
    }

    fun unblock(canonicalPath: String) {
        blocks.remove(canonicalPath)
    }

    fun isBlocked(canonicalPath: String): Boolean = blocks.containsKey(canonicalPath)

    /**
     * Lifts the block of [file] and removes its `.readfail` counter. For callers that replace the
     * committed file with known-good content (a vault import) and so know the old failure no
     * longer describes it. Takes the same per-path lock as [VaultFile].
     */
    fun isBlocked(file: File): Boolean = isBlocked(file.canonicalPath)

    /** Puts a block back on [file] after a release that turned out to be premature (a rolled-back import). */
    fun reblock(file: File, failedCipher: VaultCipher) {
        VaultFilePathLocks.withLock(file.canonicalPath) {
            block(file.canonicalPath, failedCipher, consecutiveFailures = MAX_READ_FAILURES - 1)
        }
    }

    fun release(file: File) {
        VaultFilePathLocks.withLock(file.canonicalPath) {
            unblock(file.canonicalPath)
            File(file.parentFile ?: File("."), "${file.name}.readfail").delete()
        }
    }

    /** True while [cipher] is the one that failed on this path and its next attempt is not yet allowed. */
    fun isWaiting(canonicalPath: String, cipher: VaultCipher): Boolean {
        val block = blocks[canonicalPath] ?: return false
        return block.failedCipher === cipher && clock() < block.retryAtMs
    }
}

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
 *
 * A committed file that exists but cannot be read (decryption or format failure, or a content
 * parse failure reported through [markUnreadable]) is never overwritten or deleted. The path is
 * read-blocked in this process: [writeText], [delete] and migration writes throw [IOException]
 * and leave every file untouched, and the cipher that failed is not asked to read it again until
 * [VaultReadBlocks.retryIntervalMs] has passed. Each failed read bumps a `<name>.readfail`
 * counter; a successful read removes it. When the counter reaches
 * [VaultReadBlocks.MAX_READ_FAILURES] the file and its backup are renamed to
 * `<name>.unreadable-<epochMs>` (kept, never deleted) and the read reports "no file" so the
 * store can start fresh.
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
    private val failureCounter = File(parent, "${file.name}.readfail")

    // Failure count cleared by the latest successful read, so markUnreadable() keeps counting
    // consecutive failures when the content decrypted but its store could not parse it.
    private var clearedFailureCount = 0

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
     * Reports that the text returned by the last successful read could not be parsed by its
     * store. The file is read-blocked like any other failed read and counts toward the preserve
     * threshold. Returns true when this report reached the threshold and the file was preserved
     * as `<name>.unreadable-<epochMs>`.
     */
    fun markUnreadable(): Boolean = locked {
        if (!storage.exists(file) && !storage.exists(backup)) return@locked false
        val previous = maxOf(readFailureCountLocked(), clearedFailureCount)
        clearedFailureCount = 0
        recordReadFailureLocked(previous, null)
    }

    /**
     * Returns the committed decoded text and, when needed, atomically re-writes it with [cipher].
     * Temporary files are never read as committed data.
     */
    fun readTextAndMigrate(): String? = locked {
        val (text, storedId) = readGuardedLocked { raw -> decodeRaw(raw) to storedCipherId(raw) }
            ?: return@locked null
        if (storedId != cipher.id) {
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
        val text = readGuardedLocked { raw ->
            if (storedCipherId(raw) == cipher.id) null else decodeRaw(raw)
        } ?: return@locked false
        writeTextLocked(text)
        true
    }

    /**
     * Deletes temporary and backup state before the base file to prevent backup revival.
     * Throws [IOException] without touching any file while the path is read-blocked.
     */
    fun delete(): Boolean = locked {
        checkNotReadBlockedLocked()
        VaultFilePathLocks.incrementRevision(canonicalPath)
        val hadBase = storage.exists(file)
        if (!deleteIfPresentLocked(tmp)) return@locked false
        if (!deleteIfPresentLocked(backup)) return@locked false
        hadBase && storage.delete(file)
    }

    /** True while this path is read-blocked in this process, i.e. its committed file failed to read. */
    internal fun isReadBlocked(): Boolean = locked { VaultReadBlocks.isBlocked(canonicalPath) }

    /**
     * Removes everything stored for this vault file at the user's request: the committed file, its
     * backup, temporary and `.readfail` files, and any `.unreadable-*` copies kept earlier. Unlike
     * [delete] it ignores a read block, because deleting one's own data must always work; the
     * block is lifted once the committed file is gone. Returns true when nothing is left.
     */
    fun deleteForUser(): Boolean = locked {
        VaultFilePathLocks.incrementRevision(canonicalPath)
        clearedFailureCount = 0
        var removed = deleteIfPresentLocked(tmp)
        removed = deleteIfPresentLocked(backup) && removed
        val baseGone = deleteIfPresentLocked(file)
        removed = baseGone && removed
        removed = deleteIfPresentLocked(failureCounter) && removed
        val preservedPrefixes = listOf("${file.name}.unreadable-", "${backup.name}.unreadable-")
        parent.listFiles { _, name -> preservedPrefixes.any(name::startsWith) }.orEmpty().forEach { preserved ->
            removed = deleteIfPresentLocked(preserved) && removed
        }
        if (baseGone) VaultReadBlocks.unblock(canonicalPath)
        removed
    }

    private fun readTextLocked(): String? = readGuardedLocked { raw -> decodeRaw(raw) }

    /**
     * Runs [read] on the committed bytes. Returns null when there is no committed file, when [read]
     * itself returns null, or when repeated failures just caused the unreadable file to be
     * preserved. Any other failure blocks the path and is rethrown.
     */
    private fun <T : Any> readGuardedLocked(read: (ByteArray) -> T?): T? {
        if (!storage.exists(file) && !storage.exists(backup)) {
            releaseReadBlockLocked(remember = false)
            return null
        }
        if (VaultReadBlocks.isWaiting(canonicalPath, cipher)) {
            throw IOException("vault file is read-blocked")
        }
        val result = try {
            val raw = readCommittedRawOrNullLocked() ?: return null
            read(raw)
        } catch (exception: Exception) {
            if (recordReadFailureLocked(readFailureCountLocked(), exception)) return null
            throw exception
        }
        releaseReadBlockLocked(remember = true)
        return result
    }

    /**
     * Counts one more consecutive read failure on top of [previousCount] and blocks the path.
     * At the threshold the committed files are preserved under an `.unreadable-<epochMs>` name and
     * the block is lifted; returns true in that case.
     */
    private fun recordReadFailureLocked(previousCount: Int, failure: Exception?): Boolean {
        val count = previousCount + 1
        if (count >= VaultReadBlocks.MAX_READ_FAILURES) {
            val preserved = preserveUnreadableLocked()
            if (preserved != null) {
                Timber.w(failure, "Vault file %s unreadable %d times; kept as %s", file.name, count, preserved.name)
                deleteIfPresentLocked(failureCounter)
                VaultReadBlocks.unblock(canonicalPath)
                return true
            }
            Timber.w(failure, "Vault file %s unreadable %d times; preserving it failed", file.name, count)
        } else {
            Timber.w(failure, "Vault file %s could not be read (%d of %d)", file.name, count, VaultReadBlocks.MAX_READ_FAILURES)
        }
        writeFailureCountLocked(count)
        VaultReadBlocks.block(canonicalPath, cipher, count)
        return false
    }

    /** Renames the committed files aside and returns the preserved base file, or null on failure. */
    private fun preserveUnreadableLocked(): File? {
        val stamp = VaultReadBlocks.clock()
        val preservedBase = File(parent, "${file.name}.unreadable-$stamp")
        val preservedBackup = File(parent, "${backup.name}.unreadable-$stamp")
        try {
            if (storage.exists(backup) && !storage.rename(backup, preservedBackup)) return null
            if (storage.exists(file) && !storage.rename(file, preservedBase)) return null
        } catch (exception: Exception) {
            Timber.w(exception, "Vault file %s could not be preserved", file.name)
            return null
        }
        VaultFilePathLocks.incrementRevision(canonicalPath)
        return preservedBase
    }

    private fun releaseReadBlockLocked(remember: Boolean) {
        if (remember) clearedFailureCount = readFailureCountLocked()
        if (storage.exists(failureCounter)) deleteIfPresentLocked(failureCounter)
        VaultReadBlocks.unblock(canonicalPath)
    }

    private fun checkNotReadBlockedLocked() {
        if (VaultReadBlocks.isBlocked(canonicalPath)) {
            throw IOException("vault file is read-blocked")
        }
    }

    private fun readFailureCountLocked(): Int {
        if (!storage.exists(failureCounter)) return 0
        return try {
            String(storage.readBytes(failureCounter), Charsets.US_ASCII).trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
        } catch (exception: IOException) {
            Timber.w(exception, "Vault read-failure counter for %s unreadable", file.name)
            0
        }
    }

    private fun writeFailureCountLocked(count: Int) {
        try {
            storage.writeBytesAndSync(failureCounter, count.toString().toByteArray(Charsets.US_ASCII))
        } catch (exception: IOException) {
            Timber.w(exception, "Vault read-failure counter for %s not saved", file.name)
        }
    }

    private fun decodeRaw(raw: ByteArray): String {
        val storedId = storedCipherId(raw) ?: return String(raw, Charsets.UTF_8)
        val idLength = raw[MAGIC.size].toInt() and 0xFF
        val idStart = MAGIC.size + 1
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
        checkNotReadBlockedLocked()
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

    /** Returns the cipher id recorded in [raw], or null if it has no `SGV1` header or is truncated. */
    private fun storedCipherId(raw: ByteArray): String? {
        if (!hasMagic(raw)) return null
        if (raw.size < MAGIC.size + 1) return null
        val idLength = raw[MAGIC.size].toInt() and 0xFF
        val idStart = MAGIC.size + 1
        if (raw.size < idStart + idLength) return null
        return String(raw, idStart, idLength, Charsets.US_ASCII)
    }

    private fun <T> locked(block: () -> T): T =
        VaultFilePathLocks.withLock(canonicalPath, block)

    companion object {
        private val MAGIC = byteArrayOf('S'.code.toByte(), 'G'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())

        fun aadFor(fileName: String): ByteArray = "net.chanpaca.saegeul|$fileName".toByteArray()
    }
}
