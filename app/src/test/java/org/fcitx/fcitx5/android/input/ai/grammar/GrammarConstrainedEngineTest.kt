/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.grammar

import org.fcitx.fcitx5.android.input.ai.grammar.GrammarConstrainedEngine.SchemaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GrammarConstrainedEngineTest {

    private lateinit var engine: GrammarConstrainedEngine

    @Before
    fun setUp() {
        engine = GrammarConstrainedEngine()
    }

    @Test
    fun testJsonObjectSchemaValidation() {
        var state = GrammarConstrainedEngine.STATE_JSON_START

        // Stream: { "name" : "Alice" , "age" : 30 }
        val tokens = listOf("{", "\"name\"", ":", "\"Alice\"", ",", "\"age\"", ":", "30", "}")
        for (token in tokens) {
            val (valid, nextState) = engine.isValidToken(state, token, SchemaType.JSON_OBJECT)
            assertTrue("Token '$token' should be valid at state $state", valid)
            state = nextState
        }
        assertEquals(GrammarConstrainedEngine.STATE_JSON_CLOSED, state)

        // Invalid token following closed JSON
        val (invalidAfterClose, _) = engine.isValidToken(state, "extra", SchemaType.JSON_OBJECT)
        assertFalse("Extra tokens after JSON close should be rejected", invalidAfterClose)

        // Invalid transition: key without quotes right after {
        val (invalidKey, _) = engine.isValidToken(
            GrammarConstrainedEngine.STATE_JSON_OPEN,
            "unquotedKey",
            SchemaType.JSON_OBJECT
        )
        assertFalse("Unquoted keys should be rejected in JSON_OBJECT", invalidKey)
    }

    @Test
    fun testActionChipSchemaValidation() {
        // Type 1: "[동작] <내용>"
        val type1Valid = listOf("[검색]", " 오늘의", " 날씨")
        var state1 = GrammarConstrainedEngine.STATE_CHIP_START
        for (token in type1Valid) {
            val (valid, next) = engine.isValidToken(state1, token, SchemaType.ACTION_CHIP)
            assertTrue("Token '$token' should be valid in ACTION_CHIP type 1", valid)
            state1 = next
        }

        // Full string at once
        val (fullValid1, _) = engine.isValidToken(
            GrammarConstrainedEngine.STATE_CHIP_START,
            "[일정 추가] 내일 오후 3시 회의",
            SchemaType.ACTION_CHIP
        )
        assertTrue(fullValid1)

        // Type 2: "<내용> (동작)"
        val (fullValid2, _) = engine.isValidToken(
            GrammarConstrainedEngine.STATE_CHIP_START,
            "오늘의 날씨 (검색)",
            SchemaType.ACTION_CHIP
        )
        assertTrue(fullValid2)

        // Invalid pattern: "(동작) 내용"
        val (invalidChip, _) = engine.isValidToken(
            GrammarConstrainedEngine.STATE_CHIP_START,
            "(검색) 날씨",
            SchemaType.ACTION_CHIP
        )
        assertFalse(invalidChip)
    }

    @Test
    fun testKoreanCodaParticleMasking() {
        // Case 1: Preceding noun has non-rieul coda: "사람" (coda = 'ㅁ', non-rieul)
        val candidatesForSaram = listOf("이", "을", "은", "과", "으로", "가", "를", "는", "와", "로")
        val filteredSaram = engine.filterAllowedTokens("사람", candidatesForSaram, SchemaType.FREE_TEXT)
        assertTrue(filteredSaram.containsAll(listOf("이", "을", "은", "과", "으로")))
        assertFalse(filteredSaram.contains("가"))
        assertFalse(filteredSaram.contains("를"))
        assertFalse(filteredSaram.contains("는"))
        assertFalse(filteredSaram.contains("와"))
        assertFalse(filteredSaram.contains("로"))

        // Case 2: Preceding noun has no coda: "바다" (coda = 0)
        val candidatesForBada = listOf("이", "을", "은", "과", "으로", "가", "를", "는", "와", "로")
        val filteredBada = engine.filterAllowedTokens("바다", candidatesForBada, SchemaType.FREE_TEXT)
        assertTrue(filteredBada.containsAll(listOf("가", "를", "는", "와", "로")))
        assertFalse(filteredBada.contains("이"))
        assertFalse(filteredBada.contains("을"))
        assertFalse(filteredBada.contains("은"))
        assertFalse(filteredBada.contains("과"))
        assertFalse(filteredBada.contains("으로"))

        // Case 3: Preceding noun has 'ㄹ' coda: "서울" (coda = 'ㄹ', index 8)
        // With 'ㄹ', "로" is allowed, but "으로" is disallowed. "은/이/을/과" are allowed.
        val candidatesForSeoul = listOf("로", "으로", "을", "를", "은", "는", "과", "와")
        val filteredSeoul = engine.filterAllowedTokens("서울", candidatesForSeoul, SchemaType.FREE_TEXT)
        assertTrue(filteredSeoul.contains("로"))
        assertFalse(filteredSeoul.contains("으로"))
        assertTrue(filteredSeoul.contains("을"))
        assertFalse(filteredSeoul.contains("를"))
        assertTrue(filteredSeoul.contains("은"))
        assertFalse(filteredSeoul.contains("는"))
    }

    @Test
    fun testTokenValidationPerformanceUnder30Microseconds() {
        // Warmup
        val candidates = listOf("을", "를", "이", "가", "은", "는", "으로", "로")
        for (i in 0 until 1000) {
            engine.filterAllowedTokens("회의", candidates, SchemaType.FREE_TEXT)
        }

        // Benchmark 10,000 iterations
        val iterations = 10_000
        val startTime = System.nanoTime()
        for (i in 0 until iterations) {
            engine.filterAllowedTokens("사람", candidates, SchemaType.FREE_TEXT)
        }
        val elapsedNanos = System.nanoTime() - startTime
        val avgMicrosecondsPerCall = elapsedNanos / (iterations * 1000.0)

        println("GrammarConstrainedEngine average time per call: %.3f µs".format(avgMicrosecondsPerCall))
        assertTrue(
            "Per-call overhead must be < 30µs, actual was %.3f µs".format(avgMicrosecondsPerCall),
            avgMicrosecondsPerCall < 30.0
        )
    }
}
