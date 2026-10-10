/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypoRestatementTest {

    @Test
    fun `a reply that fixes typos of the typed sentence is a restatement`() {
        assertTrue(TypoRestatement.isRestatement("오늘 회으ㅣ 몇시에", "오늘 회의 몇 시에 시작해요?"))
        assertTrue(TypoRestatement.isRestatement("안녕하세여", "안녕하세요."))
        assertTrue(TypoRestatement.isRestatement("내일 모래 만나요", "내일 모레 만나요. 기대돼요."))
    }

    @Test
    fun `a different sentence is not a restatement`() {
        assertFalse(TypoRestatement.isRestatement("안녕하세여", "내일 회의 일정을 공유할게요."))
        assertFalse(TypoRestatement.isRestatement("오늘 점심 뭐 먹지", "내일 회의 일정을 공유할게요."))
        assertFalse(TypoRestatement.isRestatement("회의 자료를", "공유합니다."))
    }

    @Test
    fun `a typed sentence with fewer than two Hangul syllables is never restated`() {
        assertFalse(TypoRestatement.isRestatement("네", "네요."))
        assertFalse(TypoRestatement.isRestatement("ㅋㅋ", "ㅋㅋㅋ"))
        assertFalse(TypoRestatement.isRestatement("a네", "a네요."))
    }

    @Test
    fun `a reply shorter than the typed sentence minus the length window cannot match`() {
        assertFalse(TypoRestatement.isRestatement("안녕하세여 반가워요 오늘도", "안녕"))
    }

    @Test
    fun `spacing differences do not count as edits`() {
        assertTrue(TypoRestatement.isRestatement("몇시에시작해여", "몇 시에 시작해요."))
    }

    @Test
    fun `a sentence reworded in tone or particles is a rewording of the previous sentence`() {
        assertTrue(
            TypoRestatement.isRewordingOf(
                "감기 걸려서 오늘 출근 못 할 것 같아요.",
                "감기 걸려서 오늘 출근을 못 할 것 같습니다."
            )
        )
        assertTrue(TypoRestatement.isRewordingOf("내일 회의는 3시예요.", "내일 회의는 3시예요."))
    }

    @Test
    fun `a different sentence or a too short previous sentence is not a rewording`() {
        assertFalse(TypoRestatement.isRewordingOf("감기 걸려서 오늘 출근 못 할 것 같아요.", "병원 들렀다가 푹 쉬고 내일은 꼭 출근하겠습니다."))
        assertFalse(TypoRestatement.isRewordingOf("네.", "네."))
    }
}
