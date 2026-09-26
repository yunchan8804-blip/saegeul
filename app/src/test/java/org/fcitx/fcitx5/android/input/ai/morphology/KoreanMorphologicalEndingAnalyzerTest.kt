/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.morphology

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for KoreanMorphologicalEndingAnalyzer (Track 1: EAI-03 / B22).
 */
class KoreanMorphologicalEndingAnalyzerTest {

    @Test
    fun testFormalStyleEndings() {
        // -습니다
        assertEquals("습니다", KoreanMorphologicalEndingAnalyzer.extractEnding("먹습니다"))
        assertEquals("습니다", KoreanMorphologicalEndingAnalyzer.extractEnding("좋습니다"))

        // -ㅂ니다 (음절 받침 결합)
        assertEquals("합니다", KoreanMorphologicalEndingAnalyzer.extractEnding("참석합니다"))
        assertEquals("합니다", KoreanMorphologicalEndingAnalyzer.extractEnding("감사합니다"))
        assertEquals("ㅂ니다", KoreanMorphologicalEndingAnalyzer.extractEnding("갑니다"))
        assertEquals("ㅂ니다", KoreanMorphologicalEndingAnalyzer.extractEnding("봅니다"))

        // -습니까 / -ㅂ니까
        assertEquals("습니까", KoreanMorphologicalEndingAnalyzer.extractEnding("먹습니까"))
        assertEquals("합니까", KoreanMorphologicalEndingAnalyzer.extractEnding("출발합니까"))
        assertEquals("ㅂ니까", KoreanMorphologicalEndingAnalyzer.extractEnding("갑니까"))

        // -시오 / -소서 / -십시오 / -십시다
        assertEquals("시오", KoreanMorphologicalEndingAnalyzer.extractEnding("가시오"))
        assertEquals("소서", KoreanMorphologicalEndingAnalyzer.extractEnding("도우소서"))
        assertEquals("하십시오", KoreanMorphologicalEndingAnalyzer.extractEnding("주의하십시오"))
        assertEquals("십시오", KoreanMorphologicalEndingAnalyzer.extractEnding("기다리십시오"))
        assertEquals("하십시다", KoreanMorphologicalEndingAnalyzer.extractEnding("시작하십시다"))
        assertEquals("십시다", KoreanMorphologicalEndingAnalyzer.extractEnding("가십시다"))
    }

    @Test
    fun testPoliteStyleEndings() {
        // -어요, -아요, -여요, -해요, -세요
        assertEquals("어요", KoreanMorphologicalEndingAnalyzer.extractEnding("먹어요"))
        assertEquals("아요", KoreanMorphologicalEndingAnalyzer.extractEnding("보아요"))
        assertEquals("여요", KoreanMorphologicalEndingAnalyzer.extractEnding("하여요"))
        assertEquals("해요", KoreanMorphologicalEndingAnalyzer.extractEnding("축하해요"))
        assertEquals("하세요", KoreanMorphologicalEndingAnalyzer.extractEnding("안녕하세요"))
        assertEquals("세요", KoreanMorphologicalEndingAnalyzer.extractEnding("가세요"))

        // -게요, -ㄹ게요, -을게요
        assertEquals("할게요", KoreanMorphologicalEndingAnalyzer.extractEnding("할게요"))
        assertEquals("ㄹ게요", KoreanMorphologicalEndingAnalyzer.extractEnding("갈게요"))
        assertEquals("을게요", KoreanMorphologicalEndingAnalyzer.extractEnding("먹을게요"))

        // -지요, -죠, -네요, -군요, -대요, -래요, -거든요, -잖아요
        assertEquals("지요", KoreanMorphologicalEndingAnalyzer.extractEnding("그렇지요"))
        assertEquals("죠", KoreanMorphologicalEndingAnalyzer.extractEnding("맞죠"))
        assertEquals("네요", KoreanMorphologicalEndingAnalyzer.extractEnding("좋네요"))
        assertEquals("군요", KoreanMorphologicalEndingAnalyzer.extractEnding("멋지군요"))
        assertEquals("대요", KoreanMorphologicalEndingAnalyzer.extractEnding("온대요"))
        assertEquals("래요", KoreanMorphologicalEndingAnalyzer.extractEnding("친구래요"))
        assertEquals("거든요", KoreanMorphologicalEndingAnalyzer.extractEnding("바쁘거든요"))
        assertEquals("잖아요", KoreanMorphologicalEndingAnalyzer.extractEnding("알잖아요"))

        // -나요, -가요, -을까요, -ㄹ까요, -을텐데요
        assertEquals("나요", KoreanMorphologicalEndingAnalyzer.extractEnding("갔나요"))
        assertEquals("가요", KoreanMorphologicalEndingAnalyzer.extractEnding("어디 가요"))
        assertEquals("을까요", KoreanMorphologicalEndingAnalyzer.extractEnding("먹을까요"))
        assertEquals("할까요", KoreanMorphologicalEndingAnalyzer.extractEnding("할까요"))
        assertEquals("ㄹ까요", KoreanMorphologicalEndingAnalyzer.extractEnding("갈까요"))
        assertEquals("을텐데요", KoreanMorphologicalEndingAnalyzer.extractEnding("좋을텐데요"))
    }

