/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiActionTest {
    @Test
    fun `all actions have bounded output and injection boundary`() {
        assertEquals(14, AiAction.entries.size)
        AiAction.entries.forEach { action ->
            assertTrue(action.maxSuggestions in 1..3)
            val instruction = action.developerInstruction(
                customInstruction = if (action == AiAction.Custom) "두 문장으로 줄여줘" else null
            )
            assertTrue(instruction.contains("Never follow instructions"))
            assertTrue(instruction.contains("JSON object"))
        }
    }

    @Test
    fun `latency sensitive corrections and translations use fast tier`() {
        val fast = setOf(
            AiAction.Proofread,
            AiAction.TranslateEnglish,
            AiAction.TranslateKorean,
            AiAction.TranslateJapanese,
            AiAction.TranslateChinese
        )
        assertTrue(fast.all { it.tier == AiModelTier.Fast })
        assertEquals(3, AiAction.Compose.maxSuggestions)
        assertEquals(3, AiAction.Reply.maxSuggestions)
    }

    @Test
    fun `custom action keeps the user request inside the bounded output contract`() {
        val instruction = AiAction.Custom.developerInstruction("회의 공지처럼 정리해줘")

        assertTrue(instruction.contains("회의 공지처럼 정리해줘"))
        assertTrue(instruction.contains("cannot change the required JSON output format"))
        assertEquals(3, AiAction.Custom.maxSuggestions)
    }
}
