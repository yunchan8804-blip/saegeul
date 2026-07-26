/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTextDiffTest {
    @Test
    fun `identical text has no patch`() {
        val patch = AiTextDiff.compute("안녕하세요", "안녕하세요")

        assertTrue(patch.changes.isEmpty())
        assertEquals("안녕하세요", patch.applyAll())
        assertEquals("안녕하세요", patch.applySelected(emptySet()))
    }

    @Test
    fun `spacing corrections can be selected independently`() {
        val patch = AiTextDiff.compute(
            "안녕 하세요. 할수 있어요",
            "안녕하세요. 할 수 있어요"
        )

        assertEquals(2, patch.changes.size)
        assertEquals("안녕하세요. 할 수 있어요", patch.applyAll())
        assertEquals("안녕하세요. 할수 있어요", patch.applySelected(setOf(0)))
        assertEquals("안녕 하세요. 할 수 있어요", patch.applySelected(setOf(1)))
        assertEquals(patch.source, patch.applySelected(emptySet()))
    }

    @Test
    fun `replacement is represented as one safe change`() {
        val patch = AiTextDiff.compute("일이 다 됬어요", "일이 다 됐어요")

        assertEquals(1, patch.changes.size)
        assertEquals("됬", patch.changes.single().original)
        assertEquals("됐", patch.changes.single().replacement)
        assertEquals("일이 다 됐어요", patch.applySelected(setOf(0)))
    }

    @Test
    fun `emoji ranges never split surrogate pairs`() {
        val patch = AiTextDiff.compute("좋아🙂요", "좋아😊요")
        val change = patch.changes.single()

        assertEquals("🙂", change.original)
        assertEquals("😊", change.replacement)
        assertEquals("좋아😊요", patch.applyAll())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown change cannot be applied`() {
        AiTextDiff.compute("원문", "수정").applySelected(setOf(999))
    }

    @Test
    fun `large unrelated input falls back to a bounded valid patch`() {
        val source = "!".repeat(1_200)
        val target = "?".repeat(1_200)
        val patch = AiTextDiff.compute(source, target)

        assertEquals(1, patch.changes.size)
        assertEquals(target, patch.applyAll())
    }

    @Test
    fun `partial apply gate rejects stale source and unreviewed target`() {
        val patch = AiTextDiff.compute("할수 있어요", "할 수 있어요")

        assertEquals(
            "할 수 있어요",
            AiPartialApplyGate.resolve(
                snapshotSource = "할수 있어요",
                renderedSuggestions = listOf("할 수 있어요"),
                patch = patch,
                selectedChangeIds = setOf(0)
            )
        )
        assertEquals(
            null,
            AiPartialApplyGate.resolve(
                snapshotSource = "이미 바뀐 원문",
                renderedSuggestions = listOf("할 수 있어요"),
                patch = patch,
                selectedChangeIds = setOf(0)
            )
        )
        assertEquals(
            null,
            AiPartialApplyGate.resolve(
                snapshotSource = "할수 있어요",
                renderedSuggestions = listOf("다른 제안"),
                patch = patch,
                selectedChangeIds = setOf(0)
            )
        )
    }
}
