/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.ai.AiPrediction
import org.fcitx.fcitx5.android.input.ai.KoreanCollocationModel
import org.fcitx.fcitx5.android.input.ai.SentenceRelevanceReranker
import org.junit.Assert
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Red Team Adversarial Unit Tests:
 * 1. KoreanCollocationModel phonological batchim mismatch prevention on bare nouns (e.g. "아이", "사과", "가을").
 * 2. Thread-safety and race condition stress testing during dynamic bigram injection under high concurrency.
 * 3. SentenceRelevanceReranker sort robustness against Float.NaN and negative confidence scores.
 * 4. Empty and blank input safety returning empty completions without failure.
 */
class RedTeamCollocationAndRerankerChaosTest {

    /**
     * Test 1: Collocation model does not falsely match batchim-mismatched trailing particles on bare nouns.
     * Bare noun "아이" (no batchim on '아') must not falsely trigger subject particle "이".
     * Bare noun "사과" (no batchim on '사') must not falsely trigger conjunctive particle "과".
     * Bare noun "가을" (no batchim on '가') must not falsely trigger object particle "을".
     * Genuine particle attachments like "회의를", "밥을", "밥이", "사과가" must retain proper suggestions.
     */
    @Test
    fun `collocation model does not falsely match batchim-mismatched trailing particles on bare nouns`() {
        val collocation = KoreanCollocationModel()

        // 1. 순수 단일 명사 "아이" (앞 글자 '아'에 종성 없음)
        // 주격 조사 '이'(종성 필수)가 붙은 것으로 오인되어 completions가 반환되어서는 안 됨.
        val childResult = collocation.predictNextWords("아이", isInformal = false)
        val subjectCompletions = listOf("필요합니다", "있습니다", "어려울 것 같습니다", "완료되었습니다")
        Assert.assertFalse(
            "Bare noun '아이' must not falsely match subject particle '이' completions",
            childResult.any { it in subjectCompletions }
        )

        // 2. 순수 단일 명사 "사과" (앞 글자 '사'에 종성 없음)
        // 접속 조사 '과'(종성 필수)가 붙은 것으로 오인되어 completions가 반환되어서는 안 됨.
        val appleResult = collocation.predictNextWords("사과", isInformal = false)
        val conjunctiveCompletions = listOf("함께", "관련하여", "동일하게")
        Assert.assertFalse(
            "Bare noun '사과' must not falsely match conjunctive particle '과' completions",
            appleResult.any { it in conjunctiveCompletions }
        )

        // 3. 순수 단일 명사 "가을" (앞 글자 '가'에 종성 없음)
        // 목적격 조사 '을'(종성 필수)이 붙은 것으로 오인되어 completions가 반환되어서는 안 됨.
        val autumnResult = collocation.predictNextWords("가을", isInformal = false)
        val objectCompletions = listOf("확인했습니다", "부탁드립니다")
        Assert.assertFalse(
            "Bare noun '가을' must not falsely match object particle '을' completions",
            autumnResult.any { it in objectCompletions }
        )

        // 반면 정상적인 조사 결합:
        // "회의를" -> listOf("확인했습니다", "부탁드립니다", "검토하겠습니다", "보내드립니다") 포함
        val meetingResult = collocation.predictNextWords("회의를", isInformal = false)
        val expectedMeetingList = listOf("확인했습니다", "부탁드립니다", "검토하겠습니다", "보내드립니다")
        Assert.assertTrue(
            "'회의를' should suggest object particle completions",
            meetingResult.containsAll(expectedMeetingList)
        )

        // "밥을" -> listOf("확인했습니다", "부탁드립니다", "검토하겠습니다", "보내드립니다") 포함
        val mealObjResult = collocation.predictNextWords("밥을", isInformal = false)
        val expectedMealObjList = listOf("확인했습니다", "부탁드립니다", "검토하겠습니다", "보내드립니다")
        Assert.assertTrue(
            "'밥을' should suggest object particle completions",
            mealObjResult.containsAll(expectedMealObjList)
        )

        // "밥이" -> listOf("필요합니다", "있습니다", "어려울 것 같습니다", "완료되었습니다") 포함
        val mealSubjResult = collocation.predictNextWords("밥이", isInformal = false)
        val expectedMealSubjList = listOf("필요합니다", "있습니다", "어려울 것 같습니다", "완료되었습니다")
        Assert.assertTrue(
            "'밥이' should suggest subject particle completions",
            mealSubjResult.containsAll(expectedMealSubjList)
        )

        // "사과가" -> listOf("필요합니다", "있습니다", "어려울 것 같습니다", "완료되었습니다") 포함
        val appleSubjResult = collocation.predictNextWords("사과가", isInformal = false)
        val expectedAppleSubjList = listOf("필요합니다", "있습니다", "어려울 것 같습니다", "완료되었습니다")
        Assert.assertTrue(
            "'사과가' should suggest subject particle completions",
            appleSubjResult.containsAll(expectedAppleSubjList)
        )
    }

