/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.prompt

import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.input.EditorIdentity
import org.fcitx.fcitx5.android.input.EditorSelection
import org.fcitx.fcitx5.android.input.InputView
import org.fcitx.fcitx5.android.input.cursor.CursorRange
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns internal prompt capture: the capture gate, the active capture and the reviewed submission
 * that waits for its reset drain. Fcitx callbacks reach a prompt only through this controller,
 * fenced by the start, submit, direct-commit and drain markers it places in the Fcitx job stream.
 */
internal class InternalPromptController(private val host: Host) {

    /** The service state and actions a prompt capture reads or triggers. */
    data class Host(
        val engineGeneration: () -> Long,
        val isEngineReady: () -> Boolean,
        val isEventCollectorReady: () -> Boolean,
        val eventCollectorEngineGeneration: () -> Long,
        val discardEventGeneration: (engineRestart: Boolean) -> Unit,
        val postFcitxJob: (block: suspend FcitxAPI.() -> Unit) -> Job,
        val lifecycleScope: () -> CoroutineScope,
        val inputView: () -> InputView?,
        val editorInfo: () -> EditorInfo?,
        val selection: () -> CursorRange,
        val inputSessionEpoch: () -> Long,
        val allowsFeature: (InternalPromptFeature) -> Boolean,
        val finishCompositionForDirectAction: () -> Boolean,
        val finishComposing: () -> Unit,
        val clearBufferedHangul: () -> Unit,
        val resetComposingState: () -> Unit,
        val removeCachedKeyEvent: (timestamp: Int) -> Unit
    )

    private data class ActiveInternalPromptCapture(
        val token: Long,
        val engineGeneration: Long,
        val spec: InternalPromptSpec,
        val session: InternalPromptCaptureSession,
        val onStarted: (token: Long) -> Unit,
        val onChanged: (token: Long, committed: String, preedit: String) -> Unit,
        val target: InternalPromptEditorTarget,
        val directCommits: InternalPromptDirectCommitQueue = InternalPromptDirectCommitQueue()
    )

    /** A reviewed prompt that may open its next IME window only after the reset drain finishes. */
    private data class PendingInternalPromptSubmission(
        val token: Long,
        val text: String,
        val target: InternalPromptEditorTarget
    )

    private val internalPromptCaptureGate = InternalPromptCaptureGate()
    private var activeInternalPromptCapture: ActiveInternalPromptCapture? = null
    private var pendingInternalPromptSubmission: PendingInternalPromptSubmission? = null

    /** A prompt is scoped to the exact Fcitx engine instance that accepted its start marker. */
    private fun ActiveInternalPromptCapture.belongsToCurrentEngine(): Boolean =
        engineGeneration == host.engineGeneration()

    /** Fails closed before any stale prompt callback can cross into a replacement engine. */
    private fun invalidateStaleInternalPromptEngine(capture: ActiveInternalPromptCapture): Boolean {
        if (capture.belongsToCurrentEngine()) return false
        host.discardEventGeneration(true)
        return true
    }

    /** Fails closed when the active capture belongs to an engine instance that no longer exists. */
    fun invalidateStaleEngine(): Boolean {
        val capture = activeInternalPromptCapture ?: return false
        return invalidateStaleInternalPromptEngine(capture)
    }

    /** Forgets every prompt after the service dropped its Fcitx event generation. */
    fun resetForEngineRestart() {
        pendingInternalPromptSubmission = null
        activeInternalPromptCapture = null
        internalPromptCaptureGate.resetForEngineRestart()
        host.inputView()?.abortInternalPromptInput()
    }

    val isInternalPromptCaptureActive: Boolean
        get() = activeInternalPromptCapture?.let { capture ->
            capture.belongsToCurrentEngine() && internalPromptCaptureGate.isActive(capture.token)
        } == true

