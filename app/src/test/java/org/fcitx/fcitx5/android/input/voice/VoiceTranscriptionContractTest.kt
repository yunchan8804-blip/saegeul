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

    @Test
    fun `device dictation offers the installed voice input or a setup action`() {
        assertEquals(
            VoiceUnavailableAction.DeviceDictation,
            VoiceFallbackPolicy.action(hasDeviceVoiceInput = true)
        )
        assertEquals(
            VoiceUnavailableAction.ProviderSetup,
            VoiceFallbackPolicy.action(hasDeviceVoiceInput = false)
        )
    }

    @Test
    fun `meeting button ignores quick dictation mode but requires stored STT and network access`() {
        assertTrue(
            VoiceTranscriptionUiPolicy.showMeetingButton(
                hasStoredSttProfile = true,
                allowsTextInspection = true,
                allowsNetworkInput = true
            )
        )
        assertFalse(
            VoiceTranscriptionUiPolicy.showMeetingButton(
                hasStoredSttProfile = false,
                allowsTextInspection = true,
                allowsNetworkInput = true
            )
        )
        assertFalse(
            VoiceTranscriptionUiPolicy.showMeetingButton(
                hasStoredSttProfile = true,
                allowsTextInspection = false,
                allowsNetworkInput = true
            )
        )
        assertFalse(
            VoiceTranscriptionUiPolicy.showMeetingButton(
                hasStoredSttProfile = true,
                allowsTextInspection = true,
                allowsNetworkInput = false
            )
        )
    }

    @Test
    fun `granted microphone permission resumes once in the same editor`() {
        val queue = VoicePermissionResumeQueue()
        val target = VoiceEditorTarget("chat.app", 7, 1, 12)

        queue.begin(41L, target)
        queue.complete(41L, granted = true)

        assertEquals(
            VoicePermissionResumeResult(target, granted = true),
            queue.consumeForEditor("chat.app", 7, 1)
        )
        assertNull(queue.consumeForEditor("chat.app", 7, 1))
    }

    @Test
    fun `permission resume is discarded when the editor changed`() {
        val queue = VoicePermissionResumeQueue()
        val target = VoiceEditorTarget("chat.app", 7, 1, 12)

        queue.begin(42L, target)
        queue.complete(42L, granted = true)

        assertNull(queue.consumeForEditor("other.app", 7, 1))
        assertNull(queue.consumeForEditor("chat.app", 7, 1))
    }

    @Test
    fun `stale permission results cannot replace the latest request`() {
        val queue = VoicePermissionResumeQueue()
        val oldTarget = VoiceEditorTarget("old.app", 1, 1, 0)
        val currentTarget = VoiceEditorTarget("chat.app", 7, 1, 12)

        queue.begin(1L, oldTarget)
        queue.begin(2L, currentTarget)
        queue.complete(1L, granted = true)
        queue.complete(2L, granted = false)

        assertEquals(
            VoicePermissionResumeResult(currentTarget, granted = false),
            queue.consumeForEditor("chat.app", 7, 1)
        )
    }
}
