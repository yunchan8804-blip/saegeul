/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class OpenAiDiarizationClientTest {
    private val source = object : MeetingAudioSource {
        override val metadata = MeetingAudioMetadata(
            contentType = "audio/mpeg",
            uploadFileName = "meeting.mp3",
            declaredSizeBytes = 3L,
            durationMillis = 2_000L
        )

        override fun openStream(): InputStream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `standard OpenAI request parses diarized speaker segments`() = runBlocking {
        var capturedUrl = ""
        var capturedAuthorization = ""
        var capturedRequest: DiarizationRequest? = null
        val transport = object : DiarizationHttpTransport {
            override fun post(
                url: String,
                authorization: String,
                request: DiarizationRequest
            ): String {
                capturedUrl = url
                capturedAuthorization = authorization
                capturedRequest = request
                return """{
                    "model":"gpt-4o-transcribe-diarize",
                    "segments":[
                      {"speaker":"B","start":1.5,"end":2.0,"text":" 둘째 "},
                      {"speaker":"A","start":0.0,"end":1.5,"text":"첫째"}
                    ]
                }"""
            }
        }
        val client = OpenAiDiarizationClient(VoiceProviderProfile(apiKey = "secret"), transport)

        val result = client.transcribe(source)

        assertEquals("https://api.openai.com/v1/audio/transcriptions", capturedUrl)
        assertEquals("Bearer secret", capturedAuthorization)
        assertTrue(capturedRequest?.source === source)
        assertEquals(listOf("A", "B"), result.segments.map(MeetingSpeakerSegment::speaker))
        assertEquals(listOf("첫째", "둘째"), result.segments.map(MeetingSpeakerSegment::text))
        assertEquals("gpt-4o-transcribe-diarize", result.model)
    }

    @Test
    fun `multipart contract requests Korean diarized JSON and automatic chunking`() {
        val fields = DiarizationMultipartContract.textFields(DiarizationRequest(source))

        assertEquals("gpt-4o-transcribe-diarize", fields["model"])
        assertEquals("ko", fields["language"])
        assertEquals("diarized_json", fields["response_format"])
        assertEquals("auto", fields["chunking_strategy"])
    }

    @Test
    fun `malformed timestamps are rejected without echoing provider payload`() {
        val payload = """{"segments":[{"speaker":"A","start":"bad","end":2,"text":"secret"}]}"""
        val failure = runCatching {
            OpenAiDiarizationClient.parseResponse(payload, "requested")
        }.exceptionOrNull()

        assertTrue(failure is VoiceTranscriptionException)
        assertFalse(failure?.message.orEmpty().contains("secret"))
        assertFalse(failure?.message.orEmpty().contains(payload))
    }
}
