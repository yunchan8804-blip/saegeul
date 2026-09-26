/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.verifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ThreeStageOutputVerifierTest {

    private lateinit var kenLmScorer: KenLmScorer
    private lateinit var miniPrmScorer: MiniPrmScorer
    private lateinit var verifier: ThreeStageOutputVerifier

    @Before
    fun setUp() {
        kenLmScorer = KenLmScorer()
        miniPrmScorer = MiniPrmScorer()
        verifier = ThreeStageOutputVerifier(kenLmScorer, miniPrmScorer)
    }

    @Test
    fun testKenLmScorerPerplexityThresholds() {
        // Normal sentences -> low PPL (< 50.0)
        val normalPpl1 = kenLmScorer.calculatePerplexity("회의에 참석합니다")
        println("PPL('회의에 참석합니다'): $normalPpl1")
        assertTrue("Expected PPL < 50.0 for '회의에 참석합니다', got $normalPpl1", normalPpl1 < 50.0)

        val normalPpl2 = kenLmScorer.calculatePerplexity("자료를 준비했습니다")
        println("PPL('자료를 준비했습니다'): $normalPpl2")
        assertTrue("Expected PPL < 50.0 for '자료를 준비했습니다', got $normalPpl2", normalPpl2 < 50.0)

        // Abnormal / awkward sentences -> high PPL (> 200.0)
        val awkwardPpl1 = kenLmScorer.calculatePerplexity("마음을 감사해요")
        println("PPL('마음을 감사해요'): $awkwardPpl1")
        assertTrue("Expected PPL > 200.0 for '마음을 감사해요', got $awkwardPpl1", awkwardPpl1 > 200.0)

        val awkwardPpl2 = kenLmScorer.calculatePerplexity("회의를 참석해요")
        println("PPL('회의를 참석해요'): $awkwardPpl2")
        assertTrue("Expected PPL > 200.0 for '회의를 참석해요', got $awkwardPpl2", awkwardPpl2 > 200.0)
    }

    @Test
    fun testMiniPrmScoring() {
        // Well-matched context and completion
        val goodScore = miniPrmScorer.scoreCandidate(
            "내일 미팅 일정 공유드립니다.",
            "회의에 참석합니다."
        )
        println("PRM score (good): $goodScore")
        assertTrue("PRM score should be >= 0.5f, got $goodScore", goodScore >= 0.5f)

        // Incomplete ending
        val incompleteScore = miniPrmScorer.scoreCandidate(
            "내일 미팅 일정 공유드립니다.",
            "회의에 참석하"
        )
        println("PRM score (incomplete): $incompleteScore")
        assertTrue("PRM score for incomplete ending should be < 0.5f, got $incompleteScore", incompleteScore < 0.5f)
    }

    @Test
    fun testStage1Rejection() {
        // ACC-01: Causal connective followed by imperative/question
        val resultAcc01 = verifier.verify("", "비가 와서 우산을 챙기세요")
        assertFalse(resultAcc01.isValid)
        assertEquals(1, resultAcc01.stage)

        // ACC-02: Accusative with intransitive verb
        val resultAcc02 = verifier.verify("", "마음을 감사해요")
        assertFalse(resultAcc02.isValid)
        assertEquals(1, resultAcc02.stage)

        // ACC-04: Formal greeting followed by informal ending in same sentence
        val resultAcc04 = verifier.verify("안녕하십니까", "밥 먹었어?")
        assertFalse(resultAcc04.isValid)
        assertEquals(1, resultAcc04.stage)
    }

    @Test
    fun testStage2Rejection() {
        // Collocations that pass Stage 1 (not ACC-01~ACC-04) but have high KenLM PPL (> 150.0)
        val result1 = verifier.verify("", "회의를 참석해요")
        assertFalse("Should be rejected at Stage 2", result1.isValid)
        assertEquals("Should fail at Stage 2 due to high PPL", 2, result1.stage)

        val result2 = verifier.verify("", "모임을 참석해요")
        assertFalse("Should be rejected at Stage 2", result2.isValid)
        assertEquals("Should fail at Stage 2 due to high PPL", 2, result2.stage)
    }

    @Test
    fun testStage3Acceptance() {
        val result1 = verifier.verify(
            "내일 미팅 일정 공유드립니다.",
            "회의에 참석합니다."
        )
        assertTrue("Should be accepted: ${result1.reason}", result1.isValid)
        assertEquals(3, result1.stage)
        assertTrue(result1.score >= 0.5f)

        val result2 = verifier.verify(
            "자료 준비 관련 문의드립니다.",
            "자료를 준비했습니다."
        )
        assertTrue("Should be accepted: ${result2.reason}", result2.isValid)
        assertEquals(3, result2.stage)
        assertTrue(result2.score >= 0.5f)
    }

    @Test
    fun testVerificationLatencyUnder6Milliseconds() {
        val context = "내일 회의 일정 공유드립니다."
        val candidate = "회의에 참석합니다."

        // Warmup
        for (i in 0 until 50) {
            verifier.verify(context, candidate)
        }

        // Benchmark 100 runs
        val timesMs = DoubleArray(100)
        for (i in 0 until 100) {
            val start = System.nanoTime()
            val res = verifier.verify(context, candidate)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
            timesMs[i] = elapsedMs
            assertTrue(res.isValid)
        }

        val avgTimeMs = timesMs.average()
        val maxTimeMs = timesMs.maxOrNull() ?: 0.0
        println("ThreeStageOutputVerifier latency: avg = %.3f ms, max = %.3f ms".format(avgTimeMs, maxTimeMs))

        assertTrue("Average latency must be < 6.0ms, was %.3f ms".format(avgTimeMs), avgTimeMs < 6.0)
        assertTrue("Max latency must be < 6.0ms, was %.3f ms".format(maxTimeMs), maxTimeMs < 6.0)
    }
}
