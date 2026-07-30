/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTextSourceTest {
    @Test
    fun `selected text rejects empty and oversized values`() {
        assertNull(AiTextSource.selectedText(""))
        assertNull(AiTextSource.selectedText("가".repeat(AiTextSource.MAX_CHARACTERS + 1)))
        assertEquals("선택", AiTextSource.selectedText("선택"))
    }

    @Test
    fun `splits a complete editor at the current cursor`() {
        val source = AiTextSource.completeEditor(
            text = "앞쪽 뒤쪽",
            startOffset = 0,
            partialStartOffset = -1,
            cursorOffset = 3,
            extractedSelectionStart = 3,
            extractedSelectionEnd = 3
        )!!

        assertEquals("앞쪽 ", source.beforeCursor)
        assertEquals("뒤쪽", source.afterCursor)
        assertEquals("앞쪽 뒤쪽", source.text)
    }

    @Test
    fun `rejects incomplete blank oversized and invalid complete editor extracts`() {
        assertNull(AiTextSource.completeEditor("내용", 1, -1, 1, 1, 1))
        assertNull(AiTextSource.completeEditor("내용", 0, 0, 1, 1, 1))
        assertNull(AiTextSource.completeEditor("   ", 0, -1, 1, 1, 1))
        assertNull(
            AiTextSource.completeEditor(
                "가".repeat(AiTextSource.MAX_CHARACTERS + 1),
                0,
                -1,
                1,
                1,
                1
            )
        )
        assertNull(AiTextSource.completeEditor("내용", 0, -1, 3, 3, 3))
    }

    @Test
    fun `rejects a complete extract when its selection is stale`() {
        assertNull(
            AiTextSource.completeEditor(
                text = "현재 입력값",
                startOffset = 0,
                partialStartOffset = -1,
                cursorOffset = 4,
                extractedSelectionStart = 2,
                extractedSelectionEnd = 2
            )
        )
        assertNull(
            AiTextSource.completeEditor(
                text = "현재 입력값",
                startOffset = 0,
                partialStartOffset = -1,
                cursorOffset = 4,
                extractedSelectionStart = 4,
                extractedSelectionEnd = 3
            )
        )
    }

    @Test
    fun `identifies whether an extract belongs to the captured editor selection`() {
        assertTrue(
            AiTextSource.matchesExtractedSelection(
                startOffset = 0,
                capturedSelectionStart = 4,
                capturedSelectionEnd = 4,
                extractedSelectionStart = 4,
                extractedSelectionEnd = 4
            )
        )
        assertTrue(
            !AiTextSource.matchesExtractedSelection(
                startOffset = 0,
                capturedSelectionStart = 4,
                capturedSelectionEnd = 4,
                extractedSelectionStart = 2,
                extractedSelectionEnd = 2
            )
        )
    }

    @Test
    fun `keeps all nearby text when the editor fallback fits the request bound`() {
        val source = AiTextSource.cursorContext("첫 문단\n커서 앞", "커서 뒤\n다음 문단")!!

        assertEquals("첫 문단\n커서 앞", source.beforeCursor)
        assertEquals("커서 뒤\n다음 문단", source.afterCursor)
        assertEquals("첫 문단\n커서 앞커서 뒤\n다음 문단", source.text)
    }

    @Test
    fun `uses bounded current paragraph around cursor for a long editor fallback`() {
        val source = AiTextSource.cursorContext(
            beforeCursor = "이전 문단\n" + "가".repeat(3_000),
            afterCursor = "나".repeat(3_000) + "\n다음 문단"
        )!!

        assertEquals(AiTextSource.MAX_CHARACTERS, source.text.length)
        assertEquals(2_000, source.beforeCursor.length)
        assertEquals(2_000, source.afterCursor.length)
        assertTrue(source.beforeCursor.all { it == '가' })
        assertTrue(source.afterCursor.all { it == '나' })
    }

    @Test
    fun `preserves an only-before cursor context up to the request bound`() {
        val source = AiTextSource.cursorContext("가".repeat(5_000), "")!!

        assertEquals(AiTextSource.MAX_CHARACTERS, source.text.length)
        assertEquals(AiTextSource.MAX_CHARACTERS, source.beforeCursor.length)
        assertTrue(source.afterCursor.isEmpty())
    }
}
