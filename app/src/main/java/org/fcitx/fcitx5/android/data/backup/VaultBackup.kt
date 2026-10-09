/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.backup

import android.content.Context
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.data.points.LevelRewardStore
import org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaRepository
import org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedSentenceBank
import org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault
import org.fcitx.fcitx5.android.input.ai.vault.KeystoreVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultFile
import org.fcitx.fcitx5.android.input.ai.vault.VaultFilePathLocks
import org.fcitx.fcitx5.android.input.ai.vault.VaultReadBlocks
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import timber.log.Timber

enum class BackupEntryKind { CORPUS, DERIVED, PUBLIC_CORPUS }

data class BackupEntry(val name: String, val kind: BackupEntryKind)

data class BackupSummary(
    val createdAtMs: Long,
    val appVersionName: String,
    val formatVersion: Int,
    val personalSentenceCount: Int,
    val publicMaterialCount: Int,
    val entries: List<BackupEntry>,
    val skippedDerivedEntries: List<String> = emptyList()
)

sealed interface ExportResult {
    data class Success(val summary: BackupSummary, val bytes: Long) : ExportResult
    data class Failure(val code: String) : ExportResult
}

sealed interface InspectResult {
    data class Valid(val summary: BackupSummary) : InspectResult
    data object WrongPassword : InspectResult
    data object Corrupted : InspectResult
    data object NewerVersion : InspectResult
    data object NotABackup : InspectResult
}

sealed interface ImportResult {
    data class Success(val summary: BackupSummary) : ImportResult
    data object WrongPassword : ImportResult
    data object Corrupted : ImportResult
    data object NewerVersion : ImportResult
    data object NotABackup : ImportResult
    data class Failure(val code: String) : ImportResult
}

/**
 * User-initiated export/import of the on-device language vault to/from a single encrypted
 * `.saegeulbackup` file. See `docs` task packet "새글 언어 금고 백업" for the confirmed format and
 * anti-exploit requirements; this object is the format/orchestration core, kept separate from any
 * particular storage backend (SAF today, cloud later) which only needs a [java.io.OutputStream]/
 * [java.io.InputStream].
 */
object VaultBackup {
    /** Plaintext (pre-encryption) ZIP payload size limit. */
    const val MAX_PLAINTEXT_BYTES = 64L * 1024 * 1024
    private const val MIN_PASSWORD_LENGTH = 8

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun export(context: Context, out: OutputStream, password: CharArray): ExportResult =
        exportInternal(
            filesDir = context.filesDir,
            noBackupFilesDir = context.noBackupFilesDir,
            appVersionName = BuildConfig.VERSION_NAME,
            cipher = KeystoreVaultCipher(),
            out = out,
            password = password
        )

    /** Decrypts and validates only; never touches the device's stores. Safe to call freely. */
    suspend fun inspect(input: InputStream, password: CharArray): InspectResult =
        withContext(Dispatchers.IO) {
            try {
                val bytes = readBounded(input) ?: return@withContext InspectResult.Corrupted
                when (val outcome = openBackup(bytes, password)) {
                    is OpenOutcome.Ok -> InspectResult.Valid(
                        outcome.opened.manifest.toSummary(outcome.opened.skippedDerivedEntries)
                    )
                    OpenOutcome.WrongPassword -> InspectResult.WrongPassword
                    OpenOutcome.Corrupted -> InspectResult.Corrupted
                    OpenOutcome.NewerVersion -> InspectResult.NewerVersion
                    OpenOutcome.NotABackup -> InspectResult.NotABackup
                }
            } finally {
                password.fill('\u0000')
            }
        }

    suspend fun import(context: Context, input: InputStream, password: CharArray): ImportResult =
        importInternal(
            filesDir = context.filesDir,
            noBackupFilesDir = context.noBackupFilesDir,
            cipher = KeystoreVaultCipher(),
            markLevelAlreadyRewarded = { level -> LevelRewardStore(context).markAlreadyRewardedUpTo(level) },
            cancelBackgroundWork = { cancelBackgroundGemmaWorkAndWait(context) },
            input = input,
            password = password
        )

