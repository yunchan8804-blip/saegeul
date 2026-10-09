/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.backup

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaProfile
import org.fcitx.fcitx5.android.input.ai.vault.PlainVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Fast PBKDF2 iteration count for tests; production always uses [VaultBackupCrypto.DEFAULT_PBKDF2_ITERATIONS]. */
private const val TEST_ITERATIONS = 4
private val TEST_PASSWORD = "test-password-123".toCharArray()

class VaultBackupTest {

    @get:Rule
    val filesDir = TemporaryFolder()

    @get:Rule
    val noBackupFilesDir = TemporaryFolder()

    private fun writeVaultFile(name: String, content: String, dir: TemporaryFolder = filesDir) {
        val file = java.io.File(dir.root, name)
        VaultFile(file, PlainVaultCipher, VaultFile.aadFor(name)).writeText(content)
    }

    private fun readVaultFile(name: String, dir: TemporaryFolder = filesDir): String? {
        val file = java.io.File(dir.root, name)
        return VaultFile(file, PlainVaultCipher, VaultFile.aadFor(name)).readText()
    }

    private val dictionaryFile: java.io.File
        get() = java.io.File(noBackupFilesDir.root, "korean-personal-dictionary/words.txt")

    private fun writeRawDictionaryFile(content: String) {
        dictionaryFile.parentFile?.mkdirs()
        dictionaryFile.writeText(content)
    }

    private fun export(password: CharArray = TEST_PASSWORD.copyOf()): Pair<ExportResult, ByteArray> {
        val out = ByteArrayOutputStream()
        val result = runBlocking {
            VaultBackup.exportInternal(
                filesDir = filesDir.root,
                noBackupFilesDir = noBackupFilesDir.root,
                appVersionName = "9.9.9-test",
                cipher = PlainVaultCipher,
                out = out,
                password = password,
                iterations = TEST_ITERATIONS
            )
        }
        return result to out.toByteArray()
    }

    private fun import(
        encrypted: ByteArray,
        password: CharArray = TEST_PASSWORD.copyOf(),
        markLevelAlreadyRewarded: (Int) -> Unit = {},
        cancelBackgroundWork: () -> Unit = {}
    ): ImportResult = runBlocking {
        VaultBackup.importInternal(
            filesDir = filesDir.root,
            noBackupFilesDir = noBackupFilesDir.root,
            cipher = PlainVaultCipher,
            markLevelAlreadyRewarded = markLevelAlreadyRewarded,
            cancelBackgroundWork = cancelBackgroundWork,
            input = ByteArrayInputStream(encrypted),
            password = password
        )
    }

    @Test
    fun exportThenImportRoundTripsEveryTargetByteForByte() = runBlocking {
        writeVaultFile("personal_rag.json", """{"docs":["안녕하세요"]}""")
        writeVaultFile("personalized_sentences.json", """{"records":["상용구 문장"]}""")
        writeVaultFile("typing_dna.json", """{"level":3}""")
        writeVaultFile("personal_ngram.json", """{"uni":{"가":1}}""")
        writeVaultFile("personal_graph.json", """{"nodes":[]}""")
        writeVaultFile("prediction_metrics.json", """{"days":{}}""")
        writeVaultFile("personal_corrections.json", """{"pairs":[]}""")
        writeVaultFile("gemma_materials.json", """{"entries":[]}""", dir = noBackupFilesDir)
        writeRawDictionaryFile("SGPD1\nenabled\t1\nword\tuser\t새글")

        val (exportResult, encrypted) = export()
        assertTrue("export should succeed: $exportResult", exportResult is ExportResult.Success)

        // Wipe the device-side files so the import step can't accidentally "succeed" by simply
        // leaving the originals untouched.
        for (name in listOf(
            "personal_rag.json", "personalized_sentences.json",
            "typing_dna.json", "personal_ngram.json", "personal_graph.json",
            "prediction_metrics.json", "personal_corrections.json"
        )) {
            java.io.File(filesDir.root, name).delete()
        }
        java.io.File(noBackupFilesDir.root, "gemma_materials.json").delete()
        dictionaryFile.delete()

        val importResult = import(encrypted)
        assertTrue("import should succeed: $importResult", importResult is ImportResult.Success)
        val summary = (importResult as ImportResult.Success).summary
        assertTrue("no derived entries should be skipped in a clean round trip", summary.skippedDerivedEntries.isEmpty())

        assertEquals("""{"docs":["안녕하세요"]}""", readVaultFile("personal_rag.json"))
        assertEquals("""{"records":["상용구 문장"]}""", readVaultFile("personalized_sentences.json"))
        assertEquals("""{"level":3}""", readVaultFile("typing_dna.json"))
        assertEquals("""{"uni":{"가":1}}""", readVaultFile("personal_ngram.json"))
        assertEquals("""{"nodes":[]}""", readVaultFile("personal_graph.json"))
        assertEquals("""{"days":{}}""", readVaultFile("prediction_metrics.json"))
        assertEquals("SGPD1\nenabled\t1\nword\tuser\t새글", dictionaryFile.readText())
        assertEquals("""{"pairs":[]}""", readVaultFile("personal_corrections.json"))
        assertEquals("""{"entries":[]}""", readVaultFile("gemma_materials.json", dir = noBackupFilesDir))
    }

