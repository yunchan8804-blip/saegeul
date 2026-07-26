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

class OpenAiTranscriptionClientTest {
    @Test
    fun `segment request uses audio endpoint Korean hint and wav multipart`() = runBlocking {
        var url = ""
        var authorization = ""
        var hasModel = false
        var hasLanguage = false
        var hasWavPart = false
        val transport = VoiceHttpTransport { capturedUrl, capturedAuthorization, request ->
            url = capturedUrl
            authorization = capturedAuthorization
            val body = request.body.toString(Charsets.ISO_8859_1)
            hasModel = body.contains("name=\"model\"\r\n\r\ngpt-4o-transcribe")
            hasLanguage = body.contains("name=\"language\"\r\n\r\nko")
            hasWavPart = body.contains("filename=\"speech.wav\"") &&
                body.contains("Content-Type: audio/wav")
            assertTrue(request.contentType.startsWith("multipart/form-data; boundary="))
            """{"text":" 안녕하세요 ","model":"gpt-4o-transcribe-live"}"""
        }
        val client = OpenAiTranscriptionClient(
            VoiceProviderProfile(apiKey = "test-key"),
            transport = transport
        )

        val result = client.transcribe(ByteArray(64) { it.toByte() })

        assertEquals("https://api.openai.com/v1/audio/transcriptions", url)
        assertEquals("Bearer test-key", authorization)
        assertTrue(hasModel)
        assertTrue(hasLanguage)
        assertTrue(hasWavPart)
        assertEquals("안녕하세요", result.text)
        assertEquals("gpt-4o-transcribe-live", result.model)
    }

    @Test
    fun `empty provider transcript is rejected`() {
        val failure = runCatching {
            OpenAiTranscriptionClient.parseResponse("""{"text":"  "}""", "requested")
        }.exceptionOrNull()

        assertTrue(failure is VoiceTranscriptionException)
        assertFalse(failure?.message.orEmpty().contains("{\"text\""))
    }
}