    /**
     * Unique `WorkManager` work names for the on-device Gemma material-generation and
     * personal-graph enrichment background jobs. Declared here (main sourceset), not imported from
     * `app/src/debug`, because release builds have no debug sourceset to import from; a JVM test
     * pins these against the debug schedulers' own constants so the two can't silently drift apart.
     */
    internal val GEMMA_BACKGROUND_WORK_NAMES = listOf(
        "gemma-accumulation-periodic",
        "gemma-accumulation-now",
        "gemma-graph-enrichment-periodic",
        "gemma-graph-enrichment-manual"
    )

    private const val WORK_CANCEL_TIMEOUT_MS = 10_000L

    /**
     * Cancels every background Gemma work item by name and waits (bounded by
     * [WORK_CANCEL_TIMEOUT_MS] total) for `WorkManager` to actually apply the cancellation, so a
     * worker that is mid-write when the import commit starts (or when the user erases all learned
     * data) doesn't race it. A cancellation that doesn't resolve in time is logged and the caller
     * proceeds anyway - see [VaultBackup]'s import
     * commit step for why the file-level locking there is the actual last line of defense.
     */
    internal fun cancelBackgroundGemmaWorkAndWait(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val operations = GEMMA_BACKGROUND_WORK_NAMES.map { name -> workManager.cancelUniqueWork(name) }
        val deadline = System.currentTimeMillis() + WORK_CANCEL_TIMEOUT_MS
        operations.forEach { operation ->
            val remainingMs = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
            try {
                operation.result.get(remainingMs, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Timber.w(e, "A background Gemma work cancellation didn't resolve in time")
            }
        }
    }

    // --- Testable core: takes plain File dirs + an injectable VaultCipher/callback instead of a
    // Context, since this module has no Robolectric/instrumentation JVM test setup. The public
    // Context-taking functions above are thin adapters over these and are exercised by the app
    // itself / instrumentation, not by JVM unit tests. ---

    internal suspend fun exportInternal(
        filesDir: File,
        noBackupFilesDir: File,
        appVersionName: String,
        cipher: VaultCipher,
        out: OutputStream,
        password: CharArray,
        iterations: Int = VaultBackupCrypto.DEFAULT_PBKDF2_ITERATIONS
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            require(password.size >= MIN_PASSWORD_LENGTH) { "backup password must be at least $MIN_PASSWORD_LENGTH characters" }

            val collected = LinkedHashMap<String, Pair<BackupEntryKind, ByteArray>>()
            val skippedOnRead = mutableListOf<String>()
            for (target in VaultBackupTargets.ALL) {
                val file = target.resolve(filesDir, noBackupFilesDir)
                val bytes = try {
                    readTargetBytes(target, file, cipher)
                } catch (e: GeneralSecurityException) {
                    if (target.kind == BackupEntryKind.CORPUS) {
                        Timber.w(e, "VaultBackup export: corpus entry ${target.name} is unreadable")
                        return@withContext ExportResult.Failure("READ_FAILED")
                    }
                    Timber.w(e, "VaultBackup export: skipping unreadable ${target.kind} entry ${target.name}")
                    skippedOnRead += target.name
                    null
                } catch (e: IOException) {
                    if (target.kind == BackupEntryKind.CORPUS) {
                        Timber.w(e, "VaultBackup export: corpus entry ${target.name} is unreadable")
                        return@withContext ExportResult.Failure("READ_FAILED")
                    }
                    Timber.w(e, "VaultBackup export: skipping unreadable ${target.kind} entry ${target.name}")
                    skippedOnRead += target.name
                    null
                } ?: continue
                collected[target.name] = target.kind to bytes
            }

            val personalSentenceCount = countPersonalSentences(filesDir, cipher)
            val publicMaterialCount = countPublicMaterials(noBackupFilesDir, cipher)

            val manifest = ManifestJson(
                formatVersion = VaultBackupCrypto.CURRENT_FORMAT_VERSION,
                createdAtMs = System.currentTimeMillis(),
                appVersionName = appVersionName,
                personalSentenceCount = personalSentenceCount,
                publicMaterialCount = publicMaterialCount,
                entries = collected.map { (name, kindAndBytes) ->
                    ManifestEntryJson(name, kindAndBytes.first.name, sha256Hex(kindAndBytes.second))
                }
            )
            val manifestJson = json.encodeToString(ManifestJson.serializer(), manifest)
            val zipBytes = VaultBackupZip.build(manifestJson, collected.mapValues { it.value.second })
            if (zipBytes.size > MAX_PLAINTEXT_BYTES) return@withContext ExportResult.Failure("TOO_LARGE")

            val encrypted = VaultBackupCrypto.encrypt(zipBytes, password, iterations)
            out.write(encrypted)
            out.flush()

            ExportResult.Success(manifest.toSummary(skippedOnRead), encrypted.size.toLong())
        } catch (e: IOException) {
            Timber.w(e, "VaultBackup export failed")
            ExportResult.Failure("IO_ERROR")
        } finally {
            password.fill('\u0000')
        }
    }

