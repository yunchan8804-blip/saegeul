/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypoReplacementTest {

    private fun TypoReplacement.Link.span() = listOf(typedStart, typedEnd, replyStart, replyEnd)

    private fun align(typed: String, reply: String) =
        TypoReplacement.align(typed.split(" "), reply.split(" ")).map { it.span() }

    @Test
    fun `identical words align one to one`() {
        assertEquals(
            listOf(listOf(0, 1, 0, 1), listOf(1, 2, 1, 2), listOf(2, 3, 2, 3)),
            align("오늘 날씨 좋네여", "오늘 날씨 좋네요")
        )
    }

    @Test
    fun `a typed word that the reply split in two aligns as one to two`() {
        assertEquals(
            listOf(listOf(0, 1, 0, 1), listOf(1, 2, 1, 3), listOf(2, 3, 3, 4)),
            align("내일 몇시에 만나여", "내일 몇 시에 만나요")
        )
    }

    @Test
    fun `typed words that the reply merged align as two to one`() {
        assertEquals(
            listOf(listOf(0, 1, 0, 1), listOf(1, 3, 1, 2), listOf(3, 4, 2, 3)),
            align("내일 몇 시에 만나여", "내일 몇시에 만나요")
        )
    }

    @Test
    fun `one typed word can align to three reply words`() {
        assertEquals(
            listOf(listOf(0, 1, 0, 3)),
            align("몇시에시작해여", "몇 시에 시작해요")
        )
    }

    @Test
    fun `reply words after the last typed word are the free continuation`() {
        assertEquals(
            listOf(listOf(0, 1, 0, 1), listOf(1, 1, 1, 2), listOf(1, 1, 2, 3)),
            align("안녕하세여", "안녕하세요 반갑습니다 오늘도")
        )
    }

    @Test
    fun `an extra reply word in the middle is an unmatched insertion`() {
        val links = align("오늘 시작해여", "오늘 회의 시작해요")

        assertEquals(listOf(0, 1, 0, 1), links.first())
        assertEquals(listOf(1, 2, 2, 3), links.last())
        assertEquals(listOf(1, 1, 1, 2), links[1])
    }

    @Test
    fun `a typed word the reply dropped is an unmatched deletion`() {
        val links = align("오늘 아주 시작해여", "오늘 시작해요")

        assertEquals(listOf(listOf(0, 1, 0, 1), listOf(1, 2, 1, 1), listOf(2, 3, 1, 2)), links)
    }

    @Test
    fun `only the typo word is taken from the reply and the added particle is dropped`() {
        assertEquals(
            "오늘 날씨 좋네요.",
            TypoReplacement.compose("오늘 날씨 좋네여", "오늘 날씨가 좋네요.", typoEvidenceOf("좋네여" to "좋네요"))
        )
    }

    @Test
    fun `a single typo word is replaced by the reply word`() {
        assertEquals(
            "안녕하세요",
            TypoReplacement.compose("안녕하세여", "안녕하세요", typoEvidenceOf("안녕하세여" to "안녕하세요"))
        )
    }

    @Test
    fun `a typo word that the reply respaced takes the reply spacing`() {
        val evidence = typoEvidenceOf("몇시에" to "몇시에", "만나여" to "만나요")

        assertEquals(
            "내일 몇 시에 만날까요?",
            TypoReplacement.compose("내일 몇시에 만나여", "내일 몇 시에 만날까요?", evidence)
        )
    }

    @Test
    fun `a known word keeps its own spacing and only the typo ending is taken`() {
        assertEquals(
            "내일 몇시에 만날까요?",
            TypoReplacement.compose("내일 몇시에 만나여", "내일 몇 시에 만날까요?", typoEvidenceOf("만나여" to "만나요"))
        )
    }

    @Test
    fun `a reply with no typo evidence in the typed sentence yields nothing`() {
        assertNull(
            TypoReplacement.compose(
                "회의록 정리해서 공유드릴게",
                "회의록을 정리해서 공유드리겠습니다.",
                NO_TYPO_EVIDENCE
            )
        )
    }

    @Test
    fun `a reply that leaves every evidence word as typed yields nothing`() {
        assertNull(
            TypoReplacement.compose("오늘 날씨 좋네여", "오늘 날씨가 좋네여.", typoEvidenceOf("좋네여" to "좋네요"))
        )
    }

    @Test
    fun `the continuation past the typed text is appended after the replacement`() {
        assertEquals(
            "안녕하세요 반갑습니다.",
            TypoReplacement.compose("안녕하세여", "안녕하세요 반갑습니다.", typoEvidenceOf("안녕하세여" to "안녕하세요"))
        )
    }

    @Test
    fun `a reply word too different from the typo word is replaced by the corrector result`() {
        assertEquals(
            "오늘 회의 시작해요",
            TypoReplacement.compose(
                "오늘 회으ㅣ 시작해요",
                "오늘 점심 시작해요",
                typoEvidenceOf("회으ㅣ" to "회의")
            )
        )
    }

    @Test
    fun `a typo word the reply dropped is replaced by the corrector result`() {
        assertEquals(
            "오늘 회의 시작해요",
            TypoReplacement.compose("오늘 회으ㅣ 시작해요", "오늘 시작해요", typoEvidenceOf("회으ㅣ" to "회의"))
        )
    }

    @Test
    fun `a punctuation only difference is not a change`() {
        assertNull(TypoReplacement.compose("안녕하세여", "안녕하세여.", typoEvidenceOf("안녕하세여" to "안녕하세요")))
    }

    @Test
    fun `the last word still being typed is not typo evidence when it starts a dictionary word`() {
        val evidence = typoEvidenceOf("날씨가좋" to "날씨가좋아", wordPrefixes = setOf("날씨가좋"))

        assertNull(TypoReplacement.compose("오늘 날씨가좋", "오늘 날씨가 좋아요.", evidence))
        assertEquals(
            "오늘 날씨가 좋아요.",
            TypoReplacement.compose("오늘 날씨가좋 ", "오늘 날씨가 좋아요.", evidence)
        )
    }

    @Test
    fun `an empty reply or typed text yields nothing`() {
        assertNull(TypoReplacement.compose("  ", "안녕하세요", typoEvidenceOf("안녕하세여" to "안녕하세요")))
        assertNull(TypoReplacement.compose("안녕하세여", "  ", typoEvidenceOf("안녕하세여" to "안녕하세요")))
    }
}
