/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies AiContextualPredictor wires the on-device [PersonalNgramModel] into real word
 * predictions, and that user-authored phrases outrank synthetic LLM ones at equal score.
 */
class AiContextualPredictorPersonalNgramTest {

    private lateinit var ngram: PersonalNgramModel
    private lateinit var predictor: AiContextualPredictor
    private val packageName = "com.kakao.talk"

    @Before
    fun setUp() {
        ngram = PersonalNgramModel()
        predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            ngram = ngram
        )
    }

    @Test
    fun blankStrokeSurfacesLearnedWordAsTopCandidate() {
        ngram.learn("오늘 회의 참석합니다", packageName)
        ngram.learn("오늘 회의 참석합니다", packageName)

        val results = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "오늘 회의 ",
            packageName = packageName,
            limit = 5
        )

        val wordCandidates = results.filter { !it.isSentenceCompletion }
        assertTrue(wordCandidates.isNotEmpty())
        assertEquals("참석합니다", wordCandidates.first().text)
        assertTrue(wordCandidates.first().confidenceScore >= 0.98f)
    }

    @Test
    fun strokeCompletionSurfacesLearnedWordAsTopCandidate() {
        ngram.learn("오늘 회의 참석합니다", packageName)
        ngram.learn("오늘 회의 참석합니다", packageName)

        val results = predictor.predict(
            currentStroke = "참",
            contextBeforeCursor = "오늘 회의 ",
            packageName = packageName,
            limit = 5
        )

        val wordCandidates = results.filter { !it.isSentenceCompletion }
        assertTrue(wordCandidates.isNotEmpty())
        assertEquals("참석합니다", wordCandidates.first().text)
    }

    @org.junit.Ignore("synthetic 개인 문장은 문장 줄에서 제외(사용자 데이터만). user_phrase 우선순위는 다른 소스와의 관계로만 의미")
    @Test
    fun userPhraseSourceScoresHigherThanSyntheticLlmAtSameScore() {
        val store = PersonalizedSentenceStore()
        store.upsert(
            PersonalizedSentenceRecord(
                sentence = "오늘 회의 참석합니다.",
                source = PersonalizedSentenceRecord.SOURCE_SYNTHETIC_LLM,
                score = 2.0f
            )
        )
        store.upsert(
            PersonalizedSentenceRecord(
                sentence = "오늘 회의 참석하겠습니다.",
                source = PersonalizedSentenceRecord.SOURCE_USER_PHRASE,
                score = 2.0f
            )
        )
        val storePredictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            personalizedStore = store,
            ngram = PersonalNgramModel()
        )

        val results = storePredictor.predict(
            currentStroke = "",
            contextBeforeCursor = "오늘 회의 ",
            packageName = packageName,
            limit = 10
        )

        val syntheticMatch = results.first { it.text == "오늘 회의 참석합니다." }
        val userPhraseMatch = results.first { it.text == "오늘 회의 참석하겠습니다." }
        assertTrue(userPhraseMatch.confidenceScore > syntheticMatch.confidenceScore)
    }
}
