/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Bounded 24 kHz mono PCM capture for OpenAI Realtime transcription. */
class PcmStreamRecorder {
    @Volatile
    private var running = true

    @Volatile
    private var cancelled = false

    @Volatile
    private var audioRecord: AudioRecord? = null

    @SuppressLint("MissingPermission")
    suspend fun record(
        onChunk: (ByteArray) -> Unit,
        onProgress: (Long) -> Unit = {}
    ): Long = withContext(Dispatchers.IO) {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minimum <= 0) throw VoiceRecordingException("Microphone is unavailable")
        val bufferSize = max(minimum, READ_BUFFER_BYTES)
        val buffer = ByteArray(bufferSize)
        var captured = 0L
        var lastProgress = -PROGRESS_INTERVAL_MILLIS
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
        audioRecord = recorder
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                throw VoiceRecordingException("Microphone could not be initialized")
            }
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw VoiceRecordingException("Microphone did not start")
            }
            while (running && captured < MAX_PCM_BYTES) {
                currentCoroutineContext().ensureActive()
                val allowed = minOf(buffer.size.toLong(), MAX_PCM_BYTES - captured).toInt()
                val read = recorder.read(buffer, 0, allowed)
                if (read <= 0) {
                    if (!running) break
                    throw VoiceRecordingException("Microphone capture failed")
                }
                val chunk = buffer.copyOf(read)
                try {
                    onChunk(chunk)
                } finally {
                    chunk.fill(0)
                }
                captured += read
                val elapsed = durationMillis(captured)
                if (elapsed - lastProgress >= PROGRESS_INTERVAL_MILLIS) {
                    lastProgress = elapsed
                    onProgress(elapsed)
                }
            }
            if (cancelled) throw CancellationException("Voice capture cancelled")
            if (captured < MIN_PCM_BYTES) {
                throw VoiceRecordingException("Recording is too short")
            }
            durationMillis(captured)
        } finally {
            running = false
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            }
            recorder.release()
            audioRecord = null
            buffer.fill(0)
        }
    }

    fun stop() {
        running = false
        runCatching { audioRecord?.stop() }
    }

    fun cancel() {
        cancelled = true
        stop()
    }

    private fun durationMillis(byteCount: Long): Long = byteCount * 1_000L / BYTES_PER_SECOND

    companion object {
        const val SAMPLE_RATE = 24_000
        const val SAFETY_MAX_DURATION_SECONDS = 5 * 60
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2
        private const val BYTES_PER_SECOND = SAMPLE_RATE * BYTES_PER_SAMPLE
        private const val MAX_PCM_BYTES = BYTES_PER_SECOND.toLong() * SAFETY_MAX_DURATION_SECONDS
        private const val MIN_PCM_BYTES = BYTES_PER_SECOND / 4
        private const val READ_BUFFER_BYTES = 4_800
        private const val PROGRESS_INTERVAL_MILLIS = 250L
    }
}
