/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalPromptCaptureSessionTest {
    @Test
    fun `combines engine commits and current preedit without duplicating editor text`() {
        val session = InternalPromptCaptureSession("두 문장 ", maxCharacters = 80)

        session.updatePreedit("짧")
        assertEquals("두 문장 짧", session.displayText)
        session.commit("짧게")

        assertEquals("두 문장 짧게", session.submission())
        assertEquals("", session.preeditText)
    }

    @Test
    fun `backspace prefers preedit then committed code points`() {
        val session = InternalPromptCaptureSession("문장🙂", maxCharacters = 80)
        session.updatePreedit("을")

        session.deleteBeforeCursor()
        assertEquals("문장🙂", session.displayText)
        session.deleteBeforeCursor()

        assertEquals("문장", session.displayText)
    }

    @Test
    fun `session respects the feature supplied character limit`() {
        val session = InternalPromptCaptureSession(
            "가".repeat(InternalPromptSpecs.Ai.maxCharacters + 20),
            maxCharacters = InternalPromptSpecs.Ai.maxCharacters
        )

        session.commit("추가")

        assertEquals(InternalPromptSpecs.Ai.maxCharacters, session.displayText.length)
    }

    @Test
    fun `AI keeps an empty prompt open while GIF submits trending search`() {
        assertFalse(InternalPromptSpecs.Ai.allowBlankSubmission)
        assertTrue(InternalPromptSpecs.gifSearch(80).allowBlankSubmission)
    }

    @Test
    fun `picker text follows the finalized engine preedit`() {
        val session = InternalPromptCaptureSession(maxCharacters = 80)
        session.updatePreedit("가")

        // The Fcitx marker finalizes preedit first, then appends the emoji/picker action.
        session.commitPreedit()
        session.commit("🙂")

        assertEquals("가🙂", session.submission())
    }
}
