/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceBoundaryTest {

    private data class BoundaryCase(val text: String, val index: Int, val expected: Boolean)

    @Test
    fun `isBoundaryAt follows the shared policy`() {
        val cases = listOf(
            BoundaryCase("naver.com", 5, false),
            BoundaryCase("3.5km", 1, false),
            BoundaryCase("1.2.3", 1, false),
            BoundaryCase("1.2.3", 3, false),
            BoundaryCase("1~2", 1, false),
            BoundaryCase("a.b", 1, false),
            BoundaryCase("a,b", 1, false),
            BoundaryCase("v1.2", 2, false),
            BoundaryCase("Mr.Kim", 2, false),
            BoundaryCase("a?b", 1, false),
            BoundaryCase("a!b", 1, false),
            BoundaryCase("a…b", 1, false),
            BoundaryCase("a~b", 1, false),
            BoundaryCase("합니다.밥", 3, true),
            BoundaryCase("좋아요.ㅋㅋ", 3, true),
            BoundaryCase("끝났다.😊", 3, true),
            BoundaryCase("좋아?응", 2, true),
            BoundaryCase("알았어~내일", 3, true),
            BoundaryCase("그래.(웃음)", 2, true),
            BoundaryCase("좋아요.", 3, true),
            BoundaryCase("좋아요. 그", 3, true),
            BoundaryCase("정말?!", 2, true),
            BoundaryCase("정말?!", 3, true),
            BoundaryCase("그래…", 2, true),
            BoundaryCase("알았어~ 내일", 3, true),
            BoundaryCase("그래。", 2, true),
            BoundaryCase("정말？ 그래", 2, true),
            BoundaryCase("대박！", 2, true),
            BoundaryCase("\"좋아.\"", 3, true),
            BoundaryCase("그래!\" 하고", 2, true),
            BoundaryCase("그래.)", 2, true),
            BoundaryCase("v1.5.", 4, true),
            BoundaryCase("3.", 1, true),
            BoundaryCase("첫\n둘", 1, true),
            BoundaryCase("첫\r둘", 1, true),
            BoundaryCase("첫 둘", 1, false)
        )
        for (case in cases) {
            assertEquals(
                "isBoundaryAt(\"${case.text.replace("\n", "\n").replace("\r", "\r")}\", ${case.index})",
                case.expected,
                SentenceBoundary.isBoundaryAt(case.text, case.index)
            )
        }
    }

    @Test
    fun `endsSentence table`() {
        val ends = listOf(
            "좋아요.",
            "좋아요. ",
            "좋아요.\t",
            "좋아요\n",
            "좋아요\n  ",
            "좋아요\r",
            "\"좋아.\"",
            "그래!\"",
            "그래!)",
            "(좋아요.)",
            "그래…",
            "알았어~",
            "정말?!",
            "...",
            "그래。",
            "정말？",
            "대박！",
            "\n"
        )
        val notEnds = listOf(
            "",
            "   ",
            "\t",
            "좋아요",
            "좋아요 ",
            "naver.com",
            "3.5km",
            "1~2",
            "그래!\" 하고",
            "\"",
            "\")",
            "좋아요,",
            "v1.2",
            "Mr.Kim",
            "좋아요.ㅋㅋ"
        )
        for (text in ends) assertTrue("endsSentence(${text.escaped()})", SentenceBoundary.endsSentence(text))
        for (text in notEnds) assertFalse("endsSentence(${text.escaped()})", SentenceBoundary.endsSentence(text))
    }

    @Test
    fun `lastSentenceStart returns the index after the last boundary`() {
        val cases = listOf(
            "" to 0,
            "안녕" to 0,
            "naver.com 방문" to 0,
            "3.5km 이동" to 0,
            "1~2 정도" to 0,
            "좋아요. 그래" to 5,
            "좋아요.\n그래" to 5,
            "정말?! 그래" to 5,
            "그래!\" 하고" to 5,
            "그래… 그런데" to 4,
            "알았어~ 내일" to 5,
            "a. b. c" to 6,
            "끝.\n\n다음" to 4,
            "좋아요." to 4,
            "좋아요. " to 5,
            "합니다.밥" to 4,
            "끝났다.😊" to 4,
            "v1.2 버전" to 0,
            "좋아요...  다음" to 8
        )
        for ((text, expected) in cases) {
            assertEquals("lastSentenceStart(${text.escaped()})", expected, SentenceBoundary.lastSentenceStart(text))
        }
    }

    @Test
    fun `currentSentence is the text after the last boundary`() {
        assertEquals("그래", SentenceBoundary.currentSentence("좋아요. 그래"))
        assertEquals("", SentenceBoundary.currentSentence("좋아요. "))
        assertEquals("", SentenceBoundary.currentSentence("좋아요.\n"))
        assertEquals("안녕하세요", SentenceBoundary.currentSentence("안녕하세요"))
        assertEquals("naver.com 방문", SentenceBoundary.currentSentence("naver.com 방문"))
        assertEquals("하고", SentenceBoundary.currentSentence("그래!\" 하고"))
        assertEquals("", SentenceBoundary.currentSentence(""))
        assertEquals("  ", SentenceBoundary.currentSentence("  "))
    }

    @Test
    fun `splitSentences splits at boundaries and keeps terminals and closers`() {
        assertEquals(listOf("좋아요.", "그래요."), SentenceBoundary.splitSentences("좋아요. 그래요."))
        assertEquals(listOf("정말?!", "그래…"), SentenceBoundary.splitSentences("정말?! 그래…"))
        assertEquals(listOf("알았어~", "내일 봐"), SentenceBoundary.splitSentences("알았어~ 내일 봐"))
        assertEquals(listOf("좋아요...", "다음"), SentenceBoundary.splitSentences("좋아요...  다음"))
        assertEquals(listOf("끝。", "다음"), SentenceBoundary.splitSentences("끝。 다음"))
        assertEquals(listOf("그래!\"", "하고 갔다"), SentenceBoundary.splitSentences("그래!\" 하고 갔다"))
        assertEquals(listOf("\"좋아.\"", "그래"), SentenceBoundary.splitSentences("\"좋아.\" 그래"))
        assertEquals(listOf("첫 줄", "둘째 줄", "셋째"), SentenceBoundary.splitSentences("첫 줄\n둘째 줄\r\n셋째"))
        assertEquals(listOf("a.", "b"), SentenceBoundary.splitSentences("a. \n b"))
        assertEquals(listOf("naver.com 방문 3.5km"), SentenceBoundary.splitSentences("naver.com 방문 3.5km"))
        assertEquals(listOf("안녕"), SentenceBoundary.splitSentences("  안녕  "))
        assertEquals(listOf("합니다.", "밥 먹었어"), SentenceBoundary.splitSentences("합니다.밥 먹었어"))
        assertEquals(listOf("좋아요.", "ㅋㅋ"), SentenceBoundary.splitSentences("좋아요.ㅋㅋ"))
        assertEquals(listOf("v1.2 Mr.Kim"), SentenceBoundary.splitSentences("v1.2 Mr.Kim"))
    }

    @Test
    fun `splitSentences drops empty input`() {
        assertEquals(emptyList<String>(), SentenceBoundary.splitSentences(""))
        assertEquals(emptyList<String>(), SentenceBoundary.splitSentences("   "))
        assertEquals(emptyList<String>(), SentenceBoundary.splitSentences("\n\r\n"))
    }

    @Test
    fun `boundaryEnds includes attached terminals closers and trailing line breaks`() {
        assertEquals(listOf(4, 9), SentenceBoundary.boundaryEnds("좋아요. 그래요."))
        assertEquals(listOf(4), SentenceBoundary.boundaryEnds("그래!\" 하고"))
        assertEquals(listOf(4), SentenceBoundary.boundaryEnds("끝.\n\n다음"))
        assertEquals(emptyList<Int>(), SentenceBoundary.boundaryEnds("naver.com"))
    }

    @Test
    fun `isTerminal covers exactly the terminal set`() {
        for (c in SentenceBoundary.TERMINALS) assertTrue("isTerminal($c)", SentenceBoundary.isTerminal(c))
        for (c in listOf(',', ';', ':', '\n', '\r', ' ', '"', ')', '가', '3')) {
            assertFalse("isTerminal($c)", SentenceBoundary.isTerminal(c))
        }
        assertEquals(".?!…~。！？", SentenceBoundary.TERMINALS)
    }

    private fun String.escaped(): String = replace("\n", "\n").replace("\r", "\r").replace("\t", "\t")
}