    /**
     * Test 2: Collocation model thread safe dynamic bigram injection stress.
     * 10 concurrent threads executing injectDynamicBigrams and predictNextWords 300 times
     * must complete without ConcurrentModificationException.
     */
    @Test
    fun `collocation model thread safe dynamic bigram injection stress`() {
        val collocation = KoreanCollocationModel()
        val numThreads = 10
        val iterationsPerThread = 300
        val executor = Executors.newFixedThreadPool(numThreads)
        val errors = CopyOnWriteArrayList<Throwable>()
        val latch = CountDownLatch(numThreads)

        for (t in 0 until numThreads) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        if (i % 2 == 0) {
                            val bigrams = mapOf(
                                "단어${i % 10}" to listOf("다음${i}", "연결${i}", "후속${i}")
                            )
                            collocation.injectDynamicBigrams(bigrams, isInformal = (i % 4 == 0))
                        } else {
                            val result = collocation.predictNextWords("단어${(i - 1) % 10}", isInformal = (i % 4 == 0))
                            Assert.assertNotNull(result)
                        }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    latch.countDown()
                }
            }
        }

        val finished = latch.await(15, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        Assert.assertTrue("All threads must finish without timing out", finished)

        if (errors.isNotEmpty()) {
            val cme = errors.firstOrNull { it is ConcurrentModificationException }
            if (cme != null) {
                throw AssertionError("ConcurrentModificationException occurred during dynamic bigram injection stress test", cme)
            }
            throw AssertionError("Thread execution failed with error: ${errors.first().message}", errors.first())
        }
    }

    /**
     * Test 3: Sentence reranker handles Float NaN and negative confidence scores without sort crash.
     * When AiPrediction list contains confidenceScore = Float.NaN, -2.0f, and 0.9f,
     * SentenceRelevanceReranker.rerank must not crash with IllegalArgumentException (TimSort contract violation).
     * All returned confidence scores must be valid (!isNaN()) and bounded between 0.0f and 1.0f.
     */
    @Test
    fun `sentence reranker handles Float NaN and negative confidence scores without sort crash`() {
        val sentences = listOf(
            AiPrediction(
                text = "오늘 회의 일정 공유드립니다",
                confidenceScore = Float.NaN,
                isSentenceCompletion = true
            ),
            AiPrediction(
                text = "오늘 회의 안건 확인 부탁드립니다",
                confidenceScore = -2.0f,
                isSentenceCompletion = true
            ),
            AiPrediction(
                text = "오늘 회의 자료 준비 완료했습니다",
                confidenceScore = 0.9f,
                isSentenceCompletion = true
            ),
            AiPrediction(
                text = "오늘 회의 참석자 명단입니다",
                confidenceScore = Float.NaN,
                isSentenceCompletion = true
            ),
            AiPrediction(
                text = "오늘 회의실 예약 확인했습니다",
                confidenceScore = -0.5f,
                isSentenceCompletion = true
            ),
            AiPrediction(
                text = "오늘 회의 결과 정리해 송부드립니다",
                confidenceScore = 0.7f,
                isSentenceCompletion = true
            )
        )

        val results = SentenceRelevanceReranker.rerank(
            sentences = sentences,
            contextBeforeCursor = "오늘 회의 ",
            ngram = null,
            packageName = "com.test",
            limit = 5
        )

        Assert.assertNotNull("Reranked results must not be null", results)
        Assert.assertTrue("Reranked results should contain predictions", results.isNotEmpty())

        for (prediction in results) {
            Assert.assertFalse(
                "Prediction confidenceScore must not be NaN: ${prediction.text}",
                prediction.confidenceScore.isNaN()
            )
            Assert.assertTrue(
                "Prediction confidenceScore must be >= 0.0f: ${prediction.confidenceScore} on ${prediction.text}",
                prediction.confidenceScore >= 0.0f
            )
            Assert.assertTrue(
                "Prediction confidenceScore must be <= 1.0f: ${prediction.confidenceScore} on ${prediction.text}",
                prediction.confidenceScore <= 1.0f
            )
        }
    }

    /**
     * Test 4: Collocation empty and blank inputs safely return empty list.
     * predictNextWords with empty or blank string must return emptyList().
     */
    @Test
    fun `collocation empty and blank inputs safely return empty list`() {
        val collocation = KoreanCollocationModel()

        val emptyResult = collocation.predictNextWords("", false)
        Assert.assertEquals("Empty input should return empty list", emptyList<String>(), emptyResult)

        val blankResult = collocation.predictNextWords("   ", true)
        Assert.assertEquals("Blank input should return empty list", emptyList<String>(), blankResult)
    }
}
