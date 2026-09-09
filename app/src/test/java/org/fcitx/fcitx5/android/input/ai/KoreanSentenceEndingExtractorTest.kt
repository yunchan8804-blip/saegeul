/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KoreanSentenceEndingExtractorTest {

    @Test
    fun `trailing punctuation quotes and brackets do not hide observed endings`() {
        assertEquals("합니다", KoreanSentenceEndingExtractor.endingOf("“감사합니다.”"))
        assertEquals("했습니다", KoreanSentenceEndingExtractor.endingOf("(확인했습니다!)"))
        assertEquals("할까요", KoreanSentenceEndingExtractor.endingOf("‘할까요?’"))
        assertEquals("보자", KoreanSentenceEndingExtractor.endingOf("다음에 보자."))
        assertEquals("ㅋㅋ", KoreanSentenceEndingExtractor.endingOf("ㅋㅋ!"))
    }

    @Test
    fun `each sentence fragment contributes its own ending`() {
        val endings = KoreanSentenceEndingExtractor.topEndings(
            listOf("확인했습니다! 다음에 보자.\n할까요?")
        )

        assertEquals(listOf("했습니다", "보자", "할까요"), endings)
    }

    @Test
    fun `longest overlapping ending is counted once`() {
        val endings = KoreanSentenceEndingExtractor.topEndings(listOf("확인 부탁드립니다."))

        assertEquals(listOf("부탁드립니다"), endings)
    }

    @Test
    fun `ordinary nouns are not treated as endings`() {
        val endings = KoreanSentenceEndingExtractor.topEndings(listOf("사과", "회의", "나무"))

        assertTrue(endings.isEmpty())
    }

    @Test
    fun `on-device profiling recognizes polite and informal observed endings`() {
        val profiler = TypingDnaProfiler()

        val polite = profiler.profileOnDevice("work", listOf("감사합니다."))
        val informal = profiler.profileOnDevice("messenger", listOf("할까?"))
        val politeWithoutListedEnding = profiler.profileOnDevice("messenger", listOf("갔나요?"))

        assertEquals("Honorific", polite.dominantTone)
        assertEquals(listOf("합니다"), polite.habitualEndings)
        assertEquals("Informal", informal.dominantTone)
        assertEquals(listOf("할까"), informal.habitualEndings)
        assertEquals("Honorific", politeWithoutListedEnding.dominantTone)
        assertTrue(politeWithoutListedEnding.habitualEndings.isEmpty())
    }

    @Test
    fun `empty llm ending list is supplemented from observed fragments`() {
        val profiler = TypingDnaProfiler {
            """{"dominantTone":"Honorific","habitualEndings":[],"frequentBigrams":[],"cannedPhrases":[]}"""
        }

        val persona = profiler.profile("work", listOf("감사합니다. 확인했습니다!"))

        assertEquals(listOf("합니다", "했습니다"), persona.habitualEndings)
    }

    @Test
    fun `nonempty llm ending list remains unchanged`() {
        val profiler = TypingDnaProfiler {
            """{"dominantTone":"Honorific","habitualEndings":["LLM 고유 표현"],"frequentBigrams":[],"cannedPhrases":[]}"""
        }

        val persona = profiler.profile("work", listOf("감사합니다."))

        assertEquals(listOf("LLM 고유 표현"), persona.habitualEndings)
    }
}