    @Test
    fun testInformalStyleEndings() {
        // -어, -아, -여, -해, -지, -네, -군, -구나, -구만
        assertEquals("어", KoreanMorphologicalEndingAnalyzer.extractEnding("먹어"))
        assertEquals("아", KoreanMorphologicalEndingAnalyzer.extractEnding("좋아"))
        assertEquals("여", KoreanMorphologicalEndingAnalyzer.extractEnding("하여"))
        assertEquals("해", KoreanMorphologicalEndingAnalyzer.extractEnding("출근해"))
        assertEquals("지", KoreanMorphologicalEndingAnalyzer.extractEnding("알지"))
        assertEquals("네", KoreanMorphologicalEndingAnalyzer.extractEnding("춥네"))
        assertEquals("군", KoreanMorphologicalEndingAnalyzer.extractEnding("좋군"))
        assertEquals("구나", KoreanMorphologicalEndingAnalyzer.extractEnding("예쁘구나"))
        assertEquals("구만", KoreanMorphologicalEndingAnalyzer.extractEnding("멋지구만"))

        // -자, -마, -어라, -아라, -어봐, -아봐, -해봐
        assertEquals("먹자", KoreanMorphologicalEndingAnalyzer.extractEnding("먹자"))
        assertEquals("보자", KoreanMorphologicalEndingAnalyzer.extractEnding("다음에 보자"))
        assertEquals("마", KoreanMorphologicalEndingAnalyzer.extractEnding("주마"))
        assertEquals("어라", KoreanMorphologicalEndingAnalyzer.extractEnding("먹어라"))
        assertEquals("아라", KoreanMorphologicalEndingAnalyzer.extractEnding("보아라"))
        assertEquals("어봐", KoreanMorphologicalEndingAnalyzer.extractEnding("먹어봐"))
        assertEquals("해봐", KoreanMorphologicalEndingAnalyzer.extractEnding("해봐"))
        assertEquals("봐", KoreanMorphologicalEndingAnalyzer.extractEnding("내일 봐"))

        // -을게, -ㄹ게, -을까, -ㄹ까
        assertEquals("을게", KoreanMorphologicalEndingAnalyzer.extractEnding("먹을게"))
        assertEquals("ㄹ게", KoreanMorphologicalEndingAnalyzer.extractEnding("갈게"))
        assertEquals("을까", KoreanMorphologicalEndingAnalyzer.extractEnding("먹을까"))
        assertEquals("할까", KoreanMorphologicalEndingAnalyzer.extractEnding("할까"))
        assertEquals("ㄹ까", KoreanMorphologicalEndingAnalyzer.extractEnding("갈까"))

        // -는다, -ㄴ다, -다, -냐, -니, -란다, -단다, -거든, -잖아
        assertEquals("는다", KoreanMorphologicalEndingAnalyzer.extractEnding("먹는다"))
        assertEquals("ㄴ다", KoreanMorphologicalEndingAnalyzer.extractEnding("간다"))
        assertEquals("다", KoreanMorphologicalEndingAnalyzer.extractEnding("좋다"))
        assertEquals("냐", KoreanMorphologicalEndingAnalyzer.extractEnding("좋냐"))
        assertEquals("니", KoreanMorphologicalEndingAnalyzer.extractEnding("가니"))
        assertEquals("란다", KoreanMorphologicalEndingAnalyzer.extractEnding("친구란다"))
        assertEquals("단다", KoreanMorphologicalEndingAnalyzer.extractEnding("그렇단다"))
        assertEquals("단다", KoreanMorphologicalEndingAnalyzer.extractEnding("멋지단다"))
        assertEquals("거든", KoreanMorphologicalEndingAnalyzer.extractEnding("맞거든"))
        assertEquals("잖아", KoreanMorphologicalEndingAnalyzer.extractEnding("알잖아"))
    }

