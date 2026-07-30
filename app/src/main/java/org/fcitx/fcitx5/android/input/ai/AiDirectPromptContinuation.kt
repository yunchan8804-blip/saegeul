/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

/**
 * Immutable hand-off from the canonical Fcitx prompt editor back to AI writing.
 *
 * The source preview is deliberately stored here instead of recaptured after the prompt closes.
 * A prompt is allowed to continue only when that exact reviewed snapshot is still current.
 */
internal data class AiPendingCustomRequest(
    val instruction: String,
    val snapshot: AiInputSnapshot,
    val replySourceOrigin: AiReplySourceOrigin?
)

/** Small, pure boundary for the direct-prompt reattach safety contract. */
internal object AiDirectPromptContinuation {
    fun bind(
        instruction: String,
        snapshot: AiInputSnapshot,
        replySourceOrigin: AiReplySourceOrigin?
    ): AiPendingCustomRequest = AiPendingCustomRequest(
        instruction = instruction,
        snapshot = snapshot,
        replySourceOrigin = replySourceOrigin
    )

    /** Never replace [AiPendingCustomRequest.snapshot] with a newer editor capture. */
    fun resumeIfSnapshotCurrent(
        pending: AiPendingCustomRequest,
        isSnapshotCurrent: (AiInputSnapshot) -> Boolean
    ): AiPendingCustomRequest? = pending.takeIf { isSnapshotCurrent(it.snapshot) }
}
