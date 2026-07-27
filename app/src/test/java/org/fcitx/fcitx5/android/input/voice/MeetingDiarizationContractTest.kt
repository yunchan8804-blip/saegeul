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
    fun `capability fails closed outside the standard OpenAI endpoint`() {
        val standard = VoiceProviderProfile(apiKey = "key")
        val unsupportedModel = standard.copy(diarizationModel = "custom-diarize")
        val customEndpoint = standard.copy(baseUrl = "https://provider.example/v1")

        assertTrue(MeetingDiarizationCapability.supports(standard))
        assertFalse(MeetingDiarizationCapability.supports(unsupportedModel))
        assertFalse(MeetingDiarizationCapability.supports(customEndpoint))
    }

    @Test
    fun `meeting reuses the STT profile for both OpenAI dictation modes`() {
        val profile = VoiceProviderProfile(apiKey = "key")

        assertEquals(
            profile,
            MeetingVoiceProfilePolicy.resolve(
                EffectiveVoiceProvider(VoiceProviderMode.OpenAiApi, profile)
            )
        )
        assertEquals(
            profile,
            MeetingVoiceProfilePolicy.resolve(
                EffectiveVoiceProvider(VoiceProviderMode.OpenAiRealtime, profile)
            )
        )
        assertNull(
            MeetingVoiceProfilePolicy.resolve(
                EffectiveVoiceProvider(VoiceProviderMode.DeviceDictation, profile)
            )
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
}
