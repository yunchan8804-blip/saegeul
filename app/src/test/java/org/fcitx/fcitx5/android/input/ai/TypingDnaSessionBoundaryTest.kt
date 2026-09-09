/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingDnaSessionBoundaryTest {

    @Test
    fun `new editor session discards unfinished context but preserves committed history`() {
        val committed = mutableListOf<String>()
        val collector = UserTypingContextCollector(
            onSentenceCommitted = { _, sentence -> committed.add(sentence) }
        )
        val sink = TypingDnaCommitSink(collector)

        sink.onEditorTextCommitted("com.example.chat", "기존 문장입니다.", inspectionAllowed = true)
        committed.clear()
        sink.onEditorTextCommitted("com.example.chat", "내가 뭘 ", inspectionAllowed = true)

        sink.onEditorSessionStarted()
        sink.onEditorTextCommitted("com.example.chat", "감사합니다.", inspectionAllowed = true)

        assertEquals(listOf("감사합니다."), committed)
        assertEquals(listOf("기존 문장입니다.", "감사합니다."), collector.getSentences("com.example.chat"))
    }

    @Test
    fun `short pending flush removes it before later text arrives`() {
        listOf("ㅇ", "ㅇㅋ", "가나다").forEach { short ->
            val committed = mutableListOf<String>()
            val collector = UserTypingContextCollector(
                onSentenceCommitted = { _, sentence -> committed.add(sentence) }
            )

            collector.recordCommittedText("com.example.chat", short)
            assertFalse(collector.flushPending("com.example.chat"))
            assertFalse(collector.hasPending("com.example.chat"))
            collector.recordCommittedText("com.example.chat", "감사합니다.")

            assertEquals(listOf("감사합니다."), committed)
        }
    }

    @Test
    fun `same app restart discards every package pending context`() {
        val committed = mutableListOf<Pair<String, String>>()
        val collector = UserTypingContextCollector(
            onSentenceCommitted = { packageName, sentence -> committed.add(packageName to sentence) }
        )
        val sink = TypingDnaCommitSink(collector)

        sink.onEditorTextCommitted("com.example.chat", "내가 뭘 ", inspectionAllowed = true)
        sink.onEditorTextCommitted("com.example.work", "회의를 ", inspectionAllowed = true)
        sink.onEditorSessionStarted()

        assertFalse(collector.hasPending("com.example.chat"))
        assertFalse(collector.hasPending("com.example.work"))
        sink.onEditorTextCommitted("com.example.chat", "감사합니다.", inspectionAllowed = true)

        assertEquals(listOf("com.example.chat" to "감사합니다."), committed)
    }

    @Test
    fun `normal editor finish still flushes valid pending text`() {
        val committed = mutableListOf<String>()
        val sink = TypingDnaCommitSink(
            UserTypingContextCollector(onSentenceCommitted = { _, sentence -> committed.add(sentence) })
        )

        sink.onEditorTextCommitted("com.example.chat", "오늘 저녁에 만나자", inspectionAllowed = true)
        sink.onEditorFinished("com.example.chat", inspectionAllowed = true)

        assertEquals(listOf("오늘 저녁에 만나자"), committed)
    }
}
