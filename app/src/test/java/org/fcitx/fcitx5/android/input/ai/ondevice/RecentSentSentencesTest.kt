/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentSentSentencesTest {

    @Test
    fun `caps at 5 entries per package and returns newest first`() {
        var now = 0L
        val store = RecentSentSentences(clockMs = { now })

        for (i in 1..7) {
            store.record("com.example.chat", "문장$i")
            now += 1_000L
        }

        assertEquals(listOf("문장7", "문장6", "문장5"), store.recent("com.example.chat", limit = 3))
        assertEquals(5, store.recent("com.example.chat", limit = 10).size)
        assertEquals(listOf("문장7", "문장6", "문장5", "문장4", "문장3"), store.recent("com.example.chat", limit = 10))
    }

    @Test
    fun `entries expire 10 minutes after being recorded`() {
        var now = 0L
        val store = RecentSentSentences(clockMs = { now })

        store.record("com.example.chat", "오래된 문장")
        now += 10L * 60 * 1000 + 1
        store.record("com.example.chat", "새 문장")

        assertEquals(listOf("새 문장"), store.recent("com.example.chat"))
    }

    @Test
    fun `entries exactly at the expiry boundary are still returned`() {
        var now = 0L
        val store = RecentSentSentences(clockMs = { now })

        store.record("com.example.chat", "경계 문장")
        now += 10L * 60 * 1000

        assertEquals(listOf("경계 문장"), store.recent("com.example.chat"))
    }

    @Test
    fun `pii bearing sentences are never recorded`() {
        val store = RecentSentSentences(clockMs = { 0L })

        store.record("com.example.chat", "제 번호는 010-1234-5678 입니다")
        store.record("com.example.chat", "안전한 문장입니다")

        assertEquals(listOf("안전한 문장입니다"), store.recent("com.example.chat"))
    }

    @Test
    fun `packages are isolated from each other`() {
        val store = RecentSentSentences(clockMs = { 0L })

        store.record("com.example.chat", "채팅 문장")
        store.record("com.example.mail", "메일 문장")

        assertEquals(listOf("채팅 문장"), store.recent("com.example.chat"))
        assertEquals(listOf("메일 문장"), store.recent("com.example.mail"))
    }

    @Test
    fun `blank sentences are dropped and clear empties every package`() {
        val store = RecentSentSentences(clockMs = { 0L })

        store.record("com.example.chat", "   ")
        assertTrue(store.recent("com.example.chat").isEmpty())

        store.record("com.example.chat", "문장")
        store.record("com.example.mail", "다른 문장")
        store.clear()

        assertTrue(store.recent("com.example.chat").isEmpty())
        assertTrue(store.recent("com.example.mail").isEmpty())
    }
}
