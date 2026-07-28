/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.AiAction

/** Identifies the policy boundary that owns an internally captured keyboard prompt. */
enum class InternalPromptFeature {
    Ai,
    GifSearch
}

/**
 * Immutable contract for text captured by the real Fcitx keyboard instead of the target editor.
 *
 * [allowBlankSubmission] is deliberately feature-specific: AI keeps an empty instruction open,
 * while an empty GIF query means the provider's trending search.
 */
data class InternalPromptSpec(
    val feature: InternalPromptFeature,
    val maxCharacters: Int,
    val allowBlankSubmission: Boolean,
    @StringRes val hintRes: Int,
    @StringRes val submitRes: Int
) {
    init {
        require(maxCharacters > 0) { "Prompt maxCharacters must be positive" }
    }
}

object InternalPromptSpecs {
    val Ai = InternalPromptSpec(
        feature = InternalPromptFeature.Ai,
        maxCharacters = AiAction.MAX_CUSTOM_INSTRUCTION_CHARACTERS,
        allowBlankSubmission = false,
        hintRes = R.string.ai_direct_prompt_hint,
        submitRes = R.string.ai_direct_prompt_run
    )

    fun gifSearch(maxCharacters: Int) = InternalPromptSpec(
        feature = InternalPromptFeature.GifSearch,
        maxCharacters = maxCharacters,
        allowBlankSubmission = true,
        hintRes = R.string.gif_query_inline_hint,
        submitRes = R.string.gif_search_action
    )
}

/** The outcome of a direct picker/emoji action while an internal prompt owns the keyboard. */
internal sealed interface InternalPromptDirectCommitResult {
    data class Reserved(val token: Long, val sequence: Long) : InternalPromptDirectCommitResult
    data object ConsumedClosing : InternalPromptDirectCommitResult
    data object NotPrompt : InternalPromptDirectCommitResult
}

/** The outcome of an explicit Run/Search press on an internal prompt. */
internal sealed interface InternalPromptFinishResult {
    /** Fcitx still has to pass the prompt's in-stream submit fence. */
    data object Pending : InternalPromptFinishResult
    data object Rejected : InternalPromptFinishResult
}

/**
 * Fail-closed identity for the editor that was active when an internal prompt started.
 *
 * Package, field metadata, and selection alone are not enough: two fields in the same app can
 * legitimately share all three. [inputSessionEpoch] advances for every Android input session, so
 * a queued prompt callback cannot reopen an AI/GIF surface against a later field.
 */
internal data class InternalPromptEditorTarget(
    val packageName: String?,
    val fieldId: Int,
    val inputType: Int,
    val selectionStart: Int,
    val selectionEnd: Int,
    val inputSessionEpoch: Long
) {
    fun matches(
        packageName: String?,
        fieldId: Int,
        inputType: Int,
        selectionStart: Int,
        selectionEnd: Int,
        inputSessionEpoch: Long
    ): Boolean =
        this.packageName == packageName &&
            this.fieldId == fieldId &&
            this.inputType == inputType &&
            this.selectionStart == selectionStart &&
            this.selectionEnd == selectionEnd &&
            this.inputSessionEpoch == inputSessionEpoch
}

/**
 * Tracks direct picker actions that must wait for Fcitx to flush its preceding composition.
 *
 * The queue is deliberately independent from the displayed text. Fcitx commits any existing
 * preedit first; only the matching in-stream marker may append the picker text afterward.
 */
internal class InternalPromptDirectCommitQueue {
    enum class SubmissionRequest {
        Started,
        AlreadyPending
    }

    enum class Completion {
        Accepted,
        SubmitReady,
        Ignored
    }

    private val pendingSequences = linkedSetOf<Long>()
    private var nextSequence = 0L
    private var submitRequested = false
    private var submitFenceReached = false

    val hasPending: Boolean
        get() = pendingSequences.isNotEmpty()

    val isSubmissionPending: Boolean
        get() = submitRequested

    fun reserve(): Long? {
        if (submitRequested) return null
        return (++nextSequence).also { pendingSequences += it }
    }

