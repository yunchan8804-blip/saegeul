/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingDnaPendingSuffixTest {

    @Test
    fun `exact candidate suffix replacement emits the replacement once`() {
        val committed = mutableListOf<String>()
        val sink = TypingDnaCommitSink(
            UserTypingContextCollector(onSentenceCommitted = { _, sentence -> committed.add(sentence) })
        )

        sink.onEditorTextCommitted("com.example.chat", "감사 ", inspectionAllowed = true)
        sink.onEditorSuffixDeleted("com.example.chat", "감사 ", inspectionAllowed = true)
        sink.onEditorTextCommitted("com.example.chat", "감사하겠습니다 ", inspectionAllowed = true)

        assertEquals(listOf("감사하겠습니다"), committed)
    }

    @Test
    fun `exact suffix removal retains earlier pending context`() {
        val committed = mutableListOf<String>()
        val sink = TypingDnaCommitSink(
            UserTypingContextCollector(onSentenceCommitted = { _, sentence -> committed.add(sentence) })
        )

        sink.onEditorTextCommitted("com.example.chat", "내가 감사 ", inspectionAllowed = true)
        sink.onEditorSuffixDeleted("com.example.chat", "감사 ", inspectionAllowed = true)
        sink.onEditorTextCommitted("com.example.chat", "감사하겠습니다 ", inspectionAllowed = true)

        assertEquals(listOf("내가 감사하겠습니다"), committed)
    }

    @Test
    fun `mismatch or null deleted suffix discards only pending and preserves history`() {
        val committed = mutableListOf<String>()
        val collector = UserTypingContextCollector(onSentenceCommitted = { _, sentence -> committed.add(sentence) })
        val sink = TypingDnaCommitSink(collector)

        sink.onEditorTextCommitted("com.example.chat", "확정 문장입니다.", inspectionAllowed = true)
        committed.clear()
        sink.onEditorTextCommitted("com.example.chat", "내가 감사 ", inspectionAllowed = true)
        sink.onEditorSuffixDeleted("com.example.chat", "다른", inspectionAllowed = true)

        assertFalse(collector.hasPending("com.example.chat"))
        assertEquals(listOf("확정 문장입니다."), collector.getSentences("com.example.chat"))

        sink.onEditorTextCommitted("com.example.chat", "또 다른 문맥 ", inspectionAllowed = true)
        sink.onEditorSuffixDeleted("com.example.chat", null, inspectionAllowed = true)

        assertFalse(collector.hasPending("com.example.chat"))
        assertTrue(committed.isEmpty())
        assertEquals(listOf("확정 문장입니다."), collector.getSentences("com.example.chat"))
    }

    @Test
    fun `suffix removal does not affect another package pending context`() {
        val collector = UserTypingContextCollector()
        val sink = TypingDnaCommitSink(collector)

        sink.onEditorTextCommitted("com.example.chat", "감사 ", inspectionAllowed = true)
        sink.onEditorTextCommitted("com.example.work", "회의를 ", inspectionAllowed = true)
        sink.onEditorSuffixDeleted("com.example.chat", "감사", inspectionAllowed = true)

        assertFalse(collector.hasPending("com.example.chat"))
        assertTrue(collector.hasPending("com.example.work"))
    }
}
