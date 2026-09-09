/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TDD Test ensuring that predictions are grounded in the active background editor context,
 * and that typing text properly trains the local model and triggers personalized augmentation.
 */
class ContextualLearningAndTypingGroundingTest {

    private lateinit var morphology: ChoseongMorphologyEngine
    private lateinit var semanticPredictor: KoreanSemanticSentencePredictor
    private lateinit var store: PersonalizedSentenceStore
    private lateinit var predictor: AiContextualPredictor

    @Before
    fun setUp() {
        morphology = ChoseongMorphologyEngine()
        semanticPredictor = KoreanSemanticSentencePredictor()
        store = PersonalizedSentenceStore(storageFile = null, morphology = morphology)
        predictor = AiContextualPredictor(
            morphology = morphology,
            semanticPredictor = semanticPredictor,
            prefetcher = null,
            personalizedStore = store
        )
    }

    @Test
    fun `learned material with context '내일 판교' returns stored pangyo sentence, not hello greetings`() {
        store.upsert(
            PersonalizedSentenceRecord(
                sentence = "내일 판교에서 만나서 이야기해요.",
                source = PersonalizedSentenceRecord.SOURCE_USER_PHRASE,
                packageName = "com.kakao.talk"
            )
        )
        store.upsert(
            PersonalizedSentenceRecord(
                sentence = "안녕하세요!",
                source = PersonalizedSentenceRecord.SOURCE_USER_PHRASE,
                packageName = "com.kakao.talk"
            )
        )
        val results = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "내일 판교에서 ",
            packageName = "com.kakao.talk",
            limit = 5
        )

        val sentenceResults = results.filter { it.isSentenceCompletion }
        assertEquals(
            listOf("내일 판교에서 만나서 이야기해요."),
            sentenceResults.map { it.text }
        )
        assertTrue("Should have sentence completions for '내일 판교에서'", sentenceResults.isNotEmpty())

        // Must be grounded in Pangyo / meeting, NEVER arbitrary '안녕하세요!'
        val containsPangyoOrMeeting = sentenceResults.any {
            it.text.contains("판교") || it.text.contains("뵙겠") || it.text.contains("만나") || it.text.contains("시간")
        }
        assertTrue("Sentences must be contextually grounded in '판교'", containsPangyoOrMeeting)

        val containsGenericHello = sentenceResults.any { it.text == "안녕하세요!" || it.text == "좋은 하루 보내세요" }
        assertFalse("Sentences must NOT fall back to generic '안녕하세요!' when specific context exists", containsGenericHello)
    }

    @Test
    fun `learned material with context '회의' returns stored meeting sentence`() {
        store.upsert(
            PersonalizedSentenceRecord(
                sentence = "오후 2시 회의 참석 가능하신가요?",
                source = PersonalizedSentenceRecord.SOURCE_USER_PHRASE,
                packageName = "com.slack"
            )
        )
        store.upsert(
            PersonalizedSentenceRecord(
                sentence = "안녕하세요!",
                source = PersonalizedSentenceRecord.SOURCE_USER_PHRASE,
                packageName = "com.slack"
            )
        )
        val results = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "오후 2시 회의 ",
            packageName = "com.slack",
            limit = 5
        )

        val sentenceResults = results.filter { it.isSentenceCompletion }
        assertEquals(
            listOf("오후 2시 회의 참석 가능하신가요?"),
            sentenceResults.map { it.text }
        )
        assertTrue("Should have sentence completions for meeting context", sentenceResults.isNotEmpty())

        val containsMeetingTerms = sentenceResults.any {
            it.text.contains("회의") || it.text.contains("참석") || it.text.contains("안건") || it.text.contains("일정")
        }
        assertTrue("Sentences must be grounded in meeting context", containsMeetingTerms)
        assertFalse("Sentences must not contain '안녕하세요!'", sentenceResults.any { it.text == "안녕하세요!" })
    }

    @Test
    fun `nonempty context with empty store returns no sentence completions`() {
        val results = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "내일 판교에서 ",
            packageName = "com.kakao.talk",
            limit = 5
        )

        val sentenceResults = results.filter { it.isSentenceCompletion }
        assertTrue("Empty learned store must not fabricate sentence completions", sentenceResults.isEmpty())
    }

    @Test
    fun `predict with empty context should NOT force fake sentence completions`() {
        // When context is completely empty and no personalized store items exist,
        // we should not fill the sentence row with forced templates.
        val results = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "",
            packageName = "com.test.app",
            limit = 5
        )

        val sentenceResults = results.filter { it.isSentenceCompletion }
        // Sentences should be empty or only genuine personalized records
        sentenceResults.forEach { s ->
            assertFalse("Should not have fake '추천' badge on sentence", s.badge == "추천")
        }
    }

    @Test
    fun `learnSentence should immediately update transition frequencies for word predictions`() {
        predictor.learnSentence("내일 저녁에 판교에서 치맥 한잔 하자", "com.kakao.talk")

        val predictions = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "내일 저녁에 ",
            packageName = "com.kakao.talk",
            limit = 5
        )

        val wordCandidates = predictions.filter { !it.isSentenceCompletion }.map { it.text }
        assertTrue(
            "Learned transition '판교에서' should be suggested after '저녁에'",
            wordCandidates.contains("판교에서")
        )
    }
}
