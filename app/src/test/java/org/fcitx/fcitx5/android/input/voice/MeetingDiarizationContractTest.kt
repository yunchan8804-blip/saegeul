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
import org.fcitx.fcitx5.android.input.ai.AiFeatureEntryGate

class MeetingDiarizationContractTest {
    @Test
    fun `audio policy accepts official formats but bounds size and duration`() {
        val accepted = MeetingAudioPolicy.validate(
            mimeType = "audio/mp4",
            displayName = "private-meeting.m4a",
            declaredSizeBytes = 1_024L,
            durationMillis = 30_000L
        )

        assertEquals("audio/mp4", accepted?.contentType)
        assertEquals("meeting.m4a", accepted?.uploadFileName)
        assertFalse(accepted?.uploadFileName.orEmpty().contains("private-meeting"))
        assertNull(MeetingAudioPolicy.validate("audio/aac", "record.aac", 100L, 1_000L))
        assertNull(MeetingAudioPolicy.validate(
            "audio/mpeg",
            "record.mp3",
            MeetingAudioPolicy.MAX_FILE_BYTES + 1L,
            1_000L
        ))
        assertNull(MeetingAudioPolicy.validate(
            "audio/mpeg",
            "record.mp3",
            100L,
            MeetingAudioPolicy.MAX_DURATION_MILLIS + 1L
        ))
        assertNull(MeetingAudioPolicy.validate("audio/mpeg", "record.mp3", 100L, null))
    }

    @Test
    fun `audio policy rejects conflicting recognized MIME and file extension`() {
        assertNull(MeetingAudioPolicy.validate("audio/wav", "record.mp3", 100L, 1_000L))
        assertNull(MeetingAudioPolicy.validate("audio/mpeg", "record.m4a", 100L, 1_000L))
    }

    @Test
    fun `audio policy canonicalizes supported MIME aliases`() {
        val wav = MeetingAudioPolicy.validate("audio/x-wav", "record.wav", 100L, 1_000L)
        val ogg = MeetingAudioPolicy.validate("application/ogg", "record.ogg", 100L, 1_000L)
        val extensionFallback = MeetingAudioPolicy.validate(
            "application/octet-stream",
            "record.m4a",
            100L,
            1_000L
        )
        val mimeFallback = MeetingAudioPolicy.validate(
            "audio/x-wav",
            "record.unknown",
            100L,
            1_000L
        )

        assertEquals("audio/wav", wav?.contentType)
        assertEquals("meeting.wav", wav?.uploadFileName)
        assertEquals("audio/ogg", ogg?.contentType)
        assertEquals("meeting.ogg", ogg?.uploadFileName)
        assertEquals("audio/mp4", extensionFallback?.contentType)
        assertEquals("meeting.m4a", extensionFallback?.uploadFileName)
        assertEquals("audio/wav", mimeFallback?.contentType)
        assertEquals("meeting.wav", mimeFallback?.uploadFileName)
    }

    @Test
    fun `capability fails closed outside the standard OpenAI endpoint`() {
        val standard = VoiceProviderProfile(apiKey = "key")
        val unsupportedModel = standard.copy(diarizationModel = "custom-diarize")
        val customEndpoint = standard.copy(baseUrl = "https://provider.example/v1")

        assertTrue(MeetingDiarizationCapability.supports(standard))
        assertFalse(MeetingDiarizationCapability.supports(unsupportedModel))
        assertFalse(MeetingDiarizationCapability.supports(customEndpoint))
    }

    @Test
    fun `meeting resolves the stored STT profile without a quick dictation mode`() {
        val profile = VoiceProviderProfile(apiKey = "key")

        assertEquals(
            profile,
            MeetingVoiceProfileResolver.resolve(
                allowsCredentialAccess = true,
                loadCredential = { profile }
            )
        )
    }

