/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.typo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests for KeyboardAwareTypoCorrector: keyboard-adjacent typo correction,
 * fuzzy prefix completion, context-boosted ranking, and correction latency
 * against the full bundled vocabulary.
 */
class KeyboardAwareTypoCorrectorTest {

    private fun buildCorrector(): KeyboardAwareTypoCorrector {
        val corrector = KeyboardAwareTypoCorrector()
        val words = listOf(
            "감사합니다" to 1.0f,
            "감사" to 0.5f,
            "사무실" to 0.5f,
            "합니다" to 0.5f,
            "안녕" to 0.5f,
            "판교" to 0.5f,
            "판사" to 0.5f,
            "오늘" to 0.5f,
            "회의" to 0.5f
        )
        words.forEach { (word, prior) -> corrector.addWord(word, prior) }
        return corrector
    }

    private fun findVocabFile(): File {
        val candidates = listOf(
            "app/src/main/assets/ko_base_vocab.tsv",
            "../app/src/main/assets/ko_base_vocab.tsv",
            "src/main/assets/ko_base_vocab.tsv",
            "../src/main/assets/ko_base_vocab.tsv"
        )
        for (path in candidates) {
            val file = File(path)
            if (file.exists()) return file
        }
        throw AssertionError(
            "ko_base_vocab.tsv를 찾을 수 없습니다. 시도한 경로: $candidates, cwd=${File(".").absolutePath}"
        )
    }

    @Test
    fun correctsPhoneticSyllableTypo() {
        val corrector = buildCorrector()
        val results = corrector.correct("사묘ㅏ함니다")
        assertTrue(results.isNotEmpty())
        assertEquals("감사합니다", results[0].word)
        assertTrue("cost=${results[0].cost}", results[0].cost <= 1.3f)
    }

    @Test
    fun correctsAdjacentKeyTypo() {
        val corrector = buildCorrector()
        val results = corrector.correct("판고")
        assertTrue(results.isNotEmpty())
        assertEquals("판교", results[0].word)
    }

    @Test
    fun correctsAlternateSurfaceFormWithZeroCost() {
        val corrector = buildCorrector()
        val results = corrector.correct("안녀ㅇ")
        assertTrue(results.isNotEmpty())
        assertEquals("안녕", results[0].word)
        assertEquals(0f, results[0].cost, 0.0001f)
    }

    @Test
    fun doesNotSuggestTypedWordItself() {
        val corrector = buildCorrector()
        val results = corrector.correct("감사합니다")
        assertTrue(results.none { it.word == "감사합니다" })
    }

    @Test
    fun returnsEmptyForUnrelatedJamoRun() {
        val corrector = buildCorrector()
        val results = corrector.correct("ㅋㅋㅋㅋ")
        assertTrue(results.isEmpty())
    }

    @Test
    fun completeFuzzyMatchesPrefixSubtree() {
        val corrector = buildCorrector()
        val words = corrector.completeFuzzy("감ㅅ").map { it.word }
        assertTrue(words.contains("감사합니다"))
        assertTrue(words.contains("감사"))
    }

    @Test
    fun contextBoostChangesRanking() {
        val corrector = buildCorrector()
        val default = corrector.correct("판고", limit = 2)
        assertEquals("판교", default[0].word)

        val boosted = corrector.correct(
            "판고",
            limit = 2,
            contextBoost = { word -> if (word == "판사") 2.0f else 0f }
        )
        assertEquals("판사", boosted[0].word)
    }

    @Test
    fun correctsWithinLatencyBudgetAgainstFullVocabulary() {
        val vocabFile = findVocabFile()
        val vocabulary = BaseKoreanVocabulary { vocabFile.reader(Charsets.UTF_8) }
        vocabulary.load()
        assertTrue("어휘 로드 실패", vocabulary.size() >= 20_000)

        val corrector = KeyboardAwareTypoCorrector()
        vocabulary.forEachWord { word, prior -> corrector.addWord(word, prior) }

        // warm-up
        corrector.correct("사묘ㅏ함니다")

        val iterations = 20
        var lastTop: String? = null
        val start = System.nanoTime()
        repeat(iterations) {
            lastTop = corrector.correct("사묘ㅏ함니다").firstOrNull()?.word
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0 / iterations

        assertTrue("평균 응답 시간이 80ms를 초과함: ${elapsedMs}ms", elapsedMs <= 80.0)
        assertEquals("감사합니다", lastTop)
    }
}
