/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import java.util.Locale

data class MeetingSpeakerSegment(
    val id: String,
    val speaker: String,
    val startSeconds: Double,
    val endSeconds: Double,
    val text: String
)

data class MeetingDiarizationResult(
    val segments: List<MeetingSpeakerSegment>,
    val model: String
)

object MeetingDiarizationCapability {
    fun supports(profile: VoiceProviderProfile): Boolean {
        val normalized = runCatching(profile::validate).getOrNull() ?: return false
        return normalized.baseUrl == VoiceProviderProfile.OPENAI_BASE_URL &&
            normalized.diarizationModel == VoiceProviderProfile.DIARIZATION_MODEL
    }
}

/**
 * Meeting transcription reuses the independently stored speech-to-text credential.
 * The selected online dictation transport must not make the same OpenAI key disappear.
 */
object MeetingVoiceProfilePolicy {
    fun resolve(effective: EffectiveVoiceProvider): VoiceProviderProfile? = when (effective.mode) {
        VoiceProviderMode.DeviceDictation -> null
        VoiceProviderMode.OpenAiRealtime,
        VoiceProviderMode.OpenAiApi -> effective.profile
    }
}

object MeetingTranscriptSelection {
    const val MAX_SEGMENTS = 500
    const val MAX_SEGMENT_CHARACTERS = 1_000
    const val MAX_INSERT_CHARACTERS = 16_000

    fun format(
        segments: List<MeetingSpeakerSegment>,
        selectedIds: Set<String>,
        speakerPrefix: String
    ): String? {
        if (selectedIds.isEmpty()) return null
        val selected = segments.filter { it.id in selectedIds }
        if (selected.isEmpty() || selected.size != selectedIds.size) return null
        val text = selected.joinToString("\n") { segment ->
            val speaker = segment.speaker.ifBlank { speakerPrefix }
            "[${timestamp(segment.startSeconds)}] $speaker: ${segment.text}"
        }
        return text.takeIf { it.length <= MAX_INSERT_CHARACTERS }
    }

    fun timestamp(seconds: Double): String {
        val total = seconds.coerceAtLeast(0.0).toLong()
        val hours = total / 3_600L
        val minutes = total % 3_600L / 60L
        val secs = total % 60L
        return if (hours > 0L) {
            String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, secs)
        }
    }
}

internal class MeetingCommitGate {
    private var consumed = false

    @Synchronized
    fun claim(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }

    @Synchronized
    fun resetForSelection() {
        consumed = false
    }
}
