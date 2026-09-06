/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for PersonalNgramTokenizer.
 * Covers punctuation stripping, PII/noise token dropping, and particle stemming.
 */
class PersonalNgramTokenizerTest {

    @Test
    fun stripsSurroundingPunctuation() {
        assertEquals(listOf("안녕하세요"), PersonalNgramTokenizer.tokenize("안녕하세요."))
        assertEquals(listOf("안녕"), PersonalNgramTokenizer.tokenize("(안녕)"))
        assertEquals(listOf("정말", "좋다"), PersonalNgramTokenizer.tokenize("\"정말\" 좋다!"))
    }

    @Test
    fun dropsDigitRunsOfFourOrMore() {
        val tokens = PersonalNgramTokenizer.tokenize("전화번호 01012345678 입니다")
        assertEquals(listOf("전화번호", "입니다"), tokens)
    }

    @Test
    fun dropsEmailTokens() {
        val tokens = PersonalNgramTokenizer.tokenize("제 이메일은 user@example.com 입니다")
        assertEquals(listOf("제", "이메일은", "입니다"), tokens)
    }

    @Test
    fun dropsUrlTokens() {
        assertEquals(
            listOf("사이트는", "확인해줘"),
            PersonalNgramTokenizer.tokenize("사이트는 http://example.com 확인해줘")
        )
        assertEquals(
            listOf("방문"),
            PersonalNgramTokenizer.tokenize("www.example.com 방문")
        )
    }

    @Test
    fun dropsPiiPlaceholderTokens() {
        val tokens = PersonalNgramTokenizer.tokenize("전화번호는 [전화번호] 입니다")
        assertEquals(listOf("전화번호는", "입니다"), tokens)
    }

    @Test
    fun keepsLaughingAndCryingJamoTokens() {
        val tokens = PersonalNgramTokenizer.tokenize("진짜 웃기다 ㅋㅋㅋ")
        assertTrue(tokens.contains("ㅋㅋㅋ"))
    }

    @Test
    fun stemsKnownParticleSuffix() {
        assertEquals("회사", PersonalNgramTokenizer.stem("회사에서는"))
    }

    @Test
    fun stemReturnsNullWhenNoRemainderLeft() {
        assertNull(PersonalNgramTokenizer.stem("밥"))
        assertNull(PersonalNgramTokenizer.stem("는"))
    }

    @Test
    fun dropsOverlongTokens() {
        val longToken = "가".repeat(21)
        val tokens = PersonalNgramTokenizer.tokenize("$longToken 짧은")
        assertFalse(tokens.contains(longToken))
        assertTrue(tokens.contains("짧은"))
    }
}
