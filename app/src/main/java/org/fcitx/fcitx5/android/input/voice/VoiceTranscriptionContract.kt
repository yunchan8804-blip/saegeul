/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

data class VoiceEditorTarget(
    val packageName: String,
    val fieldId: Int,
    val inputType: Int,
    val cursor: Int
)

object VoiceTranscriptionModels {
    /** Accuracy-first file transcription; realtime uses a separate transcription session. */
    const val ACCURACY = "gpt-4o-transcribe"
}

object VoiceTranscriptPolicy {
    const val MAX_CHARACTERS = 4_000

    fun normalize(value: String?): String? = value
        ?.trim()
        ?.take(MAX_CHARACTERS)
        ?.takeIf(String::isNotBlank)

    fun bindEditor(
        packageName: String?,
        fieldId: Int,
        inputType: Int,
        selectionStart: Int,
        selectionEnd: Int
    ): VoiceEditorTarget? {
        if (packageName.isNullOrBlank() || selectionStart < 0 || selectionStart != selectionEnd) {
            return null
        }
        return VoiceEditorTarget(packageName, fieldId, inputType, selectionStart)
    }
}

/** One reviewed transcript can dispatch at most one editor mutation. */
internal class VoiceCommitGate {
    private var consumed = false

    @Synchronized
    fun claim(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }

    @Synchronized
    fun resetForNewTranscript() {
        consumed = false
    }
}
