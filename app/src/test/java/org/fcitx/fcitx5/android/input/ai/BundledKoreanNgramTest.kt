/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.IOException

class BundledKoreanNgramTest {

    companion object {
        private lateinit var ngram: BundledKoreanNgram

        private val EXCLUDED_NEXT = setOf("은", "는", "을", "를", "의", "에", "년", "월")

        @BeforeClass
        @JvmStatic
        fun loadAsset() {
            ngram = findAsset().inputStream().use(BundledKoreanNgram::read)
        }

        private fun findAsset(): File {
            val candidates = listOf(
                "app/src/main/assets/${BundledKoreanNgram.ASSET_PATH}",
                "../app/src/main/assets/${BundledKoreanNgram.ASSET_PATH}",
                "src/main/assets/${BundledKoreanNgram.ASSET_PATH}",
                "../src/main/assets/${BundledKoreanNgram.ASSET_PATH}"
            )
            return candidates.map(::File).firstOrNull { it.exists() }
                ?: throw AssertionError("ko-ngram.bin을 찾을 수 없습니다. 시도한 경로: $candidates")
        }
    }

    @Test
    fun loadsRealBundledAsset() {
        // 2026-09-26: the pack is counted from 100M filtered web eojeols (memory-bound build),
        // so the n-gram vocabulary is ~92k rather than the earlier 200M build's >100k.
        assertTrue("V=${ngram.vocabularySize}", ngram.vocabularySize >= 80_000)
    }

    @Test
    fun bigramReturnsFrequentContinuations() {
        val afterWork = ngram.nextWords(null, "퇴근하고", 5).map { it.word }
        assertEquals("집에", afterWork.first())
        val afterMeal = ngram.nextWords(null, "밥", 8).map { it.word }
        assertTrue(afterMeal.toString(), "먹고" in afterMeal)
    }

    @Test
    fun trigramComesBeforeBigramBackoff() {
        val results = ngram.nextWords("커피", "한잔", 5)
        assertEquals("마시고", results.first().word)
        assertEquals(3, results.first().order)
    }

    @Test
    fun unknownContextReturnsNothing() {
        assertTrue(ngram.nextWords(null, "없는어절쀍", 5).isEmpty())
        assertTrue(ngram.nextWords(null, "", 5).isEmpty())
    }

    @Test
    fun resultsRespectLimitAndHaveNoDuplicatesOrExcludedWords() {
        for (prev in listOf("오늘", "그럼", "감사합니다", "올해", "지난", "우리")) {
            val results = ngram.nextWords("그리고", prev, 6)
            assertTrue(results.size <= 6)
            val words = results.map { it.word }
            assertEquals(words.toSet().size, words.size)
            assertTrue(words.toString(), words.none { it in EXCLUDED_NEXT })
            assertTrue(results.all { it.probability > 0f && it.probability <= 1f })
        }
    }

    @Test
    fun rejectsCorruptFiles() {
        val bytes = findAsset().readBytes()
        assertThrowsIo { BundledKoreanNgram.parse(bytes.copyOf(20)) }
        assertThrowsIo { BundledKoreanNgram.parse(bytes.copyOf(bytes.size / 2)) }
        val badMagic = bytes.copyOf().also { it[0] = 'X'.code.toByte() }
        assertThrowsIo { BundledKoreanNgram.parse(badMagic) }
    }

    @Test
    fun contextWordsFollowCorpusTokenization() {
        assertEquals(null to "퇴근하고", BundledKoreanNgram.contextWords("퇴근하고 "))
        assertEquals("오늘" to "퇴근하고", BundledKoreanNgram.contextWords("오늘 퇴근하고 "))
        assertEquals(null to "밥", BundledKoreanNgram.contextWords("\"밥, "))
        assertEquals(null to "오늘", BundledKoreanNgram.contextWords("끝났다. 오늘 "))
        assertEquals(null to "밥", BundledKoreanNgram.contextWords("합니다.밥 "))
        assertEquals(null to "밥", BundledKoreanNgram.contextWords("퇴근하고\n밥 "))
        assertEquals(null to "오늘", BundledKoreanNgram.contextWords("3시에 오늘 "))
        assertNull(BundledKoreanNgram.contextWords("끝났다. "))
        assertNull(BundledKoreanNgram.contextWords("Hello "))
        assertNull(BundledKoreanNgram.contextWords("2023년 "))
        assertNull(BundledKoreanNgram.contextWords(""))
    }

    @Test
    fun isSentenceStartMatchesTextBoundaries() {
        assertTrue(BundledKoreanNgram.isSentenceStart(""))
        assertTrue(BundledKoreanNgram.isSentenceStart("  "))
        assertTrue(BundledKoreanNgram.isSentenceStart("\t "))
        assertTrue(BundledKoreanNgram.isSentenceStart("안녕. "))
        assertTrue(BundledKoreanNgram.isSentenceStart("안녕?"))
        assertTrue(BundledKoreanNgram.isSentenceStart("안녕! "))
        assertTrue(BundledKoreanNgram.isSentenceStart("안녕… "))
        assertTrue(BundledKoreanNgram.isSentenceStart("줄\n"))
        assertFalse(BundledKoreanNgram.isSentenceStart("안녕 "))
        assertFalse(BundledKoreanNgram.isSentenceStart("안녕"))
        assertFalse(BundledKoreanNgram.isSentenceStart("2023년 "))
        assertFalse(BundledKoreanNgram.isSentenceStart("Apple "))
    }

