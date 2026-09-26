/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

class OnDeviceContextCompletionGate {

    data class Snapshot(
        val editorSessionId: Long,
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int
    ) {
        override fun toString(): String =
            "Snapshot(editorSessionId=$editorSessionId, selectionStart=$selectionStart, " +
                "selectionEnd=$selectionEnd, textLength=${text.length})"
    }

    sealed interface Ticket

    private class OwnedTicket : Ticket

    private enum class State {
        RUNNING,
        READY,
        INVALIDATED
    }

    private class Run(
        var snapshot: Snapshot?,
        var completion: String? = null,
        var state: State = State.RUNNING,
        var nativeFinished: Boolean = false
    )

    private val lock = Any()
    private var activeTicket: Ticket? = null
    private var activeRun: Run? = null

    fun begin(snapshot: Snapshot): Ticket? = synchronized(lock) {
        if (activeRun != null || !isValidSnapshot(snapshot)) {
            return@synchronized null
        }
        OwnedTicket().also { ticket ->
            activeTicket = ticket
            activeRun = Run(snapshot)
        }
    }

    fun publish(ticket: Ticket, currentSnapshot: Snapshot, completion: String): Boolean = synchronized(lock) {
        val run = runFor(ticket) ?: return@synchronized false
        if (run.state != State.RUNNING) {
            return@synchronized false
        }
        if (run.snapshot != currentSnapshot || !isValidCompletion(completion)) {
            invalidateRun(run)
            return@synchronized false
        }
        run.completion = completion
        run.state = State.READY
        true
    }

    fun invalidate(ticket: Ticket): Boolean = synchronized(lock) {
        val run = runFor(ticket) ?: return@synchronized false
        invalidateRun(run)
        if (run.nativeFinished) {
            clearSlot()
        }
        true
    }

    fun nativeStopped(ticket: Ticket): Boolean = synchronized(lock) {
        val run = runFor(ticket) ?: return@synchronized false
        run.nativeFinished = true
        if (run.state != State.READY) {
            clearSlot()
        }
        true
    }

    fun takeForApply(ticket: Ticket, currentSnapshot: Snapshot): String? = synchronized(lock) {
        val run = runFor(ticket) ?: return@synchronized null
        if (run.state != State.READY || !run.nativeFinished) {
            return@synchronized null
        }
        if (run.snapshot != currentSnapshot) {
            invalidateRun(run)
            clearSlot()
            return@synchronized null
        }
        val completion = checkNotNull(run.completion)
        clearSlot()
        completion
    }

    private fun runFor(ticket: Ticket): Run? =
        if (activeTicket === ticket) activeRun else null

    private fun invalidateRun(run: Run) {
        run.snapshot = null
        run.completion = null
        run.state = State.INVALIDATED
    }

    private fun clearSlot() {
        activeRun?.let(::invalidateRun)
        activeTicket = null
        activeRun = null
    }

    private fun isValidSnapshot(snapshot: Snapshot): Boolean =
        snapshot.text.isNotBlank() &&
        snapshot.selectionStart >= 0 &&
            snapshot.selectionStart <= snapshot.selectionEnd &&
            snapshot.selectionEnd <= snapshot.text.length &&
            snapshot.text.length <= MAX_CONTEXT_CHARS

    private fun isValidCompletion(completion: String): Boolean =
        completion.isNotBlank() && completion.length <= MAX_CONTEXT_CHARS

    private companion object {
        const val MAX_CONTEXT_CHARS = 2048
    }
}