    /** Lets delayed UI posts verify that their exact capture is still the current destination. */
    fun isInternalPromptCaptureActive(token: Long): Boolean =
        activeInternalPromptCapture?.let { capture ->
            capture.token == token && capture.belongsToCurrentEngine() &&
                internalPromptCaptureGate.isActive(token)
        } == true

    val isInternalPromptCaptureDraining: Boolean
        get() = internalPromptCaptureGate.isDraining

    /** True whenever a prompt is starting, active, or draining and blocks new editor actions. */
    val isInternalPromptInputOwned: Boolean
        get() = internalPromptCaptureGate.blocksNewInput

    /** True before the FIFO start marker activates prompt capture. */
    val isInternalPromptCaptureStarting: Boolean
        get() = internalPromptCaptureGate.isStarting

    /** True while an active, quarantined or draining prompt owns every Fcitx callback. */
    val ownsInput: Boolean
        get() = internalPromptCaptureGate.ownsInput

    val isInternalPromptSubmissionPending: Boolean
        get() = activeInternalPromptCapture?.let { capture ->
            capture.belongsToCurrentEngine() && internalPromptCaptureGate.isActive(capture.token) &&
                capture.directCommits.isSubmissionPending
        } == true

    /**
     * Queues IME-owned picker/clipboard text behind the prompt's existing Fcitx composition.
     *
     * This is deliberately separate from
     * [org.fcitx.fcitx5.android.input.FcitxInputMethodService.commitToEditor]: while an internal
     * prompt is active, direct UI text belongs to that prompt, never to the app editor that
     * opened the keyboard.
     */
    fun insertInternalPromptDirectText(text: String): InternalPromptDirectCommitResult {
        val capture = activeInternalPromptCapture
        if (capture != null && invalidateStaleInternalPromptEngine(capture)) {
            return InternalPromptDirectCommitResult.ConsumedClosing
        }
        if (capture != null && internalPromptCaptureGate.isActive(capture.token)) {
            val reservation = capture.directCommits.reserve()?.let { sequence ->
                InternalPromptDirectCommitResult.Reserved(capture.token, sequence)
            } ?: return InternalPromptDirectCommitResult.ConsumedClosing
            postInternalPromptDirectCommit(reservation, text)
            return reservation
        }
        return if (isInternalPromptInputOwned) {
            InternalPromptDirectCommitResult.ConsumedClosing
        } else {
            InternalPromptDirectCommitResult.NotPrompt
        }
    }

    fun shouldRetainInternalPromptCapture(info: EditorInfo): Boolean =
        activeInternalPromptCapture?.let { capture ->
            capture.belongsToCurrentEngine() && internalPromptCaptureGate.isActive(capture.token) &&
                matchesCurrentInternalPromptTarget(capture.target, info)
        } == true

    private fun captureCurrentInternalPromptTarget(
        info: EditorInfo = host.editorInfo()!!
    ): InternalPromptEditorTarget {
        val selection = host.selection()
        return InternalPromptEditorTarget(
            identity = EditorIdentity.of(info),
            selection = EditorSelection(selection.start, selection.end),
            inputSessionEpoch = host.inputSessionEpoch()
        )
    }

    private fun matchesCurrentInternalPromptTarget(
        target: InternalPromptEditorTarget,
        info: EditorInfo = host.editorInfo()!!
    ): Boolean {
        val selection = host.selection()
        return target.matches(
            identity = EditorIdentity.of(info),
            selection = EditorSelection(selection.start, selection.end),
            inputSessionEpoch = host.inputSessionEpoch()
        )
    }