    @Test
    fun importReplacesRatherThanMerges_absentEntryClearsExistingDeviceFile() = runBlocking {
        // Source device never had a personal graph.
        writeVaultFile("personal_rag.json", """{"docs":["문장"]}""")
        val (exportResult, encrypted) = export()
        assertTrue(exportResult is ExportResult.Success)

        // Target device already has stale graph data from a previous install.
        writeVaultFile("personal_graph.json", """{"nodes":["오래된"]}""")

        val importResult = import(encrypted)
        assertTrue(importResult is ImportResult.Success)

        assertNull(
            "a target absent from the backup must be cleared, not left as stale pre-import data",
            readVaultFile("personal_graph.json")
        )
    }

    @Test
    fun excludedStoresAreNeverPartOfTheBackup() = runBlocking {
        // Files that must never be touched by vault backup, even if present on disk: the point
        // ledger and the in-progress graph-enrichment staging buffer (the latter is instead
        // unconditionally *deleted* on import commit - see the dedicated test for that).
        java.io.File(filesDir.root, "points").mkdirs()
        java.io.File(filesDir.root, "points/ledger.jsonl").writeText("""{"amount":999999}""")
        writeVaultFile("personal_rag.json", """{"docs":["문장"]}""")

        val (exportResult, encrypted) = export()
        val summary = (exportResult as ExportResult.Success).summary
        val exportedNames = summary.entries.map { it.name }.toSet()

        assertFalse(exportedNames.contains("ledger.jsonl"))
        assertTrue(exportedNames.contains("personal_rag.json"))

        // And a full unzip confirms no unexpected entries at all, not just that our own manifest
        // says so - the manifest is only as trustworthy as the code that wrote it.
        val plaintext = (VaultBackupCrypto.decrypt(encrypted, TEST_PASSWORD.copyOf()) as VaultBackupCrypto.DecryptResult.Ok).plaintext
        val unpacked = VaultBackupZip.read(plaintext)!!
        assertEquals(setOf("personal_rag.json"), unpacked.entries.keys)

        // The excluded file on disk must be untouched by the (unrelated) import too.
        import(encrypted)
        assertEquals("""{"amount":999999}""", java.io.File(filesDir.root, "points/ledger.jsonl").readText())
    }

    @Test
    fun corruptedDerivedEntryStillSucceedsAsLongAsCorpusIsIntact() = runBlocking {
        writeVaultFile("personal_rag.json", """{"docs":["코퍼스는 살아있다"]}""")
        writeVaultFile("personal_ngram.json", """{"uni":{"가":1}}""")
        val (exportResult, encrypted) = export()
        assertTrue(exportResult is ExportResult.Success)

        val corrupted = corruptManifestHashFor(encrypted, "personal_ngram.json")

        val importResult = import(corrupted)
        assertTrue("expected Success, got $importResult", importResult is ImportResult.Success)
        importResult as ImportResult.Success
        assertEquals(listOf("personal_ngram.json"), importResult.summary.skippedDerivedEntries)
        assertEquals("""{"docs":["코퍼스는 살아있다"]}""", readVaultFile("personal_rag.json"))
        assertNull("a skipped derived entry must leave the target cleared, not merged", readVaultFile("personal_ngram.json"))
    }

    @Test
    fun corruptedCorpusEntryFailsTheWholeImport() = runBlocking {
        writeVaultFile("personal_rag.json", """{"docs":["코퍼스"]}""")
        val (exportResult, encrypted) = export()
        assertTrue(exportResult is ExportResult.Success)

        val corrupted = corruptManifestHashFor(encrypted, "personal_rag.json")

        assertEquals(ImportResult.Corrupted, import(corrupted))
    }

    @Test
    fun personalDictionaryIsIncludedAsCorpusEvenOnAFreshDeviceWithNoDictionaryDirectoryYet() = runBlocking {
        writeRawDictionaryFile("SGPD1\nenabled\t1\nword\tuser\t사전단어")
        val (exportResult, encrypted) = export()
        val summary = (exportResult as ExportResult.Success).summary
        val dictionaryEntry = summary.entries.single { it.name == "personal_dictionary_words.txt" }
        assertEquals(BackupEntryKind.CORPUS, dictionaryEntry.kind)

        // Fresh device: the whole "korean-personal-dictionary" directory doesn't exist yet.
        dictionaryFile.delete()
        assertTrue(dictionaryFile.parentFile!!.deleteRecursively())
        assertFalse(dictionaryFile.parentFile!!.exists())

        val importResult = import(encrypted)
        assertTrue("import should succeed: $importResult", importResult is ImportResult.Success)
        assertEquals("SGPD1\nenabled\t1\nword\tuser\t사전단어", dictionaryFile.readText())
    }

