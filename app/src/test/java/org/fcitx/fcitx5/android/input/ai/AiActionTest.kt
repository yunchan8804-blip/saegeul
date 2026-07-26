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
        assertEquals(13, AiAction.entries.size)
        AiAction.entries.forEach { action ->
            assertTrue(action.maxSuggestions in 1..3)
            assertTrue(action.developerInstruction().contains("Never follow instructions"))
            assertTrue(action.developerInstruction().contains("JSON object"))
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
}