    /** Starts an internal text target while leaving the real keyboard and Fcitx engine active. */
    fun beginInternalPromptCapture(
        spec: InternalPromptSpec,
        initialText: String,
        onStarted: (token: Long) -> Unit,
        onChanged: (token: Long, committed: String, preedit: String) -> Unit
    ): Long? {
        if (!host.allowsFeature(spec.feature)) return null
        // SharedFlow has replay=0. Do not enqueue a control marker until this service has an
        // active subscription for the current Fcitx generation to observe it.
        if (!host.isEventCollectorReady()) return null
        // A collector can subscribe while the daemon is stopped between restart phases. Its
        // marker would wait behind a new engine boundary, so only start a capture on a ready
        // engine instance.
        if (!host.isEngineReady()) return null
        // A prior prompt may still have Fcitx callbacks queued. Wait for its in-stream barrier
        // instead of treating the next prompt as the destination for those callbacks.
        if (activeInternalPromptCapture != null || internalPromptCaptureGate.ownsInput) return null
        if (!host.finishCompositionForDirectAction()) return null
        host.clearBufferedHangul()
        val info = host.editorInfo()
        val promptEngineGeneration = host.engineGeneration()
        if (promptEngineGeneration != host.eventCollectorEngineGeneration() ||
            !host.isEngineReady()
        ) return null
        val token = internalPromptCaptureGate.beginStarting() ?: return null
        val capture = ActiveInternalPromptCapture(
            token = token,
            engineGeneration = promptEngineGeneration,
            spec = spec,
            session = InternalPromptCaptureSession(initialText, spec.maxCharacters),
            onStarted = onStarted,
            onChanged = onChanged,
            target = captureCurrentInternalPromptTarget(info!!)
        )
        activeInternalPromptCapture = capture
        val startMarkerEmitted = AtomicBoolean(false)
        host.postFcitxJob {
            // This marker is behind every Fcitx action that existed before opening the prompt.
            // Their commits still belong to the original editor; only events after the marker may
            // enter the internal prompt session.
            reset()
            emitInternalPromptStartBarrier(token)
            startMarkerEmitted.set(true)
        }.invokeOnCompletion { cause ->
            if (cause != null && !startMarkerEmitted.get()) {
                host.lifecycleScope().launch { abandonInternalPromptStartFence(token) }
            }
        }
        return token
    }

    /** Starts a FIFO submit fence; the final callback is released only after the reset drain. */
    fun finishInternalPromptCapture(): InternalPromptFinishResult {
        val capture = activeInternalPromptCapture ?: return InternalPromptFinishResult.Rejected
        if (invalidateStaleInternalPromptEngine(capture)) {
            return InternalPromptFinishResult.Rejected
        }
        if (!internalPromptCaptureGate.isActive(capture.token)) {
            return InternalPromptFinishResult.Rejected
        }
        return when (capture.directCommits.requestSubmit()) {
            InternalPromptDirectCommitQueue.SubmissionRequest.AlreadyPending -> {
                InternalPromptFinishResult.Pending
            }
            InternalPromptDirectCommitQueue.SubmissionRequest.Started -> {
                val submitMarkerEmitted = AtomicBoolean(false)
                host.postFcitxJob {
                    if (!flushInternalPromptDirectComposition()) {
                        host.lifecycleScope().launch { abandonInternalPromptSubmitFence(capture.token) }
                        return@postFcitxJob
                    }
                    emitInternalPromptSubmitBarrier(capture.token)
                    submitMarkerEmitted.set(true)
                }
                    .invokeOnCompletion { cause ->
                        if (cause != null && !submitMarkerEmitted.get()) {
                            host.lifecycleScope().launch {
                                abandonInternalPromptSubmitFence(capture.token)
                            }
                        }
                    }
                InternalPromptFinishResult.Pending
            }
        }
    }

