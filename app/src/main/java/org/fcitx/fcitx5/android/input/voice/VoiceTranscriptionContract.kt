/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.EditorResumeQueue
import org.fcitx.fcitx5.android.input.EditorResumeTarget

data class VoiceEditorTarget(
    override val packageName: String,
    override val fieldId: Int,
    override val inputType: Int,
    val cursor: Int
) : EditorResumeTarget

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
internal class VoicePermissionResumeQueue :
    EditorResumeQueue<VoiceEditorTarget, Boolean, VoicePermissionResumeResult>(::VoicePermissionResumeResult)

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
    fun action(hasDeviceVoiceInput: Boolean, allowsNetworkInput: Boolean): VoiceUnavailableAction =
        if (hasDeviceVoiceInput && allowsNetworkInput) {
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

internal enum class VoiceReviewedCommitResult {
    Inserted,
    NotReady,
    AlreadyConsumed,
    EditorChanged,
    CommitFailed
}

/**
 * Preview-first transcript state shared by the production Window and deterministic runtime tests.
 * Publishing a transcript never mutates the editor; only [insert] can dispatch one mutation.
 */
internal class VoiceTranscriptReviewSession {
    private val gate = VoiceCommitGate()
    private var target: VoiceEditorTarget? = null
    private var transcript: String? = null

    @Synchronized
    fun begin(target: VoiceEditorTarget) {
        this.target = target
        transcript = null
        gate.resetForNewTranscript()
    }

    @Synchronized
    fun publish(value: String): Boolean {
        if (target == null || VoiceTranscriptPolicy.normalize(value) != value) return false
        transcript = value
        gate.resetForNewTranscript()
        return true
    }

    fun insert(
        matchesCurrentEditor: (VoiceEditorTarget) -> Boolean,
        commitText: (String) -> Boolean
    ): VoiceReviewedCommitResult {
        val reviewed: String
        val boundTarget: VoiceEditorTarget
        synchronized(this) {
            reviewed = transcript ?: return VoiceReviewedCommitResult.NotReady
            boundTarget = target ?: return VoiceReviewedCommitResult.NotReady
            if (!gate.claim()) return VoiceReviewedCommitResult.AlreadyConsumed
        }
        if (!matchesCurrentEditor(boundTarget)) return VoiceReviewedCommitResult.EditorChanged
        if (!commitText(reviewed)) return VoiceReviewedCommitResult.CommitFailed
        clear()
        return VoiceReviewedCommitResult.Inserted
    }

    @Synchronized
    fun clear() {
        target = null
        transcript = null
        gate.resetForNewTranscript()
    }
}
