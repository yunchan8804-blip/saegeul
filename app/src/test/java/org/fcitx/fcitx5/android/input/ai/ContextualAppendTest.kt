/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextualAppendTest {
    @Test
    fun `insertion supplies a gap or preserves trailing whitespace`() {
        val append = ContextualAppend("회의가 끝나고", "연락할게")

        assertEquals(" 연락할게 ", append.insertionFor("회의가 끝나고"))
        assertEquals("연락할게 ", append.insertionFor("회의가 끝나고 "))
    }

    @Test
    fun `insertion rejects stale context and keeps punctuation in the expected context`() {
        val append = ContextualAppend("회의가 끝났어?", "응, 방금 끝났어.")

        assertNull(append.insertionFor("회의가 끝났어!"))
        assertEquals(" 응, 방금 끝났어. ", append.insertionFor("회의가 끝났어?"))
    }

    @Test
    fun `attachment joins a particle or ending without changing existing text`() {
        val append = ContextualAppend(
            "회의",
            "에 참석해 주세요.",
            ContextualAppend.JoinMode.ATTACH
        )

        assertEquals("에 참석해 주세요. ", append.insertionFor("회의"))
        assertNull(append.insertionFor("회의 "))
    }

    @Test
    fun `insertion rejects oversized or invalid raw suffixes`() {
        assertNull(ContextualAppend("가".repeat(1025), "후속").insertionFor("가".repeat(1025)))
        assertNull(ContextualAppend("문맥", "다음\n문장").insertionFor("문맥"))
        assertNull(ContextualAppend("문맥", "깨짐\uFFFD").insertionFor("문맥"))
        assertNull(ContextualAppend("문맥", "숨김\u200B문자").insertionFor("문맥"))
    }
}
