/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import org.fcitx.fcitx5.android.core.CandidateWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the candidate order [HorizontalCandidateComponent] shows today: which source goes first,
 * how duplicates are dropped by text, and that merging itself never caps the count (the caps live
 * in the contextual snapshot's word/sentence limits).
 */
class HorizontalCandidateMergerTest {

    private fun word(text: String, comment: String = "") = CandidateWord("", text, comment)

    private fun Array<CandidateWord>.texts() = map { it.text }

    @Test
    fun `address field shows composed word then contextual chips then remaining native alternatives`() {
        val merged = HorizontalCandidateMerger.merge(
            nativeList = listOf(word("gmail"), word("gmai"), word("gma")),
            contextualWords = listOf(word("@gmail.com"), word(".com")),
            contextualSentences = listOf(word("메일 주소를 확인해 주세요.")),
            addressField = true
        )

        assertEquals(listOf("gmail", "@gmail.com", ".com", "gmai", "gma"), merged.texts())
    }

    @Test
    fun `address field drops later duplicates by text keeping the first occurrence`() {
        val contextualDomain = word("naver.com", comment = "도메인")
        val merged = HorizontalCandidateMerger.merge(
            nativeList = listOf(word("naver"), word("naver.com"), word("daum")),
            contextualWords = listOf(contextualDomain, word("naver")),
            contextualSentences = emptyList(),
            addressField = true
        )

        assertEquals(listOf("naver", "naver.com", "daum"), merged.texts())
        assertSame(contextualDomain, merged[1])
    }

    @Test
    fun `address field without native candidates returns contextual words as they are`() {
        val merged = HorizontalCandidateMerger.merge(
            nativeList = emptyList(),
            contextualWords = listOf(word(".com"), word(".com"), word(".net")),
            contextualSentences = listOf(word("문장")),
            addressField = true
        )

        assertEquals(listOf(".com", ".com", ".net"), merged.texts())
    }

    @Test
    fun `conversational field orders composed word, typo fixes, native alternatives, then other contextual candidates`() {
        val merged = HorizontalCandidateMerger.merge(
            nativeList = listOf(word("안녕"), word("안녕히")),
            contextualWords = listOf(word("안넝→안녕", comment = "✏️"), word("안녕하세요")),
            contextualSentences = listOf(word("안녕 하세요→안녕하세요", comment = "✏️ 교정"), word("안녕, 잘 지냈어?")),
            addressField = false
        )

        assertEquals(
            listOf("안녕", "안넝→안녕", "안녕 하세요→안녕하세요", "안녕히", "안녕하세요", "안녕, 잘 지냈어?"),
            merged.texts()
        )
    }

    @Test
    fun `conversational field without native candidates puts typo fixes before other contextual candidates`() {
        val merged = HorizontalCandidateMerger.merge(
            nativeList = emptyList(),
            contextualWords = listOf(word("고마워"), word("감사합니다", comment = "✏️")),
            contextualSentences = listOf(word("정말 고마워요."), word("감사합니다.", comment = "✏️")),
            addressField = false
        )

        assertEquals(listOf("감사합니다", "감사합니다.", "고마워", "정말 고마워요."), merged.texts())
    }

    @Test
    fun `conversational field de-duplicates by text across every source keeping the first occurrence`() {
        val composed = word("안녕")
        val typoWord = word("안녕하세요", comment = "✏️")
        val merged = HorizontalCandidateMerger.merge(
            nativeList = listOf(composed, word("안녕하세요"), word("안녕")),
            contextualWords = listOf(typoWord, word("안녕")),
            contextualSentences = listOf(word("안녕하세요")),
            addressField = false
        )

        assertEquals(listOf("안녕", "안녕하세요"), merged.texts())
        assertSame(composed, merged[0])
        assertSame(typoWord, merged[1])
    }

    @Test
    fun `conversational field without native candidates also de-duplicates contextual candidates`() {
        val merged = HorizontalCandidateMerger.merge(
            nativeList = emptyList(),
            contextualWords = listOf(word("네"), word("네")),
            contextualSentences = listOf(word("네"), word("네, 알겠습니다.")),
            addressField = false
        )

        assertEquals(listOf("네", "네, 알겠습니다."), merged.texts())
    }

    @Test
    fun `typo badge counts only when the comment starts with it`() {
        val merged = HorizontalCandidateMerger.merge(
            nativeList = listOf(word("가")),
            contextualWords = listOf(word("가나", comment = "추천 ✏️"), word("가다", comment = "✏️교정")),
            contextualSentences = emptyList(),
            addressField = false
        )

        assertEquals(listOf("가", "가다", "가나"), merged.texts())
    }

    @Test
    fun `merge keeps every distinct candidate without a count cap`() {
        val native = (1..10).map { word("native$it") }
        val contextual = (1..6).map { word("contextual$it") }
        val sentences = (1..4).map { word("sentence$it") }

        val merged = HorizontalCandidateMerger.merge(native, contextual, sentences, addressField = false)

        assertEquals(20, merged.size)
    }

    @Test
    fun `automatic candidates lead and hide legacy candidates with the same text`() {
        val automaticFirst = word("좋아요", comment = "자동")
        val merged = HorizontalCandidateMerger.prependAutomatic(
            automatic = listOf(automaticFirst, word("감사합니다", comment = "자동")),
            legacy = arrayOf(word("좋아요"), word("네"), word("감사합니다"), word("네"))
        )

        assertEquals(listOf("좋아요", "감사합니다", "네", "네"), merged.texts())
        assertSame(automaticFirst, merged[0])
    }

    @Test
    fun `no automatic candidates leaves legacy candidates unchanged`() {
        val legacy = arrayOf(word("하나"), word("둘"), word("하나"))

        val merged = HorizontalCandidateMerger.prependAutomatic(emptyList(), legacy)

        assertEquals(listOf("하나", "둘", "하나"), merged.texts())
    }
}
