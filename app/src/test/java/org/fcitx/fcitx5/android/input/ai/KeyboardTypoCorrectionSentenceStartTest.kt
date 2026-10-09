/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.prediction.source.KeyboardTypoCorrectionSource
import org.fcitx.fcitx5.android.input.ai.prediction.source.PredictionInput
import org.fcitx.fcitx5.android.input.ai.typo.BaseKoreanVocabulary
import org.fcitx.fcitx5.android.input.ai.typo.CorrectionPatternStore
import org.fcitx.fcitx5.android.input.ai.typo.KeyboardAwareTypoCorrector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.StringReader

/**
 * 오타 교정에서 문장 시작 분포(`<s>`)가 문맥 가중으로 더해지는지 확인한다. "가나"는 어휘에 없고,
 * "거나"와 "기나"는 모두 인접 키(ㅏ→ㅓ, ㅏ→ㅣ) 한 번 차이라 키 거리 비용이 같다. 어휘 빈도는
 * "거나"가 높고, 문장 시작 분포는 "기나"가 높다.
 */
class KeyboardTypoCorrectionSentenceStartTest {

    private lateinit var source: KeyboardTypoCorrectionSource

    @Before
    fun setUp() {
        val vocabulary = BaseKoreanVocabulary { StringReader("거나\t1000\n기나\t600\n") }
        vocabulary.load()
        val typoCorrector = KeyboardAwareTypoCorrector()
        vocabulary.forEachWord(BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) { word, prior -> typoCorrector.addWord(word, prior) }
        val bundled = SyntheticKoreanNgram.parse(
            mapOf(BundledKoreanNgram.SENTENCE_START to listOf("기나" to 90, "거나" to 10))
        )
        source = KeyboardTypoCorrectionSource(
            typoCorrector, vocabulary, CorrectionPatternStore(), PersonalNgramModel()
        ) { bundled }
    }

    private fun corrections(stroke: String, context: String): List<String> =
        source.correct(PredictionInput(stroke, context), "com.example.chat").candidates.map { it.text }

    @Test
    fun sentenceStartDistributionReordersCorrectionsForTheFirstWord() {
        for (context in listOf("", "안녕. ", "끝났다?", "줄\n")) {
            val words = corrections("가나", context)
            assertEquals("context=[$context] $words", "기나", words.first())
        }
        assertEquals("기나", corrections("", "가나 ").first())
        assertEquals("기나", corrections("", "안녕. 가나 ").first())
    }

    @Test
    fun midSentenceCorrectionsKeepVocabularyOrder() {
        for (context in listOf("그리고 ", "2023년 ", "Apple ")) {
            val words = corrections("가나", context)
            assertEquals("context=[$context] $words", "거나", words.first())
        }
        assertEquals("거나", corrections("", "그리고 가나 ").first())
    }

    @Test
    fun unreadableTextBeforeCursorGetsNoSentenceStartWeight() {
        for ((stroke, context) in listOf("가나" to "", "" to "가나 ")) {
            val known = source.correct(PredictionInput(stroke, context), "com.example.chat")
            assertEquals("기나", known.candidates.first().text)
            val unknown = source.correct(
                PredictionInput(stroke, context, isContextKnown = false),
                "com.example.chat"
            )
            assertEquals("거나", unknown.candidates.first().text)
        }
    }

    @Test
    fun withoutBundledNgramCorrectionsKeepVocabularyOrder() {
        val vocabulary = BaseKoreanVocabulary { StringReader("거나\t1000\n기나\t600\n") }
        vocabulary.load()
        val typoCorrector = KeyboardAwareTypoCorrector()
        vocabulary.forEachWord(BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) { word, prior -> typoCorrector.addWord(word, prior) }
        val plain = KeyboardTypoCorrectionSource(
            typoCorrector, vocabulary, CorrectionPatternStore(), PersonalNgramModel()
        ) { null }
        val words = plain.correct(PredictionInput("가나", ""), "com.example.chat").candidates.map { it.text }
        assertTrue(words.toString(), words.isNotEmpty())
        assertEquals("거나", words.first())
    }
}
