/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.typo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringReader

/**
 * Unit tests for BaseKoreanVocabulary: TSV parsing, log-scaled prior, and
 * surface/jamo/choseong prefix completion, including a load of the real
 * bundled asset.
 */
class BaseKoreanVocabularyTest {

    private val sampleTsv = """
        # test vocab
        가방	100
        가위	50
        것	80
        것은	40
        감사	200
    """.trimIndent()

    private fun buildVocabulary(): BaseKoreanVocabulary {
        val vocabulary = BaseKoreanVocabulary { StringReader(sampleTsv) }
        vocabulary.load()
        return vocabulary
    }

    @Test
    fun loadsOnlyOnceAndParsesEntries() {
        val vocabulary = buildVocabulary()
        assertEquals(5, vocabulary.size())
        assertTrue(vocabulary.isLoaded)
    }

    @Test
    fun containsChecksExactSurfaceForm() {
        val vocabulary = buildVocabulary()
        assertTrue(vocabulary.contains("감사"))
        assertFalse(vocabulary.contains("없는말"))
    }

    @Test
    fun priorIsLogScaledAgainstMaxCount() {
        val vocabulary = buildVocabulary()
        assertEquals(1.0f, vocabulary.prior("감사"), 0.0001f)
        assertEquals(0f, vocabulary.prior("없는말"), 0.0001f)
        assertTrue(vocabulary.prior("가위") in 0f..1f)
        assertTrue(vocabulary.prior("가방") > vocabulary.prior("가위"))
    }

    @Test
    fun completionsMatchByChoseongPrefix() {
        val vocabulary = buildVocabulary()
        val words = vocabulary.completions("ㄱ", 10).map { it.first }
        assertTrue(words.contains("것"))
        assertTrue(words.contains("감사"))
    }

    @Test
    fun completionsMatchChoseongPrefixDistinctFromJamoPrefix() {
        val vocabulary = buildVocabulary()
        // "감사"의 자모열은 "ㄱㅏㅁㅅㅏ"라서 "ㄱㅅ"으로 시작하지 않지만,
        // 초성열은 "ㄱㅅ"이라 초성 전용 스트로크로만 매칭된다.
        val words = vocabulary.completions("ㄱㅅ", 10).map { it.first }
        assertTrue(words.contains("감사"))
    }

    @Test
    fun completionsMatchBySurfacePrefix() {
        val vocabulary = buildVocabulary()
        val words = vocabulary.completions("것", 10).map { it.first }
        assertTrue(words.contains("것"))
        assertTrue(words.contains("것은"))
        assertFalse(words.contains("가방"))
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
    fun loadsRealBundledAsset() {
        val vocabFile = findVocabFile()
        val vocabulary = BaseKoreanVocabulary { vocabFile.reader(Charsets.UTF_8) }
        vocabulary.load()

        assertTrue("size=${vocabulary.size()}", vocabulary.size() >= 20_000)
        assertTrue(vocabulary.contains("감사합니다"))

        val words = vocabulary.completions("ㄱ", 10).map { it.first }
        assertTrue(words.contains("것"))
    }

    // 같은 첫 글자·같은 초성을 다수 공유하는 16개 단어. 접두 인덱스 경로가 전수 스캔과
    // 정확히 같은 집합·순서를 내는지 검증한다. count가 모두 서로 달라 prior 동점이
    // 없으므로 기대 순서는 count 내림차순으로 결정론적이다.
    private val overlappingTsv = """
        가방	100
        가위	90
        가족	80
        가지	70
        가수	60
        감사	200
        감기	150
        감자	50
        감정	40
        것	30
        것은	20
        고양이	250
        구름	45
        나무	300
        다리	10
        라디오	5
    """.trimIndent()

    private fun buildOverlappingVocabulary(): BaseKoreanVocabulary {
        val vocabulary = BaseKoreanVocabulary { StringReader(overlappingTsv) }
        vocabulary.load()
        return vocabulary
    }

    @Test
    fun completionsIndexedPathMatchesFullScanForChoseongPrefix() {
        val vocabulary = buildOverlappingVocabulary()

        val words = vocabulary.completions("ㄱ", 10).map { it.first }

        assertEquals(
            listOf("고양이", "감사", "감기", "가방", "가위", "가족", "가지", "가수", "감자", "구름"),
            words
        )
    }

    @Test
    fun completionsIndexedPathMatchesFullScanForSurfacePrefixGa() {
        val vocabulary = buildOverlappingVocabulary()

        val words = vocabulary.completions("가", 10).map { it.first }

        assertEquals(listOf("가방", "가위", "가족", "가지", "가수"), words)
    }

    @Test
    fun completionsIndexedPathMatchesFullScanForSurfacePrefixGam() {
        val vocabulary = buildOverlappingVocabulary()

        val words = vocabulary.completions("감", 10).map { it.first }

        assertEquals(listOf("감사", "감기", "감자", "감정"), words)
    }

    @Test
    fun completionsPerformanceStaysWithinBudgetOnRealAsset() {
        val vocabFile = findVocabFile()
        val vocabulary = BaseKoreanVocabulary { vocabFile.reader(Charsets.UTF_8) }
        vocabulary.load()

        repeat(5) { vocabulary.completions("가", 8) }

        val iterations = 1000
        val start = System.nanoTime()
        repeat(iterations) {
            vocabulary.completions("가", 8)
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0 / iterations

        assertTrue("completions() averaged ${elapsedMs}ms per call, expected <= 20ms", elapsedMs <= 20.0)
    }
}
