/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import java.io.Closeable

/** Testable boundary around one-shot microphone capture. */
internal interface VoiceSegmentRecorder {
    suspend fun record(onProgress: (Long) -> Unit): WavMemoryRecording
    fun stop()
    fun cancel()
}

/** Testable boundary around one-shot online transcription. */
internal interface VoiceSegmentTranscriber {
    suspend fun transcribe(wav: ByteArray): VoiceTranscriptionResult
    fun cancel()
}

/**
 * Owns the complete segment capture lifetime, including wiping the in-memory WAV.
 *
 * Editor checks deliberately remain callbacks: the Window is the only component that can compare
 * the captured editor with the currently focused Android editor.
 */
internal class SegmentTranscriptionRuntime(
    private val recorder: VoiceSegmentRecorder,
    private val transcriber: VoiceSegmentTranscriber
) {
    suspend fun run(
        canContinue: () -> Boolean,
        onProgress: (Long) -> Unit,
        onTranscribing: () -> Unit
    ): VoiceTranscriptionResult? {
        var recording: WavMemoryRecording? = null
        return try {
            val captured = recorder.record(onProgress)
            recording = captured
            if (!canContinue()) return null
            onTranscribing()
            val result = transcriber.transcribe(captured.bytes)
            result.takeIf { canContinue() }
        } finally {
            recording?.close()
        }
    }

    fun stopRecording() = recorder.stop()

    fun cancel() {
        recorder.cancel()
        transcriber.cancel()
    }
}

/** Testable boundary around streaming microphone capture. */
internal interface VoiceRealtimeRecorder {
    suspend fun record(onChunk: (ByteArray) -> Unit, onProgress: (Long) -> Unit): Long
    fun stop()
    fun cancel()
}

/** Testable boundary around one OpenAI Realtime socket session. */
internal interface VoiceRealtimeSession : Closeable {
    suspend fun connect()
    fun append(pcm: ByteArray)
    suspend fun finish(): VoiceTranscriptionResult
    fun cancel()
}

/**
 * Owns the Realtime connect/capture/finalize sequence and makes terminal socket races deterministic.
 */
internal class RealtimeTranscriptionRuntime(
    private val recorder: VoiceRealtimeRecorder,
    sessionFactory: (onTerminalError: (Exception) -> Unit) -> VoiceRealtimeSession
) {
    private val terminalFailure = RealtimeTerminalFailure()
    private val session = sessionFactory { error ->
        terminalFailure.report(error, recorder::stop)
    }

    suspend fun run(
        canContinue: () -> Boolean,
        onConnected: () -> Unit,
        onProgress: (Long) -> Unit,
        onFinalizing: () -> Unit
    ): VoiceTranscriptionResult? = try {
        session.connect()
        terminalFailure.throwIfPresent()
        if (!canContinue()) return null
        onConnected()
        terminalFailure.throwIfPresent()
        try {
            recorder.record(session::append, onProgress)
        } catch (exception: Exception) {
            terminalFailure.throwIfPresent()
            throw exception
        }
        terminalFailure.throwIfPresent()
        if (!canContinue()) return null
        onFinalizing()
        val result = session.finish()
        terminalFailure.throwIfPresent()
        result.takeIf { canContinue() }
    } finally {
        session.close()
    }

    fun stopRecording() = recorder.stop()

    fun cancel() {
        recorder.cancel()
        session.cancel()
    }
}

internal interface VoiceTranscriptionRuntimeFactory {
    fun createSegment(profile: VoiceProviderProfile): SegmentTranscriptionRuntime

    fun createRealtime(
        profile: VoiceProviderProfile,
        onPartial: (String) -> Unit
    ): RealtimeTranscriptionRuntime
}

internal object ProductionVoiceTranscriptionRuntimeFactory : VoiceTranscriptionRuntimeFactory {
    override fun createSegment(profile: VoiceProviderProfile): SegmentTranscriptionRuntime {
        val recorder = PcmMemoryRecorder()
        val transcriber = OpenAiTranscriptionClient(profile)
        return SegmentTranscriptionRuntime(
            recorder = object : VoiceSegmentRecorder {
                override suspend fun record(onProgress: (Long) -> Unit) =
                    recorder.record(onProgress)

                override fun stop() = recorder.stop()
                override fun cancel() = recorder.cancel()
            },
            transcriber = object : VoiceSegmentTranscriber {
                override suspend fun transcribe(wav: ByteArray) = transcriber.transcribe(wav)
                override fun cancel() = transcriber.cancel()
            }
        )
    }

    override fun createRealtime(
        profile: VoiceProviderProfile,
        onPartial: (String) -> Unit
    ): RealtimeTranscriptionRuntime {
        val recorder = PcmStreamRecorder()
        return RealtimeTranscriptionRuntime(
            recorder = object : VoiceRealtimeRecorder {
                override suspend fun record(
                    onChunk: (ByteArray) -> Unit,
                    onProgress: (Long) -> Unit
                ) = recorder.record(onChunk, onProgress)

                override fun stop() = recorder.stop()
                override fun cancel() = recorder.cancel()
            },
            sessionFactory = { onTerminalError ->
                val session = OpenAiRealtimeTranscriptionSession(
                    profile = profile,
                    onPartial = onPartial,
                    onTerminalError = onTerminalError
                )
                object : VoiceRealtimeSession {
                    override suspend fun connect() = session.connect()
                    override fun append(pcm: ByteArray) = session.append(pcm)
                    override suspend fun finish() = session.finish()
                    override fun cancel() = session.cancel()
                    override fun close() = session.close()
                }
            }
        )
    }
}