    @Test
    fun importCommitAlwaysDeletesTheGraphEnrichmentStagingFile() = runBlocking {
        writeVaultFile("personal_rag.json", """{"docs":["문장"]}""")
        val (_, encrypted) = export()

        // A stale in-progress enrichment batch, built from the pre-import corpus.
        writeVaultFile("personal_graph_staging.json", """{"nextChunkIndex":3}""")

        val importResult = import(encrypted)

        assertTrue(importResult is ImportResult.Success)
        assertNull(
            "the staging buffer must always be cleared on a committed import, never resumed into",
            readVaultFile("personal_graph_staging.json")
        )
    }

    @Test
    fun backgroundWorkIsCancelledBeforeFilesAreCommitted() = runBlocking {
        writeVaultFile("personal_rag.json", """{"docs":["새 코퍼스"]}""")
        val (_, encrypted) = export()
        writeVaultFile("personal_rag.json", """{"docs":["옛 코퍼스"]}""") // still on disk when cancellation runs

        var cancelledBeforeCommit = false
        var cancelCalls = 0
        val result = import(
            encrypted,
            cancelBackgroundWork = {
                cancelCalls++
                cancelledBeforeCommit = readVaultFile("personal_rag.json") == """{"docs":["옛 코퍼스"]}"""
            }
        )

        assertTrue(result is ImportResult.Success)
        assertEquals(1, cancelCalls)
        assertTrue("background work must be cancelled before the commit touches real files", cancelledBeforeCommit)
        assertEquals("""{"docs":["새 코퍼스"]}""", readVaultFile("personal_rag.json"))
    }

    @Test
    fun inspectMirrorsImportsToleranceAndReportsTheSameSkipCount() = runBlocking {
        writeVaultFile("personal_rag.json", """{"docs":["문장"]}""")
        writeVaultFile("personal_graph.json", """{"nodes":[]}""")
        val (_, encrypted) = export()
        val corrupted = corruptManifestHashFor(encrypted, "personal_graph.json")

        val result = VaultBackup.inspect(ByteArrayInputStream(corrupted), TEST_PASSWORD.copyOf())

        assertTrue("expected Valid, got $result", result is InspectResult.Valid)
        result as InspectResult.Valid
        assertEquals(listOf("personal_graph.json"), result.summary.skippedDerivedEntries)
    }

    @Test
    fun plaintextOverTheSizeLimitFailsExportWithoutEncrypting() = runBlocking {
        // Bypass PersonalSentenceVault's own capacity limits: write raw, hard-to-compress content
        // straight into the vault file so the exported ZIP plaintext exceeds MAX_PLAINTEXT_BYTES.
        val random = java.security.SecureRandom()
        // Base64-encoding roughly cancels out Deflate's ~25% reduction on random bytes, so the
        // compressed ZIP plaintext lands close to this raw byte count; add a comfortable margin
        // over the limit so the assertion isn't sensitive to the exact compression ratio.
        val bytes = ByteArray((VaultBackup.MAX_PLAINTEXT_BYTES * 11 / 10).toInt())
        random.nextBytes(bytes)
        val huge = java.util.Base64.getEncoder().encodeToString(bytes)
        writeVaultFile("personal_rag.json", huge)

        val (exportResult, bytesWritten) = export()

        assertEquals(ExportResult.Failure("TOO_LARGE"), exportResult)
        assertEquals(0, bytesWritten.size)
    }

    @Test
    fun wrongPasswordOnInspectIsReportedAsWrongPassword() = runBlocking {
        writeVaultFile("personal_rag.json", """{"docs":["문장"]}""")
        val (_, encrypted) = export()

        val result = VaultBackup.inspect(ByteArrayInputStream(encrypted), "totally-wrong-pw".toCharArray())

        assertEquals(InspectResult.WrongPassword, result)
    }

