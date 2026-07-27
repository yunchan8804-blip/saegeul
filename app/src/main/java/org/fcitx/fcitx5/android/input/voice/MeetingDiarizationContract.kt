/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import org.fcitx.fcitx5.android.input.ai.AiFeatureEntryGate
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

data class VoiceAudioDocumentResumeResult(
    val target: VoiceEditorTarget,
    val documentUri: String?
)

/**
 * One-shot process-memory handoff across the system document picker boundary.
 *
 * Opening the picker detaches and later restarts the IME. The stale meeting window must therefore
 * never own the result callback; the new window consumes it only for the editor that launched it.
 */
internal class VoiceAudioDocumentResumeQueue {
    private data class Pending(val id: Long, val target: VoiceEditorTarget)

    private var pending: Pending? = null
    private var completed: VoiceAudioDocumentResumeResult? = null

    @Synchronized
    fun begin(id: Long, target: VoiceEditorTarget) {
        pending = Pending(id, target)
        completed = null
    }

    @Synchronized
    fun complete(id: Long, documentUri: String?) {
        val request = pending?.takeIf { it.id == id } ?: return
        pending = null
        completed = VoiceAudioDocumentResumeResult(request.target, documentUri)
    }

    @Synchronized
    fun cancel(id: Long) {
        if (pending?.id == id) pending = null
    }

    @Synchronized
    fun consumeForEditor(
        packageName: String?,
        fieldId: Int,
        inputType: Int
    ): VoiceAudioDocumentResumeResult? {
        val result = completed ?: return null
        completed = null
        return result.takeIf {
            packageName == it.target.packageName &&
                fieldId == it.target.fieldId &&
                inputType == it.target.inputType
        }
    }
}

object MeetingDiarizationCapability {
    fun supports(profile: VoiceProviderProfile): Boolean {
        val normalized = runCatching(profile::validate).getOrNull() ?: return false
        return normalized.baseUrl == VoiceProviderProfile.OPENAI_BASE_URL &&
            normalized.diarizationModel == VoiceProviderProfile.DIARIZATION_MODEL
    }
}

/**
 * Meeting transcription owns an explicit online entry and therefore resolves the independently
 * stored STT profile without consulting the quick-dictation transport selection.
 */
object MeetingVoiceProfileResolver {
    fun resolve(context: Context, allowsCredentialAccess: Boolean): VoiceProviderProfile? = resolve(
        allowsCredentialAccess = allowsCredentialAccess,
        loadCredential = { VoiceProviderCredentialStore(context).load() }
    )

    internal fun resolve(
        allowsCredentialAccess: Boolean,
        loadCredential: () -> VoiceProviderProfile?
    ): VoiceProviderProfile? {
        if (!allowsCredentialAccess) return null
        return loadCredential()?.takeIf(VoiceProviderProfile::isConfigured)
    }
}

/** Pure window-state contract kept testable without constructing an InputMethodService. */
internal object MeetingWindowEntryPolicy {
    fun evaluate(
        allowsTextInspection: Boolean,
        allowsNetworkInput: Boolean,
        profile: VoiceProviderProfile?
    ): AiFeatureEntryGate = AiFeatureEntryGate.evaluate(
        allowsTextInspection = allowsTextInspection,
        allowsAiInput = allowsNetworkInput,
        hasConfiguredProfile = profile != null
    )
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