    /**
     * Locks the prompt while its FIFO submit fence crosses the Fcitx event stream.
     *
     * The fence is required even with no picker action: a normal virtual key or candidate
     * selection may already be queued on the same Fcitx worker when Search/Run is pressed.
     */
    fun requestSubmit(): SubmissionRequest {
        if (submitRequested) return SubmissionRequest.AlreadyPending
        submitRequested = true
        submitFenceReached = false
        return SubmissionRequest.Started
    }

    fun complete(sequence: Long): Completion {
        if (!pendingSequences.remove(sequence)) return Completion.Ignored
        return resolveSubmissionIfReady()
    }

    /** Called only by the generic submit fence after every earlier Fcitx callback is observed. */
    fun reachSubmitFence(): Completion {
        if (!submitRequested) return Completion.Ignored
        submitFenceReached = true
        return resolveSubmissionIfReady()
    }

    /** Drops all reservations when the prompt is cancelled or its editor changes. */
    fun discard() {
        pendingSequences.clear()
        submitRequested = false
        submitFenceReached = false
    }

    /** Returns true when a failed worker job leaves a waiting Search/Run press to be retried. */
    fun abandon(sequence: Long): Boolean {
        if (!pendingSequences.remove(sequence)) return false
        if (pendingSequences.isEmpty() && submitRequested) {
            submitRequested = false
            submitFenceReached = false
            return true
        }
        return false
    }

    /** Aborts a submit fence that could not be enqueued, leaving the prompt open for retry. */
    fun abortSubmission(): Boolean {
        if (!submitRequested) return false
        submitRequested = false
        submitFenceReached = false
        return true
    }

    private fun resolveSubmissionIfReady(): Completion {
        if (submitRequested && submitFenceReached && pendingSequences.isEmpty()) {
            submitRequested = false
            submitFenceReached = false
            return Completion.SubmitReady
        }
        return Completion.Accepted
    }
}

/**
 * End-cursor text target used while the real keyboard surface captures text internally.
 *
 * The Fcitx engine remains the sole composer. This state only mirrors committed and preedit
 * output, so a prompt never leaks into the editor that originally opened the IME.
 */
class InternalPromptCaptureSession(
    initialText: String = "",
    private val maxCharacters: Int
) {
    private var committed = initialText.take(maxCharacters)
    private var preedit = ""

    init {
        require(maxCharacters > 0) { "Prompt maxCharacters must be positive" }
    }

    val committedText: String
        get() = committed

    val preeditText: String
        get() = preedit

    val displayText: String
        get() = bounded(committed + preedit)

    fun updatePreedit(text: String) {
        preedit = text.take(remainingAfterCommitted())
    }

    fun commit(text: String) {
        if (text.isEmpty()) return
        committed = bounded(committed + text)
        preedit = ""
    }

    fun commitPreedit() {
        if (preedit.isEmpty()) return
        committed = bounded(committed + preedit)
        preedit = ""
    }

    fun deleteBeforeCursor(codePoints: Int = 1) {
        if (codePoints <= 0) return
        if (preedit.isNotEmpty()) {
            preedit = preedit.dropLastCodePoints(codePoints)
        } else {
            committed = committed.dropLastCodePoints(codePoints)
        }
    }

    fun submission(): String = displayText.trim()

    private fun remainingAfterCommitted(): Int =
        (maxCharacters - committed.length).coerceAtLeast(0)

    private fun bounded(text: String): String = text.take(maxCharacters)

    private fun String.dropLastCodePoints(count: Int): String {
        if (isEmpty()) return this
        val available = codePointCount(0, length)
        val removed = count.coerceAtMost(available)
        return substring(0, offsetByCodePoints(length, -removed))
    }
}

/**
 * Owns the Fcitx-stream boundaries around an internal prompt.
 *
 * Start barriers let already-queued Fcitx events finish against the original editor before the
 * prompt becomes active. Drain barriers keep late prompt events out of that editor while closing.
 */
