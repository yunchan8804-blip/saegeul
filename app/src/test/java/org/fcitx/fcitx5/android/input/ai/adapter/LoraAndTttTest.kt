/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LoraAndTttTest {

    private lateinit var loraTrainer: OnDeviceLoraTrainer
    private lateinit var tttTrainer: TestTimeTrainer

    @Before
    fun setUp() {
        loraTrainer = OnDeviceLoraTrainer(
            rank = 4,
            alpha = 8.0f
        )
        tttTrainer = TestTimeTrainer()
    }

    @Test
    fun testLoraConfigurationAndDimension() {
        assertEquals(4, loraTrainer.rank)
        assertEquals(8.0f, loraTrainer.alpha, 1e-5f)
        assertEquals(32, loraTrainer.dimension)
    }

    @Test
    fun testLoraBatchTrainingWithEwc() {
        val samples = listOf(
            "내일 오전 팀 회의 참석 부탁드립니다",
            "점심 메뉴 파스타 어떠세요",
            "프로젝트 릴리스 일정 조율 필요합니다"
        )

        val result = loraTrainer.trainBatch(samples, maxSteps = 5)

        assertEquals(5, result.stepsCompleted)
        assertEquals(4, result.rank)
        assertEquals(8.0f, result.alpha, 1e-5f)
        assertEquals(256, result.adaptedParametersCount)

        // Delta weight norm must be positive after training steps
        assertTrue(
            "Delta weight norm should be positive, was: ${result.deltaWeightNorm}",
            result.deltaWeightNorm > 0.0f
        )

        // EWC catastrophic forgetting rate must strictly be < 2.0% (0.02)
        assertTrue(
            "Forgetting rate must be < 2.0% (0.02), was: ${result.forgettingRate}",
            result.forgettingRate < 0.02f
        )
    }

    @Test
    fun testLoraEmptyBatchHandling() {
        val result = loraTrainer.trainBatch(emptyList(), maxSteps = 5)
        assertEquals(0, result.stepsCompleted)
        assertEquals(0.0f, result.finalLoss, 1e-5f)
        assertEquals(0.0f, result.forgettingRate, 1e-5f)
    }

    @Test
    fun testTttOnlineAdaptationLatency() {
        val context = "안녕하세요 오늘 발표 준비 자료 전달드립니다"

        // Warm up JIT
        repeat(50) {
            tttTrainer.adaptOnline(context)
        }
        tttTrainer.reset()

        // Measured runs
        val iterations = 50
        var totalLatency = 0.0
        var maxObservedLatency = 0.0

        repeat(iterations) {
            val state = tttTrainer.adaptOnline(context)
            totalLatency += state.latencyMs
            if (state.latencyMs > maxObservedLatency) {
                maxObservedLatency = state.latencyMs
            }
            assertTrue("Online adaptation updateNorm must be positive", state.updateNorm > 0.0f)
            assertTrue("Drift from base must be positive while adapted", state.driftFromBase > 0.0f)
        }

        val avgLatency = totalLatency / iterations
        assertTrue(
            "TTT online adaptation avg latency must be < 20ms, was: $avgLatency ms (max: $maxObservedLatency ms)",
            avgLatency < 20.0
        )
        assertTrue(
            "TTT online adaptation single run max latency must be < 20ms, was: $maxObservedLatency ms",
            maxObservedLatency < 20.0
        )
    }

    @Test
    fun testTttZeroParameterDriftUponReset() {
        tttTrainer.adaptOnline("내일 오전 10시 회의 일정 확인")
        tttTrainer.adaptOnline("점심 식사 맛있게 하세요")

        // Confirm non-zero drift during adaptation
        val driftBeforeReset = tttTrainer.getDriftFromBase()
        assertTrue("Drift must be > 0.0 before reset, was: $driftBeforeReset", driftBeforeReset > 0.0f)

        // Execute reset
        tttTrainer.reset()

        // Verify exact zero drift
        val driftAfterReset = tttTrainer.getDriftFromBase()
        assertEquals(0.0f, driftAfterReset, 0.0f)
        assertTrue("isZeroDrift must be true after reset", tttTrainer.isZeroDrift())
    }
}
