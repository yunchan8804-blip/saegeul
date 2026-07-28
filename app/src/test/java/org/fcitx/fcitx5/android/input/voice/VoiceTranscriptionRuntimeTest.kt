/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VoiceTranscriptionRuntimeTest {
    @Test
    fun `segment success remains preview-only until one explicit insert`() = runBlocking {
        val events = mutableListOf<String>()
        val audio = byteArrayOf(1, 2, 3, 4)
        val recorder = FakeSegmentRecorder(audio) { events += "record" }
        val transcriber = FakeSegmentTranscriber {
            events += "transcribe"
            VoiceTranscriptionResult("안녕하세요", "gpt-4o-transcribe")
        }
        val runtime = SegmentTranscriptionRuntime(recorder, transcriber)
        val review = VoiceTranscriptReviewSession()
        val target = VoiceEditorTarget("chat.app", 7, 1, 12)
        var commits = 0

        review.begin(target)
        val result = runtime.run(
            canContinue = { true },
            onProgress = { events += "progress:$it" },
            onTranscribing = { events += "transcribing" }
        )
        assertTrue(review.publish(requireNotNull(result).text))

        assertEquals(listOf("record", "progress:250", "transcribing", "transcribe"), events)
        assertEquals(0, commits)
        assertTrue(audio.all { it == 0.toByte() })
        assertEquals(
            VoiceReviewedCommitResult.Inserted,
            review.insert(
                matchesCurrentEditor = { it == target },
                commitText = {
                    commits += 1
                    assertEquals("안녕하세요", it)
                    true
                }
            )
        )
        assertEquals(VoiceReviewedCommitResult.NotReady, review.insert({ true }) { true })
        assertEquals(1, commits)
    }

    @Test
    fun `segment editor change after capture wipes audio and makes zero provider calls`() = runBlocking {
        val audio = byteArrayOf(9, 8, 7)
        val recorder = FakeSegmentRecorder(audio)
        var providerCalls = 0
        var targetChecks = 0
        val runtime = SegmentTranscriptionRuntime(
            recorder,
            FakeSegmentTranscriber {
                providerCalls += 1
                VoiceTranscriptionResult("사용하면 안 됨", "model")
            }
        )

        val result = runtime.run(
            canContinue = { targetChecks++ == 0 },
            onProgress = {},
            onTranscribing = { fail("stale editor must not enter transcription") }
        )

        assertNull(result)
        assertEquals(0, providerCalls)
        assertTrue(audio.all { it == 0.toByte() })
    }

    @Test
    fun `stale editor before segment capture skips microphone and provider`() = runBlocking {
        val recorder = FakeSegmentRecorder(byteArrayOf(9, 8, 7))
        var providerCalls = 0
        val runtime = SegmentTranscriptionRuntime(
            recorder,
            FakeSegmentTranscriber {
                providerCalls += 1
                VoiceTranscriptionResult("사용하면 안 됨", "model")
            }
        )

        val result = runtime.run(
            canContinue = { false },
            onProgress = { fail("stale editor must not start capture") },
            onTranscribing = { fail("stale editor must not enter transcription") }
        )

        assertNull(result)
        assertEquals(0, recorder.recordCalls)
        assertEquals(0, providerCalls)
    }

    @Test
    fun `segment cancellation reaches microphone and network together`() {
        val recorder = FakeSegmentRecorder(byteArrayOf(1))
        val transcriber = FakeSegmentTranscriber {
            VoiceTranscriptionResult("text", "model")
        }
        val runtime = SegmentTranscriptionRuntime(recorder, transcriber)

        runtime.cancel()

        assertEquals(1, recorder.cancelCalls)
        assertEquals(1, transcriber.cancelCalls)
    }

    @Test
    fun `realtime success connects streams finalizes and returns one preview`() = runBlocking {
        val events = mutableListOf<String>()
        val recorder = FakeRealtimeRecorder { onChunk, onProgress ->
            events += "record"
            onChunk(byteArrayOf(4, 5, 6))
            onProgress(1_000L)
        }
        lateinit var session: FakeRealtimeSession
        val runtime = RealtimeTranscriptionRuntime(recorder) { _ ->
            FakeRealtimeSession(events).also { session = it }
        }

        val result = runtime.run(
            canContinue = { true },
            onConnected = { events += "connected" },
            onProgress = { events += "progress:$it" },
            onFinalizing = { events += "finalizing" }
        )

        assertEquals("실시간 전사", result?.text)
        assertEquals(
            listOf(
                "connect",
                "connected",
                "record",
                "append:3",
                "progress:1000",
                "finalizing",
                "finish",
                "close"
            ),
            events
        )
        assertEquals(1, session.finishCalls)
    }

    @Test
    fun `stale editor before realtime connection skips socket and microphone`() = runBlocking {
        val events = mutableListOf<String>()
        val recorder = FakeRealtimeRecorder()
        lateinit var session: FakeRealtimeSession
        val runtime = RealtimeTranscriptionRuntime(recorder) { _ ->
            FakeRealtimeSession(events).also { session = it }
        }

        val result = runtime.run(
            canContinue = { false },
            onConnected = { fail("stale editor must not connect") },
            onProgress = { fail("stale editor must not start capture") },
            onFinalizing = { fail("stale editor must not finalize") }
        )

        assertNull(result)
        assertEquals(0, session.connectCalls)
        assertEquals(0, recorder.recordCalls)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `realtime terminal failure before capture stops recorder and wins the race`() = runBlocking {
        val recorder = FakeRealtimeRecorder()
        val authentication = VoiceAuthenticationException("bad key")
        var terminal: ((Exception) -> Unit)? = null
        val session = object : VoiceRealtimeSession {
            var closeCalls = 0

            override suspend fun connect() {
                requireNotNull(terminal).invoke(authentication)
            }

            override fun append(pcm: ByteArray) = Unit
            override suspend fun finish() = VoiceTranscriptionResult("unexpected", "model")
            override fun cancel() = Unit
            override fun close() {
                closeCalls += 1
            }
        }
        val runtime = RealtimeTranscriptionRuntime(recorder) { callback ->
            terminal = callback
            session
        }

        try {
            runtime.run({ true }, {}, {}, {})
            fail("terminal authentication failure must escape")
        } catch (error: VoiceAuthenticationException) {
            assertTrue(error === authentication)
        }

        assertEquals(1, recorder.stopCalls)
        assertEquals(0, recorder.recordCalls)
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun `failed commit never falls back to automatic or duplicate insertion`() {
        val review = VoiceTranscriptReviewSession()
        review.begin(VoiceEditorTarget("chat.app", 7, 1, 12))
        assertTrue(review.publish("검토한 전사"))
        var commits = 0

        assertEquals(
            VoiceReviewedCommitResult.CommitFailed,
            review.insert(
                matchesCurrentEditor = { true },
                commitText = {
                    commits += 1
                    false
                }
            )
        )
        assertEquals(
            VoiceReviewedCommitResult.AlreadyConsumed,
            review.insert({ true }) {
                commits += 1
                true
            }
        )
        assertEquals(1, commits)
    }

    private class FakeSegmentRecorder(
        private val audio: ByteArray,
        private val beforeReturn: () -> Unit = {}
    ) : VoiceSegmentRecorder {
        var recordCalls = 0
        var cancelCalls = 0

        override suspend fun record(onProgress: (Long) -> Unit): WavMemoryRecording {
            recordCalls += 1
            beforeReturn()
            onProgress(250L)
            return WavMemoryRecording(audio, 250L)
        }

        override fun stop() = Unit

        override fun cancel() {
            cancelCalls += 1
        }
    }

    private class FakeSegmentTranscriber(
        private val response: suspend () -> VoiceTranscriptionResult
    ) : VoiceSegmentTranscriber {
        var cancelCalls = 0

        override suspend fun transcribe(wav: ByteArray) = response()

        override fun cancel() {
            cancelCalls += 1
        }
    }

    private class FakeRealtimeRecorder(
        private val capture: ((ByteArray) -> Unit, (Long) -> Unit) -> Unit = { _, _ -> }
    ) : VoiceRealtimeRecorder {
        var recordCalls = 0
        var stopCalls = 0
        var cancelCalls = 0

        override suspend fun record(
            onChunk: (ByteArray) -> Unit,
            onProgress: (Long) -> Unit
        ): Long {
            recordCalls += 1
            capture(onChunk, onProgress)
            return 1_000L
        }

        override fun stop() {
            stopCalls += 1
        }

        override fun cancel() {
            cancelCalls += 1
        }
    }

    private class FakeRealtimeSession(
        private val events: MutableList<String>
    ) : VoiceRealtimeSession {
        var connectCalls = 0
        var finishCalls = 0

        override suspend fun connect() {
            connectCalls += 1
            events += "connect"
        }

        override fun append(pcm: ByteArray) {
            events += "append:${pcm.size}"
        }

        override suspend fun finish(): VoiceTranscriptionResult {
            finishCalls += 1
            events += "finish"
            return VoiceTranscriptionResult("실시간 전사", "gpt-realtime")
        }

        override fun cancel() = Unit

        override fun close() {
            events += "close"
        }
    }
}
