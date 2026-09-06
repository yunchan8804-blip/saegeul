/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Extensive TDD Test Suite for AI Realtime Stroke-Level Next Word & Sentence Predictor Engine.
 * Tests jaso decomposition, n-gram context learning, choseong matching, app context personalization,
 * zero-latency score ranking, and confidence decay.
 */
class AiStrokeLevelPredictorTest {

    private lateinit var predictor: AiContextualPredictor
    private lateinit var lexicon: PersonalizedLexiconModel
    private lateinit var morphology: ChoseongMorphologyEngine

    @Before
    fun setUp() {
        lexicon = PersonalizedLexiconModel(maxCapacity = 1000)
        morphology = ChoseongMorphologyEngine()
        predictor = AiContextualPredictor(lexicon, morphology)
    }

    @Test
    fun testHangulJasoDecompositionAndChoseongExtraction() {
        assertEquals("ㅎㅏㄴ", morphology.decomposeHangul("한"))
        assertEquals("ㄱㅡㄹ", morphology.decomposeHangul("글"))
        assertEquals("ㅅㅐ", morphology.decomposeHangul("새"))
        assertEquals("ㄱ", morphology.extractChoseong("가"))
        assertEquals("ㅅㄱ", morphology.extractChoseongSequence("새글"))
        assertEquals("ㅇㄴㅎㅅㅇ", morphology.extractChoseongSequence("안녕하세요"))
    }

    @Test
    fun testInitialGreetingAndCommonPredictorSuggestions() {
        // When user types '안'
        val suggestions = predictor.predict(currentStroke = "안", contextBeforeCursor = "", packageName = "com.kakao.talk")
        assertTrue(suggestions.isNotEmpty())
        assertTrue(suggestions.any { it.text.startsWith("안녕하세요") || it.text == "안녕하세요" })
        assertTrue(suggestions.any { it.text.contains("안녕") })
    }

    @Test
    fun testChoseongAbbreviationRealtimeCompletion() {
        // Typing 'ㄱㅅ' suggests '감사합니다', '고맙습니다'
        val suggestions = predictor.predict(currentStroke = "ㄱㅅ", contextBeforeCursor = "", packageName = "com.kakao.talk")
        assertTrue(suggestions.isNotEmpty())
        assertTrue(suggestions.any { it.text == "감사합니다" || it.text.startsWith("감사") })
    }

    @Test
    fun testPersonalizedContextLearningAndNGramBoost() {
        val appContext = "com.slack"
        // Simulate user frequently typing "배포 완료했습니다" in Slack
        for (i in 0 until 5) {
            predictor.learnSentence("배포 완료했습니다", packageName = appContext)
        }

        // Now when typing "배포 " in Slack, "완료했습니다" should be top #1 prediction with high confidence
        val predictions = predictor.predict(currentStroke = "", contextBeforeCursor = "배포 ", packageName = appContext)
        assertTrue(predictions.isNotEmpty())
        assertEquals("완료했습니다", predictions[0].text)
        assertTrue(predictions[0].confidenceScore > 0.8f)
    }

    @Test
    fun testDifferentPredictionsPerAppPackage() {
        // Work context in Slack
        predictor.learnSentence("회의 참석 부탁드립니다", packageName = "com.slack")
        // Casual context in KakaoTalk
        predictor.learnSentence("회의 끝나고 밥 먹자", packageName = "com.kakao.talk")

        val slackPredictions = predictor.predict(currentStroke = "", contextBeforeCursor = "회의 ", packageName = "com.slack")
        val kakaoPredictions = predictor.predict(currentStroke = "", contextBeforeCursor = "회의 ", packageName = "com.kakao.talk")

        assertTrue(slackPredictions.any { it.text.contains("참석") || it.text.contains("부탁드립니다") })
        assertTrue(kakaoPredictions.any { it.text.contains("끝나고") || it.text.contains("밥") || it.text.contains("먹자") })
    }

    @Test
    fun testIncrementalStrokeByStrokePredictionRefinement() {
        // Stroke sequence: 'ㅎ' -> '회' -> '회의'
        val step1 = predictor.predict(currentStroke = "ㅎ", contextBeforeCursor = "", packageName = "com.slack")
        val step2 = predictor.predict(currentStroke = "회", contextBeforeCursor = "", packageName = "com.slack")
        val step3 = predictor.predict(currentStroke = "회의", contextBeforeCursor = "", packageName = "com.slack")

        assertTrue(step1.isNotEmpty())
        assertTrue(step2.isNotEmpty())
        assertTrue(step3.isNotEmpty())
        assertTrue(step3.any { it.text.startsWith("회의") })
    }