    /**
     * Cancels the current prompt.
     *
     * A user cancellation can still let callbacks already ahead of the start marker finish in the
     * same editor. Lifecycle/editor changes instead set [discardPreStartCallbacks] so those
     * callbacks are quarantined until their marker and cannot leak into a new InputConnection.
     */
    fun cancelInternalPromptCapture(discardPreStartCallbacks: Boolean = false) {
        // A detached/restarted InputView must also suppress a submission that is waiting for its
        // drain barrier. The callback can never reopen a tool against a changed editor.
        pendingInternalPromptSubmission = null
        val capture = activeInternalPromptCapture
        if (capture == null) {
            // A user may have cancelled while the start marker was still pending. Keep enough
            // state to quarantine those old callbacks if Android immediately changes editors.
            if (discardPreStartCallbacks) {
                internalPromptCaptureGate.discardPendingStart()
            }
            return
        }
        if (invalidateStaleInternalPromptEngine(capture)) return
        val cancelledStart = if (discardPreStartCallbacks) {
            internalPromptCaptureGate.discardStart(capture.token)
        } else {
            internalPromptCaptureGate.cancelStart(capture.token)
        }
        if (cancelledStart) {
            activeInternalPromptCapture = null
            return
        }
        if (!internalPromptCaptureGate.isActive(capture.token)) return
        capture.directCommits.discard()
        beginInternalPromptDrain(capture)
    }

    private fun beginInternalPromptDrain(capture: ActiveInternalPromptCapture) {
        if (invalidateStaleInternalPromptEngine(capture)) return
        if (!internalPromptCaptureGate.beginDrain(capture.token)) return
        activeInternalPromptCapture = null
        host.resetComposingState()
        val drainMarkerEmitted = AtomicBoolean(false)
        host.postFcitxJob {
            resetForInternalPromptDrain(capture.token)
            drainMarkerEmitted.set(true)
        }.invokeOnCompletion { cause ->
            if (cause != null && !drainMarkerEmitted.get()) {
                host.lifecycleScope().launch { abandonInternalPromptDrainFence(capture.token) }
            }
        }
    }

    /** Enables capture only after all older Fcitx callbacks have crossed the start marker. */
    fun deliverInternalPromptStartFence(token: Long) {
        if (internalPromptCaptureGate.releaseDiscardedStart(token)) return
        if (internalPromptCaptureGate.releaseCancelledStart(token)) return
        val capture = activeInternalPromptCapture ?: return
        if (invalidateStaleInternalPromptEngine(capture)) return
        if (capture.token != token || !internalPromptCaptureGate.activateStart(token)) return
        capture.onStarted(token)
        notifyInternalPromptChanged(capture)
    }

    /** Releases a drain marker, then opens the submission that was waiting for it. */
    fun deliverInternalPromptDrainFence(token: Long) {
        if (internalPromptCaptureGate.releaseDrain(token)) {
            deliverSettledInternalPromptSubmission(token)
        }
    }

    /**
     * Fails a start fence without ever reopening its queued event generation to an editor.
     *
     * A worker error says nothing about callbacks already buffered ahead of the marker. Drop the
     * service subscription first, then create a replay-free replacement before allowing input.
     */
    private fun abandonInternalPromptStartFence(token: Long) {
        if (!internalPromptCaptureGate.hasPendingStart(token)) return
        host.discardEventGeneration(false)
    }

    /** A cancelled drain marker can never release its gate against a possibly restarted engine. */
    private fun abandonInternalPromptDrainFence(token: Long) {
        if (!internalPromptCaptureGate.isDraining(token)) return
        host.discardEventGeneration(false)
    }

    /** Schedules the picker/clipboard marker only after Fcitx flushes the preceding preedit. */
    private fun postInternalPromptDirectCommit(
        reservation: InternalPromptDirectCommitResult.Reserved,
        text: String
    ) {
        val directMarkerEmitted = AtomicBoolean(false)
        host.postFcitxJob {
            if (!flushInternalPromptDirectComposition()) {
                host.lifecycleScope().launch { abandonInternalPromptDirectCommit(reservation) }
                return@postFcitxJob
            }
            emitInternalPromptDirectCommitBarrier(reservation.token, reservation.sequence, text)
            directMarkerEmitted.set(true)
        }.invokeOnCompletion { cause ->
            if (cause != null && !directMarkerEmitted.get()) {
                host.lifecycleScope().launch { abandonInternalPromptDirectCommit(reservation) }
            }
        }
    }

