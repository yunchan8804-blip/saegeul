/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiPromptCaptureSessionTest {
    @Test
    fun `combines engine commits and current preedit without duplicating editor text`() {
        val session = AiPromptCaptureSession("두 문장 ")

        session.updatePreedit("짧")
        assertEquals("두 문장 짧", session.displayText)
        session.commit("짧게")

        assertEquals("두 문장 짧게", session.submission())
        assertEquals("", session.preeditText)
    }

    @Test
    fun `backspace prefers preedit then committed code points`() {
        val session = AiPromptCaptureSession("문장🙂")
        session.updatePreedit("을")

        session.deleteBeforeCursor()
        assertEquals("문장🙂", session.displayText)
        session.deleteBeforeCursor()

        assertEquals("문장", session.displayText)
    }

    @Test
    fun `instruction stays within the custom action limit`() {
        val session = AiPromptCaptureSession(
            "가".repeat(AiAction.MAX_CUSTOM_INSTRUCTION_CHARACTERS + 20)
        )

        session.commit("추가")
        assertEquals(AiAction.MAX_CUSTOM_INSTRUCTION_CHARACTERS, session.displayText.length)
    }
}
