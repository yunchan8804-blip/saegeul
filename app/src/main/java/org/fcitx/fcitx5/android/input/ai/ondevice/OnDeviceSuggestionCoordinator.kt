/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Main-thread-confined coordinator for automatic on-device suggestions.
 *
 * The backend is deliberately injected: this class owns editor-session validity and the order in
 * which requests are cancelled, drained, and replaced, but it does not select a native runtime.
 */
class OnDeviceSuggestionCoordinator(
    private val scope: CoroutineScope,
    private val session: OnDeviceSuggestionSession,
    private val backend: Backend,
    private val clockMs: () -> Long,
    private val isCurrent: (OnDeviceSuggestionSession.Snapshot) -> Boolean,
    private val onChanged: () -> Unit,
    /**
     * Optionally enriches the prompt input (e.g. with personal style examples) right before
     * generation. Invoked off the main thread; a thrown exception is logged and the original,
     * unenriched input is used instead, since a personalization failure must never block a
     * suggestion.
     */
    private val promptContextEnricher: ((OnDeviceSuggestionPolicy.Input) -> OnDeviceSuggestionPolicy.Input)? = null
) {

    interface Backend {
        suspend fun generate(prompt: String): String

        fun cancel()

        suspend fun close()

        /**
         * 지금 생성을 시작해도 되는지. 거짓이면 요청을 오류 없이 건너뛴다(후보 없음). 예: 엔진이 아직 차가운데
         * 키보드가 떠 있어, 생성하려면 화면을 멈추게 하는 GPU 초기화부터 해야 하는 경우.
         */
        fun isReadyToGenerate(): Boolean = true
    }

    class BackendException(val code: String) : IllegalStateException(code)

    enum class State {
        OFF,
        DEBOUNCING,
        GENERATING,
        READY,
        NO_CANDIDATE,
        ERROR
    }

    data class Status internal constructor(
        val state: State,
        val errorCode: String? = null
    ) {
        override fun toString(): String = "Status(state=$state, errorCode=$errorCode)"
    }

    class Candidate internal constructor(
        val mode: OnDeviceSuggestionPolicy.Mode,
        val insertion: String,
        val origin: OnDeviceSuggestionSession.Origin,
        private val proposal: OnDeviceSuggestionSession.Proposal
    ) {
        override fun toString(): String =
            "Candidate(mode=$mode, insertion=<redacted>, origin=$origin)"

        internal fun proposal(): OnDeviceSuggestionSession.Proposal = proposal
    }

    var status: Status = Status(State.OFF)
        private set

    var candidates: List<Candidate> = emptyList()
        private set

    /** Whether this coordinator instance has permanently latched a terminal native failure. */
    val isTerminal: Boolean
        get() = terminalFailure

    private val serializedBackend = Mutex()
    private var enabled = false
    private var terminalFailure = false
    private var epoch = 0L
    private var lastObservation: Observation? = null
    private var runningJob: Job? = null
    private var closeJob: Job? = null

    /** Enables or disables automatic generation. It is off until explicitly enabled. */
    fun setEnabled(value: Boolean) {
        if (enabled == value && !(value && status.state == State.OFF)) return
        enabled = value
        if (!value) {
            invalidateInternal(State.OFF)
            return
        }
        if (!terminalFailure) {
            update(State.OFF)
        }
    }

    /**
     * Observes one exact editor state and its metadata. Input text and package identity must match
     * the snapshot before either can enter the in-memory session.
     */
    fun observe(
        snapshot: OnDeviceSuggestionSession.Snapshot,
        input: OnDeviceSuggestionPolicy.Input
    ) {
        if (
            snapshot.textBeforeCursor != input.textBeforeCursor ||
                snapshot.scope.packageName != input.packageName
        ) {
            invalidateForInvalidInput()
            return
        }
        if (!enabled || terminalFailure) return

        val observation = Observation(snapshot, input)
        if (lastObservation == observation) {
            if (candidates.isNotEmpty() && session.cached(clockMs()) == null) {
                candidates = emptyList()
                update(State.NO_CANDIDATE)
            }
            return
        }
        val previousObservation = lastObservation
        if (previousObservation != null && isSamePosition(previousObservation.snapshot, snapshot) && !isIdle()) {
            if (session.rebase(snapshot)) {
                lastObservation = observation
                Timber.i(
                    "Automatic suggestion observation=duplicate textLength=%d epoch=%d",
                    snapshot.textBeforeCursor.length,
                    epoch
                )
                return
            }
        }
        lastObservation = observation
        epoch += 1
        val previousJob = runningJob
        candidates = emptyList()

        if (!session.observe(snapshot)) {
            update(State.NO_CANDIDATE)
            return
        }
        publishCached(snapshot, input)
        if (candidates.isNotEmpty()) return

        val requestEpoch = epoch
        val closeBarrier = closeJob
        val job = scope.launch {
            closeBarrier?.join()
            previousJob?.join()
            serializedBackend.withLock {
                runRequest(requestEpoch, snapshot, input)
            }
        }
        runningJob = job
        update(State.DEBOUNCING)
    }

    /**
     * Clears RAM and drains the backend before a later observation can generate again.
     *
     * When [closeBackend] is false, this is a soft invalidation: the session and candidates are
     * cleared, but any in-flight request is left running to completion (its result is discarded
     * via the epoch check) and the backend (and any warm engine it holds) is left open for reuse
     * by the next observation.
     */
    fun invalidate(closeBackend: Boolean = true) {
        invalidateInternal(if (enabled) State.NO_CANDIDATE else State.OFF, closeBackend = closeBackend)
    }

    /** Applies only a currently displayed candidate and consumes its session proposal once. */
    fun takeForApply(
        candidate: Candidate,
        currentSnapshot: OnDeviceSuggestionSession.Snapshot
    ): String? {
        if (candidates.none { it === candidate } || !isCurrent(currentSnapshot)) return null
        val full = session.takeForApply(candidate.proposal(), currentSnapshot, clockMs()) ?: run {
            candidates = emptyList()
            update(State.NO_CANDIDATE)
            return null
        }
        candidates = emptyList()
        update(State.NO_CANDIDATE)
        return candidate.insertion.takeIf(full::startsWith)
    }

    private suspend fun runRequest(
        requestEpoch: Long,
        snapshot: OnDeviceSuggestionSession.Snapshot,
        input: OnDeviceSuggestionPolicy.Input
    ) {
        try {
            if (!isRequestCurrent(requestEpoch, snapshot)) return
            delay(DEBOUNCE_MS)
            if (!isRequestCurrent(requestEpoch, snapshot)) return
            if (!backend.isReadyToGenerate()) {
                publishCached(snapshot, input)
                if (candidates.isEmpty() && isRequestCurrent(requestEpoch, snapshot)) {
                    update(State.NO_CANDIDATE)
                }
                return
            }

            val ticket = session.begin(clockMs()) ?: run {
                publishCached(snapshot, input)
                if (candidates.isEmpty() && isRequestCurrent(requestEpoch, snapshot)) {
                    update(State.NO_CANDIDATE)
                }
                return
            }
            update(State.GENERATING)
            var published = false
            try {
                val enrichedInput = enrichInput(input)
                val generateStartedMs = clockMs()
                val raw = backend.generate(OnDeviceSuggestionPolicy.promptFor(enrichedInput))
                val current = isRequestCurrent(requestEpoch, snapshot)
                val suffix = if (current) parseStoredSuffix(enrichedInput, raw) else null
                Timber.i(
                    "Automatic suggestion generated elapsedMs=%d rawLength=%d current=%s parsed=%s",
                    clockMs() - generateStartedMs,
                    raw.length,
                    current,
                    suffix != null
                )
                if (!current) return
                if (suffix == null || !session.publish(ticket, suffix, clockMs())) {
                    update(State.NO_CANDIDATE)
                    return
                }
                published = true
                publishCached(snapshot, input)
                if (candidates.isEmpty()) update(State.NO_CANDIDATE)
            } finally {
                if (!published) session.invalidate(ticket)
            }
        } catch (_: CancellationException) {
            // Cancellation is an expected invalidation path; the following waiter owns replacement.
        } catch (error: Throwable) {
            val code = safeCode(error)
            if (code in TERMINAL_NATIVE_CODES) {
                latchTerminalFailure(code)
            } else if (isRequestCurrent(requestEpoch, snapshot)) {
                update(State.ERROR, code)
            }
        } finally {
            if (runningJob === kotlinx.coroutines.currentCoroutineContext()[Job]) {
                runningJob = null
            }
        }
    }

    /**
     * Runs [promptContextEnricher] off the main thread. Its failure is not fatal to the request:
     * only its error code is logged (never its message, which could otherwise leak user text),
     * and generation proceeds with the original [input].
     */
    private suspend fun enrichInput(
        input: OnDeviceSuggestionPolicy.Input
    ): OnDeviceSuggestionPolicy.Input {
        val enricher = promptContextEnricher ?: return input
        return try {
            withContext(Dispatchers.Default) { enricher(input) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Timber.w("Automatic suggestion prompt enrichment failed code=%s", error.javaClass.simpleName)
            input
        }
    }

    private fun parseStoredSuffix(input: OnDeviceSuggestionPolicy.Input, raw: String): String? {
        val sentenceInput = input.withMode(OnDeviceSuggestionPolicy.Mode.SENTENCE)
        val sentence = OnDeviceSuggestionPolicy.parseSuffix(sentenceInput, raw)
        if (sentence != null) return sentence
        val wordInput = input.withMode(OnDeviceSuggestionPolicy.Mode.WORD)
        val word = OnDeviceSuggestionPolicy.parseSuffix(wordInput, raw)
        if (word == null) {
            Timber.i(
                "Automatic suggestion rejected sentence=%s word=%s",
                OnDeviceSuggestionPolicy.rejectionReason(sentenceInput, raw),
                OnDeviceSuggestionPolicy.rejectionReason(wordInput, raw)
            )
        }
        return word
    }

    private fun publishCached(
        snapshot: OnDeviceSuggestionSession.Snapshot,
        input: OnDeviceSuggestionPolicy.Input
    ) {
        val proposal = session.cached(clockMs()) ?: return
        val current = latestKnownSnapshot(snapshot)
        if (proposal.snapshot != current || !isCurrent(current)) return
        val sentence = OnDeviceSuggestionPolicy.parseSuffix(
            input.withMode(OnDeviceSuggestionPolicy.Mode.SENTENCE),
            proposal.suffix
        )
        val word = OnDeviceSuggestionPolicy.parseSuffix(
            input.withMode(OnDeviceSuggestionPolicy.Mode.WORD),
            proposal.suffix
        )
        candidates = buildList {
            word?.let { add(Candidate(OnDeviceSuggestionPolicy.Mode.WORD, it, proposal.origin, proposal)) }
            sentence?.let { add(Candidate(OnDeviceSuggestionPolicy.Mode.SENTENCE, it, proposal.origin, proposal)) }
        }
        update(if (candidates.isEmpty()) State.NO_CANDIDATE else State.READY)
    }

    private fun isRequestCurrent(
        requestEpoch: Long,
        snapshot: OnDeviceSuggestionSession.Snapshot
    ): Boolean = enabled && !terminalFailure && epoch == requestEpoch && isCurrent(latestKnownSnapshot(snapshot))

    /**
     * A same-position observation (identical scope/text/selection, newer revision only) updates
     * [lastObservation] without touching [epoch] or the running job. Whenever [epoch] still
     * matches a request's captured epoch, [lastObservation] is therefore guaranteed to be
     * position-equal to that request's originally captured snapshot, but it may carry a newer
     * revision that the externally injected [isCurrent] requires for an exact match.
     */
    private fun latestKnownSnapshot(
        fallback: OnDeviceSuggestionSession.Snapshot
    ): OnDeviceSuggestionSession.Snapshot = lastObservation?.snapshot ?: fallback

    private fun isIdle(): Boolean = runningJob == null && candidates.isEmpty()

    private fun isSamePosition(
        a: OnDeviceSuggestionSession.Snapshot,
        b: OnDeviceSuggestionSession.Snapshot
    ): Boolean =
        a.scope == b.scope &&
            a.textBeforeCursor == b.textBeforeCursor &&
            a.selectionStart == b.selectionStart &&
            a.selectionEnd == b.selectionEnd

    private fun invalidateForInvalidInput() {
        invalidateInternal(State.ERROR, "INVALID_INPUT")
    }

    private fun invalidateInternal(
        nextState: State,
        errorCode: String? = null,
        closeBackend: Boolean = true
    ) {
        epoch += 1
        lastObservation = null
        candidates = emptyList()
        session.clear()
        if (closeBackend) {
            runningJob?.cancel()
            runningJob = null
            backend.cancel()
        }
        update(nextState, errorCode)
        if (closeBackend) scheduleClose()
    }

    private suspend fun latchTerminalFailure(code: String) {
        terminalFailure = true
        epoch += 1
        lastObservation = null
        candidates = emptyList()
        session.clear()
        val currentJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        runningJob?.takeUnless { it === currentJob }?.cancel()
        if (runningJob !== currentJob) runningJob = null
        backend.cancel()
        update(State.ERROR, code)
        scheduleClose()
    }

    private fun scheduleClose() {
        val close = scope.launch(NonCancellable) {
            serializedBackend.withLock {
                try {
                    backend.close()
                } catch (error: Throwable) {
                    terminalFailure = true
                    update(State.ERROR, safeCode(error))
                }
            }
        }
        closeJob = close
    }

    private fun update(state: State, errorCode: String? = null) {
        if (state != status.state || errorCode != status.errorCode) {
            Timber.i("Automatic suggestion state=%s errorCode=%s epoch=%d", state, errorCode, epoch)
        }
        status = Status(state, errorCode)
        onChanged()
    }

    private fun safeCode(error: Throwable): String = when (error) {
        is BackendException -> error.code.takeIf(::isSafeCode) ?: "BACKEND_FAILURE"
        else -> "BACKEND_FAILURE"
    }

    private fun isSafeCode(code: String): Boolean =
        code.length in 1..64 && code.all { it == '_' || it.isDigit() || (it in 'A'..'Z') }

    private fun OnDeviceSuggestionPolicy.Input.withMode(
        mode: OnDeviceSuggestionPolicy.Mode
    ): OnDeviceSuggestionPolicy.Input = OnDeviceSuggestionPolicy.Input(
        textBeforeCursor = textBeforeCursor,
        packageName = packageName,
        inputType = inputType,
        imeAction = imeAction,
        mode = mode,
        appCategory = appCategory,
        fieldHint = fieldHint,
        recentSentences = recentSentences,
        styleExamples = styleExamples
    )

    private data class Observation(
        val snapshot: OnDeviceSuggestionSession.Snapshot,
        val textBeforeCursor: String,
        val packageName: String,
        val inputType: Int,
        val imeAction: Int,
        val mode: OnDeviceSuggestionPolicy.Mode
    ) {
        constructor(snapshot: OnDeviceSuggestionSession.Snapshot, input: OnDeviceSuggestionPolicy.Input) : this(
            snapshot = snapshot,
            textBeforeCursor = input.textBeforeCursor,
            packageName = input.packageName,
            inputType = input.inputType,
            imeAction = input.imeAction,
            mode = input.mode
        )

        override fun toString(): String = "Observation(snapshot=$snapshot, input=<redacted>)"
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
        val TERMINAL_NATIVE_CODES = setOf(
            "NATIVE_STOP_TIMEOUT",
            "NATIVE_CLOSE_FAILED",
            "NATIVE_CANCEL_FAILED"
        )
    }
}
