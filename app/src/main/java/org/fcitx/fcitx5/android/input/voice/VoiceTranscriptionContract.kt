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

data class VoicePermissionResumeResult(
    val target: VoiceEditorTarget,
    val granted: Boolean
)

/**
 * Process-memory handoff across the permission Activity boundary.
 *
 * Opening a runtime-permission Activity hides and restarts the IME. The result therefore cannot
 * safely start AudioRecord from the old input window. Keep the intent until the original editor is
 * active again, then let InputView restore the voice window exactly once.
 */
internal class VoicePermissionResumeQueue {
    private data class Pending(val id: Long, val target: VoiceEditorTarget)

    private var pending: Pending? = null
    private var completed: VoicePermissionResumeResult? = null

    @Synchronized
    fun begin(id: Long, target: VoiceEditorTarget) {
        pending = Pending(id, target)
        completed = null
    }

    @Synchronized
    fun complete(id: Long, granted: Boolean) {
        val request = pending?.takeIf { it.id == id } ?: return
        pending = null
        completed = VoicePermissionResumeResult(request.target, granted)
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
    ): VoicePermissionResumeResult? {
        val result = completed ?: return null
        completed = null
        return result.takeIf {
            packageName == it.target.packageName &&
                fieldId == it.target.fieldId &&
                inputType == it.target.inputType
        }
    }
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

enum class VoiceUnavailableAction {
    DeviceDictation,
    ProviderSetup
}

object VoiceFallbackPolicy {
    fun action(hasDeviceVoiceInput: Boolean): VoiceUnavailableAction =
        if (hasDeviceVoiceInput) {
            VoiceUnavailableAction.DeviceDictation
        } else {
            VoiceUnavailableAction.ProviderSetup
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