    @Test
    fun exportRejectsAPasswordShorterThanEightCharacters() {
        writeVaultFile("personal_rag.json", """{"docs":["문장"]}""")
        try {
            export(password = "short1".toCharArray())
            fail("expected an IllegalArgumentException for a too-short password")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun partialCommitFailureLeavesOriginalFilesInPlace() = runBlocking {
        writeVaultFile("personal_rag.json", """{"docs":["원본 코퍼스"]}""")
        val (_, encrypted) = export()

        // Replace the on-device file with a *directory* of the same name so the staged rename
        // over it during commit is guaranteed to fail, simulating a mid-commit I/O failure.
        val target = java.io.File(filesDir.root, "personal_rag.json")
        target.delete()
        target.mkdirs()
        java.io.File(target, "keep-this-directory-non-empty").writeText("x")

        val result = import(encrypted)

        assertTrue("expected a Failure result, got $result", result is ImportResult.Failure)
        assertTrue("the original (directory) target must be left in place on failure", target.isDirectory)
    }

    @Test
    fun failedCommitOfALaterTargetRestoresTargetsThatWereClearedBecauseTheBackupLacksThem() = runBlocking {
        writeVaultFile("personal_rag.json", """{"docs":["백업 코퍼스"]}""")
        val (_, encrypted) = export()

        writeVaultFile("personal_ngram.json", """{"uni":{"가":1}}""")
        writeVaultFile("personal_corrections.json", """{"pairs":["원본"]}""")
        val blocked = java.io.File(filesDir.root, "personal_rag.json")
        blocked.delete()
        blocked.mkdirs()
        java.io.File(blocked, "keep-this-directory-non-empty").writeText("x")

        val result = import(encrypted)

        assertEquals(ImportResult.Failure("COMMIT_FAILED"), result)
        assertEquals("""{"uni":{"가":1}}""", readVaultFile("personal_ngram.json"))
        assertEquals("""{"pairs":["원본"]}""", readVaultFile("personal_corrections.json"))
        assertTrue(blocked.isDirectory)
        assertNoImportLeftovers()
    }

    @Test
    fun failedCommitAfterTypingDnaWasPlacedRestoresTheOriginalLevelAndNeverLocksIt() = runBlocking {
        val highLevelJson = TypingDnaProfile(totalAnalyzedSentences = 5000).toJson()
        val lowLevelJson = TypingDnaProfile(totalAnalyzedSentences = 10).toJson()
        writeVaultFile("typing_dna.json", highLevelJson)
        writeVaultFile("prediction_metrics.json", """{"days":{}}""")
        val (exportResult, encrypted) = export()
        assertTrue(exportResult is ExportResult.Success)

        writeVaultFile("typing_dna.json", lowLevelJson)
        val blocked = java.io.File(filesDir.root, "prediction_metrics.json")
        blocked.delete()
        blocked.mkdirs()
        java.io.File(blocked, "keep-this-directory-non-empty").writeText("x")

        var lockCalls = 0
        val result = import(encrypted, markLevelAlreadyRewarded = { lockCalls++ })

        assertEquals(ImportResult.Failure("COMMIT_FAILED"), result)
        assertEquals(lowLevelJson, readVaultFile("typing_dna.json"))
        assertEquals(0, lockCalls)
        assertNoImportLeftovers()
    }

    @Test
    fun levelLockFailureRollsBackEveryFile() = runBlocking {
        writeVaultFile("personal_rag.json", """{"docs":["백업 코퍼스"]}""")
        writeVaultFile("typing_dna.json", TypingDnaProfile(totalAnalyzedSentences = 5000).toJson())
        val (_, encrypted) = export()

        writeVaultFile("personal_rag.json", """{"docs":["기기 코퍼스"]}""")
        val deviceDna = TypingDnaProfile(totalAnalyzedSentences = 10).toJson()
        writeVaultFile("typing_dna.json", deviceDna)
        writeVaultFile("personal_graph.json", """{"nodes":["기기 그래프"]}""")

        val result = import(
            encrypted,
            markLevelAlreadyRewarded = { throw java.io.IOException("prefs commit failed") }
        )

        assertEquals(ImportResult.Failure("COMMIT_FAILED"), result)
        assertEquals("""{"docs":["기기 코퍼스"]}""", readVaultFile("personal_rag.json"))
        assertEquals(deviceDna, readVaultFile("typing_dna.json"))
        assertEquals("""{"nodes":["기기 그래프"]}""", readVaultFile("personal_graph.json"))
        assertNoImportLeftovers()
    }

    @Test
    fun rollbackAlsoRestoresTheBakFileOfAReplacedTarget() = runBlocking {
        writeVaultFile("personal_rag.json", """{"docs":["백업 코퍼스"]}""")
        writeVaultFile("typing_dna.json", TypingDnaProfile(totalAnalyzedSentences = 5000).toJson())
        val (_, encrypted) = export()

        writeVaultFile("personal_rag.json", """{"docs":["기기 2세대"]}""")
        val target = java.io.File(filesDir.root, "personal_rag.json")
        val bak = java.io.File(filesDir.root, "personal_rag.json.bak")
        bak.writeText("previous-generation-bytes")
        val targetBytes = target.readBytes()
        val bakBytes = bak.readBytes()

        val result = import(encrypted, markLevelAlreadyRewarded = { throw java.io.IOException("prefs commit failed") })

        assertEquals(ImportResult.Failure("COMMIT_FAILED"), result)
        org.junit.Assert.assertArrayEquals(targetBytes, target.readBytes())
        org.junit.Assert.assertArrayEquals(bakBytes, bak.readBytes())
        assertNoImportLeftovers()
    }

    @Test
    fun successfulImportLeavesNoRollbackOrStagingFiles() = runBlocking {
        writeVaultFile("personal_rag.json", """{"docs":["백업 코퍼스"]}""")
        writeVaultFile("typing_dna.json", TypingDnaProfile(totalAnalyzedSentences = 5000).toJson())
        val (_, encrypted) = export()
        writeVaultFile("personal_rag.json", """{"docs":["기기 코퍼스"]}""")
        writeVaultFile("personal_rag.json", """{"docs":["기기 코퍼스 2"]}""")
        writeVaultFile("personal_graph.json", """{"nodes":["기기 그래프"]}""")

        val result = import(encrypted)

        assertTrue("expected Success, got $result", result is ImportResult.Success)
        assertEquals("""{"docs":["백업 코퍼스"]}""", readVaultFile("personal_rag.json"))
        assertNull(readVaultFile("personal_graph.json"))
        assertNoImportLeftovers()
    }

    @Test
    fun exportNeverIncludesTheRawSentencePendingBuffer() = runBlocking {
        writeVaultFile("personal_rag.json", """{"docs":["문장"]}""")
        writeVaultFile("typing_dna_pending.json", """{"pending":["분석 전 원문"]}""")

        val (exportResult, encrypted) = export()

        val summary = (exportResult as ExportResult.Success).summary
        assertFalse(summary.entries.any { it.name == "typing_dna_pending.json" })
        val plaintext = (VaultBackupCrypto.decrypt(encrypted, TEST_PASSWORD.copyOf()) as VaultBackupCrypto.DecryptResult.Ok).plaintext
        assertEquals(setOf("personal_rag.json"), VaultBackupZip.read(plaintext)!!.entries.keys)
    }

    @Test
    fun legacyBackupCarryingThePendingBufferStillImportsAndLeavesTheDevicePendingFileAlone() = runBlocking {
        val legacy = buildBackup(
            mapOf(
                "personal_rag.json" to ("CORPUS" to """{"docs":["백업 코퍼스"]}""".toByteArray()),
                "typing_dna_pending.json" to ("CORPUS" to """{"pending":["옛 백업 원문"]}""".toByteArray())
            )
        )
        writeVaultFile("typing_dna_pending.json", """{"pending":["기기 원문"]}""")

        val result = import(legacy)

        assertTrue("expected Success, got $result", result is ImportResult.Success)
        assertEquals("""{"docs":["백업 코퍼스"]}""", readVaultFile("personal_rag.json"))
        assertEquals("""{"pending":["기기 원문"]}""", readVaultFile("typing_dna_pending.json"))
        assertTrue((result as ImportResult.Success).summary.skippedDerivedEntries.isEmpty())
    }

    @Test
    fun legacyPendingEntryWithAWrongHashIsIgnoredRatherThanCorruptingTheBackup() = runBlocking {
        val legacy = buildBackup(
            mapOf(
                "personal_rag.json" to ("CORPUS" to """{"docs":["백업 코퍼스"]}""".toByteArray()),
                "typing_dna_pending.json" to ("CORPUS" to """{"pending":["옛 백업 원문"]}""".toByteArray())
            ),
            wrongHashFor = "typing_dna_pending.json"
        )

        val result = import(legacy)

        assertTrue("expected Success, got $result", result is ImportResult.Success)
        assertEquals("""{"docs":["백업 코퍼스"]}""", readVaultFile("personal_rag.json"))
    }

    @Test
    fun exportSkipsAnUnreadableDerivedFileAndStillSucceedsWithTheCorpus() = runBlocking {
        writeVaultFile("personal_rag.json", """{"docs":["코퍼스"]}""")
        writeVaultFile("gemma_materials.json", """{"entries":[]}""", dir = noBackupFilesDir)
        writeUndecryptableVaultFile("personal_ngram.json")

        val (exportResult, encrypted) = export()

        assertTrue("expected Success, got $exportResult", exportResult is ExportResult.Success)
        exportResult as ExportResult.Success
        assertEquals(listOf("personal_ngram.json"), exportResult.summary.skippedDerivedEntries)
        assertEquals(setOf("personal_rag.json", "gemma_materials.json"), exportResult.summary.entries.map { it.name }.toSet())
        val plaintext = (VaultBackupCrypto.decrypt(encrypted, TEST_PASSWORD.copyOf()) as VaultBackupCrypto.DecryptResult.Ok).plaintext
        assertEquals(setOf("personal_rag.json", "gemma_materials.json"), VaultBackupZip.read(plaintext)!!.entries.keys)
    }

    @Test
    fun exportSkipsAnUnreadablePublicCorpusFile() = runBlocking {
        writeVaultFile("personal_rag.json", """{"docs":["코퍼스"]}""")
        java.io.File(noBackupFilesDir.root, "gemma_materials.json").writeBytes(undecryptableVaultBytes())

        val (exportResult, _) = export()

        assertTrue("expected Success, got $exportResult", exportResult is ExportResult.Success)
        assertEquals(listOf("gemma_materials.json"), (exportResult as ExportResult.Success).summary.skippedDerivedEntries)
    }

    @Test
    fun exportFailsWithReadFailedWhenACorpusFileIsUnreadableAndWritesNothing() = runBlocking {
        writeUndecryptableVaultFile("personal_rag.json")

        val (exportResult, bytesWritten) = export()

        assertEquals(ExportResult.Failure("READ_FAILED"), exportResult)
        assertEquals(0, bytesWritten.size)
    }

    @Test
    fun zipEntryInflatingPastTheLimitMakesInspectReportCorrupted() = runBlocking {
        val oversized = ByteArray((VaultBackup.MAX_PLAINTEXT_BYTES + 1).toInt())
        val backup = buildBackup(mapOf("personal_rag.json" to ("CORPUS" to oversized)))

        val result = VaultBackup.inspect(ByteArrayInputStream(backup), TEST_PASSWORD.copyOf())

        assertEquals(InspectResult.Corrupted, result)
    }

    @Test
    fun backupWhoseZipIsCutOffMidwayIsReportedAsCorruptedByInspectAndImport() = runBlocking {
        val noise = ByteArray(200_000).also { java.util.Random(7).nextBytes(it) }
        val backup = buildBackup(
            mapOf(
                "personal_rag.json" to ("CORPUS" to noise),
                "personal_graph.json" to ("DERIVED" to noise.reversedArray())
            )
        )
        val plaintext = (VaultBackupCrypto.decrypt(backup, TEST_PASSWORD.copyOf()) as VaultBackupCrypto.DecryptResult.Ok).plaintext
        val truncated = VaultBackupCrypto.encrypt(plaintext.copyOf(plaintext.size / 2), TEST_PASSWORD.copyOf(), TEST_ITERATIONS)
        writeVaultFile("personal_rag.json", """{"docs":["기기 코퍼스"]}""")

        assertEquals(InspectResult.Corrupted, VaultBackup.inspect(ByteArrayInputStream(truncated), TEST_PASSWORD.copyOf()))
        assertEquals(ImportResult.Corrupted, import(truncated))
        assertEquals("""{"docs":["기기 코퍼스"]}""", readVaultFile("personal_rag.json"))
        assertNoImportLeftovers()
    }

    @Test
    fun zipReadAcceptsContentExactlyAtTheLimit() {
        val zip = VaultBackupZip.build("{}", mapOf("a" to ByteArray(10)))

        assertEquals(setOf("a"), VaultBackupZip.read(zip, maxTotalBytes = 12)!!.entries.keys)
        assertNull(VaultBackupZip.read(zip, maxTotalBytes = 11))
    }

    @Test
    fun inputLargerThanTheLargestValidBackupIsRejectedWithoutDecrypting() = runBlocking {
        val limit = VaultBackupCrypto.maxEncryptedBytes(VaultBackup.MAX_PLAINTEXT_BYTES)
        fun endless(size: Long) = object : java.io.InputStream() {
            var remaining = size
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val n = minOf(len.toLong(), remaining).toInt()
                java.util.Arrays.fill(b, off, off + n, 0)
                remaining -= n
                return n
            }
        }

        assertEquals(InspectResult.Corrupted, VaultBackup.inspect(endless(limit + 1), TEST_PASSWORD.copyOf()))
        val importResult = VaultBackup.importInternal(
            filesDir = filesDir.root,
            noBackupFilesDir = noBackupFilesDir.root,
            cipher = PlainVaultCipher,
            markLevelAlreadyRewarded = {},
            input = endless(limit + 1),
            password = TEST_PASSWORD.copyOf()
        )
        assertEquals(ImportResult.Failure("TOO_LARGE"), importResult)
        // At the limit the size gate passes and the (all-zero) payload is just not a backup.
        assertEquals(InspectResult.NotABackup, VaultBackup.inspect(endless(limit), TEST_PASSWORD.copyOf()))
    }

    private fun assertNoImportLeftovers() {
        val leftovers = listOf(filesDir.root, noBackupFilesDir.root)
            .flatMap { it.walkTopDown().filter { f -> f.isFile }.toList() }
            .filter {
                it.name.contains(".import-rollback") || it.name.endsWith(".import-staging") ||
                    it.name.startsWith("vault-import-")
            }
        assertTrue("import must leave no rollback/staging/journal files, found $leftovers", leftovers.isEmpty())
    }

    private fun file(name: String) = java.io.File(filesDir.root, name)

    private fun writeJournal(vararg lines: String) {
        file("vault-import-journal").writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun snapshot(): Map<String, String> =
        listOf(filesDir.root, noBackupFilesDir.root).flatMap { root ->
            root.walkTopDown().filter { it.isFile }.map { it.absolutePath to it.readText() }.toList()
        }.toMap()

    @Test
    fun recoverRestoresEveryTargetToThePreImportStateAfterAMidCommitKill() {
        // Device before the import: rag (+bak) and graph existed, dna and the dictionary did not.
        // The kill came after rag and the dictionary were put in place but before graph was.
        writeJournal(
            "place\tpersonal_rag.json", "place\ttyping_dna.json", "place\tpersonal_dictionary_words.txt",
            "aside\tpersonal_rag.json", "aside\tpersonal_rag.json.bak", "aside\tpersonal_graph.json"
        )
        file("personal_rag.json").writeText("new-rag")
        file("personal_rag.json.import-rollback").writeText("old-rag")
        file("personal_rag.json.bak.import-rollback").writeText("old-rag-bak")
        file("personal_graph.json.import-rollback").writeText("old-graph")
        file("typing_dna.json").writeText("new-dna")
        file("personal_graph.json.import-staging").writeText("new-graph")
        writeRawDictionaryFile("new-dictionary")
        file("personal_ngram.json").writeText("untouched-ngram")

        assertTrue(VaultBackup.recoverInterruptedImport(filesDir.root, noBackupFilesDir.root))

        assertEquals("old-rag", file("personal_rag.json").readText())
        assertEquals("old-rag-bak", file("personal_rag.json.bak").readText())
        assertEquals("old-graph", file("personal_graph.json").readText())
        assertFalse(file("typing_dna.json").exists())
        assertFalse(dictionaryFile.exists())
        assertEquals("untouched-ngram", file("personal_ngram.json").readText())
        assertNoImportLeftovers()
    }

    @Test
    fun recoverRemovesATargetThatTheInterruptedImportPlacedAndThatDidNotExistBefore() {
        writeJournal("place\tpersonal_graph.json", "place\tpersonal_rag.json", "aside\tpersonal_rag.json")
        file("personal_graph.json").writeText("new-graph")
        file("personal_rag.json").writeText("new-rag")
        file("personal_rag.json.import-rollback").writeText("old-rag")

        assertTrue(VaultBackup.recoverInterruptedImport(filesDir.root, noBackupFilesDir.root))

        assertFalse(file("personal_graph.json").exists())
        assertEquals("old-rag", file("personal_rag.json").readText())
        assertNoImportLeftovers()
    }

    @Test
    fun recoverKeepsAnOriginalFileWhenTheKillCameBeforeItWasMovedAside() {
        writeJournal("place\tpersonal_rag.json", "aside\tpersonal_rag.json")
        file("personal_rag.json").writeText("original-rag")
        file("personal_rag.json.import-staging").writeText("new-rag")

        assertTrue(VaultBackup.recoverInterruptedImport(filesDir.root, noBackupFilesDir.root))

        assertEquals("original-rag", file("personal_rag.json").readText())
        assertNoImportLeftovers()
    }

    @Test
    fun recoverWithoutAJournalStillRestoresLeftoverRollbackFiles() {
        file("personal_rag.json").writeText("new-rag")
        file("personal_rag.json.import-rollback").writeText("old-rag")
        file("personal_graph.json").writeText("new-graph")
        file("personal_graph.json.import-staging").writeText("staged")

        assertTrue(VaultBackup.recoverInterruptedImport(filesDir.root, noBackupFilesDir.root))

        assertEquals("old-rag", file("personal_rag.json").readText())
        assertEquals("new-graph", file("personal_graph.json").readText())
        assertNoImportLeftovers()
    }

    @Test
    fun recoverFinishesTheCleanupOfAnImportThatHadAlreadyCommitted() {
        writeJournal("place\tpersonal_rag.json", "place\tpersonal_graph.json", "aside\tpersonal_rag.json")
        file("vault-import-committed").writeText("committed\n")
        file("personal_rag.json").writeText("new-rag")
        file("personal_graph.json").writeText("new-graph")
        file("personal_rag.json.import-rollback").writeText("old-rag")

        assertTrue(VaultBackup.recoverInterruptedImport(filesDir.root, noBackupFilesDir.root))

        assertEquals("new-rag", file("personal_rag.json").readText())
        assertEquals("new-graph", file("personal_graph.json").readText())
        assertNoImportLeftovers()
    }

    @Test
    fun recoverChangesNothingWhenNoImportWasInterrupted() {
        writeVaultFile("personal_rag.json", """{"docs":["문장"]}""")
        file("personal_rag.json.bak").writeText("bak")
        writeRawDictionaryFile("SGPD1\nenabled\t1")
        file("points").mkdirs()
        file("points/ledger.jsonl").writeText("ledger")
        val before = snapshot()

        assertTrue(VaultBackup.recoverInterruptedImport(filesDir.root, noBackupFilesDir.root))

        assertEquals(before, snapshot())
    }

    @Test
    fun importFirstUndoesAnInterruptedImportBeforeCommittingItsOwn() = runBlocking {
        writeVaultFile("personal_rag.json", """{"docs":["백업 코퍼스"]}""")
        writeVaultFile("typing_dna.json", TypingDnaProfile(totalAnalyzedSentences = 5000).toJson())
        val (_, encrypted) = export()
        file("typing_dna.json").delete()
        writeVaultFile("personal_rag.json", """{"docs":["기기 코퍼스"]}""")
        val deviceRag = file("personal_rag.json").readBytes()
        file("personal_rag.json").renameTo(file("personal_rag.json.import-rollback"))
        file("personal_rag.json").writeText("half-imported")
        writeJournal("place\tpersonal_rag.json", "aside\tpersonal_rag.json")

        val result = import(encrypted, markLevelAlreadyRewarded = { throw java.io.IOException("prefs commit failed") })

        // The failing level lock forces this import's own rollback, which must land on the
        // recovered device state rather than on the half-imported file.
        assertEquals(ImportResult.Failure("COMMIT_FAILED"), result)
        org.junit.Assert.assertArrayEquals(deviceRag, file("personal_rag.json").readBytes())
        assertNoImportLeftovers()
    }

    private fun undecryptableVaultBytes(): ByteArray =
        "SGV1".toByteArray() + byteArrayOf(3) + "zzz".toByteArray() + ByteArray(8)

    private fun writeUndecryptableVaultFile(name: String) {
        java.io.File(filesDir.root, name).writeBytes(undecryptableVaultBytes())
    }

    /** Builds an encrypted backup straight from (kind, bytes) pairs, bypassing [VaultBackup.exportInternal]. */
    private fun buildBackup(
        entries: Map<String, Pair<String, ByteArray>>,
        wrongHashFor: String? = null
    ): ByteArray {
        val json = Json { ignoreUnknownKeys = true }
        val manifest = ManifestJson(
            formatVersion = VaultBackupCrypto.CURRENT_FORMAT_VERSION,
            createdAtMs = 1L,
            appVersionName = "legacy",
            personalSentenceCount = 0,
            publicMaterialCount = 0,
            entries = entries.map { (name, kindAndBytes) ->
                val hash = if (name == wrongHashFor) {
                    "0".repeat(64)
                } else {
                    MessageDigest.getInstance("SHA-256").digest(kindAndBytes.second).joinToString("") { "%02x".format(it) }
                }
                ManifestEntryJson(name, kindAndBytes.first, hash)
            }
        )
        val zip = VaultBackupZip.build(
            json.encodeToString(ManifestJson.serializer(), manifest),
            entries.mapValues { it.value.second }
        )
        return VaultBackupCrypto.encrypt(zip, TEST_PASSWORD.copyOf(), TEST_ITERATIONS)
    }

    /**
     * Rebuilds [encrypted] with the manifest's recorded SHA-256 for [entryName] deliberately wrong,
     * while leaving the actual zip entry bytes (and the GCM tag over the whole payload) internally
     * consistent - a case that can only be produced by a bug in the writer, not by an attacker
     * tampering with the ciphertext (which would instead fail the GCM tag and report Corrupted for
     * an entirely different reason - see [VaultBackupCryptoTest]).
     */
    private fun corruptManifestHashFor(encrypted: ByteArray, entryName: String): ByteArray {
        val plaintext = (VaultBackupCrypto.decrypt(encrypted, TEST_PASSWORD.copyOf()) as VaultBackupCrypto.DecryptResult.Ok).plaintext
        val unpacked = VaultBackupZip.read(plaintext)!!
        val json = Json { ignoreUnknownKeys = true }
        val manifest = json.decodeFromString(ManifestJson.serializer(), unpacked.manifestJson)
        val fakeHash = MessageDigest.getInstance("SHA-256").digest("not-the-real-content".toByteArray()).joinToString("") { "%02x".format(it) }
        val corruptedManifest = manifest.copy(
            entries = manifest.entries.map { if (it.name == entryName) it.copy(sha256 = fakeHash) else it }
        )
        val corruptedManifestJson = json.encodeToString(ManifestJson.serializer(), corruptedManifest)
        val rebuiltZip = VaultBackupZip.build(corruptedManifestJson, unpacked.entries)
        return VaultBackupCrypto.encrypt(rebuiltZip, TEST_PASSWORD.copyOf(), TEST_ITERATIONS)
    }
}
