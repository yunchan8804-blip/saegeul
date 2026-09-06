/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

/**
 * Result classification for buffered input session termination.
 * P0-03 Silent Data Loss Elimination SSOT.
 */
enum class BufferTerminationResult {
    Submitted,
    PreservedForRetry,
    DiscardedByPolicy,
    UserCancelled
}

/**
 * Reason classification for policy-based buffer discards.
 */
enum class BufferDiscardReason {
    ExternalSelectionChanged,
    EditorChanged,
    InputFinished,
    UnbindInput,
    ExplicitCancel,
    EngineReset
}

/**
 * Lifecycle and delivery state of the compatibility buffer.
 */
sealed class BufferedSessionState {
    data object Idle : BufferedSessionState()
    data class Composing(val committedPrefix: String, val currentPreedit: String = "") : BufferedSessionState()
    data class Submitted(val submittedText: String, val transport: BufferedInputTransport) : BufferedSessionState()
    data class PreservedForRetry(val preservedText: String, val failedTransport: BufferedInputTransport) : BufferedSessionState()
    data class Discarded(val reason: BufferDiscardReason, val characterCount: Int = 0) : BufferedSessionState()
    data object UserCancelled : BufferedSessionState()
}

/**
 * Diagnostic and UI event emitted upon any buffer state termination.
 * Contains no raw PII or plaintext credentials, only lengths and metadata.
 */
data class BufferTerminationEvent(
    val result: BufferTerminationResult,
    val reason: BufferDiscardReason? = null,
    val characterCount: Int,
    val transport: BufferedInputTransport? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/** Holds text finalized by an engine until a compatibility transport submits it as one unit. */
class BufferedInputController {

    private val committed = StringBuilder()

    var state: BufferedSessionState = BufferedSessionState.Idle
        private set

    private var terminationListener: ((BufferTerminationEvent) -> Unit)? = null

    val prefix: String
        get() = committed.toString()

    val isEmpty: Boolean
        get() = committed.isEmpty()

    fun setTerminationListener(listener: ((BufferTerminationEvent) -> Unit)?) {
        this.terminationListener = listener
    }

    fun capture(text: String) {
        committed.append(text)
        state = BufferedSessionState.Composing(committed.toString())
    }

    fun snapshot(currentPreedit: String = ""): String = buildString {
        append(committed)
        append(currentPreedit)
    }

    fun deleteLastCodePoint(): Boolean {
        if (committed.isEmpty()) {
            state = BufferedSessionState.Idle
            return false
        }
        val start = committed.offsetByCodePoints(committed.length, -1)
        committed.delete(start, committed.length)
        state = if (committed.isEmpty()) {
            BufferedSessionState.Idle
        } else {
            BufferedSessionState.Composing(committed.toString())
        }
        return true
    }

    fun clear() {
        committed.clear()
        state = BufferedSessionState.Idle
    }

    /**
     * Successfully submits the active buffer to the target editor.
     * Returns the submitted text payload and transitions to Submitted state.
     */
    fun markSubmitted(transport: BufferedInputTransport, currentPreedit: String = ""): String {
        val fullText = snapshot(currentPreedit)
        val charCount = fullText.length
        committed.clear()
        state = BufferedSessionState.Submitted(fullText, transport)
        terminationListener?.invoke(
            BufferTerminationEvent(
                result = BufferTerminationResult.Submitted,
                characterCount = charCount,
                transport = transport
            )
        )
        return fullText
    }

    /**
     * Marks a delivery attempt as failed without dropping user input.
     * Merges current preedit into the committed prefix and preserves it for explicit retry.
     */
    fun markDeliveryFailed(transport: BufferedInputTransport, currentPreedit: String = "") {
        if (currentPreedit.isNotEmpty()) {
            committed.append(currentPreedit)
        }
        val preservedText = committed.toString()
        state = BufferedSessionState.PreservedForRetry(preservedText, transport)
        terminationListener?.invoke(
            BufferTerminationEvent(
                result = BufferTerminationResult.PreservedForRetry,
                characterCount = preservedText.length,
                transport = transport
            )
        )
    }

    /**
     * Prepares the preserved buffer for a subsequent retry attempt.
     */
    fun prepareRetry(): String {
        return committed.toString()
    }

    /**
     * User-initiated cancellation. Clears the buffer and notifies with UserCancelled.
     */
    fun cancel(): String {
        val cancelledText = committed.toString()
        val charCount = cancelledText.length
        committed.clear()
        state = BufferedSessionState.UserCancelled
        terminationListener?.invoke(
            BufferTerminationEvent(
                result = BufferTerminationResult.UserCancelled,
                characterCount = charCount
            )
        )
        return cancelledText
    }

    /**
     * Policy-based discard (e.g. external cursor jump, editor/focus change, process shutdown).
     * Strictly prevents cross-editor leaks while notifying diagnostic listeners.
     */
    fun discardByPolicy(reason: BufferDiscardReason) {
        val charCount = committed.length
        committed.clear()
        state = BufferedSessionState.Discarded(reason, charCount)
        terminationListener?.invoke(
            BufferTerminationEvent(
                result = BufferTerminationResult.DiscardedByPolicy,
                reason = reason,
                characterCount = charCount
            )
        )
    }

    /**
     * Safe extraction for explicit user copy without mutating or clearing the active buffer.
     */
    fun extractForCopy(currentPreedit: String = ""): String {
        return snapshot(currentPreedit)
    }
}
