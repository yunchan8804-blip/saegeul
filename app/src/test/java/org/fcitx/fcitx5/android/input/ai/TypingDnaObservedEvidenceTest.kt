/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingDnaObservedEvidenceTest {

    @Test
    fun llmGeneratedPhrasesAndBigramsAreExcludedWhileObservedEvidenceIsRetained() {
        val sentences = listOf("내가 뭘 잘못 했어", "오늘 회의 참석합니다")
        val profiler = TypingDnaProfiler {
            """
            {
              "dominantTone": "Informal",
              "habitualEndings": ["했어"],
              "frequentBigrams": [
                {"prev": "내가", "next": "감사", "weight": 1.0},
                {"prev": "가짜", "next": "전이", "weight": 1.0}
              ],
              "cannedPhrases": ["내가 뭘 감사 감사하겠습니다", "없는 문장입니다"]
            }
            """.trimIndent()
        }

        val profile = profiler.profile("messenger", sentences)

        assertEquals("Informal", profile.dominantTone)
        assertEquals(listOf("했어"), profile.habitualEndings)
        assertTrue(profile.cannedPhrases.containsAll(sentences))
        assertFalse(profile.cannedPhrases.contains("내가 뭘 감사 감사하겠습니다"))
        assertFalse(profile.cannedPhrases.contains("없는 문장입니다"))
        assertTrue(profile.frequentBigrams.any { it.prev == "내가" && it.next == "뭘" })
        assertTrue(profile.frequentBigrams.any { it.prev == "뭘" && it.next == "잘못" })
        assertTrue(profile.frequentBigrams.any { it.prev == "오늘" && it.next == "회의" })
        assertTrue(profile.frequentBigrams.any { it.prev == "회의" && it.next == "참석합니다" })
        assertFalse(profile.frequentBigrams.any { it.prev == "내가" && it.next == "감사" })
        assertFalse(profile.frequentBigrams.any { it.prev == "가짜" && it.next == "전이" })
    }

    @Test
    fun emptyLlmEndingsUseObservedEndingsWhileKeepingLlmTone() {
        val profiler = TypingDnaProfiler {
            """
            {
              "dominantTone": "Informal",
              "habitualEndings": [],
              "frequentBigrams": [],
              "cannedPhrases": []
            }
            """.trimIndent()
        }

        val profile = profiler.profile("messenger", listOf("감사합니다.", "확인했습니다!"))

        assertEquals("Informal", profile.dominantTone)
        assertEquals(listOf("합니다", "했습니다"), profile.habitualEndings)
    }
}
