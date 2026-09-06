/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TDD tests for Speculative Asynchronous Background LLM Prefetcher & Smart Cache.
 * Validates context key normalization, punctuation resilience, whitespace collapsing,
 * LRU cache eviction, and cache hits.
 */
class AiSentenceCompletionPrefetcherTest {

    private lateinit var prefetcher: AiSentenceCompletionPrefetcher

    @Before
    fun setUp() {
        prefetcher = AiSentenceCompletionPrefetcher(
            clientProvider = null,
            maxCacheCapacity = 5
        )
    }

    @Test
    fun testContextKeyNormalizationCollapsesWhitespaceAndTrimsPunctuation() {
        val raw1 = "오늘  회의가  몇 시에 시작하나요?   "
        val raw2 = "오늘 회의가 몇 시에 시작하나요."
        val raw3 = "오늘 회의가 몇 시에 시작하나요"
        val raw4 = "오늘 회의가 몇 시에 시작하나요!~"

        val norm1 = prefetcher.normalizeContextKey(raw1)
        val norm2 = prefetcher.normalizeContextKey(raw2)
        val norm3 = prefetcher.normalizeContextKey(raw3)
        val norm4 = prefetcher.normalizeContextKey(raw4)

        assertEquals("오늘 회의가 몇 시에 시작하나요", norm1)
        assertEquals("오늘 회의가 몇 시에 시작하나요", norm2)
        assertEquals("오늘 회의가 몇 시에 시작하나요", norm3)
        assertEquals("오늘 회의가 몇 시에 시작하나요", norm4)
    }

    @Test
    fun testCacheHitAcrossPunctuationAndSpacingVariations() {
        val proposals = listOf(
            "오후 2시에 대회의실에서 진행됩니다.",
            "아직 확정되지 않았습니다.",
            "일정 확인 후 공유해 드리겠습니다."
        )

        // Store with trailing question mark and extra space
        prefetcher.putPredictions("오늘 회의가 몇 시에 시작하나요?  ", proposals)

        // Retrieve with trailing period or different spacing
        val cachedFromPeriod = prefetcher.getCachedPredictions("오늘  회의가 몇 시에 시작하나요.")
        assertNotNull(cachedFromPeriod)
        assertEquals(3, cachedFromPeriod!!.size)
        assertEquals("오후 2시에 대회의실에서 진행됩니다.", cachedFromPeriod[0])

        val cachedFromBare = prefetcher.getCachedPredictions("오늘 회의가 몇 시에 시작하나요")
        assertNotNull(cachedFromBare)
        assertEquals(proposals, cachedFromBare)
    }

    @Test
    fun testPrefixMatchFallbackWhenTypingContinues() {
        val baseContext = "오늘 점심 뭐"
        val proposals = listOf("먹을까?", "먹을래?", "추천해줘")
        prefetcher.putPredictions(baseContext, proposals)

        // Exact match
        assertEquals(proposals, prefetcher.getCachedPredictions("오늘 점심 뭐"))

        // User typed space and next character "먹" -> "오늘 점심 뭐 먹"
        val prefixHit = prefetcher.getCachedPredictions("오늘 점심 뭐 먹")
        assertNotNull(prefixHit)
        assertEquals(proposals, prefixHit)
    }

    @Test
    fun testLruCacheEvictionMaintainsMaxCapacity() {
        for (i in 1..10) {
            prefetcher.putPredictions("컨텍스트 번호 $i", listOf("결과 $i"))
        }

        // Only the last 5 should remain (capacity = 5)
        assertNull(prefetcher.getCachedPredictions("컨텍스트 번호 1"))
        assertNull(prefetcher.getCachedPredictions("컨텍스트 번호 5"))
        assertNotNull(prefetcher.getCachedPredictions("컨텍스트 번호 6"))
        assertNotNull(prefetcher.getCachedPredictions("컨텍스트 번호 10"))
    }

    @Test
    fun testEnrichedTypoCorrectionsInEngine() {
        val typoEngine = KoreanTypoCorrectionEngine()
        val corrected1 = typoEngine.correct("잇슴")
        assertTrue(corrected1.contains("있음"))

        val corrected2 = typoEngine.correct("알겟습니다")
        assertTrue(corrected2.contains("알겠습니다"))

        val corrected3 = typoEngine.correct("모르겟어")
        assertTrue(corrected3.contains("모르겠어"))

        val sentenceCorrected = typoEngine.correctSentence("모르겟어 내가 알겟습니다 잇슴")
        assertEquals("모르겠어 내가 알겠습니다 있음", sentenceCorrected)
    }

    @Test
    fun testLlmCachedPredictionsDifferentiateWordsSentencesAndTypos() {
        val testContext = "오늘 판고에서 회의"
        val llmProposals = listOf(
            "판교에서", // Typo correction / word
            "안건에", // Next word
            "오후 3시에 회의실에서 진행됩니다." // Next sentence
        )
        prefetcher.putPredictions(testContext, llmProposals)

        val predictor = AiContextualPredictor(
            lexicon = PersonalizedLexiconModel(),
            morphology = ChoseongMorphologyEngine(),
            prefetcher = prefetcher
        )

        val predictions = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = testContext,
            packageName = "com.kakao.talk",
            limit = 6
        )

        // Verify next word candidate (안건에) is classified as word (isSentenceCompletion = false) with ✨ AI단어 badge
        val wordCandidate = predictions.firstOrNull { it.text == "안건에" }
        assertNotNull(wordCandidate)
        assertEquals(false, wordCandidate!!.isSentenceCompletion)
        assertEquals("✨ AI단어", wordCandidate.badge)

        // Verify sentence candidate is classified as sentence completion with ✨ AI완성 badge
        val sentenceCandidate = predictions.firstOrNull { it.text.contains("진행됩니다") }
        assertNotNull(sentenceCandidate)
        assertEquals(true, sentenceCandidate!!.isSentenceCompletion)
        assertEquals("✨ AI완성", sentenceCandidate.badge)

        // Verify typo-corrected candidate (판교에서) is classified and has top-tier confidence
        val typoCandidate = predictions.firstOrNull { it.text == "판교에서" }
        assertNotNull(typoCandidate)
        assertTrue(typoCandidate!!.confidenceScore >= 0.98f)
    }
}
