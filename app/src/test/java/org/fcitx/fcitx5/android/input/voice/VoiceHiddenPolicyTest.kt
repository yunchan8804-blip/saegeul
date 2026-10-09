/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceHiddenPolicyTest {
    @Test
    fun cloudRecordingThatIsNotSentYetIsCancelled() {
        listOf(VoiceHiddenEngine.Segment, VoiceHiddenEngine.Realtime).forEach { engine ->
            assertEquals(
                VoiceHiddenAction.CancelRecording,
                VoiceHiddenPolicy.decide(engine, VoiceHiddenPhase.Recording)
            )
        }
    }

    @Test
    fun cloudRequestThatTheUserAlreadyStartedIsLeftAlone() {
        listOf(VoiceHiddenEngine.Segment, VoiceHiddenEngine.Realtime).forEach { engine ->
            assertEquals(
                VoiceHiddenAction.Keep,
                VoiceHiddenPolicy.decide(engine, VoiceHiddenPhase.Sending)
            )
        }
    }

    @Test
    fun idleCloudEngineAndNoEngineChangeNothing() {
        listOf(VoiceHiddenEngine.Segment, VoiceHiddenEngine.Realtime).forEach { engine ->
            assertEquals(
                VoiceHiddenAction.Keep,
                VoiceHiddenPolicy.decide(engine, VoiceHiddenPhase.Idle)
            )
        }
        VoiceHiddenPhase.entries.forEach { phase ->
            assertEquals(VoiceHiddenAction.Keep, VoiceHiddenPolicy.decide(null, phase))
        }
    }

    @Test
    fun deviceDictationIsAlwaysReleased() {
        VoiceHiddenPhase.entries.forEach { phase ->
            assertEquals(
                VoiceHiddenAction.ReleaseDevice,
                VoiceHiddenPolicy.decide(VoiceHiddenEngine.Device, phase)
            )
        }
    }

    @Test
    fun cancelledSegmentRecordingNeverStopsOrTranscribes() = runBlocking {
        val recorder = SuspendedSegmentRecorder()
        val transcriber = CountingSegmentTranscriber()
        val runtime = SegmentTranscriptionRuntime(recorder, transcriber)
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            runtime.run(canContinue = { true }, onProgress = {}, onTranscribing = {})
        }
        recorder.started.await()

        runtime.cancel()
        job.cancelAndJoin()

        assertEquals(1, recorder.cancelCalls)
        assertEquals(0, recorder.stopCalls)
        assertEquals(0, transcriber.transcribeCalls)
        assertEquals(1, transcriber.cancelCalls)
    }

    @Test
    fun cancelledRealtimeRecordingNeverFinalizesAndClosesTheSocket() = runBlocking {
        val recorder = SuspendedRealtimeRecorder()
        val session = CountingRealtimeSession()
        val runtime = RealtimeTranscriptionRuntime(recorder) { _ -> session }
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            runtime.run(
                canContinue = { true },
                onConnected = {},
                onProgress = {},
                onFinalizing = {}
            )
        }
        recorder.started.await()

        runtime.cancel()
        job.cancelAndJoin()

        assertEquals(1, recorder.cancelCalls)
        assertEquals(0, recorder.stopCalls)
        assertEquals(0, session.finishCalls)
        assertEquals(1, session.cancelCalls)
        assertTrue(session.closeCalls >= 1)
    }

    private class SuspendedSegmentRecorder : VoiceSegmentRecorder {
        val started = CompletableDeferred<Unit>()
        var stopCalls = 0
        var cancelCalls = 0

        override suspend fun record(onProgress: (Long) -> Unit): WavMemoryRecording {
            started.complete(Unit)
            awaitCancellation()
        }

        override fun stop() {
            stopCalls += 1
        }

        override fun cancel() {
            cancelCalls += 1
        }
    }

    private class CountingSegmentTranscriber : VoiceSegmentTranscriber {
        var transcribeCalls = 0
        var cancelCalls = 0

        override suspend fun transcribe(wav: ByteArray): VoiceTranscriptionResult {
            transcribeCalls += 1
            return VoiceTranscriptionResult("사용하면 안 됨", "model")
        }

        override fun cancel() {
            cancelCalls += 1
        }
    }

    private class SuspendedRealtimeRecorder : VoiceRealtimeRecorder {
        val started = CompletableDeferred<Unit>()
        var stopCalls = 0
        var cancelCalls = 0

        override suspend fun record(onChunk: (ByteArray) -> Unit, onProgress: (Long) -> Unit): Long {
            started.complete(Unit)
            awaitCancellation()
        }

        override fun stop() {
            stopCalls += 1
        }

        override fun cancel() {
            cancelCalls += 1
        }
    }

    private class CountingRealtimeSession : VoiceRealtimeSession {
        var finishCalls = 0
        var cancelCalls = 0
        var closeCalls = 0

        override suspend fun connect() = Unit

        override fun append(pcm: ByteArray) = Unit

        override suspend fun finish(): VoiceTranscriptionResult {
            finishCalls += 1
            return VoiceTranscriptionResult("사용하면 안 됨", "model")
        }

        override fun cancel() {
            cancelCalls += 1
        }

        override fun close() {
            closeCalls += 1
        }
    }
}