    /**
     * Finalizes the engine-owned segment before a direct IME insert.
     *
     * Chinese must select a real candidate. If that fails, resetting would silently discard the
     * raw preedit, so the caller leaves the prompt open and restores its Search/Run button.
     */
    private suspend fun FcitxAPI.flushInternalPromptDirectComposition(): Boolean {
        if (inputMethodEntryCached.languageCode.startsWith("zh")) {
            if (clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty()) {
                if (!select(0)) return false
            }
        } else {
            withContext(Dispatchers.Main.immediate) { host.finishComposing() }
        }
        reset()
        return true
    }

    fun captureInternalPromptCommit(text: String): Boolean {
        val capture = activeInternalPromptCapture
        if (capture != null && internalPromptCaptureGate.isActive(capture.token)) {
            if (invalidateStaleInternalPromptEngine(capture)) return true
            capture.session.commit(text)
            notifyInternalPromptChanged(capture)
            return true
        }
        return internalPromptCaptureGate.ownsInput
    }

    /** Resolves a generic Search/Run fence after all earlier Fcitx callbacks reached this IME. */
    fun deliverInternalPromptSubmitFence(token: Long) {
        val capture = activeInternalPromptCapture
        if (capture == null || capture.token != token || !internalPromptCaptureGate.isActive(token)) {
            return
        }
        if (invalidateStaleInternalPromptEngine(capture)) return
        if (capture.directCommits.reachSubmitFence() ==
            InternalPromptDirectCommitQueue.Completion.SubmitReady
        ) {
            settleInternalPromptSubmission(capture)
        }
    }

    /** Appends a picker result only when its original prompt is still the active destination. */
    fun deliverInternalPromptDirectCommit(token: Long, sequence: Long, text: String) {
        val capture = activeInternalPromptCapture
        if (capture == null || capture.token != token || !internalPromptCaptureGate.isActive(token)) {
            // Prompt closed, changed editors, or a newer prompt owns the keyboard. The marker is
            // deliberately dropped; a stale picker action must never fall through to the editor.
            return
        }
        if (invalidateStaleInternalPromptEngine(capture)) return
        val completion = capture.directCommits.complete(sequence)
        if (completion == InternalPromptDirectCommitQueue.Completion.Ignored) return
        capture.session.commit(text)
        notifyInternalPromptChanged(capture)
        if (completion == InternalPromptDirectCommitQueue.Completion.SubmitReady) {
            settleInternalPromptSubmission(capture)
        }
    }

    /** Snapshots a fenced prompt, then waits for reset callbacks before opening the next surface. */
    private fun settleInternalPromptSubmission(capture: ActiveInternalPromptCapture) {
        if (invalidateStaleInternalPromptEngine(capture)) return
        val prompt = capture.session.submission()
        if (prompt.isBlank() && !capture.spec.allowBlankSubmission) {
            host.inputView()?.restoreInternalPromptSubmission(capture.token)
            return
        }
        pendingInternalPromptSubmission = PendingInternalPromptSubmission(
            token = capture.token,
            text = prompt,
            target = capture.target
        )
        beginInternalPromptDrain(capture)
    }

    /** Opens the next IME-owned surface only after the matching reset drain released the gate. */
    private fun deliverSettledInternalPromptSubmission(token: Long) {
        val pending = pendingInternalPromptSubmission ?: return
        if (pending.token != token) return
        pendingInternalPromptSubmission = null
        if (!matchesCurrentInternalPromptTarget(pending.target)) return
        host.inputView()?.completeInternalPromptSubmission(pending.token, pending.text)
    }

