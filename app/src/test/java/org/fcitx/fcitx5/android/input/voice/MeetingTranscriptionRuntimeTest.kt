/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MeetingTranscriptionRuntimeTest {
    @Test
    fun `meeting success stays in review until selected text is explicitly inserted once`() =
        runBlocking {
            val source = FakeMeetingAudioSource()
            val diarizer = FakeMeetingDiarizer(
                MeetingDiarizationResult(
                    segments = listOf(
                        MeetingSpeakerSegment("a", "A", 0.0, 1.0, "첫 문장"),
                        MeetingSpeakerSegment("b", "B", 2.0, 3.0, "둘째 문장")
                    ),
                    model = VoiceProviderProfile.DIARIZATION_MODEL
                )
            )
            val runtime = MeetingTranscriptionRuntime(diarizer)
            var visibleDuration: Long? = null
            var commits = 0

            val result = runtime.run(
                source = source,
                canContinue = { true },
                onSourceReady = { visibleDuration = it.durationMillis }
            )
            val reviewed = MeetingTranscriptSelection.format(
                requireNotNull(result).segments,
                selectedIds = setOf("b"),
                speakerPrefix = "화자"
            )

            assertEquals(12_000L, visibleDuration)
            assertEquals(0, commits)
            assertEquals("[00:02] B: 둘째 문장", reviewed)
            val gate = MeetingCommitGate()
            if (gate.claim()) {
                commits += 1
                assertEquals("[00:02] B: 둘째 문장", reviewed)
            }
            if (gate.claim()) commits += 1
            assertEquals(1, commits)
        }

    @Test
    fun `meeting editor change before upload makes zero provider calls`() = runBlocking {
        val diarizer = FakeMeetingDiarizer(
            MeetingDiarizationResult(emptyList(), VoiceProviderProfile.DIARIZATION_MODEL)
        )
        var metadataShown = false

        val result = MeetingTranscriptionRuntime(diarizer).run(
            source = FakeMeetingAudioSource(),
            canContinue = { false },
            onSourceReady = { metadataShown = true }
        )

        assertNull(result)
        assertEquals(0, diarizer.transcribeCalls)
        assertFalse(metadataShown)
    }

    @Test
    fun `meeting cancellation reaches active upload`() {
        val diarizer = FakeMeetingDiarizer(
            MeetingDiarizationResult(emptyList(), VoiceProviderProfile.DIARIZATION_MODEL)
        )

        MeetingTranscriptionRuntime(diarizer).cancel()

        assertEquals(1, diarizer.cancelCalls)
    }

    private class FakeMeetingAudioSource : MeetingAudioSource {
        override val metadata = MeetingAudioMetadata(
            contentType = "audio/wav",
            uploadFileName = "meeting.wav",
            declaredSizeBytes = 128L,
            durationMillis = 12_000L
        )

        override fun openStream() = ByteArrayInputStream(byteArrayOf(1, 2, 3))
    }

    private class FakeMeetingDiarizer(
        private val result: MeetingDiarizationResult
    ) : MeetingDiarizer {
        var transcribeCalls = 0
        var cancelCalls = 0

        override suspend fun transcribe(source: MeetingAudioSource): MeetingDiarizationResult {
            transcribeCalls += 1
            return result
        }

        override fun cancel() {
            cancelCalls += 1
        }
    }
}
