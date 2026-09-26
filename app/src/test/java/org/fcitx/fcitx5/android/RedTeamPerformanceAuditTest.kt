/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.points.PointEvent
import org.fcitx.fcitx5.android.data.points.PointLedgerMath
import org.fcitx.fcitx5.android.data.theme.CustomThemeSerializer
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.ai.KoreanDiscourseContinuation
import org.fcitx.fcitx5.android.input.ai.TypingDnaLevelCurve
import org.fcitx.fcitx5.android.input.keyboard.MobileHangulComposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis

/**
 * Red Team Performance Audit Test Suite.
 *
 * Enforces strict latency budgets, computational complexity limits,
 * ReDoS resistance, and zero-jank frame timing across Saegeul core pipelines.
 */
class RedTeamPerformanceAuditTest {

    /**
     * Audit 1: MobileHangulComposer 10,000 Keystroke Latency Budget.
     * Invariant: Continuous injection of 10,000 jamo/chunjiin tokens must complete
     * within 500ms (budget <= 0.05ms per keystroke), vastly outperforming Android Vitals
     * keyboard responsiveness benchmark (30ms per event).
     */
    @Test
    fun audit1_mobileHangulComposer10kKeystrokeLatencyBudget() {
        val composer = MobileHangulComposer()

        // Diverse token sequence representing realistic mobile Hangul input patterns
        val sampleTokens = listOf(
            MobileHangulComposer.Token.Jamo('ㄱ'),
            MobileHangulComposer.Token.VowelDot,
            MobileHangulComposer.Token.VowelI, // Forms '가'
            MobileHangulComposer.Token.Jamo('ㄴ'), // Final consonant
            MobileHangulComposer.Token.Boundary,
            MobileHangulComposer.Token.Cycle("giyeok", listOf('ㄱ', 'ㅋ', 'ㄲ'), timeoutMillis = 1500),
            MobileHangulComposer.Token.VowelEu,
            MobileHangulComposer.Token.VowelI, // Forms 'ㅢ'
            MobileHangulComposer.Token.AddStroke,
            MobileHangulComposer.Token.DoubleConsonant
        )

        // Warm up JIT compiler
        val warmupComposer = MobileHangulComposer()
        repeat(1_000) { i ->
            warmupComposer.press(sampleTokens[i % sampleTokens.size], 1000L + i * 10)
        }

        val totalKeystrokes = 10_000
        var totalOutputsCount = 0
        var maxSingleKeystrokeNanos = 0L

        val elapsedNanos = measureNanoTime {
            for (i in 0 until totalKeystrokes) {
                val token = sampleTokens[i % sampleTokens.size]
                val now = 10_000L + i * 50L
                val singleStart = System.nanoTime()
                val outputs = composer.press(token, now)
                val singleDuration = System.nanoTime() - singleStart
                if (singleDuration > maxSingleKeystrokeNanos) {
                    maxSingleKeystrokeNanos = singleDuration
                }
                totalOutputsCount += outputs.size
            }
        }

        val elapsedMillis = elapsedNanos / 1_000_000.0
        val avgPerKeystrokeNanos = elapsedNanos / totalKeystrokes.toDouble()
        val avgPerKeystrokeMillis = avgPerKeystrokeNanos / 1_000_000.0
        val maxSingleKeystrokeMillis = maxSingleKeystrokeNanos / 1_000_000.0

        println(
            "Audit 1 - Composer 10k Keystrokes: Total = %.2f ms, Avg = %.4f ms/key (%.2f µs), Max = %.4f ms, Outputs = %d"
                .format(elapsedMillis, avgPerKeystrokeMillis, avgPerKeystrokeNanos / 1_000.0, maxSingleKeystrokeMillis, totalOutputsCount)
        )

        assertTrue(
            "Audit 1 Violation: Total 10k keystroke time ($elapsedMillis ms) exceeded 500ms budget!",
            elapsedMillis <= 500.0
        )
        assertTrue(
            "Audit 1 Violation: Max single keystroke latency ($maxSingleKeystrokeMillis ms) exceeded Android Vitals 30ms threshold!",
            maxSingleKeystrokeMillis < 30.0
        )
        assertTrue(totalOutputsCount > 0)
    }

