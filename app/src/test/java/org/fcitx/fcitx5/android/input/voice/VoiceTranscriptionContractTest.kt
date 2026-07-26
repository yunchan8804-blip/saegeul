/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTranscriptionContractTest {
    @Test
    fun `transcript is bounded and blank text is rejected`() {
        assertNull(VoiceTranscriptPolicy.normalize("  "))
        val normalized = VoiceTranscriptPolicy.normalize(
            "가".repeat(VoiceTranscriptPolicy.MAX_CHARACTERS + 20)
        )
        assertEquals(VoiceTranscriptPolicy.MAX_CHARACTERS, normalized?.length)
    }

    @Test
    fun `editor binding requires one known cursor`() {
        val target = VoiceTranscriptPolicy.bindEditor("chat.app", 7, 1, 12, 12)

        assertEquals(VoiceEditorTarget("chat.app", 7, 1, 12), target)
        assertNull(VoiceTranscriptPolicy.bindEditor("chat.app", 7, 1, 12, 13))
        assertNull(VoiceTranscriptPolicy.bindEditor("chat.app", 7, 1, -1, -1))
        assertNull(VoiceTranscriptPolicy.bindEditor("", 7, 1, 12, 12))
    }

    @Test
    fun `reviewed transcript can dispatch exactly once`() {
        val gate = VoiceCommitGate()

        assertTrue(gate.claim())
        assertFalse(gate.claim())

        gate.resetForNewTranscript()
        assertTrue(gate.claim())
    }
}