    @Test
    fun testLRUCapacityPruningAndMemorySafety() {
        val smallLexicon = PersonalizedLexiconModel(maxCapacity = 50)
        val smallPredictor = AiContextualPredictor(smallLexicon, morphology)

        for (i in 0 until 500) {
            smallPredictor.learnSentence("단어_$i 다음단어_$i", packageName = "com.test")
        }

        assertEquals(50, smallLexicon.size())
    }

    @Test
    fun testPredictionResponseTimeBenchmark() {
        // Warm up JIT compiler and class loading
        for (i in 0 until 1000) {
            predictor.predict(currentStroke = "안", contextBeforeCursor = "오늘 ", packageName = "com.kakao.talk")
        }

        val startTime = System.nanoTime()
        for (i in 0 until 5000) {
            predictor.predict(currentStroke = "안", contextBeforeCursor = "오늘 ", packageName = "com.kakao.talk")
        }
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        assertTrue("5000 prediction passes must complete in under 500ms (took ${elapsedMs}ms)", elapsedMs < 500)
    }

    @Test
    fun testTypoSentenceCorrectionWithComposingStroke() {
        val predictions = predictor.predict(
            currentStroke = "까",
            contextBeforeCursor = "난 그걸하고 시프니까",
            packageName = "com.kakao.talk",
            limit = 10
        )
        assertTrue(predictions.isNotEmpty())
        assertTrue("Must propose word correction 싶으니까", predictions.any { it.text == "싶으니까" && !it.isSentenceCompletion })
        assertTrue("Must propose sentence correction 난 그걸하고 싶으니까", predictions.any { it.text == "난 그걸하고 싶으니까" && it.isSentenceCompletion })
    }

    @Test
    fun testCollocationNextWordPredictionOnTrailingSpace() {
        // When typing "오늘 " with space, KoreanCollocationModel should suggest natural next words
        val predictions = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "오늘 ",
            packageName = "com.kakao.talk",
            limit = 5
        )
        assertTrue("Collocation next words must not be empty", predictions.isNotEmpty())
        assertTrue(
            "Must suggest common bigrams for 오늘 such as 저녁, 점심, 회의",
            predictions.any { it.text == "저녁" || it.text == "점심" || it.text == "회의" || it.text == "일정" }
        )
    }

    @Test
    fun testParticleDirectedNextWordPrediction() {
        // "회의를 " -> suggests verbs like "확인했습니다", "부탁드립니다"
        val predictions = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "회의를 ",
            packageName = "com.slack",
            limit = 5
        )
        assertTrue(predictions.isNotEmpty())
        assertTrue(
            "Must suggest particle-directed verb completions for -를",
            predictions.any { it.text.contains("확인했습니다") || it.text.contains("부탁드립니다") || it.text.contains("진행하겠습니다") }
        )
    }

    @Test
    fun testExtendedChoseongMobileAbbreviations() {
        // ㅈㅅ -> 죄송합니다
        val js = predictor.predict(currentStroke = "ㅈㅅ", contextBeforeCursor = "", packageName = "com.kakao.talk")
        assertTrue(js.any { it.text.contains("죄송") })

        // ㅇㅋ -> 알겠습니다 / 알겠어
        val ok = predictor.predict(currentStroke = "ㅇㅋ", contextBeforeCursor = "", packageName = "com.kakao.talk")
        assertTrue(ok.any { it.text.contains("알겠") || it.text.contains("오케이") })

        // ㅅㄱ -> 수고하셨습니다 / 수고했어
        val sg = predictor.predict(currentStroke = "ㅅㄱ", contextBeforeCursor = "", packageName = "com.kakao.talk")
        assertTrue(sg.any { it.text.contains("수고") })

        // ㅊㅋ -> 축하드립니다
        val ck = predictor.predict(currentStroke = "ㅊㅋ", contextBeforeCursor = "", packageName = "com.kakao.talk")
        assertTrue(ck.any { it.text.contains("축하") })
    }
}
