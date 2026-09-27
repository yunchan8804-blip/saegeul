/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MockEditor 기반 AI 문장 추천 단위 통합 검사.
 * 관측 문장 저장, 문맥 검색, 후보 매핑, 편집기 삽입을 검사한다.
 * 실제 Android 기기 E2E 검사는 아니다.
 */
class SemanticSentenceRecommendationE2ETest {

    private lateinit var semanticPredictor: KoreanSemanticSentencePredictor
    private lateinit var personalSentenceVault: PersonalSentenceVault
    private lateinit var contextualPredictor: AiContextualPredictor

    /**
     * Simulated Text Editor representing Android InputConnection text state.
     */
    class MockEditor(var textBeforeCursor: String = "") {
        fun getTextBeforeCursor(length: Int, flags: Int): String {
            return if (textBeforeCursor.length > length) {
                textBeforeCursor.takeLast(length)
            } else {
                textBeforeCursor
            }
        }

        fun commitText(text: String): Boolean {
            textBeforeCursor += text
            return true
        }
    }

    @Before
    fun setUp() {
        semanticPredictor = KoreanSemanticSentencePredictor()
        personalSentenceVault = PersonalSentenceVault(storeFile = null)
        contextualPredictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            semanticPredictor = semanticPredictor,
            personalSentenceVault = personalSentenceVault
        )
    }

    private fun observeSentence(sentence: String, packageName: String) {
        contextualPredictor.learnSentence(sentence, packageName)
        assertTrue(personalSentenceVault.record(sentence, packageName))
    }

    private fun getContextualCandidateWords(
        editor: MockEditor,
        packageName: String = "com.kakao.talk",
        limit: Int = 4
    ): List<CandidateWord> {
        val beforeCursor = editor.getTextBeforeCursor(2048, 0)
        if (beforeCursor.isBlank()) return emptyList()

        val predictions = contextualPredictor.predict(
            currentStroke = "",
            contextBeforeCursor = beforeCursor,
            packageName = packageName,
            limit = limit
        )

        return predictions.mapIndexed { index, pred ->
            CandidateWord(
                label = (index + 1).toString(),
                text = pred.text,
                comment = pred.badge
            )
        }
    }

    private fun commitCandidate(editor: MockEditor, candidate: CandidateWord): Boolean {
        val sentence = candidate.text
        val textToCommit = if (sentence.endsWith(" ") || sentence.endsWith("\n")) sentence else "$sentence "
        return editor.commitText(textToCommit)
    }

    @Test
    fun testE2E_ToneConsistencyAcrossStyles() {
        // Sentence-line tone consistency comes from observed personal sentences retrieved for
        // the user's current context.
        // 1. Honorific
        observeSentence("회의 참석하겠습니다", "com.kakao.talk")
        val honorificEditor = MockEditor("회의 참석")
        val honorificCandidates = getContextualCandidateWords(honorificEditor)
        assertTrue(honorificCandidates.isNotEmpty())
        assertTrue(honorificCandidates.any {
            it.text.startsWith("회의 참석") &&
                (it.text.endsWith("습니다") || it.text.endsWith("드립니다") || it.text.endsWith("세요"))
        })

        // 2. Informal
        observeSentence("뭐 확인했어", "com.kakao.talk")
        val informalEditor = MockEditor("뭐 확인")
        val informalCandidates = getContextualCandidateWords(informalEditor)
        assertTrue(informalCandidates.isNotEmpty())
        assertTrue(informalCandidates.any {
            it.text.startsWith("뭐 확인") &&
                (it.text.endsWith("할게") || it.text.endsWith("했어") || it.text.endsWith("하자"))
        })
    }

    @Test
    fun testE2E_StrokeVsBlankPriorities() {
        val context = "오늘 회의 내용 "
        // The observed sentence is retrieved as a continuation of the typed context.
        observeSentence("오늘 회의 내용 정리했습니다", "com.kakao.talk")

        // 1. Blank stroke: Full sentences should have top confidence
        val blankPredictions = contextualPredictor.predict(
            currentStroke = "",
            contextBeforeCursor = context,
            packageName = "com.kakao.talk",
            limit = 3
        )
        assertTrue(blankPredictions.isNotEmpty())
        assertTrue(blankPredictions.first().isSentenceCompletion)
        assertTrue(blankPredictions.first().confidenceScore >= 0.90f)

        // 2. Active stroke: Choseong match should prioritize typed keyword
        val strokePredictions = contextualPredictor.predict(
            currentStroke = "ㅎㅇ", // Choseong for 회의
            contextBeforeCursor = context,
            packageName = "com.kakao.talk",
            limit = 3
        )
        assertTrue(strokePredictions.isNotEmpty())
        assertTrue(strokePredictions.any { it.text.contains("회의") })
    }

    @Test
    fun testE2E_NextWordChainingFlow() {
        // User starts with "오늘 "
        val editor = MockEditor("오늘 ")
        val step1Candidates = getContextualCandidateWords(editor, limit = 5)
        assertTrue("Next words after '오늘 ' must be generated", step1Candidates.isNotEmpty())

        // Find a word suggestion (e.g. "저녁" or "회의")
        val chosenWord = step1Candidates.firstOrNull { it.text == "저녁" || it.text == "회의" || it.text == "점심" }
        assertNotNull("Should propose high-frequency next word", chosenWord)

        // Simulate committing the chosen word
        commitCandidate(editor, chosenWord!!)
        assertEquals("오늘 ${chosenWord.text} ", editor.textBeforeCursor)

        // Step 2: Editor now has "오늘 저녁 " or "오늘 회의 "
        val step2Candidates = getContextualCandidateWords(editor, limit = 5)
        assertTrue("Subsequent candidates must be generated", step2Candidates.isNotEmpty())
    }
}