internal class InternalPromptCaptureGate {
    private sealed interface State {
        data object Idle : State
        data class Starting(val token: Long) : State
        /** UI cancelled, but callbacks ahead of this start marker still belong to its editor. */
        data class CancelledStart(val token: Long) : State
        /** A new editor session arrived before this start marker; discard its older callbacks. */
        data class DiscardingStart(val token: Long) : State
        data class Active(val token: Long) : State
        data class Draining(val token: Long) : State
    }

    private var state: State = State.Idle
    private var nextToken = 0L

    val ownsInput: Boolean
        get() = state is State.Active || state is State.DiscardingStart || state is State.Draining

    /** Blocks newly initiated UI/hardware input while an in-stream start marker is pending. */
    val blocksNewInput: Boolean
        get() = state !is State.Idle

    val isDraining: Boolean
        get() = state is State.Draining

    val isStarting: Boolean
        get() = state is State.Starting || state is State.CancelledStart

    fun begin(): Long? {
        if (state !is State.Idle) return null
        return (++nextToken).also { state = State.Active(it) }
    }

    /** Reserves a prompt token without capturing any callbacks that predate its Fcitx marker. */
    fun beginStarting(): Long? {
        if (state !is State.Idle) return null
        return (++nextToken).also { state = State.Starting(it) }
    }

    /** Activates a reserved prompt only when its in-stream start barrier reaches the IME. */
    fun activateStart(token: Long): Boolean {
        if ((state as? State.Starting)?.token != token) return false
        state = State.Active(token)
        return true
    }

    /**
     * Closes the UI before its start barrier reaches the IME without losing older callbacks.
     *
     * The state stays non-idle until the marker, so a subsequent editor change can still turn
     * those callbacks into a discard quarantine instead of leaking them to the next field.
     */
    fun cancelStart(token: Long): Boolean {
        if ((state as? State.Starting)?.token != token) return false
        state = State.CancelledStart(token)
        return true
    }

    /**
     * Quarantines callbacks that precede a start marker after the Android editor changes.
     *
     * They cannot safely finish in the old editor anymore, and must never be delivered to the
     * new one. The marker releases this state after every older callback is consumed.
     */
    fun discardStart(token: Long): Boolean {
        val matches = when (val current = state) {
            is State.Starting -> current.token == token
            is State.CancelledStart -> current.token == token
            else -> false
        }
        if (matches) state = State.DiscardingStart(token)
        return matches
    }

    /** Converts a user-cancelled pending start into a discard quarantine after editor change. */
    fun discardPendingStart(): Boolean {
        val token = (state as? State.CancelledStart)?.token ?: return false
        state = State.DiscardingStart(token)
        return true
    }

    /** Releases a same-session UI cancellation only after its original start marker arrives. */
    fun releaseCancelledStart(token: Long): Boolean {
        if ((state as? State.CancelledStart)?.token != token) return false
        state = State.Idle
        return true
    }

    /** Releases a cancelled-start quarantine only when its original marker reaches the IME. */
    fun releaseDiscardedStart(token: Long): Boolean {
        if ((state as? State.DiscardingStart)?.token != token) return false
        state = State.Idle
        return true
    }

    fun hasPendingStart(token: Long): Boolean = when (val current = state) {
        is State.Starting -> current.token == token
        is State.CancelledStart -> current.token == token
        is State.DiscardingStart -> current.token == token
        else -> false
    }

    /** The Fcitx event collector was discarded during an engine restart, so no marker can arrive. */
    fun resetForEngineRestart() {
        state = State.Idle
    }

    fun isActive(token: Long): Boolean =
        (state as? State.Active)?.token == token

    fun beginDrain(token: Long): Boolean {
        if (!isActive(token)) return false
        state = State.Draining(token)
        return true
    }

    fun releaseDrain(token: Long): Boolean {
        if ((state as? State.Draining)?.token != token) return false
        state = State.Idle
        return true
    }

    fun isDraining(token: Long): Boolean =
        (state as? State.Draining)?.token == token
}
