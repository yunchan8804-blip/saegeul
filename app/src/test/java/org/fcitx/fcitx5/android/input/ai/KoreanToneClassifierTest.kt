/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 존댓말/반말 판정 정본([KoreanToneClassifier])의 회귀 방어선.
 * "정리할게요"류 해요체를 반말 표지어("할게")의 부분 문자열 일치로 잘못 반말 판정하던
 * 버그(2026-09-27 실기기 보고)의 재발을 막는다.
 */
class KoreanToneClassifierTest {

    @Test
    fun `haeyo-che endings ending in yo are honorific despite containing an informal marker substring`() {
        val honorificExamples = listOf(
            "정리할게요", "좋아요", "맞아요", "편해요", "그래요", "뭐예요?", "고마워요"
        )
        honorificExamples.forEach { text ->
            assertEquals("'$text' 는 존댓말이어야 한다", KoreanTone.Honorific, KoreanToneClassifier.infer(text))
        }
    }

    @Test
    fun `informal endings without a trailing yo stay informal`() {
        val informalExamples = listOf(
            "정리할게", "좋아", "맞아", "고마워", "뭐해?"
        )
        informalExamples.forEach { text ->
            assertEquals("'$text' 는 반말이어야 한다", KoreanTone.Informal, KoreanToneClassifier.infer(text))
        }
    }

    @Test
    fun `mixed context lets the most recent sentence win`() {
        assertEquals(
            KoreanTone.Informal,
            KoreanToneClassifier.infer("회의록 정리해서 공유드렸습니다. 오늘 도와줘서 정말 고마워")
        )
        assertEquals(
            KoreanTone.Honorific,
            KoreanToneClassifier.infer("아까 고마워. 오늘 자료 정리해서 다시 보내드리겠습니다.")
        )
    }

    @Test
    fun `blank context defaults to honorific`() {
        assertEquals(KoreanTone.Honorific, KoreanToneClassifier.infer(""))
        assertEquals(KoreanTone.Honorific, KoreanToneClassifier.infer("   "))
    }

    @Test
    fun `bare short context without a terminal or yo-yong ending has no tone evidence`() {
        val noEvidenceExamples = listOf("아까", "오늘 저녁", "나 지금", "분야")
        noEvidenceExamples.forEach { text ->
            assertNull("'$text' 는 말투 근거가 없어야 한다", KoreanToneClassifier.evidence(text))
        }
    }

    @Test
    fun `a terminal punctuation mark unlocks the ending-based score`() {
        assertEquals(KoreanTone.Informal, KoreanToneClassifier.evidence("아까 말했잖아."))
        assertEquals(KoreanTone.Informal, KoreanToneClassifier.infer("아까 말했잖아."))
    }

    @Test
    fun `kka only counts as informal when the preceding syllable has a rieul batchim`() {
        assertEquals(KoreanTone.Informal, KoreanToneClassifier.evidence("할까?"))
        assertEquals(KoreanTone.Informal, KoreanToneClassifier.infer("할까?"))
        assertEquals(KoreanTone.Honorific, KoreanToneClassifier.evidence("할까요?"))
        assertEquals(KoreanTone.Honorific, KoreanToneClassifier.infer("할까요?"))
    }
}
