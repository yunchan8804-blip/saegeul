/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEditorTransactionTest {
    @Test
    fun `failed replacement restores the complete original selection`() {
        val calls = mutableListOf<Pair<Int, Int>>()

        val committed = AiEditorTransaction.replaceRange(
            start = 4,
            end = 9,
            replacement = "교정문",
            restoreStart = 2,
            restoreEnd = 7,
            setSelection = { start, end ->
                calls += start to end
                true
            },
            commitText = { false },
            confirmCommit = { error("a failed dispatch must not be confirmed") }
        )

        assertFalse(committed)
        assertEquals(listOf(4 to 9, 2 to 7), calls)
    }

    @Test
    fun `failed append restores the original selection after moving the cursor`() {
        val calls = mutableListOf<Pair<Int, Int>>()

        val committed = AiEditorTransaction.commitAtCursor(
            cursor = 11,
            text = "\n제안문",
            restoreStart = 3,
            restoreEnd = 8,
            setSelection = { start, end ->
                calls += start to end
                true
            },
            commitText = { false },
            confirmCommit = { error("a failed dispatch must not be confirmed") }
        )

        assertFalse(committed)
        assertEquals(listOf(11 to 11, 3 to 8), calls)
    }

    @Test
    fun `transport acknowledgement without visible confirmation restores selection`() {
        val calls = mutableListOf<Pair<Int, Int>>()

        val committed = AiEditorTransaction.replaceRange(
            start = 24,
            end = 46,
            replacement = "교정문",
            restoreStart = 24,
            restoreEnd = 46,
            setSelection = { start, end ->
                calls += start to end
                true
            },
            commitText = { true },
            confirmCommit = { false }
        )

        assertFalse(committed)
        assertEquals(listOf(24 to 46, 24 to 46), calls)
    }

    @Test
    fun `confirmed empty replacement succeeds for undo deletion`() {
        val calls = mutableListOf<Pair<Int, Int>>()

        val committed = AiEditorTransaction.replaceRange(
            start = 24,
            end = 46,
            replacement = "",
            restoreStart = 24,
            restoreEnd = 46,
            setSelection = { start, end ->
                calls += start to end
                true
            },
            commitText = { text -> text.isEmpty() },
            confirmCommit = { text -> text.isEmpty() }
        )

        assertTrue(committed)
        assertEquals(listOf(24 to 46), calls)
    }
}