    internal suspend fun importInternal(
        filesDir: File,
        noBackupFilesDir: File,
        cipher: VaultCipher,
        markLevelAlreadyRewarded: (Int) -> Unit,
        cancelBackgroundWork: () -> Unit = {},
        input: InputStream,
        password: CharArray
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            val bytes = readBounded(input) ?: return@withContext ImportResult.Failure("TOO_LARGE")
            val opened = when (val outcome = openBackup(bytes, password)) {
                is OpenOutcome.Ok -> outcome.opened
                OpenOutcome.WrongPassword -> return@withContext ImportResult.WrongPassword
                OpenOutcome.Corrupted -> return@withContext ImportResult.Corrupted
                OpenOutcome.NewerVersion -> return@withContext ImportResult.NewerVersion
                OpenOutcome.NotABackup -> return@withContext ImportResult.NotABackup
            }

            // A previous import that was killed mid-commit must be undone first: its leftover
            // rollback files would otherwise be overwritten by this import's own set-aside step.
            if (!recoverInterruptedImport(filesDir, noBackupFilesDir)) {
                return@withContext ImportResult.Failure("COMMIT_FAILED")
            }

            // Replace, not merge: a target absent from the manifest (never existed on the source
            // device, or was a derived/public-corpus entry that failed its hash check) is cleared
            // on this device too, rather than left as whatever pre-import state it happened to be in.
            val actions = VaultBackupTargets.ALL.map { target ->
                target to opened.entryBytes[target.name]
            }

            val staged = mutableListOf<Pair<File, File>>() // staging file -> real target file
            try {
                for ((target, plaintext) in actions) {
                    if (plaintext == null) continue
                    val targetFile = target.resolve(filesDir, noBackupFilesDir)
                    targetFile.parentFile?.mkdirs()
                    val stagingFile = File(targetFile.parentFile, "${targetFile.name}$STAGING_SUFFIX")
                    stagingFile.delete()
                    writeTargetBytes(target, stagingFile, cipher, plaintext)
                    staged += stagingFile to targetFile
                }
            } catch (e: Exception) {
                Timber.w(e, "VaultBackup import staging write failed")
                staged.forEach { (staging, _) -> staging.delete() }
                return@withContext ImportResult.Failure("STAGE_WRITE_FAILED")
            }

            // Cancel any in-flight background generation/enrichment before touching real files -
            // a worker mid-write on personal_graph.json (etc.) racing this commit is exactly what
            // would let stale, pre-import data overwrite what's about to be restored.
            cancelBackgroundWork()

            // All-or-nothing: every device file (and its .bak) is first moved aside to an
            // `.import-rollback` name, then the staged files are renamed in. Any failure up to and
            // including the level lock puts the originals back, so the device is never left
            // half old / half imported. A crash instead leaves the on-disk import journal behind,
            // which [recoverInterruptedImport] uses to put the originals back on the next start.
            val journal = ImportCommitJournal()
            val targetFiles = actions.map { (target, _) -> target.resolve(filesDir, noBackupFilesDir) }
            val blockedBeforeImport = targetFiles.filter(VaultReadBlocks::isBlocked)
            try {
                writeImportJournal(filesDir, noBackupFilesDir, actions)
                for ((target, _) in actions) {
                    moveAsideForImport(target.resolve(filesDir, noBackupFilesDir), journal)
                }
                for ((stagingFile, targetFile) in staged) {
                    promoteStagingToTarget(stagingFile, targetFile, journal)
                }
                // The imported files replace whatever failed to read before, so an old read block
                // must not hold back the first read or write of the new content.
                targetFiles.forEach(VaultReadBlocks::release)
                if (opened.entryBytes[TYPING_DNA_FILE_NAME] != null) {
                    val level = TypingDnaRepository(File(filesDir, TYPING_DNA_FILE_NAME), cipher = cipher)
                        .getStats(forceReload = true).level
                    markLevelAlreadyRewarded(level)
                }
                writeAtomically(File(filesDir, COMMITTED_MARKER_FILE_NAME), COMMITTED_MARKER_CONTENT)
            } catch (e: Exception) {
                Timber.w(e, "VaultBackup import commit failed")
                if (rollbackImportCommit(journal)) removeImportJournalFiles(filesDir)
                // The originals are back, including any that could not be read: keep protecting them.
                blockedBeforeImport.forEach { VaultReadBlocks.reblock(it, cipher) }
                return@withContext ImportResult.Failure("COMMIT_FAILED")
            } finally {
                staged.forEach { (staging, _) -> staging.delete() }
            }

            // The committed marker is already durable, so a rollback file that survives here (or a
            // crash before this finishes) is cleaned up by [recoverInterruptedImport], never undone.
            var rollbackFilesDeleted = true
            journal.movedAside.forEach { (_, aside) ->
                if (aside.exists() && !aside.delete()) {
                    Timber.w("VaultBackup import: could not delete rollback file ${aside.name}")
                    rollbackFilesDeleted = false
                }
            }
            if (rollbackFilesDeleted) removeImportJournalFiles(filesDir)
            // A stale in-progress graph-enrichment batch was built from the pre-import corpus;
            // never let it land on top of (or resume into) a freshly imported personal_graph.json.
            // PersonalGraphEnrichmentStagingStore's own code is untouched - this only deletes its file.
            try {
                clearTarget(File(filesDir, GRAPH_ENRICHMENT_STAGING_FILE_NAME))
            } catch (e: IOException) {
                Timber.w(e, "VaultBackup import: could not clear the graph enrichment staging file")
            }

            ImportResult.Success(opened.manifest.toSummary(opened.skippedDerivedEntries))
        } finally {
            password.fill('\u0000')
        }
    }

    private const val TYPING_DNA_FILE_NAME = "typing_dna.json"
    private const val GRAPH_ENRICHMENT_STAGING_FILE_NAME = "personal_graph_staging.json"

    /** Reads [target]'s committed bytes per its [BackupTarget.format], or null if it doesn't exist. */
    private fun readTargetBytes(target: BackupTarget, file: File, cipher: VaultCipher): ByteArray? =
        when (target.format) {
            BackupTargetFormat.VAULT_FILE ->
                VaultFile(file, cipher, VaultFile.aadFor(target.name)).readText()?.toByteArray(Charsets.UTF_8)
            BackupTargetFormat.RAW_FILE -> if (file.isFile) file.readBytes() else null
        }

    /** Writes [plaintext] to [file] per [target]'s [BackupTarget.format]. */
    private fun writeTargetBytes(target: BackupTarget, file: File, cipher: VaultCipher, plaintext: ByteArray) {
        when (target.format) {
            BackupTargetFormat.VAULT_FILE ->
                VaultFile(file, cipher, VaultFile.aadFor(target.name)).writeText(String(plaintext, Charsets.UTF_8))
            BackupTargetFormat.RAW_FILE -> file.writeBytes(plaintext)
        }
    }

    private fun clearTarget(targetFile: File) = VaultFilePathLocks.withLock(targetFile.canonicalPath) {
        targetFile.delete()
        File(targetFile.parentFile, "${targetFile.name}.bak").delete()
        VaultFilePathLocks.incrementRevision(targetFile.canonicalPath)
    }

    private class ImportCommitJournal {
        /** Device file that was renamed to its `.import-rollback` name -> that rollback file. */
        val movedAside = mutableListOf<Pair<File, File>>()

        /** Target files an import rename has already put in place. */
        val placed = mutableListOf<File>()
    }

    private const val ROLLBACK_SUFFIX = ".import-rollback"

    /**
     * Moves [targetFile] and its `.bak` out of the way as `<name>.import-rollback` /
     * `<name>.bak.import-rollback`. Only regular files are moved: anything else at the target
     * path (e.g. a directory) stays, so the staged rename onto it fails and the commit rolls back.
     */
    private fun moveAsideForImport(targetFile: File, journal: ImportCommitJournal) =
        VaultFilePathLocks.withLock(targetFile.canonicalPath) {
            for (file in listOf(targetFile, File(targetFile.parentFile, "${targetFile.name}.bak"))) {
                if (!file.isFile) continue
                val aside = File(file.parentFile, "${file.name}$ROLLBACK_SUFFIX")
                aside.delete()
                if (!file.renameTo(aside)) {
                    throw IOException("Failed to set aside vault file for import: ${file.name}")
                }
                journal.movedAside += file to aside
            }
            VaultFilePathLocks.incrementRevision(targetFile.canonicalPath)
        }

    private fun promoteStagingToTarget(stagingFile: File, targetFile: File, journal: ImportCommitJournal) =
        VaultFilePathLocks.withLock(targetFile.canonicalPath) {
            if (!stagingFile.renameTo(targetFile)) {
                throw IOException("Failed to commit imported vault file: ${targetFile.name}")
            }
            journal.placed += targetFile
            VaultFilePathLocks.incrementRevision(targetFile.canonicalPath)
        }

    /**
     * Removes the target files this import placed and renames the rollback files back. A step that
     * fails is logged and the remaining steps still run; the caller reports the commit as failed
     * either way. Returns false when a step failed, in which case the on-disk import journal must
     * stay so [recoverInterruptedImport] finishes the job on the next start.
     */
    private fun rollbackImportCommit(journal: ImportCommitJournal): Boolean {
        var restored = true
        for (placed in journal.placed.asReversed()) {
            try {
                VaultFilePathLocks.withLock(placed.canonicalPath) {
                    if (placed.exists() && !placed.delete()) {
                        throw IOException("Failed to remove imported vault file: ${placed.name}")
                    }
                    VaultFilePathLocks.incrementRevision(placed.canonicalPath)
                }
            } catch (e: Exception) {
                Timber.e(e, "VaultBackup import rollback: could not remove ${placed.name}")
                restored = false
            }
        }
        for ((original, aside) in journal.movedAside.asReversed()) {
            try {
                VaultFilePathLocks.withLock(original.canonicalPath) {
                    if (!aside.renameTo(original)) {
                        throw IOException("Failed to restore vault file: ${original.name}")
                    }
                    VaultFilePathLocks.incrementRevision(original.canonicalPath)
                }
            } catch (e: Exception) {
                Timber.e(e, "VaultBackup import rollback: could not restore ${original.name}")
                restored = false
            }
        }
        return restored
    }

    // --- Crash recovery. The import commit is a sequence of renames, so a process kill in the
    // middle leaves old and new files mixed. Three small files in filesDir make that recoverable:
    // - JOURNAL_FILE_NAME: written (atomically, fsynced) before the first rename. Only file names:
    //   `place\t<target>` for every target this import puts in place, `aside\t<file>` for every
    //   existing file (target or `<target>.bak`) it is about to move aside.
    // - COMMITTED_MARKER_FILE_NAME: written once every rename and the level lock succeeded and
    //   before the rollback files are deleted. With it present the import counts as done, so a
    //   crash while deleting rollback files finishes the cleanup instead of undoing half of it.
    // Both are removed last. Success is only returned after that, so any rollback file found
    // without the marker means the user never saw a success: the old state is restored. ---

    private const val JOURNAL_FILE_NAME = "vault-import-journal"
    private const val COMMITTED_MARKER_FILE_NAME = "vault-import-committed"
    private const val COMMITTED_MARKER_CONTENT = "committed\n"
    private const val ATOMIC_TMP_SUFFIX = ".tmp"
    private const val STAGING_SUFFIX = ".import-staging"
    private const val JOURNAL_PLACE = "place"
    private const val JOURNAL_ASIDE = "aside"

    private fun writeImportJournal(
        filesDir: File,
        noBackupFilesDir: File,
        actions: List<Pair<BackupTarget, ByteArray?>>
    ) {
        val lines = mutableListOf<String>()
        for ((target, plaintext) in actions) {
            if (plaintext != null) lines += "$JOURNAL_PLACE\t${target.name}"
            val targetFile = target.resolve(filesDir, noBackupFilesDir)
            if (targetFile.isFile) lines += "$JOURNAL_ASIDE\t${target.name}"
            if (File(targetFile.parentFile, "${targetFile.name}.bak").isFile) {
                lines += "$JOURNAL_ASIDE\t${target.name}.bak"
            }
        }
        writeAtomically(File(filesDir, JOURNAL_FILE_NAME), lines.joinToString("\n", postfix = "\n"))
    }

    /** Writes [content] to a temp file next to [file], fsyncs it, then renames it to [file]. */
    private fun writeAtomically(file: File, content: String) {
        val tmp = File(file.parentFile, "${file.name}$ATOMIC_TMP_SUFFIX")
        try {
            FileOutputStream(tmp).use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Failed to publish ${file.name}")
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    private fun removeImportJournalFiles(filesDir: File) {
        // The journal goes before the marker: marker-only still reads as "committed" (finish the
        // cleanup), whereas journal-only would read as "interrupted" (undo).
        for (name in listOf(JOURNAL_FILE_NAME, "$JOURNAL_FILE_NAME$ATOMIC_TMP_SUFFIX", COMMITTED_MARKER_FILE_NAME, "$COMMITTED_MARKER_FILE_NAME$ATOMIC_TMP_SUFFIX")) {
            val file = File(filesDir, name)
            if (file.exists() && !file.delete()) {
                Timber.w("VaultBackup import: could not delete ${file.name}")
            }
        }
    }

    private class ImportJournalContents(val placed: Set<String>, val movedAside: Set<String>)

    private fun readImportJournal(file: File): ImportJournalContents {
        val placed = mutableSetOf<String>()
        val movedAside = mutableSetOf<String>()
        for (line in file.readLines(Charsets.UTF_8)) {
            val kind = line.substringBefore('\t')
            val name = line.substringAfter('\t', "")
            when (kind) {
                JOURNAL_PLACE -> placed += name
                JOURNAL_ASIDE -> movedAside += name
            }
        }
        return ImportJournalContents(placed, movedAside)
    }

    fun recoverInterruptedImport(context: Context): Boolean =
        recoverInterruptedImport(context.filesDir, context.noBackupFilesDir)

    /**
     * Finishes or undoes an import commit that a process kill interrupted. Must run before any
     * vault store reads its file. Returns false when a step failed (logged); the journal then
     * stays so the next call retries.
     *
     * Marker present: the import had succeeded, so only the leftover rollback and staging files
     * are removed. Otherwise the pre-import state is restored: each `<name>.import-rollback`
     * replaces the (new or missing) file, and a target the journal says was newly placed but did
     * not exist before (no `aside` entry) is removed. Without a journal only rollback files are
     * restored.
     */
    internal fun recoverInterruptedImport(filesDir: File, noBackupFilesDir: File): Boolean {
        val journalFile = File(filesDir, JOURNAL_FILE_NAME)
        val committed = File(filesDir, COMMITTED_MARKER_FILE_NAME).exists()
        val targetFiles = VaultBackupTargets.ALL.map { it to it.resolve(filesDir, noBackupFilesDir) }
        val interrupted = journalFile.exists() ||
            File(filesDir, "$JOURNAL_FILE_NAME$ATOMIC_TMP_SUFFIX").exists() ||
            committed ||
            targetFiles.any { (_, file) ->
                File(file.parentFile, "${file.name}$STAGING_SUFFIX").exists() ||
                    File(file.parentFile, "${file.name}$ROLLBACK_SUFFIX").exists() ||
                    File(file.parentFile, "${file.name}.bak$ROLLBACK_SUFFIX").exists()
            }
        if (!interrupted) return true

        val journal = try {
            if (journalFile.exists()) readImportJournal(journalFile) else null
        } catch (e: IOException) {
            Timber.e(e, "VaultBackup import recovery: could not read the import journal")
            return false
        }

        var recovered = true
        for ((target, file) in targetFiles) {
            try {
                VaultFilePathLocks.withLock(file.canonicalPath) {
                    val restoreNewlyPlaced = !committed && journal != null &&
                        target.name in journal.placed && target.name !in journal.movedAside
                    if (!recoverTargetFile(file, committed, deleteIfNoRollback = restoreNewlyPlaced)) recovered = false
                    if (!recoverTargetFile(File(file.parentFile, "${file.name}.bak"), committed, deleteIfNoRollback = false)) {
                        recovered = false
                    }
                    File(file.parentFile, "${file.name}$STAGING_SUFFIX").delete()
                    VaultFilePathLocks.incrementRevision(file.canonicalPath)
                }
            } catch (e: IOException) {
                Timber.e(e, "VaultBackup import recovery: could not recover ${target.name}")
                recovered = false
            }
        }
        if (recovered) removeImportJournalFiles(filesDir)
        return recovered
    }

    private fun recoverTargetFile(file: File, committed: Boolean, deleteIfNoRollback: Boolean): Boolean {
        val rollback = File(file.parentFile, "${file.name}$ROLLBACK_SUFFIX")
        if (committed) {
            if (rollback.exists() && !rollback.delete()) {
                Timber.e("VaultBackup import recovery: could not delete ${rollback.name}")
                return false
            }
            return true
        }
        if (rollback.exists()) {
            if (file.exists() && !file.delete()) {
                Timber.e("VaultBackup import recovery: could not remove ${file.name}")
                return false
            }
            if (!rollback.renameTo(file)) {
                Timber.e("VaultBackup import recovery: could not restore ${file.name}")
                return false
            }
        } else if (deleteIfNoRollback && file.exists() && !file.delete()) {
            Timber.e("VaultBackup import recovery: could not remove ${file.name}")
            return false
        }
        return true
    }

    /** Reads [input] fully, or returns null when it exceeds the largest valid encrypted backup. */
    private fun readBounded(input: InputStream): ByteArray? {
        val limit = VaultBackupCrypto.maxEncryptedBytes(MAX_PLAINTEXT_BYTES)
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > limit) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    // --- shared decrypt+unzip+validate path for inspect() and import() ---

    private data class OpenedBackup(
        val manifest: ManifestJson,
        /** Entry name -> plaintext bytes, or null when declared but skipped (corrupt/missing derived or public-corpus entry). */
        val entryBytes: Map<String, ByteArray?>,
        val skippedDerivedEntries: List<String>
    )

    private sealed interface OpenOutcome {
        data class Ok(val opened: OpenedBackup) : OpenOutcome
        data object WrongPassword : OpenOutcome
        data object Corrupted : OpenOutcome
        data object NewerVersion : OpenOutcome
        data object NotABackup : OpenOutcome
    }

    private fun openBackup(bytes: ByteArray, password: CharArray): OpenOutcome {
        val plaintext = when (val decrypted = VaultBackupCrypto.decrypt(bytes, password)) {
            is VaultBackupCrypto.DecryptResult.Ok -> decrypted.plaintext
            VaultBackupCrypto.DecryptResult.WrongPassword -> return OpenOutcome.WrongPassword
            VaultBackupCrypto.DecryptResult.Corrupted -> return OpenOutcome.Corrupted
            VaultBackupCrypto.DecryptResult.NewerVersion -> return OpenOutcome.NewerVersion
            VaultBackupCrypto.DecryptResult.NotABackup -> return OpenOutcome.NotABackup
        }
        val unpacked = try {
            VaultBackupZip.read(plaintext)
        } catch (e: IOException) {
            Timber.w(e, "VaultBackup zip is truncated or malformed")
            return OpenOutcome.Corrupted
        } ?: return OpenOutcome.Corrupted
        val manifest = try {
            json.decodeFromString(ManifestJson.serializer(), unpacked.manifestJson)
        } catch (e: Exception) {
            Timber.w(e, "VaultBackup manifest decode failed")
            return OpenOutcome.Corrupted
        }
        if (manifest.formatVersion > VaultBackupCrypto.CURRENT_FORMAT_VERSION) return OpenOutcome.NewerVersion
        if (manifest.formatVersion != VaultBackupCrypto.CURRENT_FORMAT_VERSION) return OpenOutcome.Corrupted

        val entryBytes = LinkedHashMap<String, ByteArray?>()
        val skipped = mutableListOf<String>()
        for (declared in manifest.entries) {
            if (declared.name in VaultBackupTargets.LEGACY_IGNORED_ENTRY_NAMES) continue
            val kind = runCatching { BackupEntryKind.valueOf(declared.kind) }.getOrNull()
            val actual = unpacked.entries[declared.name]
            val valid = actual != null && sha256Hex(actual) == declared.sha256
            when {
                valid -> entryBytes[declared.name] = actual
                kind == BackupEntryKind.CORPUS || kind == null -> return OpenOutcome.Corrupted
                else -> {
                    entryBytes[declared.name] = null
                    skipped += declared.name
                }
            }
        }
        return OpenOutcome.Ok(OpenedBackup(manifest, entryBytes, skipped))
    }

    /**
     * Best-effort only: this count feeds the human-readable summary, not the backup's actual
     * content (that comes straight from [VaultFile.readText] above), so a read failure here
     * degrades to "0" in the summary rather than failing the whole export.
     */
    private fun countPersonalSentences(filesDir: File, cipher: VaultCipher): Int = try {
        PersonalSentenceVault(storeFile = File(filesDir, "personal_rag.json"), cipher = cipher).stats().sentences
    } catch (e: Exception) {
        Timber.w(e, "Vault backup: failed to count personal sentences for the summary")
        0
    }

    /** Best-effort only; see [countPersonalSentences]. */
    private fun countPublicMaterials(noBackupFilesDir: File, cipher: VaultCipher): Int = try {
        GeneratedSentenceBank(file = File(noBackupFilesDir, "gemma_materials.json"), cipher = cipher)
            .apply { load() }.sentenceCount
    } catch (e: Exception) {
        Timber.w(e, "Vault backup: failed to count public sentence material for the summary")
        0
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
