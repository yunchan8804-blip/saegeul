/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiRealtimeTranscriptionClientTest {
    @Test
    fun `session update uses transcription mode Korean 24k PCM and manual commit`() {
        val root = Json.parseToJsonElement(
            OpenAiRealtimeProtocol.sessionUpdate("gpt-realtime-whisper")
        ).jsonObject
        val session = root.getValue("session").jsonObject
        val input = session.getValue("audio").jsonObject
            .getValue("input").jsonObject
        val format = input.getValue("format").jsonObject
        val transcription = input.getValue("transcription").jsonObject

        assertEquals("session.update", root.getValue("type").jsonPrimitive.content)
        assertEquals("transcription", session.getValue("type").jsonPrimitive.content)
        assertEquals("audio/pcm", format.getValue("type").jsonPrimitive.content)
        assertEquals(24_000, format.getValue("rate").jsonPrimitive.content.toInt())
        assertEquals("gpt-realtime-whisper", transcription.getValue("model").jsonPrimitive.content)
        assertEquals("ko", transcription.getValue("language").jsonPrimitive.content)
        assertEquals("low", transcription.getValue("delay").jsonPrimitive.content)
        assertTrue(input.getValue("turn_detection").toString() == "null")
        assertEquals(
            "{\"type\":\"input_audio_buffer.commit\"}",
            OpenAiRealtimeProtocol.COMMIT
        )
    }

    @Test
    fun `protocol parses partial completed and provider errors without exposing payload`() {
        assertEquals(
            VoiceRealtimeEvent.TranscriptDelta("item-1", "안녕"),
            OpenAiRealtimeProtocol.parse(
                """{"type":"conversation.item.input_audio_transcription.delta","item_id":"item-1","delta":"안녕"}"""
            )
        )
        assertEquals(
            VoiceRealtimeEvent.TranscriptCompleted("item-1", "안녕하세요"),
            OpenAiRealtimeProtocol.parse(
                """{"type":"conversation.item.input_audio_transcription.completed","item_id":"item-1","transcript":"안녕하세요"}"""
            )
        )
        assertEquals(
            VoiceRealtimeEvent.Error("bad key", "invalid_api_key"),
            OpenAiRealtimeProtocol.parse(
                """{"type":"error","error":{"message":"bad key","code":"invalid_api_key"}}"""
            )
        )
        assertTrue(OpenAiRealtimeProtocol.parse("not-json") is VoiceRealtimeEvent.Error)
    }

    @Test
    fun `partial transcript is item scoped bounded and final transcript is authoritative`() {
        val accumulator = RealtimeTranscriptAccumulator()

        assertEquals(
            "안녕",
            accumulator.apply(VoiceRealtimeEvent.TranscriptDelta("item-1", "안녕"))
        )
        assertEquals(
            "안녕하세요",
            accumulator.apply(VoiceRealtimeEvent.TranscriptDelta("item-1", "하세요"))
        )
        assertEquals(
            "안녕하세요.",
            accumulator.apply(
                VoiceRealtimeEvent.TranscriptCompleted("item-1", " 안녕하세요. ")
            )
        )
        val bounded = accumulator.apply(
            VoiceRealtimeEvent.TranscriptDelta(
                "item-2",
                "가".repeat(VoiceTranscriptPolicy.MAX_CHARACTERS + 50)
            )
        )
        assertEquals(VoiceTranscriptPolicy.MAX_CHARACTERS, bounded?.length)
    }

    @Test
    fun `session streams preview then returns one authoritative final transcript`() = runBlocking {
        val connector = FakeRealtimeConnector()
        val partials = mutableListOf<String>()
        val session = OpenAiRealtimeTranscriptionSession(
            profile = VoiceProviderProfile(apiKey = "secret"),
            connector = connector,
            onPartial = partials::add
        )
        val connecting = async { session.connect() }
        yield()

        connector.open()
        assertTrue(connector.sent.single().contains("\"type\":\"session.update\""))
        connector.text("""{"type":"session.updated"}""")
        connecting.await()

        session.append(byteArrayOf(1, 2, 3, 4))
        assertTrue(connector.sent.last().contains("\"type\":\"input_audio_buffer.append\""))
        connector.text(
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"one","delta":"안녕"}"""
        )
        assertEquals(listOf("안녕"), partials)

        val finishing = async { session.finish() }
        yield()
        assertEquals(OpenAiRealtimeProtocol.COMMIT, connector.sent.last())
        connector.text(
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"one","transcript":"안녕하세요."}"""
        )
        assertEquals("안녕하세요.", finishing.await().text)
        session.close()
        assertTrue(connector.closed)
        assertFalse(connector.cancelled)
    }

    @Test
    fun `handshake 401 becomes a typed authentication failure`() = runBlocking {
        val connector = FakeRealtimeConnector()
        val session = OpenAiRealtimeTranscriptionSession(
            profile = VoiceProviderProfile(apiKey = "rejected"),
            connector = connector
        )
        val connecting = async { runCatching { session.connect() }.exceptionOrNull() }
        yield()

        connector.failure(httpStatus = 401)

        assertTrue(connecting.await() is VoiceAuthenticationException)
        assertTrue(connector.cancelled || connector.closed)
    }

    @Test
    fun `connection timeout becomes a visible transcription failure instead of cancellation`() =
        runBlocking {
            val connector = FakeRealtimeConnector()
            val terminalErrors = mutableListOf<Exception>()
            val session = OpenAiRealtimeTranscriptionSession(
                profile = VoiceProviderProfile(apiKey = "secret"),
                connector = connector,
                onTerminalError = terminalErrors::add,
                connectTimeoutMillis = 20,
                transcriptionTimeoutMillis = 1_000
            )

            val error = runCatching { session.connect() }.exceptionOrNull()

            assertTrue(error is VoiceTranscriptionException)
            assertTrue(error?.message?.contains("timed out") == true)
            assertEquals(error, terminalErrors.single())
            assertTrue(connector.closed || connector.cancelled)
        }

    @Test
    fun `finalization timeout becomes a visible transcription failure`() = runBlocking {
        val connector = FakeRealtimeConnector()
        val terminalErrors = mutableListOf<Exception>()
        val session = OpenAiRealtimeTranscriptionSession(
            profile = VoiceProviderProfile(apiKey = "secret"),
            connector = connector,
            onTerminalError = terminalErrors::add,
            connectTimeoutMillis = 1_000,
            transcriptionTimeoutMillis = 20
        )
        val connecting = async { session.connect() }
        yield()
        connector.open()
        connector.text("""{"type":"session.updated"}""")
        connecting.await()

        val error = runCatching { session.finish() }.exceptionOrNull()

        assertTrue(error is VoiceTranscriptionException)
        assertTrue(error?.message?.contains("timed out") == true)
        assertEquals(error, terminalErrors.single())
        session.close()
    }

    @Test
    fun `post-ready provider failure is delivered immediately and only once`() = runBlocking {
        val connector = FakeRealtimeConnector()
        val terminalErrors = mutableListOf<Exception>()
        val session = OpenAiRealtimeTranscriptionSession(
            profile = VoiceProviderProfile(apiKey = "rejected"),
            connector = connector,
            onTerminalError = terminalErrors::add
        )
        val connecting = async { session.connect() }
        yield()
        connector.open()
        connector.text("""{"type":"session.updated"}""")
        connecting.await()

        connector.text(
            """{"type":"error","error":{"message":"bad key","code":"invalid_api_key"}}"""
        )
        connector.failure(httpStatus = 401)

        assertEquals(1, terminalErrors.size)
        assertTrue(terminalErrors.single() is VoiceAuthenticationException)
        session.close()
    }

    @Test
    fun `terminal failure stops capture once and remains authoritative`() {
        val terminalFailure = RealtimeTerminalFailure()
        val first = VoiceAuthenticationException("first")
        var stops = 0

        terminalFailure.report(first) { stops += 1 }
        terminalFailure.report(VoiceTranscriptionException("second")) { stops += 1 }
        val thrown = runCatching { terminalFailure.throwIfPresent() }.exceptionOrNull()

        assertEquals(1, stops)
        assertEquals(first, thrown)
    }

    private class FakeRealtimeConnector : VoiceRealtimeConnector {
        val sent = mutableListOf<String>()
        var closed = false
        var cancelled = false
        private lateinit var listener: VoiceRealtimeSocketListener
        private val socket = object : VoiceRealtimeSocket {
            override fun send(text: String): Boolean = sent.add(text)

            override fun close(code: Int, reason: String): Boolean {
                closed = true
                return true
            }

            override fun cancel() {
                cancelled = true
            }
        }

        override fun open(
            request: VoiceRealtimeSocketRequest,
            listener: VoiceRealtimeSocketListener
        ): VoiceRealtimeSocket {
            assertEquals(
                "wss://api.openai.com/v1/realtime?model=gpt-realtime-whisper",
                request.url
            )
            assertTrue(request.authorization.startsWith("Bearer "))
            this.listener = listener
            return socket
        }

        fun open() = listener.onOpen(socket)
        fun text(value: String) = listener.onText(value)
        fun failure(httpStatus: Int?) = listener.onFailure(IllegalStateException("failed"), httpStatus)
    }
}
