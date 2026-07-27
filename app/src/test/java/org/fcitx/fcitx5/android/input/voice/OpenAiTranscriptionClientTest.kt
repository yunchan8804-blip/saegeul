/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    @Test
    fun `cancel releases a blocked segment transport`() = runBlocking {
        val transport = BlockingVoiceTransport()
        val client = OpenAiTranscriptionClient(
            VoiceProviderProfile(apiKey = "test-key"),
            transport = transport
        )
        val request = async(Dispatchers.Default) {
            runCatching { client.transcribe(ByteArray(64)) }.exceptionOrNull()
        }
        assertTrue(transport.awaitRequest())

        client.cancel()

        val failure = withTimeout(1_000) { request.await() }
        assertTrue(transport.cancelled)
        assertTrue(failure is IOException)
    }

    @Test
    fun `cancel before segment request prevents transport success`() = runBlocking {
        val transport = BlockingVoiceTransport()
        val client = OpenAiTranscriptionClient(
            VoiceProviderProfile(apiKey = "test-key"),
            transport = transport
        )

        client.cancel()
        val failure = runCatching {
            client.transcribe(ByteArray(64))
        }.exceptionOrNull()

        assertTrue(transport.cancelled)
        assertTrue(failure is IOException)
    }

    private class BlockingVoiceTransport : VoiceHttpTransport {
        private val requestStarted = CountDownLatch(1)
        private val releaseRequest = CountDownLatch(1)

        @Volatile
        var cancelled = false
            private set

        override fun post(
            url: String,
            authorization: String,
            request: VoiceMultipartRequest
        ): String {
            requestStarted.countDown()
            releaseRequest.await()
            if (cancelled) throw IOException("cancelled")
            return """{"text":"unexpected"}"""
        }

        override fun cancel() {
            cancelled = true
            releaseRequest.countDown()
        }

        fun awaitRequest(): Boolean = requestStarted.await(1, TimeUnit.SECONDS)
    }
}
