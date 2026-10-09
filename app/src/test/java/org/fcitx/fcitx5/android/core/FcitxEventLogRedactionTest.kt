/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FcitxEventLogRedactionTest {
    private val secret = "비밀"

    private fun formatted(text: String) =
        FormattedText(arrayOf(text), intArrayOf(0), text.length)

    @Test
    fun `commit string event hides the committed text`() {
        val rendered = FcitxEvent.CommitStringEvent(
            FcitxEvent.CommitStringEvent.Data(secret, -1)
        ).toString()

        assertFalse(rendered, rendered.contains(secret))
        assertTrue(rendered, rendered.contains("textLen=2"))
    }

    @Test
    fun `client preedit event hides the preedit text`() {
        val rendered = FcitxEvent.ClientPreeditEvent(formatted(secret)).toString()

        assertFalse(rendered, rendered.contains(secret))
        assertTrue(rendered, rendered.contains("len=2"))
    }

    @Test
    fun `input panel event hides preedit and aux text`() {
        val rendered = FcitxEvent.InputPanelEvent(
            FcitxEvent.InputPanelEvent.Data(
                formatted(secret),
                formatted("위$secret"),
                formatted("아래$secret"),
                emptyArray()
            )
        ).toString()

        assertFalse(rendered, rendered.contains(secret))
    }

    @Test
    fun `candidate list event hides candidate text and comments`() {
        val rendered = FcitxEvent.CandidateListEvent(
            FcitxEvent.CandidateListEvent.Data(
                1,
                arrayOf(CandidateWord("1", secret, "설명$secret"))
            )
        ).toString()

        assertFalse(rendered, rendered.contains(secret))
    }

    @Test
    fun `paged candidate event hides candidate text`() {
        val rendered = FcitxEvent.PagedCandidateEvent(
            FcitxEvent.PagedCandidateEvent.Data(
                arrayOf(CandidateWord("1", secret, "")),
                0,
                FcitxEvent.PagedCandidateEvent.LayoutHint.NotSet,
                hasPrev = false,
                hasNext = false
            )
        ).toString()

        assertFalse(rendered, rendered.contains(secret))
    }

    @Test
    fun `candidate word never prints its text`() {
        val rendered = CandidateWord("라벨$secret", secret, "설명$secret").toString()

        assertFalse(rendered, rendered.contains(secret))
    }

    @Test
    fun `key event hides the typed character`() {
        val rendered = FcitxEvent.KeyEvent(
            FcitxEvent.KeyEvent.Data(KeySym(0x61), KeyStates.of(0), 0x1234, up = false, timestamp = 7)
        ).toString()

        assertFalse(rendered, rendered.contains("4660"))
        assertFalse(rendered, rendered.contains("unicode"))
    }

    @Test
    fun `internal direct commit barrier hides its text`() {
        val rendered = FcitxEvent.InternalPromptDirectCommitBarrier(
            FcitxEvent.InternalPromptDirectCommitBarrier.Data(1L, 2L, secret)
        ).toString()

        assertFalse(rendered, rendered.contains(secret))
    }
}