    /** Releases a failed Fcitx picker job so Search/Run never remains permanently disabled. */
    private fun abandonInternalPromptDirectCommit(
        reservation: InternalPromptDirectCommitResult.Reserved
    ) {
        val capture = activeInternalPromptCapture ?: return
        if (capture.token != reservation.token || !internalPromptCaptureGate.isActive(capture.token)) {
            return
        }
        if (invalidateStaleInternalPromptEngine(capture)) return
        if (capture.directCommits.abandon(reservation.sequence)) {
            host.inputView()?.restoreInternalPromptSubmission(capture.token)
        }
    }

    /** Restores an active prompt after its generic submit-fence worker cannot run. */
    private fun abandonInternalPromptSubmitFence(token: Long) {
        val capture = activeInternalPromptCapture ?: return
        if (capture.token != token || !internalPromptCaptureGate.isActive(token)) return
        if (invalidateStaleInternalPromptEngine(capture)) return
        if (capture.directCommits.abortSubmission()) {
            host.inputView()?.restoreInternalPromptSubmission(token)
        }
    }

    fun updateInternalPromptPreedit(text: String): Boolean {
        val capture = activeInternalPromptCapture
        if (capture != null && internalPromptCaptureGate.isActive(capture.token)) {
            if (invalidateStaleInternalPromptEngine(capture)) return true
            capture.session.updatePreedit(text)
            notifyInternalPromptChanged(capture)
            return true
        }
        return internalPromptCaptureGate.ownsInput
    }

    fun deleteInternalPromptBeforeCursor(codePoints: Int): Boolean {
        val capture = activeInternalPromptCapture
        if (capture != null && internalPromptCaptureGate.isActive(capture.token)) {
            if (invalidateStaleInternalPromptEngine(capture)) return true
            capture.session.deleteBeforeCursor(codePoints)
            notifyInternalPromptChanged(capture)
            return true
        }
        return internalPromptCaptureGate.ownsInput
    }

    /**
     * Commits the active prompt's preedit in place of finishing the editor's composition.
     *
     * Returns true whenever the prompt owns input, so the caller must leave the editor untouched.
     */
    fun finishComposingIfOwned(): Boolean {
        activeInternalPromptCapture?.let { capture ->
            if (internalPromptCaptureGate.isActive(capture.token)) {
                if (invalidateStaleInternalPromptEngine(capture)) return true
                capture.session.commitPreedit()
                notifyInternalPromptChanged(capture)
                return true
            }
        }
        // A queued keyboard action may call this after the prompt has submitted. Do not turn the
        // old composing span into editor text until its reset barrier has reached this service.
        return internalPromptCaptureGate.ownsInput
    }

    private fun notifyInternalPromptChanged(capture: ActiveInternalPromptCapture) {
        capture.onChanged(capture.token, capture.session.committedText, capture.session.preeditText)
    }

    /** Consumes only keys that Fcitx chose to forward; engine-owned composition stays untouched. */
    fun handleInternalPromptForwardedKey(data: FcitxEvent.KeyEvent.Data): Boolean {
        if (!internalPromptCaptureGate.ownsInput) return false
        if (!data.states.virtual) host.removeCachedKeyEvent(data.timestamp)
        if (internalPromptCaptureGate.isDraining) return true
        if (isInternalPromptSubmissionPending) return true
        if (data.up) return true
        val hasShortcutModifier = data.states.ctrl || data.states.alt || data.states.meta ||
            data.states.has(KeyState.Super) || data.states.has(KeyState.Super2) ||
            data.states.has(KeyState.Hyper)
        if (hasShortcutModifier) return true
        when (data.sym.sym) {
            FcitxKeyMapping.FcitxKey_BackSpace -> deleteInternalPromptBeforeCursor(1)
            FcitxKeyMapping.FcitxKey_Return -> host.inputView()?.submitInternalPromptInput()
            FcitxKeyMapping.FcitxKey_Left,
            FcitxKeyMapping.FcitxKey_Right -> Unit // The internal target intentionally uses an end cursor.
            else -> if (data.unicode > 0) {
                captureInternalPromptCommit(Character.toString(data.unicode))
            }
        }
        return true
    }
}
