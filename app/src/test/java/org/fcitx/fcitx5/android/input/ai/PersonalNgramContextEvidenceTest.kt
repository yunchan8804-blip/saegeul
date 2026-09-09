/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalNgramContextEvidenceTest {

    private val defaultPackage = "com.example.test"

    @Test
    fun contextualPredictionExcludesUnrelatedUnigrams() {
        val model = PersonalNgramModel(clock = { 1_000_000_000L })
        repeat(20) { model.learn("오늘 회의 참석합니다", defaultPackage) }

        val result = model.predictContextualNext("내가 뭘", defaultPackage, 5)

        assertTrue(result.isEmpty())
    }

    @Test
    fun contextualPredictionIncludesObservedNextWord() {
        val model = PersonalNgramModel(clock = { 1_000_000_000L })
        model.learn("내가 뭘 잘못 했어", defaultPackage)

        val result = model.predictContextualNext("내가 뭘", defaultPackage, 5)

        assertTrue(result.any { it.word == "잘못" && it.level >= 2 })
    }

    @Test
    fun contextualPredictionFiltersBeforeLimit() {
        val model = PersonalNgramModel(clock = { 1_000_000_000L })
        repeat(40) { model.learn("회의 참석합니다", defaultPackage) }
        model.learn("내가 뭘 잘못 했어", defaultPackage)

        val result = model.predictContextualNext("내가 뭘", defaultPackage, 1)

        assertEquals(listOf("잘못"), result.map { it.word })
        assertFalse(result.any { it.level < 2 })
    }

    @Test
    fun categoryUnigramDoesNotHideGlobalBigramEvidence() {
        val model = PersonalNgramModel(clock = { 1_000_000_000L })
        val workPackage = "com.slack"
        model.learn("후보 독립", workPackage)
        model.learn("문맥 후보", "com.kakao.talk")

        val result = model.predictContextualNext("문맥", workPackage, 5)
        val candidate = result.first { it.word == "후보" }

        assertEquals(2, candidate.level)
        assertTrue(candidate.evidence > 0f)
    }

    @Test
    fun emptyContextAllowsSentenceStartBigram() {
        val model = PersonalNgramModel(clock = { 1_000_000_000L })
        model.learn("오늘 회의 참석합니다", defaultPackage)

        val result = model.predictContextualNext("", defaultPackage, 5)

        assertTrue(result.any { it.word == "오늘" && it.level >= 2 })
    }

    @Test
    fun completeKeepsPrefixCompatibility() {
        val model = PersonalNgramModel(clock = { 1_000_000_000L })
        model.learn("오늘 회의 참석합니다", defaultPackage)

        val result = model.complete("참", "오늘 회의", defaultPackage, 5)

        assertEquals("참석합니다", result.first().word)
    }
}
