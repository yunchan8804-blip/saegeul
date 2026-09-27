/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateBadgeTest {

    @Test
    fun `keyword based comments resolve to their icon`() {
        assertEquals("✨", CandidateBadge.iconFor("이어쓰기"))
        assertEquals("✨", CandidateBadge.iconFor("Gemma 생성"))
        assertEquals("✨", CandidateBadge.iconFor("Gemma 이어쓰기"))
        // English locale (values/gemma_automatic_strings.xml) translations of the two comments
        // above must keep resolving to the same icon; see CandidateItemUiPillTest.
        assertEquals("✨", CandidateBadge.iconFor("Gemma Generated"))
        assertEquals("✨", CandidateBadge.iconFor("Gemma Continuation"))
        assertEquals("✨", CandidateBadge.iconFor("문장완성"))
        assertEquals("💬", CandidateBadge.iconFor("동의/확인"))
        assertEquals("📖", CandidateBadge.iconFor("기본문장"))
    }

    @Test
    fun `renamed AI and personalization badges without the emoji prefix still resolve to the AI icon`() {
        // 2026-09-26 확정(design.md 라운드 3): 배지 문자열에서 ✨ 접두어를 뺐다. 텍스트에 있던
        // 한국어 단서(완성/단어/스타일/맞춤/AI/기록)만으로 계속 같은 아이콘으로 분류돼야 한다.
        assertEquals("✨", CandidateBadge.iconFor("AI 완성"))
        assertEquals("✨", CandidateBadge.iconFor("AI 단어"))
        assertEquals("✨", CandidateBadge.iconFor("내 스타일"))
        assertEquals("✨", CandidateBadge.iconFor("내 기록"))
        assertEquals("✨", CandidateBadge.iconFor("맞춤 AI"))
    }

    @Test
    fun `symbol only comments are returned as their own icon`() {
        assertEquals("⚡", CandidateBadge.iconFor("⚡"))
        assertEquals("✏️", CandidateBadge.iconFor("✏️"))
    }

    @Test
    fun `non badge comments resolve to null`() {
        assertNull(CandidateBadge.iconFor(null))
        assertNull(CandidateBadge.iconFor(""))
        assertNull(CandidateBadge.iconFor("ㄱ"))
        assertNull(CandidateBadge.iconFor("hànzì"))
    }

    @Test
    fun `hangul next word notes are hidden in either language`() {
        assertTrue(CandidateBadge.isNextWordMarker("Next word"))
        assertTrue(CandidateBadge.isNextWordMarker("next word"))
        assertTrue(CandidateBadge.isNextWordMarker("다음 단어"))
        assertFalse(CandidateBadge.isNextWordMarker("다음"))
        assertFalse(CandidateBadge.isNextWordMarker(null))
    }
}