    @Test
    fun contextWordsStripEmojiAndOtherSupplementarySymbols() {
        assertEquals(null to "고마워", BundledKoreanNgram.contextWords("고마워😊 "))
        assertEquals(null to "고마워", BundledKoreanNgram.contextWords("고마워😊"))
        assertEquals("오늘" to "고마워", BundledKoreanNgram.contextWords("오늘 고마워😊😊 "))
        assertEquals(null to "고마워", BundledKoreanNgram.contextWords("😊고마워 "))
        assertEquals("고마워" to "오늘", BundledKoreanNgram.contextWords("고마워😊 오늘 "))
        assertEquals(null to "고마워", BundledKoreanNgram.contextWords("😊고마워😊 "))
    }

    @Test
    fun emojiOnlyTokenBreaksTheContextChainLikeTheCorpusBuild() {
        assertNull(BundledKoreanNgram.contextWords("😊 "))
        assertNull(BundledKoreanNgram.contextWords("고마워 😊 "))
        assertNull(BundledKoreanNgram.contextWords("끝났다.😊 "))
        assertFalse(BundledKoreanNgram.isSentenceStart("😊 "))
        assertFalse(BundledKoreanNgram.isSentenceStart("고마워😊 "))
        assertFalse(BundledKoreanNgram.isSentenceStart("끝났다.😊 "))
        assertTrue(BundledKoreanNgram.isSentenceStart("고마워. 😊\n"))
    }

    @Test
    fun noBreakAndIdeographicSpacesSeparateContextWords() {
        assertEquals("오늘" to "회의", BundledKoreanNgram.contextWords("오늘 회의 "))
        assertEquals("오늘" to "회의", BundledKoreanNgram.contextWords("오늘　회의　"))
        assertTrue(BundledKoreanNgram.isSentenceStart("안녕. "))
        assertTrue(BundledKoreanNgram.isSentenceStart(" 　"))
        assertFalse(BundledKoreanNgram.isSentenceStart("안녕 "))
    }

    private fun sentenceStartNgram() = SyntheticKoreanNgram.parse(
        mapOf(
            BundledKoreanNgram.SENTENCE_START to listOf("그" to 50, "그리고" to 30, "하지만" to 15, "고마워" to 5),
            "퇴근하고" to listOf("집에" to 10)
        )
    )

    @Test
    fun sentenceStartCompletionsFollowStoredOrderAndMatchRules() {
        val synthetic = sentenceStartNgram()
        assertEquals(listOf("그", "그리고", "고마워"), synthetic.sentenceStartCompletions("ㄱ", 5).map { it.word })
        assertEquals(listOf("그", "그리고"), synthetic.sentenceStartCompletions("ㄱ", 2).map { it.word })
        assertEquals(listOf("그", "그리고"), synthetic.sentenceStartCompletions("그", 5).map { it.word })
        assertEquals(listOf("그리고"), synthetic.sentenceStartCompletions("그리", 5).map { it.word })
        assertEquals(listOf("그리고"), synthetic.sentenceStartCompletions("ㄱㄹ", 5).map { it.word })
        assertEquals(listOf("하지만"), synthetic.sentenceStartCompletions("ㅎㅈㅁ", 5).map { it.word })
        assertTrue(synthetic.sentenceStartCompletions("ㅋ", 5).isEmpty())
        assertTrue(synthetic.sentenceStartCompletions("ㄱ", 0).isEmpty())
        val first = synthetic.sentenceStartCompletions("ㄱ", 5).first()
        assertEquals(0.5f, first.probability, 1e-6f)
        assertEquals(2, first.order)
    }

    @Test
    fun sentenceStartProbabilityIsRelativeFrequencyWithinTheStartList() {
        val synthetic = sentenceStartNgram()
        assertEquals(0.5f, synthetic.sentenceStartProbability("그"), 1e-6f)
        assertEquals(0.05f, synthetic.sentenceStartProbability("고마워"), 1e-6f)
        assertEquals(0f, synthetic.sentenceStartProbability("집에"), 0f)
        assertEquals(0f, synthetic.sentenceStartProbability("없는어절"), 0f)
    }

    @Test
    fun binWithoutSentenceStartRowGivesNoStartCandidates() {
        val synthetic = SyntheticKoreanNgram.parse(mapOf("퇴근하고" to listOf("집에" to 10)))
        assertTrue(synthetic.sentenceStartCompletions("ㄱ", 5).isEmpty())
        assertTrue(synthetic.sentenceStartCompletions("집", 5).isEmpty())
        assertEquals(0f, synthetic.sentenceStartProbability("집에"), 0f)
    }

    @Test
    fun realAssetHasSentenceStartDistribution() {
        val top = ngram.sentenceStartCompletions("ㄱ", 5).map { it.word }
        assertTrue(top.toString(), "그" in top && "그리고" in top)
        assertTrue(ngram.sentenceStartProbability("그") > ngram.sentenceStartProbability("그러나"))
        assertTrue(ngram.sentenceStartProbability("그리고") > 0f)
        val total = ngram.sentenceStartCompletions("", Int.MAX_VALUE).sumOf { it.probability.toDouble() }
        assertEquals(1.0, total, 1e-3)
    }

    private fun assertThrowsIo(block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue("expected IOException, got $thrown", thrown is IOException)
    }
}