    /**
     * Audit 2: TypingDNA Level Curve 2,500 Level Endgame Complexity Audit.
     * Invariant: 10,000 evaluations across levelFor, thresholdFor, and describe at endgame
     * 1,000,000 sentences (Level 2,500) must execute within 100ms budget.
     */
    @Test
    fun audit2_typingDnaLevelCurveEndgameComplexityAudit() {
        val endgameSentences = 1_000_000
        val totalOperations = 10_000
        val levelForCount = 500
        val describeCount = 500
        val thresholdCount = totalOperations - levelForCount - describeCount // 9,000

        // Warm up JIT compiler
        repeat(1_000) {
            TypingDnaLevelCurve.thresholdFor(TypingDnaLevelCurve.MAX_LEVEL)
            TypingDnaLevelCurve.levelFor(endgameSentences)
            TypingDnaLevelCurve.describe(endgameSentences)
        }

        val elapsedMillis = measureTimeMillis {
            // 9,000 threshold evaluations
            for (i in 0 until thresholdCount) {
                val th = TypingDnaLevelCurve.thresholdFor(TypingDnaLevelCurve.MAX_LEVEL)
                if (i == 0) assertTrue(th > 0)
            }
            // 500 endgame levelFor evaluations
            for (i in 0 until levelForCount) {
                val lvl = TypingDnaLevelCurve.levelFor(endgameSentences)
                if (i == 0) assertEquals(TypingDnaLevelCurve.MAX_LEVEL, lvl)
            }
            // 500 endgame describe evaluations
            for (i in 0 until describeCount) {
                val desc = TypingDnaLevelCurve.describe(endgameSentences)
                if (i == 0) assertEquals(TypingDnaLevelCurve.MAX_LEVEL, desc.level)
            }
        }

        val avgPerOpMicros = (elapsedMillis * 1000.0) / totalOperations
        println(
            "Audit 2 - TypingDNA Endgame 10k evaluations ($thresholdCount thresholds, $levelForCount levelFors, $describeCount describes): Total = $elapsedMillis ms, Avg = %.3f µs/op"
                .format(avgPerOpMicros)
        )

        assertTrue(
            "Audit 2 Violation: TypingDNA 10k endgame evaluations ($elapsedMillis ms) exceeded 100ms budget!",
            elapsedMillis <= 100
        )
    }

    /**
     * Audit 3: PointLedger 10,000 Massive Transaction Aggregation Audit.
     * Invariant: PointLedgerMath.balance over 10,000 transactions must complete
     * in under 20ms.
     */
    @Test
    fun audit3_pointLedgerMassiveTransactionAggregationAudit() {
        val eventCount = 10_000
        val baseEpoch = 1_777_000_000_000L

        val events = ArrayList<PointEvent>(eventCount)
        for (i in 0 until eventCount) {
            val type = if (i % 3 == 0) PointEvent.SPEND else PointEvent.EARN
            val amount = (i % 25) + 1
            events.add(
                PointEvent(
                    epochMs = baseEpoch + i,
                    type = type,
                    amount = amount,
                    venueId = "theme_audit_venue_$i",
                    detail = "perf_audit_tx_$i"
                )
            )
        }

        // Warm up JIT compiler
        repeat(50) {
            PointLedgerMath.balance(events)
        }

        val elapsedNanos = measureNanoTime {
            val balance = PointLedgerMath.balance(events)
            assertTrue("Calculated balance must be positive", balance > 0)
        }

        val elapsedMillis = elapsedNanos / 1_000_000.0
        println("Audit 3 - PointLedger 10k tx aggregation: Total = %.3f ms".format(elapsedMillis))

        assertTrue(
            "Audit 3 Violation: PointLedgerMath.balance over 10k events ($elapsedMillis ms) exceeded 20ms budget!",
            elapsedMillis <= 20.0
        )
    }

