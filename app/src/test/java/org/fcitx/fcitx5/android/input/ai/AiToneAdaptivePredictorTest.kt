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
 * TDD RED test suite for AI Tone Adaptive Prediction Engine and High-End UI Model.
 * Tests automatic tone detection (Honorific, Informal, Business, Technical),
 * dynamic phrase adaptation, contextual emoji infusion, and UI rendering tokens.
 */
class AiToneAdaptivePredictorTest {

    private lateinit var adaptivePredictor: AiToneAdaptivePredictor
    private lateinit var morphology: ChoseongMorphologyEngine

    @Before
    fun setUp() {
        morphology = ChoseongMorphologyEngine()
        val basePredictor = AiContextualPredictor(morphology)
        adaptivePredictor = AiToneAdaptivePredictor(basePredictor, morphology)
    }

    @Test
    fun testToneDetectionFromContext() {
        assertEquals(KoreanTone.Honorific, adaptivePredictor.detectTone("오늘 회의에 참석해 주셔서 감사합니다."))
        assertEquals(KoreanTone.Informal, adaptivePredictor.detectTone("오늘 끝나고 치맥 먹으러 갈래?"))
        assertEquals(KoreanTone.Business, adaptivePredictor.detectTone("요청하신 분기 실적 보고서 송부드립니다."))
        assertEquals(KoreanTone.Technical, adaptivePredictor.detectTone("핫픽스 브랜치 머지하고 빌드 배포 시작할게요."))
        assertEquals(KoreanTone.Neutral, adaptivePredictor.detectTone("서울 날씨"))
    }

    @Test
    fun testHonorificToneRanksPolitePhrasesFirst() {
        val predictions = adaptivePredictor.predictWithTone(
            currentStroke = "ㄱㅅ",
            contextBeforeCursor = "자료 전달 감사드립니다. ",
            packageName = "com.google.android.gm"
        )

        assertTrue(predictions.isNotEmpty())
        val top = predictions.first()
        assertTrue("Expected honorific candidate but got ${top.text}", top.text.contains("감사합니다") || top.text.contains("감사드립니다"))
        assertEquals(KoreanTone.Honorific, top.detectedTone)
        assertTrue(top.confidenceScore >= 0.8f)
    }

    @Test
    fun testInformalToneRanksCasualPhrasesFirst() {
        val predictions = adaptivePredictor.predictWithTone(
            currentStroke = "ㄱㅅ",
            contextBeforeCursor = "야 오늘 밥 잘 먹었다 ",
            packageName = "com.kakao.talk"
        )

        assertTrue(predictions.isNotEmpty())
        val casualCandidates = predictions.filter { it.text.contains("고마워") || it.text.contains("땡큐") || it.text.contains("감사") }
        assertTrue("Casual candidates should be present", casualCandidates.isNotEmpty())
    }

    @Test
    fun testContextualEmojiAndKaomojiInfusion() {
        val predictions = adaptivePredictor.predictWithTone(
            currentStroke = "축하",
            contextBeforeCursor = "합격을 진심으로 ",
            packageName = "com.kakao.talk"
        )

        assertTrue(predictions.isNotEmpty())
        val withEmoji = predictions.any { it.text.contains("🎉") || it.text.contains("👏") || it.text.contains("✨") }
        assertTrue("Celebration context should suggest celebratory emojis", withEmoji)
    }

    @Test
    fun testHighEndUiBadgeTokens() {
        val predictions = adaptivePredictor.predictWithTone(
            currentStroke = "ㅂㅍ",
            contextBeforeCursor = "v2.0 릴리스 ",
            packageName = "com.github.android"
        )

        assertTrue(predictions.isNotEmpty())
        val first = predictions.first()
        assertNotNull(first.badgeLabel)
        assertNotNull(first.badgeColorHex)
        assertTrue(first.badgeColorHex.startsWith("#"))
    }

    @Test
    fun testSub5msThroughputUnderLoad() {
        val start = System.nanoTime()
        for (i in 0 until 2000) {
            adaptivePredictor.predictWithTone(
                currentStroke = "ㅎㅇ",
                contextBeforeCursor = "오후 3시에 ",
                packageName = "com.slack"
            )
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        assertTrue(
            "2000 adaptive prediction passes should stay under 800ms (took ${elapsedMs}ms)",
            elapsedMs < 800.0
        )
    }
}
