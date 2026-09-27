/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.fcitx.fcitx5.android.input.ai.sentencepack.MatchEvidence
import org.fcitx.fcitx5.android.input.ai.vault.AesGcmVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultFile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class GeneratedSentenceBankTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun generatedSentencesPersistEncryptedAndReloadForDifferentPrefixes() {
        val file = file("generated.json")
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val first = GeneratedSentenceBank(file, cipher)

        assertEquals(2, first.addGenerated(responseWith("오늘 회의 끝나고 바로 공유하겠습니다.", "내일 회의 전에 자료를 준비하겠습니다."), MODEL_ID, SHA))
        assertEquals(1, first.sourceCount)
        assertEquals("SGV1", file.readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII))
        val stored = VaultFile(file, cipher, VaultFile.aadFor(file.name)).readText()
        assertTrue(stored?.contains("\"modelId\":\"$MODEL_ID\"") == true)
        assertTrue(stored?.contains("\"modelSha256\":\"$SHA\"") == true)

        val reloaded = GeneratedSentenceBank(file, cipher)
        reloaded.load()

        assertEquals(2, reloaded.sentenceCount)
        assertTrue(reloaded.complete("오늘 회의 ", 3).isNotEmpty())
        assertTrue(reloaded.complete("내일 회의 ", 3).isNotEmpty())
    }

    @Test
    fun addWithoutExplicitLoadMergesExistingEncryptedEntries() {
        val file = file("restart-merge.json")
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        GeneratedSentenceBank(file, cipher).addGenerated(responseWith("오늘 회의 끝나고 다시 연락드리겠습니다."), MODEL_ID, SHA)

        val resumed = GeneratedSentenceBank(file, cipher)
        assertEquals(1, resumed.addGenerated(responseWith("내일 회의 전에 자료를 준비하겠습니다."), MODEL_ID, SHA))
        assertEquals(2, resumed.sentenceCount)

        val reloaded = GeneratedSentenceBank(file, cipher)
        reloaded.load()
        assertEquals(2, reloaded.sentenceCount)
        assertTrue(reloaded.complete("오늘 회의 ", 3).isNotEmpty())
        assertTrue(reloaded.complete("내일 회의 ", 3).isNotEmpty())
    }

    @Test
    fun onlyStrongPrefixAndContextSuffixEvidenceAreReturned() {
        val bank = GeneratedSentenceBank(file("strong-match.json"), AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        bank.addGenerated(responseWith("오늘 회의는 정해진 시간에 진행하겠습니다."), MODEL_ID, SHA)

        assertTrue(bank.complete("회의는", 3).isEmpty())
        val matches = bank.complete("앞 문장은 끝났고 오늘 회의는 ", 3)
        assertTrue(matches.isNotEmpty())
        assertEquals(listOf("정해진 시간에 진행하겠습니다."), matches.map { it.suffix })
        assertTrue(matches.all { it.evidence == MatchEvidence.CONTEXT_SUFFIX })
    }

    @Test
    fun malformedOrPiiResponsePreservesPublishedSnapshot() {
        val bank = GeneratedSentenceBank(file("failure.json"), AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        bank.addGenerated(responseWith("오늘 회의 끝나고 다시 연락드리겠습니다."), MODEL_ID, SHA)
        val revision = bank.revision

        assertFormatFailure { bank.addGenerated("[\"연락처는 010-1234-5678 입니다.\"]", MODEL_ID, SHA) }
        assertFormatFailure { bank.addGenerated("[\"오늘 회의 진행합니다\"]", MODEL_ID, SHA) }

        assertEquals(revision, bank.revision)
        assertEquals(1, bank.sentenceCount)
        assertTrue(bank.complete("오늘 회의 ", 3).isNotEmpty())
    }

    @Test
    fun malformedStoredContentPreservesPublishedSnapshot() {
        val file = file("load-failure.json")
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val bank = GeneratedSentenceBank(file, cipher)
        bank.addGenerated(responseWith("오늘 회의 끝나고 다시 연락드리겠습니다."), MODEL_ID, SHA)
        val revision = bank.revision
        VaultFile(file, cipher, VaultFile.aadFor(file.name)).writeText("{\"version\":1,\"entries\":[{}]}")
        val malformedBytes = file.readBytes()

        assertFormatFailure { bank.load() }

        assertEquals(revision, bank.revision)
        assertEquals(1, bank.sentenceCount)
        assertTrue(malformedBytes.contentEquals(file.readBytes()))

        VaultFile(file, cipher, VaultFile.aadFor(file.name)).writeText("{\"version\":1.5,\"entries\":[]}")
        val unsupportedBytes = file.readBytes()
        assertFormatFailure { bank.load() }
        assertEquals(revision, bank.revision)
        val restarted = GeneratedSentenceBank(file, cipher)
        assertFormatFailure { restarted.addGenerated(responseWith("내일 회의 전에 자료를 준비하겠습니다."), MODEL_ID, SHA) }
        assertTrue(unsupportedBytes.contentEquals(file.readBytes()))
    }

    @Test
    fun capKeepsAtMostTwoThousandUniqueSentences() {
        val bank = GeneratedSentenceBank(file("cap.json"), AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        val response = JSONArrayBuilder.sentences(2_001)

        assertEquals(2_000, bank.addGenerated(response, MODEL_ID, SHA))
        assertEquals(2_000, bank.sentenceCount)
        assertEquals(1, bank.addGenerated(responseWith("첫 번째 고유 문장입니다."), MODEL_ID, SHA))
        assertEquals(2_000, bank.sentenceCount)
        assertTrue(bank.complete("첫 번째 ", 3).isNotEmpty())
    }

    @Test
    fun writeFailurePreservesPublishedSnapshotForAddAndClear() {
        val file = file("write-failure.json")
        val cipher = FailingEncryptCipher(AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        val bank = GeneratedSentenceBank(file, cipher)
        bank.addGenerated(responseWith("오늘 회의 끝나고 다시 연락드리겠습니다."), MODEL_ID, SHA)
        val revision = bank.revision
        val bytes = file.readBytes()
        cipher.failEncrypt = true

        assertIoFailure { bank.addGenerated(responseWith("내일 회의 전에 자료를 준비하겠습니다."), MODEL_ID, SHA) }
        assertEquals(revision, bank.revision)
        assertEquals(1, bank.sentenceCount)
        assertTrue(bytes.contentEquals(file.readBytes()))

        assertIoFailure { bank.clear() }
        assertEquals(revision, bank.revision)
        assertEquals(1, bank.sentenceCount)
        assertTrue(bytes.contentEquals(file.readBytes()))
    }

    @Test
    fun markdownJsonFenceIsAcceptedAndClearRemovesPublishedEntries() {
        val bank = GeneratedSentenceBank(file("fence.json"), AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))

        assertEquals(1, bank.addGenerated("```json\n[\"오늘 회의 끝나고 다시 연락드리겠습니다.\"]\n```", MODEL_ID, SHA))
        bank.clear()

        assertEquals(0, bank.sentenceCount)
        assertTrue(bank.complete("오늘 회의 ", 3).isEmpty())
    }

    @Test
    fun prefixIngestionReportsPartialRejectsAndDuplicates() {
        val bank = GeneratedSentenceBank(file("prefix-ingestion.json"), AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        val first = bank.addGeneratedForPrefix(
            responseWith("회의 자료를 오늘 검토하겠습니다.", "안녕하세요 오늘 반갑습니다."),
            "회의 자료를 ", MODEL_ID, SHA
        )
        assertEquals(1, first.added)
        assertEquals(1, first.rejected)
        val duplicate = bank.addGeneratedForPrefix(
            responseWith("회의 자료를 오늘 검토하겠습니다."),
            "회의 자료를 ", MODEL_ID, SHA
        )
        assertEquals(0, duplicate.added)
        assertEquals(1, duplicate.duplicate)
    }

    @Test
    fun ambiguousAcceptedSuffixDoesNotChangeMaterial() {
        val bank = GeneratedSentenceBank(file("ambiguous-suffix.json"), AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        bank.addGenerated(responseWith("회의 자료를 오늘 확인하겠습니다.", "오늘 저녁 확인하겠습니다."), MODEL_ID, SHA)
        assertEquals(0, bank.recordAcceptedSuffix("확인하겠습니다."))
    }

    @Test
    fun prefixIngestionRejectsBadTerminalPiiAndNonStringIndependently() {
        val bank = GeneratedSentenceBank(file("partial-prefix.json"), AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        val report = bank.addGeneratedForPrefix(
            "[\"회의 자료를 오늘 확인하겠습니다.\",\"회의 자료를 오늘 확인합니다\",\"회의 자료를 010-1234-5678 확인합니다.\",1]",
            "회의 자료를 ", MODEL_ID, SHA
        )
        assertEquals(1, report.added)
        assertEquals(3, report.rejected)
        assertEquals(1, bank.exactPrefixCount("회의 자료를 "))
    }

    @Test
    fun openIngestionAcceptsNewStartsAndPersistsForCompletion() {
        val file = file("open-ingestion.json")
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val bank = GeneratedSentenceBank(file, cipher)

        val report = bank.addGeneratedOpen(
            responseWith("주말 전시 관람 같이 하실래요?", "저녁 메뉴 결정 같이 할까요?"),
            MODEL_ID, SHA
        )
        assertEquals(2, report.added)
        assertEquals(0, report.rejected)
        assertTrue(bank.complete("주말 전시 ", 3).isNotEmpty())

        val reloaded = GeneratedSentenceBank(file, cipher)
        reloaded.load()
        assertTrue(reloaded.complete("저녁 메뉴 ", 3).isNotEmpty())
    }

    @Test
    fun openIngestionRejectsPiiShortRepeatedAndDuplicateWithoutChangingPrefixRules() {
        val bank = GeneratedSentenceBank(file("open-rejections.json"), AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        val response = responseWith(
            "이번 주말 전시에 같이 가실래요?",
            "연락처는 010-1234-5678이에요.",
            "오늘 가요.",
            "오늘 계획을 오늘 계획을 오늘 계획을 확인해요."
        )
        val report = bank.addGeneratedOpen(response, MODEL_ID, SHA)
        assertEquals(1, report.added)
        assertEquals(3, report.rejected)
        assertEquals(1, bank.addGeneratedOpen(responseWith("이번 주말 전시에 같이 가실래요?"), MODEL_ID, SHA).duplicate)

        val prefixReport = bank.addGeneratedForPrefix(
            responseWith("이번 주말 전시에 같이 가실래요?"),
            "회의 자료를 ", MODEL_ID, SHA
        )
        assertEquals(1, prefixReport.rejected)
        assertEquals(IngestionRejectionReason.PREFIX_MISMATCH, prefixReport.rejectionReasons.keys.single())
    }

    @Test
    fun recentPublicStartsUsesNewestTwoWordsDeduplicatesAndLeavesSnapshotUntouched() {
        val file = file("recent-starts.json")
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val bank = GeneratedSentenceBank(file, cipher)
        bank.addGeneratedOpen(responseWith(
            "오늘 회의 자료를 확인해요.",
            "저녁 메뉴를 먼저 정할까요?",
            "오늘 회의 시간을 조정해요."
        ), MODEL_ID, SHA)
        val revision = bank.revision
        val count = bank.sentenceCount

        assertEquals(listOf("오늘 회의 ", "저녁 메뉴를 "), bank.recentPublicStarts())
        assertEquals(listOf("오늘 회의 "), bank.recentPublicStarts(1))
        assertTrue(GeneratedMaterialPolicy.openPromptFor(0, bank.recentPublicStarts()).contains("[\"오늘 회의 \",\"저녁 메뉴를 \"]"))
        assertEquals(revision, bank.revision)
        assertEquals(count, bank.sentenceCount)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { bank.recentPublicStarts(0) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { bank.recentPublicStarts(13) }
    }

    @Test
    fun recentPublicStartsExcludesStoredSentenceWhoseFirstTwoWordsCannotFormPromptHistory() {
        val file = file("non-korean-start.json")
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val bank = GeneratedSentenceBank(file, cipher)
        assertEquals(1, bank.addGeneratedOpen(responseWith("😀 😀 모두 좋은 아침이에요."), MODEL_ID, SHA).added)

        val starts = bank.recentPublicStarts()
        assertTrue(starts.isEmpty())
        assertTrue(GeneratedMaterialPolicy.openPromptFor(0, starts).contains("제외 시작구절 JSON: []"))

        val reloaded = GeneratedSentenceBank(file, cipher)
        reloaded.load()
        assertEquals(1, reloaded.sentenceCount)
        assertTrue(reloaded.complete("😀 😀 ", 3).isNotEmpty())
    }

    @Test
    fun prefixIngestionRejectsRepeatedPrefixAndRepeatedThreeWordWindows() {
        val bank = GeneratedSentenceBank(file("prefix-policy.json"), AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        val report = bank.addGeneratedForPrefix(
            responseWith(
                "회의 자료를 회의 자료를 오늘 확인하겠습니다.",
                "회의 자료를 오늘 확인하고 오늘 확인하고 오늘 확인하겠습니다.",
                "회의 자료를 오늘 확인하겠습니다."
            ),
            "회의 자료를 ", MODEL_ID, SHA
        )
        assertEquals(1, report.added)
        assertEquals(2, report.rejected)
    }

    @Test
    fun prefixIngestionReportsFirstRejectionReasonWithoutSourceText() {
        val bank = GeneratedSentenceBank(file("rejection-reasons.json"), AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        val pii = "회의 자료를 010-1234-5678 확인합니다."
        val report = bank.addGeneratedForPrefix(
            "[1,${JSONObject.quote(pii)},${JSONObject.quote("회의 자료를 오늘 확인\n하겠습니다.")}," +
                "${JSONObject.quote("회의 자료를 오늘 확인하겠습니다")}," +
                "${JSONObject.quote("안녕하세요 오늘 확인하겠습니다.")}," +
                "${JSONObject.quote("회의 자료를 회의 자료를 오늘 확인하겠습니다.")}," +
                "${JSONObject.quote("회의 자료를 오늘 확인하겠습니다. 다음에 연락드리겠습니다.")}]",
            "회의 자료를 ", MODEL_ID, SHA
        )

        assertEquals(0, report.added)
        assertEquals(7, report.rejected)
        assertEquals(
            mapOf(
                IngestionRejectionReason.NON_STRING to 1,
                IngestionRejectionReason.PII to 1,
                IngestionRejectionReason.INVALID_SENTENCE to 1,
                IngestionRejectionReason.MISSING_TERMINAL to 1,
                IngestionRejectionReason.PREFIX_MISMATCH to 1,
                IngestionRejectionReason.REPEATED_PREFIX to 1,
                IngestionRejectionReason.MULTIPLE_SENTENCES to 1
            ),
            report.rejectionReasons
        )
        assertEquals(report.rejected, report.rejectionReasons.values.sum())
        assertFalse(report.toString().contains(pii))
    }

    @Test
    fun prefixIngestionReportsInvalidSentenceAndRepetitionAfterEarlierChecks() {
        val bank = GeneratedSentenceBank(file("rejection-reason-order.json"), AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        val report = bank.addGeneratedForPrefix(
            responseWith(
                "회의 자료를.",
                "회의 자료를 오늘 확인하고 오늘 확인하고 오늘 확인하겠습니다.",
                "회의 자료를 확인했습니다.",
                "회의 자료를 오늘 확인하겠습니다."
            ),
            "회의 자료를 ", MODEL_ID, SHA
        )

        assertEquals(2, report.added)
        assertEquals(2, report.rejected)
        assertEquals(
            mapOf(
                IngestionRejectionReason.INVALID_SENTENCE to 1,
                IngestionRejectionReason.REPETITION to 1
            ),
            report.rejectionReasons
        )
        assertEquals(report.rejected, report.rejectionReasons.values.sum())
    }

    @Test
    fun prefixIngestionRejectsUnknownPrefixInvalidJsonAndOversize() {
        val bank = GeneratedSentenceBank(file("prefix-invalid.json"), AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            bank.addGeneratedForPrefix("[]", "알 수 없는 prefix ", MODEL_ID, SHA)
        }
        assertFormatFailure { bank.addGeneratedForPrefix("not-json", "회의 자료를 ", MODEL_ID, SHA) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            bank.addGeneratedForPrefix("[\"${"가".repeat(70_000)}\"]", "회의 자료를 ", MODEL_ID, SHA)
        }
    }

    @Test
    fun oldschemaWithNoAcceptedCountLoadsAndAcceptancePersists() {
        val file = file("old-schema.json")
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        VaultFile(file, cipher, VaultFile.aadFor(file.name)).writeText(
            "{\"version\":1,\"entries\":[{" +
                "\"text\":\"회의 자료를 오늘 확인하겠습니다.\",\"modelId\":\"$MODEL_ID\",\"modelSha256\":\"$SHA\"}] }"
        )
        val bank = GeneratedSentenceBank(file, cipher)
        bank.load()
        assertEquals(1, bank.recordAcceptedSuffix("확인하겠습니다."))

        val reloaded = GeneratedSentenceBank(file, cipher)
        reloaded.load()
        val stored = JSONObject(requireNotNull(VaultFile(file, cipher, VaultFile.aadFor(file.name)).readText()))
            .getJSONArray("entries").getJSONObject(0)
        assertEquals(1L, stored.getLong("acceptedCount"))
    }

    @Test
    fun retentionKeepsAcceptedOldAndDropsOldUnused() {
        val file = file("accepted-retention.json")
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val bank = GeneratedSentenceBank(file, cipher)
        val initial = buildString {
            append('[')
            repeat(2_000) { index ->
                if (index > 0) append(',')
                append(JSONObject.quote("가 회의 ${hangulToken(index)}."))
            }
            append(']')
        }
        assertEquals(2_000, bank.addGenerated(initial, MODEL_ID, SHA))
        assertEquals(1, bank.recordAcceptedSuffix("${hangulToken(0)}."))
        assertEquals(1, bank.addGenerated(responseWith("가 회의 새문장."), MODEL_ID, SHA))
        val stored = requireNotNull(VaultFile(file, cipher, VaultFile.aadFor(file.name)).readText())
        assertTrue(stored.contains(hangulToken(0)))
        assertTrue(!stored.contains(hangulToken(1)))
        assertTrue(stored.contains("새문장"))
        assertEquals(2_000, bank.sentenceCount)
    }

    @Test
    fun prefixWriteFailurePreservesBytesAndSnapshot() {
        val file = file("prefix-write-failure.json")
        val cipher = FailingEncryptCipher(AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        val bank = GeneratedSentenceBank(file, cipher)
        bank.addGenerated(responseWith("회의 자료를 기존 문장입니다."), MODEL_ID, SHA)
        val revision = bank.revision
        val count = bank.sentenceCount
        val bytes = file.readBytes()
        cipher.failEncrypt = true
        assertIoFailure {
            bank.addGeneratedForPrefix(
                responseWith("회의 자료를 새로 확인하겠습니다."),
                "회의 자료를 ", MODEL_ID, SHA
            )
        }
        assertEquals(revision, bank.revision)
        assertEquals(count, bank.sentenceCount)
        assertTrue(bytes.contentEquals(file.readBytes()))
    }

    @Test
    fun acceptanceMetadataRejectsInvalidValuesAndSaturates() {
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        for (value in listOf("-1", "1.5", Long.MAX_VALUE.toString())) {
            val target = file("metadata-$value.json")
            val vault = VaultFile(target, cipher, VaultFile.aadFor(target.name))
            vault.writeText(
                """{"version":1,"entries":[{"text":"회의 자료를 오늘 확인하겠습니다.","modelId":"$MODEL_ID","modelSha256":"$SHA","acceptedCount":$value}]}"""
            )
            val bank = GeneratedSentenceBank(target, cipher)
            if (value != Long.MAX_VALUE.toString()) {
                assertFormatFailure { bank.load() }
            } else {
                bank.load()
                assertEquals(1, bank.recordAcceptedSuffix("확인하겠습니다."))
                assertEquals(Long.MAX_VALUE, JSONObject(requireNotNull(vault.readText()))
                    .getJSONArray("entries").getJSONObject(0).getLong("acceptedCount"))
            }
        }
    }

    @Test
    fun openIngestionRejectsSpacingLintViolationWithoutChangingMaterial() {
        val bank = GeneratedSentenceBank(file("spacing-open.json"), AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        val report = bank.addGeneratedOpen(
            responseWith("렬하고 자신감있게 하면 되지 않을까싶어요."),
            MODEL_ID, SHA
        )
        assertEquals(0, report.added)
        assertEquals(1, report.rejected)
        assertEquals(IngestionRejectionReason.SPACING, report.rejectionReasons.keys.single())
        assertEquals(0, bank.sentenceCount)
    }

    @Test
    fun prefixIngestionRejectsSpacingLintViolation() {
        val bank = GeneratedSentenceBank(file("spacing-prefix.json"), AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        val report = bank.addGeneratedForPrefix(
            responseWith("회의 자료를 할수있어요."),
            "회의 자료를 ", MODEL_ID, SHA
        )
        assertEquals(0, report.added)
        assertEquals(1, report.rejected)
        assertEquals(IngestionRejectionReason.SPACING, report.rejectionReasons.keys.single())
    }

    @Test
    fun strictAddGeneratedRejectsSpacingLintViolation() {
        val bank = GeneratedSentenceBank(file("spacing-strict.json"), AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        assertFormatFailure { bank.addGenerated(responseWith("먹을것같아서 그냥 왔어요."), MODEL_ID, SHA) }
        assertEquals(0, bank.sentenceCount)
    }

    private fun file(name: String): File = File(tempFolder.root, name)

    private fun responseWith(vararg sentences: String): String =
        sentences.joinToString(prefix = "[", postfix = "]") { JSONObjectQuote.quote(it) }

    private fun assertFormatFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected GeneratedSentenceBankFormatException")
        } catch (_: GeneratedSentenceBankFormatException) {
        }
    }

    private fun assertIoFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected IOException")
        } catch (_: IOException) {
        }
    }

    private class FailingEncryptCipher(private val delegate: VaultCipher) : VaultCipher {
        override val id: String = delegate.id
        var failEncrypt = false

        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            if (failEncrypt) throw IOException("Injected vault encryption failure")
            return delegate.encrypt(plain, aad)
        }

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray = delegate.decrypt(blob, aad)
    }

    private object JSONObjectQuote {
        fun quote(value: String): String = org.json.JSONObject.quote(value)
    }

    private object JSONArrayBuilder {
        fun sentences(count: Int): String = buildString {
            append('[')
            repeat(count) { index ->
                if (index > 0) append(',')
                append(org.json.JSONObject.quote("가 ${uniqueToken(index)} 다."))
            }
            append(']')
        }

        private fun uniqueToken(index: Int): String = buildString {
            append(('가'.code + index / 11172).toChar())
            append(('가'.code + index % 11172).toChar())
        }
    }

    private fun hangulToken(index: Int): String = buildString {
        append(('가'.code + index / 11172).toChar())
        append(('가'.code + index % 11172).toChar())
    }

    private companion object {
        const val MODEL_ID = "gemma-4-e2b-it"
        const val SHA = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    }
}
