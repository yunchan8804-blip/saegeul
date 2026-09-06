/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.core.CandidateWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * End-to-End (E2E) Integration Tests for AI-driven Contextual Sentence Recommendation.
 * Validates the entire user input lifecycle:
 * Context Ingestion -> Intent & Entity Extraction -> Slot-Filling Sentence Synthesis ->
 * Polarity/Nuance Filtering -> Candidate Mapping -> Selection & Trailing Whitespace Commit.
 */
class SemanticSentenceRecommendationE2ETest {

    private lateinit var semanticPredictor: KoreanSemanticSentencePredictor
    private lateinit var prefetcher: AiSentenceCompletionPrefetcher
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
        prefetcher = AiSentenceCompletionPrefetcher(clientProvider = null)
        contextualPredictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            semanticPredictor = semanticPredictor,
            prefetcher = prefetcher
        )
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

    @Ignore("고정 템플릿·엔티티 합성 문장 제거: 문장 줄은 학습·입력 기반으로 전환")
    @Test
    fun testE2E_WorkAndDeploymentFlow() {
        val editor = MockEditor("서버 작업 마무리되었고 머지 요청드렸습니다. 배포 준비 중인데 ")
        val candidates = getContextualCandidateWords(editor, limit = 4)

        assertTrue("Work progress candidates must be generated", candidates.isNotEmpty())

        val topCandidate = candidates.first()
        assertNotNull(topCandidate)
        assertTrue(
            "Candidate should relate to work progress / deployment",
            candidates.any { it.text.contains("배포") || it.text.contains("모니터링") || it.text.contains("승인") || it.text.contains("확인") }
        )

        // Simulate user clicking on candidate 0
        val committed = commitCandidate(editor, topCandidate)
        assertTrue(committed)

        // Verify editor buffer now contains the chosen sentence followed by a clean space
        assertTrue(editor.textBeforeCursor.endsWith(" "))
        assertTrue(editor.textBeforeCursor.contains(topCandidate.text))
    }

    @Ignore("고정 템플릿·엔티티 합성 문장 제거: 문장 줄은 학습·입력 기반으로 전환")
    @Test
    fun testE2E_SchedulingWithEntitySlotFilling() {
        val editor = MockEditor("내일 판교에서 3시 회의")
        val candidates = getContextualCandidateWords(editor, limit = 4)

        assertTrue(candidates.isNotEmpty())

        // Verify that extracted entities (판교, 3시, 회의) were synthesized into personalized suggestions
        val customized = candidates.filter { it.comment.contains("맞춤AI") || it.text.contains("판교") || it.text.contains("3시") }
        assertTrue("At least one synthesized slot-filled sentence should be present", customized.isNotEmpty())

        val chosen = customized.first()
        commitCandidate(editor, chosen)
        assertTrue(editor.textBeforeCursor.contains(chosen.text))
        assertTrue(editor.textBeforeCursor.endsWith(" "))
    }

    @Ignore("고정 템플릿·엔티티 합성 문장 제거: 문장 줄은 학습·입력 기반으로 전환")
    @Test
    fun testE2E_NuancePolarityRejectionGuard() {
        val editor = MockEditor("정말 죄송하지만 이번 주말에는 선약이 있어서 참석이 어렵습니다.")
        val candidates = getContextualCandidateWords(editor, limit = 5)

        assertTrue(candidates.isNotEmpty())

        // Affirmative acceptance sentences must NEVER appear when user is declining
        assertFalse("Must not suggest positive agreement when user is declining",
            candidates.any { it.text.contains("그때 뵙겠습니다") || it.text.contains("그렇게 진행하시죠") }
        )

        // Must provide polite declining or alternative rescheduling
        assertTrue("Must propose polite alternatives / rescheduling",
            candidates.any { it.text.contains("다음") || it.text.contains("양해") || it.text.contains("조율") }
        )
    }

    @Test
    fun testE2E_ToneConsistencyAcrossStyles() {
        // Sentence-line tone consistency is now carried by input_continuation, which appends a
        // tone-matching ending onto a personal-n-gram-trained continuable input.
        // 1. Honorific
        contextualPredictor.learnSentence("회의 참석하겠습니다", "com.kakao.talk")
        val honorificEditor = MockEditor("회의 참석")
        val honorificCandidates = getContextualCandidateWords(honorificEditor)
        assertTrue(honorificCandidates.isNotEmpty())
        assertTrue(honorificCandidates.any {
            it.text.startsWith("회의 참석") &&
                (it.text.endsWith("습니다") || it.text.endsWith("드립니다") || it.text.endsWith("세요"))
        })

        // 2. Informal
        contextualPredictor.learnSentence("뭐 확인했어", "com.kakao.talk")
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
        // "내용" is not a 하다-명사, so the sentence-line result here only comes from
        // input_continuation's personal-n-gram chaining, which needs this trained first.
        contextualPredictor.learnSentence("오늘 회의 내용 정리했습니다", "com.kakao.talk")

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

    @Ignore("고정 템플릿·엔티티 합성 문장 제거: 문장 줄은 학습·입력 기반으로 전환")
    @Test
    fun testE2E_DesignpacaBadgeIntegrity() {
        val editor = MockEditor("오후 3시에 회의 가능하실까요?")
        val candidates = getContextualCandidateWords(editor, limit = 4)

        assertTrue(candidates.isNotEmpty())
        // Every contextual candidate must have a meaningful badge for Designpaca UI rendering
        candidates.forEach { candidate ->
            assertTrue(
                "Comment badge must not be blank for Designpaca chip rendering",
                candidate.comment.isNotBlank()
            )
            assertTrue(
                "Comment badge must be a valid Designpaca category",
                candidate.comment.contains("AI") ||
                    candidate.comment.contains("일정") ||
                    candidate.comment.contains("답변") ||
                    candidate.comment.contains("업무") ||
                    candidate.comment.contains("제안") ||
                    candidate.comment.contains("구문") ||
                    candidate.comment.contains("내스타일") ||
                    candidate.comment.contains("맞춤") ||
                    candidate.comment.contains("동의") ||
                    candidate.comment.contains("응원") ||
                    candidate.comment.contains("현황") ||
                    candidate.comment.contains("요청") ||
                    candidate.comment.contains("감사") ||
                    candidate.comment.contains("안심") ||
                    candidate.comment.contains("안부") ||
                    candidate.comment.contains("인사") ||
                    candidate.comment.contains("⚡")
            )
        }
    }

    @Ignore("고정 템플릿·엔티티 합성 문장 제거: 문장 줄은 학습·입력 기반으로 전환")
    @Test
    fun testE2E_CompositeMultiEntityAndMealSynthesis() {
        // 1. Time + Place + Topic
        val tripleEditor = MockEditor("내일 판교에서 3시에 회의")
        val tripleCandidates = getContextualCandidateWords(tripleEditor, limit = 5)
        assertTrue(tripleCandidates.isNotEmpty())
        assertTrue(
            "Should synthesize composite sentences with time, place, and topic",
            tripleCandidates.any { it.text.contains("판교") && (it.text.contains("회의") || it.text.contains("3시")) }
        )

        // 2. Place + Meal
        val mealEditor = MockEditor("오늘 저녁 강남에서 점심 ")
        val mealCandidates = getContextualCandidateWords(mealEditor, limit = 4)
        assertTrue(mealCandidates.isNotEmpty())
        assertTrue(
            "Should synthesize meal recommendation near place",
            mealCandidates.any { it.text.contains("강남") || it.text.contains("식사") || it.text.contains("점심") }
        )
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
