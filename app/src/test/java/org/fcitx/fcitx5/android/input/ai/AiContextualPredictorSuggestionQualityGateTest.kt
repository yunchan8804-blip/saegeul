/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A committed sentence with a common typing mistake (typo, run-together spacing, doubled word,
 * or a stray compat jamo left mid-word) still gets learned into the user's on-device personal
 * n-gram/RAG models exactly like any other sentence (no user data is deleted). What must not
 * happen is that mistake resurfacing verbatim as a suggestion: [AiContextualPredictor] runs every
 * candidate through [org.fcitx.fcitx5.android.input.ai.rule.SuggestionQualityGate] right before
 * returning, so the original mistake text never reaches the candidate list.
 */
class AiContextualPredictorSuggestionQualityGateTest {

    private lateinit var ngram: PersonalNgramModel
    private lateinit var ragVault: PersonalSentenceVault
    private lateinit var predictor: AiContextualPredictor
    private val packageName = "com.kakao.talk"

    @Before
    fun setUp() {
        ngram = PersonalNgramModel()
        ragVault = PersonalSentenceVault()
        predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            ngram = ngram,
            personalSentenceVault = ragVault
        )
    }

    @Test
    fun jamoMixedTypoLearnedIntoPersonalNgramNeverSurfacesAsWordCandidate() {
        ngram.learn("안녕ㅎ 오늘 뭐해.", packageName)
        ngram.learn("안녕ㅎ 오늘 뭐해.", packageName)

        val results = predictor.predict(
            currentStroke = "안",
            contextBeforeCursor = "",
            packageName = packageName,
            limit = 8
        )

        assertFalse(results.any { it.text.contains("안녕ㅎ") })
    }

    @Test
    fun runTogetherSpacingTypoLearnedIntoPersonalNgramNeverSurfacesAsWordCandidate() {
        ngram.learn("그건 잘 몰겠는데 될까싶어서 다시 확인했어요.", packageName)
        ngram.learn("그건 잘 몰겠는데 될까싶어서 다시 확인했어요.", packageName)

        val results = predictor.predict(
            currentStroke = "될",
            contextBeforeCursor = "",
            packageName = packageName,
            limit = 8
        )

        assertFalse(results.any { it.text.contains("될까싶어서") })
    }

    @Test
    fun mistakeSentenceRecordedIntoRagVaultNeverSurfacesVerbatim() {
        ragVault.record("안녕ㅎ 오늘 뭐해.", packageName)

        val results = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "오늘 뭐",
            packageName = packageName,
            limit = 8
        )

        assertFalse(results.any { it.text.contains("안녕ㅎ") })
    }

    @Test
    fun consecutiveDuplicateWordSentenceRecordedIntoRagVaultNeverSurfacesVerbatim() {
        ragVault.record("회의 회의 끝나고 바로 갈게요.", packageName)

        val results = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "회의 끝나고",
            packageName = packageName,
            limit = 8
        )

        assertFalse(results.any { it.text.contains("회의 회의") })
    }

    @Test
    fun ordinaryLearnedSentenceStillSurfaces() {
        // 게이트가 과잉 차단하지 않는지 함께 확인한다: 정상 문장은 그대로 학습되고 노출된다.
        ngram.learn("오늘 회의 자료 준비해서 보내드릴게요.", packageName)
        ngram.learn("오늘 회의 자료 준비해서 보내드릴게요.", packageName)

        val results = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "오늘 회의 ",
            packageName = packageName,
            limit = 8
        )

        assertTrue(results.any { it.text.contains("자료") })
    }
}
