/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingDnaEditorContinuityTest {

    @Test
    fun `continuity loss discards only current package pending context`() {
        val collector = UserTypingContextCollector()
        val sink = TypingDnaCommitSink(collector)

        sink.onEditorTextCommitted("com.example.chat", "확정 문장입니다.", inspectionAllowed = true)
        sink.onEditorTextCommitted("com.example.chat", "내가 뭘 ", inspectionAllowed = true)
        sink.onEditorTextCommitted("com.example.work", "회의를 ", inspectionAllowed = true)

        sink.onEditorContinuityLost("com.example.chat", inspectionAllowed = true)

        assertFalse(collector.hasPending("com.example.chat"))
        assertTrue(collector.hasPending("com.example.work"))
        assertEquals(listOf("확정 문장입니다."), collector.getSentences("com.example.chat"))
    }

    @Test
    fun `disallowed inspection and blank package preserve pending context`() {
        val collector = UserTypingContextCollector()
        val sink = TypingDnaCommitSink(collector)

        sink.onEditorTextCommitted("com.example.chat", "내가 뭘 ", inspectionAllowed = true)
        sink.onEditorContinuityLost("com.example.chat", inspectionAllowed = false)
        sink.onEditorContinuityLost("", inspectionAllowed = true)

        assertTrue(collector.hasPending("com.example.chat"))
    }
}
