/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit Tests for KoreanCollocationModel.
 * Validates high-frequency Korean Bigrams, particle-directed transitions,
 * and tone-aware next-word completions.
 */
class KoreanCollocationModelTest {

    private lateinit var collocationModel: KoreanCollocationModel

    @Before
    fun setUp() {
        collocationModel = KoreanCollocationModel()
    }

    @Test
    fun testHonorificBigramTransitions() {
        val nextWords = collocationModel.predictNextWords("오늘", isInformal = false, limit = 5)
        assertTrue("Next words for '오늘' must not be empty", nextWords.isNotEmpty())
        assertTrue(nextWords.contains("저녁") || nextWords.contains("점심") || nextWords.contains("회의"))

        val workNextWords = collocationModel.predictNextWords("배포", isInformal = false, limit = 4)
        assertTrue(workNextWords.contains("완료") || workNextWords.contains("준비") || workNextWords.contains("진행"))

        val confirmNextWords = collocationModel.predictNextWords("확인", isInformal = false, limit = 4)
        assertTrue(confirmNextWords.contains("부탁드립니다") || confirmNextWords.contains("감사합니다") || confirmNextWords.contains("완료했습니다"))
    }

    @Test
    fun testInformalBigramTransitions() {
        val nextWords = collocationModel.predictNextWords("오늘", isInformal = true, limit = 5)
        assertTrue(nextWords.isNotEmpty())
        assertTrue(nextWords.any { it.contains("뭐해") || it.contains("볼까") || it.contains("먹자") || it.contains("저녁") })

        val cheerWords = collocationModel.predictNextWords("축하", isInformal = true, limit = 3)
        assertTrue(cheerWords.any { it.contains("해") || it.contains("대단") })
    }

    @Test
    fun testParticleDirectedTransitions() {
        // Object particle -을/를
        val objectParticleWords = collocationModel.predictNextWords("회의를", isInformal = false, limit = 4)
        assertTrue("Transitions for '회의를' must contain relevant verbs", objectParticleWords.isNotEmpty())
        assertTrue(objectParticleWords.any { it.contains("확인했습니다") || it.contains("부탁드립니다") || it.contains("진행하겠습니다") })

        // Location particle -에 / -에서
        val locationWords = collocationModel.predictNextWords("판교에서", isInformal = false, limit = 4)
        assertTrue(locationWords.isNotEmpty())
        assertTrue(locationWords.any { it.contains("뵙겠습니다") || it.contains("만나요") || it.contains("진행됩니다") })

        // Subject particle -이/가
        val subjectWords = collocationModel.predictNextWords("시간이", isInformal = false, limit = 4)
        assertTrue(subjectWords.isNotEmpty())
        assertTrue(subjectWords.any { it.contains("필요합니다") || it.contains("있습니다") || it.contains("어려울 것 같습니다") })
    }

    @Test
    fun testEmptyOrUnknownWords() {
        val emptyResult = collocationModel.predictNextWords("", isInformal = false)
        assertTrue(emptyResult.isEmpty())

        val unknownResult = collocationModel.predictNextWords("xyzabc123", isInformal = false)
        assertTrue(unknownResult.isEmpty())
    }
}
