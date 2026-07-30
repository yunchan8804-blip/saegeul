/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDirectPromptContinuationTest {
    private val reviewedSnapshot = AiInputSnapshot(
        editor = AiEditorTarget(
            packageName = "example.editor",
            fieldId = 42,
            inputType = 1,
            selectionStart = 4,
            selectionEnd = 4,
            inputSessionEpoch = 9
        ),
        source = "검토한 원문입니다",
        sourceKind = AiSourceKind.SurroundingEditor,
        beforeCursor = "검토한 ",
        afterCursor = "원문입니다",
        scope = AiSourceScope.EntireEditor
    )

    @Test
    fun `direct prompt resumes with the exact reviewed snapshot rather than a newer capture`() {
        val pending = AiDirectPromptContinuation.bind(
            instruction = "두 문장으로 줄여줘",
            snapshot = reviewedSnapshot,
            replySourceOrigin = AiReplySourceOrigin.Clipboard
        )
        val newerCapture = reviewedSnapshot.copy(
            editor = reviewedSnapshot.editor.copy(selectionStart = 2, selectionEnd = 2),
            source = "사용자가 바꾼 새 원문",
            beforeCursor = "사용자",
            afterCursor = "가 바꾼 새 원문"
        )

        val resumed = AiDirectPromptContinuation.resumeIfSnapshotCurrent(pending) {
            it == reviewedSnapshot
        }

        assertEquals("두 문장으로 줄여줘", resumed?.instruction)
        assertEquals(reviewedSnapshot, resumed?.snapshot)
        assertTrue(resumed?.snapshot != newerCapture)
        assertEquals(AiReplySourceOrigin.Clipboard, resumed?.replySourceOrigin)
    }

    @Test
    fun `direct prompt does not resume when its reviewed snapshot became stale`() {
        val pending = AiDirectPromptContinuation.bind(
            instruction = "더 공손하게 바꿔줘",
            snapshot = reviewedSnapshot,
            replySourceOrigin = null
        )

        val resumed = AiDirectPromptContinuation.resumeIfSnapshotCurrent(pending) { false }

        assertNull(resumed)
    }
}