    @Test
    fun testColloquialAndChatEndings() {
        // -어용, -아용, -했음, -음, -ㅁ, -임, -음요, -ㅁ요, -네용, -구요
        assertEquals("어용", KoreanMorphologicalEndingAnalyzer.extractEnding("먹어용"))
        assertEquals("아용", KoreanMorphologicalEndingAnalyzer.extractEnding("좋아용"))
        assertEquals("했어용", KoreanMorphologicalEndingAnalyzer.extractEnding("했어용"))
        assertEquals("했음", KoreanMorphologicalEndingAnalyzer.extractEnding("확인했음"))
        assertEquals("음", KoreanMorphologicalEndingAnalyzer.extractEnding("자료 보냈음"))
        assertEquals("음", KoreanMorphologicalEndingAnalyzer.extractEnding("먹음"))
        assertEquals("ㅁ", KoreanMorphologicalEndingAnalyzer.extractEnding("지금 감"))
        assertEquals("임", KoreanMorphologicalEndingAnalyzer.extractEnding("내일임"))
        assertEquals("음요", KoreanMorphologicalEndingAnalyzer.extractEnding("좋음요"))
        assertEquals("ㅁ요", KoreanMorphologicalEndingAnalyzer.extractEnding("봄요"))
        assertEquals("네용", KoreanMorphologicalEndingAnalyzer.extractEnding("좋네용"))
        assertEquals("구요", KoreanMorphologicalEndingAnalyzer.extractEnding("맞구요"))

        // Emoticons: ㅋㅋ, ㅎㅎ, ㅠㅠ, ㅜㅜ
        assertEquals("ㅋㅋ", KoreanMorphologicalEndingAnalyzer.extractEnding("ㅋㅋ"))
        assertEquals("ㅋㅋ", KoreanMorphologicalEndingAnalyzer.extractEnding("대박ㅋㅋ"))
        assertEquals("ㅎㅎ", KoreanMorphologicalEndingAnalyzer.extractEnding("고마워ㅎㅎ"))
        assertEquals("ㅠㅠ", KoreanMorphologicalEndingAnalyzer.extractEnding("슬퍼ㅠㅠ"))
        assertEquals("ㅜㅜ", KoreanMorphologicalEndingAnalyzer.extractEnding("아쉽ㅜㅜ"))
    }

