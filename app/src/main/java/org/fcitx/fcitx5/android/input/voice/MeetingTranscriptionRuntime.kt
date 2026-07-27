/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.net.Uri

internal interface MeetingDiarizer {
    suspend fun transcribe(source: MeetingAudioSource): MeetingDiarizationResult
    fun cancel()
}

/** Testable target-validation boundary around one selected meeting audio file. */
internal class MeetingTranscriptionRuntime(
    private val diarizer: MeetingDiarizer
) {
    suspend fun run(
        source: MeetingAudioSource,
        canContinue: () -> Boolean,
        onSourceReady: (MeetingAudioMetadata) -> Unit
    ): MeetingDiarizationResult? {
        if (!canContinue()) return null
        onSourceReady(source.metadata)
        val result = diarizer.transcribe(source)
        return result.takeIf { canContinue() }
    }

    fun cancel() = diarizer.cancel()
}

internal interface MeetingTranscriptionRuntimeFactory {
    suspend fun inspect(context: Context, uri: Uri): MeetingAudioSource
    fun create(profile: VoiceProviderProfile): MeetingTranscriptionRuntime
}

internal object ProductionMeetingTranscriptionRuntimeFactory : MeetingTranscriptionRuntimeFactory {
    override suspend fun inspect(context: Context, uri: Uri): MeetingAudioSource =
        ContentUriMeetingAudioSource.inspect(context, uri)

    override fun create(profile: VoiceProviderProfile): MeetingTranscriptionRuntime {
        val client = OpenAiDiarizationClient(profile)
        return MeetingTranscriptionRuntime(
            object : MeetingDiarizer {
                override suspend fun transcribe(source: MeetingAudioSource) =
                    client.transcribe(source)

                override fun cancel() = client.cancel()
            }
        )
    }
}
