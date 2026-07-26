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
import java.io.Closeable
import kotlin.math.max

class VoiceRecordingException(message: String) : Exception(message)

class WavMemoryRecording internal constructor(
    val bytes: ByteArray,
    val durationMillis: Long
) : Closeable {
    override fun close() {
        bytes.fill(0)
    }
}

/** Bounded PCM capture that creates a WAV payload entirely in memory. */
class PcmMemoryRecorder {
    @Volatile
    private var running = true

    @Volatile
    private var cancelled = false

    @Volatile
    private var audioRecord: AudioRecord? = null

    @SuppressLint("MissingPermission")
    suspend fun record(onProgress: (Long) -> Unit = {}): WavMemoryRecording =
        withContext(Dispatchers.IO) {
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minimum <= 0) throw VoiceRecordingException("Microphone is unavailable")
            val bufferSize = max(minimum, READ_BUFFER_BYTES)
            val pcm = ByteArray(MAX_PCM_BYTES)
            var captured = 0
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
                while (running && captured < pcm.size) {
                    currentCoroutineContext().ensureActive()
                    val read = recorder.read(
                        pcm,
                        captured,
                        minOf(bufferSize, pcm.size - captured)
                    )
                    if (read <= 0) {
                        if (!running) break
                        throw VoiceRecordingException("Microphone capture failed")
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
                WavMemoryRecording(toWav(pcm, captured), durationMillis(captured))
            } finally {
                running = false
                runCatching {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                }
                recorder.release()
                audioRecord = null
                pcm.fill(0)
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

    private fun durationMillis(byteCount: Int): Long =
        byteCount * 1_000L / BYTES_PER_SECOND

    private fun toWav(pcm: ByteArray, length: Int): ByteArray = ByteArray(WAV_HEADER_BYTES + length).also {
        writeAscii(it, 0, "RIFF")
        writeInt32(it, 4, 36 + length)
        writeAscii(it, 8, "WAVE")
        writeAscii(it, 12, "fmt ")
        writeInt32(it, 16, 16)
        writeInt16(it, 20, 1)
        writeInt16(it, 22, 1)
        writeInt32(it, 24, SAMPLE_RATE)
        writeInt32(it, 28, BYTES_PER_SECOND)
        writeInt16(it, 32, BYTES_PER_SAMPLE)
        writeInt16(it, 34, BITS_PER_SAMPLE)
        writeAscii(it, 36, "data")
        writeInt32(it, 40, length)
        pcm.copyInto(it, WAV_HEADER_BYTES, 0, length)
    }

    private fun writeAscii(target: ByteArray, offset: Int, value: String) {
        value.forEachIndexed { index, char -> target[offset + index] = char.code.toByte() }
    }

    private fun writeInt16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeInt32(target: ByteArray, offset: Int, value: Int) {
        repeat(4) { index -> target[offset + index] = (value ushr (index * 8)).toByte() }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_DURATION_SECONDS = 30
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        private const val BYTES_PER_SECOND = SAMPLE_RATE * BYTES_PER_SAMPLE
        private const val MAX_PCM_BYTES = BYTES_PER_SECOND * MAX_DURATION_SECONDS
        private const val MIN_PCM_BYTES = BYTES_PER_SECOND / 4
        private const val READ_BUFFER_BYTES = 4_096
        private const val WAV_HEADER_BYTES = 44
        private const val PROGRESS_INTERVAL_MILLIS = 250L
    }
}
