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
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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
                val bytes = input.readBytes()
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
     * worker that is mid-write when the import commit starts doesn't race it. A cancellation that
     * doesn't resolve in time is logged and the import proceeds anyway - see [VaultBackup]'s import
     * commit step for why the file-level locking there is the actual last line of defense.
     */
    private fun cancelBackgroundGemmaWorkAndWait(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val operations = GEMMA_BACKGROUND_WORK_NAMES.map { name -> workManager.cancelUniqueWork(name) }
        val deadline = System.currentTimeMillis() + WORK_CANCEL_TIMEOUT_MS
        operations.forEach { operation ->
            val remainingMs = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
            try {
                operation.result.get(remainingMs, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Timber.w(e, "Vault backup import: a background Gemma work cancellation didn't resolve in time")
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
            for (target in VaultBackupTargets.ALL) {
                val file = target.resolve(filesDir, noBackupFilesDir)
                val bytes = readTargetBytes(target, file, cipher) ?: continue
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

            ExportResult.Success(manifest.toSummary(emptyList()), encrypted.size.toLong())
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
            val bytes = input.readBytes()
            val opened = when (val outcome = openBackup(bytes, password)) {
                is OpenOutcome.Ok -> outcome.opened
                OpenOutcome.WrongPassword -> return@withContext ImportResult.WrongPassword
                OpenOutcome.Corrupted -> return@withContext ImportResult.Corrupted
                OpenOutcome.NewerVersion -> return@withContext ImportResult.NewerVersion
                OpenOutcome.NotABackup -> return@withContext ImportResult.NotABackup
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
                    val stagingFile = File(targetFile.parentFile, "${targetFile.name}.import-staging")
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

            try {
                for ((target, plaintext) in actions) {
                    val targetFile = target.resolve(filesDir, noBackupFilesDir)
                    if (plaintext != null) continue // handled via staged rename below
                    clearTarget(targetFile)
                }
                for ((stagingFile, targetFile) in staged) {
                    promoteStagingToTarget(stagingFile, targetFile)
                }
                // A stale in-progress graph-enrichment batch was built from the pre-import corpus;
                // never let it land on top of (or resume into) a freshly imported personal_graph.json.
                // PersonalGraphEnrichmentStagingStore's own code is untouched - this only deletes its file.
                clearTarget(File(filesDir, GRAPH_ENRICHMENT_STAGING_FILE_NAME))
            } catch (e: Exception) {
                Timber.w(e, "VaultBackup import commit failed")
                return@withContext ImportResult.Failure("COMMIT_FAILED")
            } finally {
                staged.forEach { (staging, _) -> staging.delete() }
            }

            if (opened.entryBytes[TYPING_DNA_FILE_NAME] != null) {
                val level = TypingDnaRepository(File(filesDir, TYPING_DNA_FILE_NAME), cipher = cipher)
                    .getStats(forceReload = true).level
                markLevelAlreadyRewarded(level)
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

    private fun promoteStagingToTarget(stagingFile: File, targetFile: File) =
        VaultFilePathLocks.withLock(targetFile.canonicalPath) {
            targetFile.delete()
            File(targetFile.parentFile, "${targetFile.name}.bak").delete()
            if (!stagingFile.renameTo(targetFile)) {
                throw IOException("Failed to commit imported vault file: ${targetFile.name}")
            }
            VaultFilePathLocks.incrementRevision(targetFile.canonicalPath)
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
        val unpacked = VaultBackupZip.read(plaintext) ?: return OpenOutcome.Corrupted
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