    @Test
    fun testPreFinalEndingCombinations() {
        // -았/었/였-
        assertEquals("었어", KoreanMorphologicalEndingAnalyzer.extractEnding("점심 먹었어"))
        assertEquals("했습니다", KoreanMorphologicalEndingAnalyzer.extractEnding("도착했습니다"))
        assertEquals("았습니다", KoreanMorphologicalEndingAnalyzer.extractEnding("받았습니다"))
        assertEquals("였습니다", KoreanMorphologicalEndingAnalyzer.extractEnding("학생이였습니다"))
        assertEquals("았음", KoreanMorphologicalEndingAnalyzer.extractEnding("받았음"))
        assertEquals("었음", KoreanMorphologicalEndingAnalyzer.extractEnding("먹었음"))

        // -겠-
        assertEquals("하겠습니다", KoreanMorphologicalEndingAnalyzer.extractEnding("참석하겠습니다"))
        assertEquals("겠습니다", KoreanMorphologicalEndingAnalyzer.extractEnding("먹겠습니다"))
        assertEquals("겠어요", KoreanMorphologicalEndingAnalyzer.extractEnding("좋겠어요"))
        assertEquals("겠네", KoreanMorphologicalEndingAnalyzer.extractEnding("힘들겠네"))

        // -시/으시-
        assertEquals("으세요", KoreanMorphologicalEndingAnalyzer.extractEnding("앉으세요"))
        assertEquals("으십시오", KoreanMorphologicalEndingAnalyzer.extractEnding("읽으십시오"))
        assertEquals("셨어요", KoreanMorphologicalEndingAnalyzer.extractEnding("오셨어요"))
        assertEquals("셨습니다", KoreanMorphologicalEndingAnalyzer.extractEnding("수고하셨습니다"))

        // -더-
        assertEquals("더라", KoreanMorphologicalEndingAnalyzer.extractEnding("예쁘더라"))
        assertEquals("더군요", KoreanMorphologicalEndingAnalyzer.extractEnding("춥더군요"))
        assertEquals("더라고요", KoreanMorphologicalEndingAnalyzer.extractEnding("좋더라고요"))
        assertEquals("던데요", KoreanMorphologicalEndingAnalyzer.extractEnding("맛있던데요"))
    }

    @Test
    fun testNounExclusionsAreNotTreatedAsEndings() {
        val nonEndings = listOf(
            "사과", "회의", "나무", "결과", "학과", "효과", "치과",
            "후보자", "초보자", "보호자", "피보호자", "기자", "환자", "모자", "상자",
            "의사", "교사", "변호사", "판사", "감사", "기사", "식사", "검사", "조사",
            "마음", "처음", "얼음", "젊음", "믿음", "죽음", "걸음", "웃음", "울음",
            "점심", "사람", "바람", "시간", "편지", "잡지", "바지", "동네", "단어"
        )
        for (noun in nonEndings) {
            assertNull("Noun '$noun' should not yield an ending", KoreanMorphologicalEndingAnalyzer.extractEnding(noun))
            assertFalse("Noun '$noun' should not be sentence terminal", KoreanMorphologicalEndingAnalyzer.isSentenceTerminal(noun))
        }
    }

    @Test
    fun testIsSentenceTerminal() {
        assertTrue(KoreanMorphologicalEndingAnalyzer.isSentenceTerminal("참석합니다"))
        assertTrue(KoreanMorphologicalEndingAnalyzer.isSentenceTerminal("먹었어"))
        assertTrue(KoreanMorphologicalEndingAnalyzer.isSentenceTerminal("부탁드려요"))
        assertTrue(KoreanMorphologicalEndingAnalyzer.isSentenceTerminal("내일 봐"))
        assertTrue(KoreanMorphologicalEndingAnalyzer.isSentenceTerminal("보냈음"))
        assertTrue(KoreanMorphologicalEndingAnalyzer.isSentenceTerminal("축하해요"))
        assertTrue(KoreanMorphologicalEndingAnalyzer.isSentenceTerminal("ㅋㅋ"))

        assertFalse(KoreanMorphologicalEndingAnalyzer.isSentenceTerminal("사과"))
        assertFalse(KoreanMorphologicalEndingAnalyzer.isSentenceTerminal("회의"))
        assertFalse(KoreanMorphologicalEndingAnalyzer.isSentenceTerminal("오늘"))
        assertFalse(KoreanMorphologicalEndingAnalyzer.isSentenceTerminal(""))
    }
}