    /**
     * Audit 4: KoreanDiscourseContinuation 10,000-char ReDoS & Latency Audit.
     * Invariant: Discourse continuation analysis on massive 10,000-character payload
     * with adversarial whitespace and punctuation patterns must finish safely within 10ms without ReDoS.
     */
    @Test
    fun audit4_koreanDiscourseContinuationLargePayloadAndRedosAudit() {
        // Payload 1: 10,000 characters of natural Korean conversation with connective endings
        val naturalChunk = "새글 키보드로 한국어 장문을 빠르게 입력하고 있는데 "
        val naturalPayload = buildString {
            while (length < 10_000) {
                append(naturalChunk)
            }
        }.take(10_000)

        // Payload 2: Adversarial ReDoS pattern with thousands of consecutive spaces and trailing connective
        val redosSpacesPayload = "테스트".repeat(100) + " ".repeat(9_000) + "하지만 "

        // Payload 3: Adversarial ReDoS pattern with nested punctuation endings
        val redosPunctPayload = "새글 정밀 문체 테스트".repeat(500) + ".".repeat(4_000) + " "

        val testPayloads = listOf(naturalPayload, redosSpacesPayload, redosPunctPayload)

        // Warm up JIT
        repeat(100) {
            KoreanDiscourseContinuation.suggest("안녕하세요 날씨가 좋은데 ")
        }

        for ((idx, payload) in testPayloads.withIndex()) {
            val elapsedNanos = measureNanoTime {
                val suggestions = KoreanDiscourseContinuation.suggest(payload)
                assertNotNull(suggestions)
            }
            val elapsedMillis = elapsedNanos / 1_000_000.0
            println("Audit 4 - Discourse ReDoS payload #$idx (${payload.length} chars): %.3f ms".format(elapsedMillis))

            assertTrue(
                "Audit 4 Violation: Discourse analysis on payload #$idx ($elapsedMillis ms) exceeded 10ms budget!",
                elapsedMillis <= 10.0
            )
        }
    }

    /**
     * Audit 5: Theme JSON Deserialization Latency & Frame-Drop (Jank) Audit.
     * Invariant: Parsing custom/preset theme JSON must execute within 10ms per call
     * to avoid dropping frames (120Hz = 8.3ms, 60Hz = 16.6ms) during theme switching.
     */
    @Test
    fun audit5_themeJsonDeserializationLatencyAudit() {
        val preset = ThemePreset.SeoulMistGlass
        val customTheme = preset.deriveCustomBackground(
            "custom-audit-theme",
            "crop_audit.png",
            "src_audit.png"
        )
        val jsonString = Json.encodeToString(CustomThemeSerializer, customTheme)

        // Warm up JIT
        repeat(50) {
            Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, jsonString)
        }

        // Measure multiple iterations to evaluate both single-shot latency and average latency
        val iterations = 100
        val latenciesMillis = mutableListOf<Double>()

        for (i in 0 until iterations) {
            val elapsedNanos = measureNanoTime {
                val (decoded, migrated) = Json.decodeFromString(
                    CustomThemeSerializer.WithMigrationStatus,
                    jsonString
                )
                assertEquals(customTheme.name, decoded.name)
            }
            latenciesMillis.add(elapsedNanos / 1_000_000.0)
        }

        val maxLatencyMillis = latenciesMillis.maxOrNull() ?: 0.0
        val avgLatencyMillis = latenciesMillis.average()

        println(
            "Audit 5 - Theme JSON Deserialization: Avg = %.3f ms, Max = %.3f ms (Budget <= 10ms)"
                .format(avgLatencyMillis, maxLatencyMillis)
        )

        assertTrue(
            "Audit 5 Violation: Theme JSON deserialization avg latency ($avgLatencyMillis ms) exceeded 10ms budget!",
            avgLatencyMillis <= 10.0
        )
        assertTrue(
            "Audit 5 Violation: Theme JSON deserialization peak latency ($maxLatencyMillis ms) exceeded 10ms frame budget!",
            maxLatencyMillis <= 10.0
        )
    }
}
