/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
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
        assertTrue("V=${ngram.vocabularySize}", ngram.vocabularySize >= 100_000)
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
        val results = ngram.nextWords("수신을", "원치", 5)
        assertEquals("않으시면", results.first().word)
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

    private fun assertThrowsIo(block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue("expected IOException, got $thrown", thrown is IOException)
    }
}
