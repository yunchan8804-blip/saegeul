/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.fcitx.fcitx5.android.input.ai.sentencepack.MatchEvidence
import org.fcitx.fcitx5.android.input.ai.vault.AesGcmVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultFile
import org.junit.Assert.assertEquals
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

    private companion object {
        const val MODEL_ID = "gemma-4-e2b-it"
        const val SHA = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    }
}