    @Test
    fun `blocked meeting entry never reads the STT credential`() {
        var reads = 0

        val resolved = MeetingVoiceProfileResolver.resolve(
            allowsCredentialAccess = false,
            loadCredential = {
                reads += 1
                VoiceProviderProfile(apiKey = "must-not-be-read")
            }
        )

        assertNull(resolved)
        assertEquals(0, reads)
    }

    @Test
    fun `meeting window gate keeps privacy network and setup states distinct`() {
        val profile = VoiceProviderProfile(apiKey = "key")

        assertEquals(
            AiFeatureEntryGate.PrivateEditor,
            MeetingWindowEntryPolicy.evaluate(false, false, null)
        )
        assertEquals(
            AiFeatureEntryGate.NetworkPolicyBlocked,
            MeetingWindowEntryPolicy.evaluate(true, false, null)
        )
        assertEquals(
            AiFeatureEntryGate.SetupRequired,
            MeetingWindowEntryPolicy.evaluate(true, true, null)
        )
        assertEquals(
            AiFeatureEntryGate.Ready,
            MeetingWindowEntryPolicy.evaluate(true, true, profile)
        )
    }

    @Test
    fun `only selected speaker segments are formatted and insert gate is exactly once`() {
        val segments = listOf(
            MeetingSpeakerSegment("a", "A", 1.2, 2.0, "첫 문장"),
            MeetingSpeakerSegment("b", "B", 65.9, 67.0, "둘째 문장")
        )

        assertEquals("[01:05] B: 둘째 문장", MeetingTranscriptSelection.format(
            segments,
            setOf("b"),
            "화자"
        ))
        assertNull(MeetingTranscriptSelection.format(segments, setOf("missing"), "화자"))

        val gate = MeetingCommitGate()
        assertTrue(gate.claim())
        assertFalse(gate.claim())
        gate.resetForSelection()
        assertTrue(gate.claim())
    }

    @Test
    fun `audio picker result resumes once in the same editor`() {
        val queue = VoiceAudioDocumentResumeQueue()
        val target = VoiceEditorTarget("chat.app", 7, 1, 12)

        queue.begin(41L, target)
        queue.complete(41L, "content://documents/audio/1")

        assertEquals(
            VoiceAudioDocumentResumeResult(target, "content://documents/audio/1"),
            queue.consumeForEditor("chat.app", 7, 1)
        )
        assertNull(queue.consumeForEditor("chat.app", 7, 1))
    }

    @Test
    fun `cancelled audio picker restores meeting window without a file`() {
        val queue = VoiceAudioDocumentResumeQueue()
        val target = VoiceEditorTarget("chat.app", 7, 1, 12)

        queue.begin(42L, target)
        queue.complete(42L, null)

        assertEquals(
            VoiceAudioDocumentResumeResult(target, null),
            queue.consumeForEditor("chat.app", 7, 1)
        )
    }

    @Test
    fun `audio picker result is discarded when editor identity changed`() {
        val queue = VoiceAudioDocumentResumeQueue()
        val target = VoiceEditorTarget("chat.app", 7, 1, 12)

        queue.begin(43L, target)
        queue.complete(43L, "content://documents/audio/2")

        assertNull(queue.consumeForEditor("other.app", 7, 1))
        assertNull(queue.consumeForEditor("chat.app", 7, 1))
    }

    @Test
    fun `stale audio picker result cannot replace the latest request`() {
        val queue = VoiceAudioDocumentResumeQueue()
        val latestTarget = VoiceEditorTarget("chat.app", 7, 1, 12)

        queue.begin(1L, VoiceEditorTarget("old.app", 1, 1, 0))
        queue.begin(2L, latestTarget)
        queue.complete(1L, "content://documents/audio/old")
        queue.complete(2L, "content://documents/audio/latest")

        assertEquals(
            VoiceAudioDocumentResumeResult(latestTarget, "content://documents/audio/latest"),
            queue.consumeForEditor("chat.app", 7, 1)
        )
    }
}
