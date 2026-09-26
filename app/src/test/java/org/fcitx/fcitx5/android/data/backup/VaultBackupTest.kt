/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.backup

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
        writeVaultFile("typing_dna_pending.json", """{"pending":["대기 문장"]}""")
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
            "personal_rag.json", "personalized_sentences.json", "typing_dna_pending.json",
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
        assertEquals("""{"pending":["대기 문장"]}""", readVaultFile("typing_dna_pending.json"))
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
