/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import kotlin.text.CharCategory.CONTROL
import kotlin.text.CharCategory.FORMAT

/**
 * Keeps an automatic on-device suggestion bound to one editable field and one
 * exact editor snapshot. It owns no native-generation lease.
 */
class OnDeviceSuggestionSession {

    data class Scope(
        val packageName: String,
        val fieldId: Int,
        val editorSessionId: Long
    ) {
        override fun toString(): String =
            "Scope(packageLength=${packageName.length}, fieldId=$fieldId, editorSessionId=$editorSessionId)"
    }

    data class Snapshot(
        val scope: Scope,
        val revision: Long,
        val textBeforeCursor: String,
        val selectionStart: Int,
        val selectionEnd: Int
    ) {
        override fun toString(): String =
            "Snapshot(scope=$scope, revision=$revision, textLength=${textBeforeCursor.length}, " +
                "selectionStart=$selectionStart, selectionEnd=$selectionEnd)"
    }

    enum class Origin {
        GENERATED,
        CONTINUATION_CACHE
    }

    data class Proposal internal constructor(
        private var trackedSnapshot: Snapshot,
        val suffix: String,
        val origin: Origin
    ) {
        /** Only the session's [OnDeviceSuggestionSession.rebase] moves this, keeping the proposal's identity. */
        val snapshot: Snapshot
            get() = trackedSnapshot

        internal fun rebaseTo(snapshot: Snapshot) {
            trackedSnapshot = snapshot
        }

        override fun toString(): String =
            "Proposal(snapshot=$snapshot, suffixLength=${suffix.length}, origin=$origin)"
    }

    sealed interface Ticket

    private class OwnedTicket(var snapshot: Snapshot?) : Ticket

    private class StoredCompletion(
        var snapshot: Snapshot?,
        var suffix: String?,
        val createdAtMs: Long,
        var proposal: Proposal?
    ) {
        fun clear() {
            snapshot = null
            suffix = null
            proposal = null
        }
    }

    private val lock = Any()
    private var currentSnapshot: Snapshot? = null
    private var activeTicket: OwnedTicket? = null
    private var storedCompletion: StoredCompletion? = null

    /**
     * Records the newest exact editor state. A cache may only move forward when
     * the user has typed an exact prefix of its previously generated suffix.
     */
    fun observe(snapshot: Snapshot): Boolean = synchronized(lock) {
        if (!isValidSnapshot(snapshot)) {
            clearLocked()
            return@synchronized false
        }

        val previous = currentSnapshot
        if (previous == snapshot) {
            return@synchronized true
        }

        invalidateActiveTicketLocked()
        val previousCompletion = storedCompletion
        currentSnapshot = snapshot
        storedCompletion = continuationFor(previous, snapshot, previousCompletion)
        previousCompletion?.takeUnless { it === storedCompletion }?.clear()
        true
    }

    fun begin(nowMs: Long): Ticket? = synchronized(lock) {
        expireStoredCompletionLocked(nowMs)
        val snapshot = currentSnapshot ?: return@synchronized null
        if (activeTicket != null || storedCompletion != null) {
            return@synchronized null
        }
        OwnedTicket(snapshot).also { activeTicket = it }
    }

    /** Records a complete engine response for the currently active snapshot. */
    fun publish(ticket: Ticket, suffix: String, nowMs: Long): Boolean = synchronized(lock) {
        val ownedTicket = ticket as? OwnedTicket ?: return@synchronized false
        val snapshot = currentSnapshot
        if (ownedTicket !== activeTicket || ownedTicket.snapshot != snapshot || snapshot == null) {
            return@synchronized false
        }
        if (!isValidSuffix(suffix)) {
            invalidateActiveTicketLocked()
            return@synchronized false
        }

        invalidateActiveTicketLocked()
        val proposal = Proposal(snapshot, suffix, Origin.GENERATED)
        storedCompletion?.clear()
        storedCompletion = StoredCompletion(snapshot, suffix, nowMs, proposal)
        true
    }

    /** Cancels one in-flight request; a late [publish] for it is rejected. */
    fun invalidate(ticket: Ticket): Boolean = synchronized(lock) {
        val ownedTicket = ticket as? OwnedTicket ?: return@synchronized false
        if (ownedTicket !== activeTicket) return@synchronized false
        invalidateActiveTicketLocked()
        true
    }

    fun cached(nowMs: Long): Proposal? = synchronized(lock) {
        expireStoredCompletionLocked(nowMs)
        val completion = storedCompletion ?: return@synchronized null
        if (completion.snapshot != currentSnapshot) {
            completion.clear()
            storedCompletion = null
            return@synchronized null
        }
        completion.proposal
    }

    /** Consumes one proposal only when the editor still exactly matches it. */
    fun takeForApply(proposal: Proposal, currentSnapshot: Snapshot, nowMs: Long): String? = synchronized(lock) {
        expireStoredCompletionLocked(nowMs)
        val completion = storedCompletion ?: return@synchronized null
        if (
            completion.proposal !== proposal ||
                completion.snapshot != this.currentSnapshot ||
                proposal.snapshot != currentSnapshot ||
                currentSnapshot != this.currentSnapshot
        ) {
            return@synchronized null
        }
        val suffix = completion.suffix ?: return@synchronized null
        completion.clear()
        storedCompletion = null
        suffix
    }

    /**
     * Moves the tracked snapshot, active ticket, and any stored completion/proposal forward to
     * [snapshot] in place, without changing their identity, when [snapshot] is at the same editor
     * position (scope, text, selection) the session already tracks. Used when a newer
     * revision-only observation confirms the position the session is already generating for or
     * holding a result for, so [begin] tickets and [cached]/[takeForApply] proposals keep matching
     * the newest revision instead of a stale one. Returns false, changing nothing, when the
     * session has no snapshot yet or [snapshot] is at a different position.
     */
    fun rebase(snapshot: Snapshot): Boolean = synchronized(lock) {
        val existing = currentSnapshot ?: return@synchronized false
        if (
            existing.scope != snapshot.scope ||
                existing.textBeforeCursor != snapshot.textBeforeCursor ||
                existing.selectionStart != snapshot.selectionStart ||
                existing.selectionEnd != snapshot.selectionEnd
        ) {
            return@synchronized false
        }
        currentSnapshot = snapshot
        activeTicket?.snapshot = snapshot
        storedCompletion?.let { completion ->
            completion.snapshot = snapshot
            completion.proposal?.rebaseTo(snapshot)
        }
        true
    }

    /** Releases every private reference to an editor snapshot or generated text. */
    fun clear() = synchronized(lock) {
        clearLocked()
    }

    override fun toString(): String = synchronized(lock) {
        "OnDeviceSuggestionSession(hasSnapshot=${currentSnapshot != null}, " +
            "hasActiveTicket=${activeTicket != null}, hasStoredCompletion=${storedCompletion != null})"
    }

    private fun continuationFor(
        previous: Snapshot?,
        current: Snapshot,
        completion: StoredCompletion?
    ): StoredCompletion? {
        if (previous == null || completion == null || completion.snapshot != previous) return null
        if (previous.scope != current.scope || current.revision <= previous.revision) return null
        if (!current.textBeforeCursor.startsWith(previous.textBeforeCursor)) return null

        val appended = current.textBeforeCursor.removePrefix(previous.textBeforeCursor)
        val suffix = completion.suffix ?: return null
        if (appended.isEmpty() || !suffix.startsWith(appended)) return null

        val remaining = suffix.removePrefix(appended)
        if (!isValidSuffix(remaining)) return null
        val proposal = Proposal(current, remaining, Origin.CONTINUATION_CACHE)
        return StoredCompletion(current, remaining, completion.createdAtMs, proposal)
    }

    private fun expireStoredCompletionLocked(nowMs: Long) {
        val completion = storedCompletion ?: return
        if (nowMs < completion.createdAtMs || nowMs - completion.createdAtMs >= CACHE_TTL_MS) {
            completion.clear()
            storedCompletion = null
        }
    }

    private fun invalidateActiveTicketLocked() {
        activeTicket?.snapshot = null
        activeTicket = null
    }

    private fun clearLocked() {
        invalidateActiveTicketLocked()
        currentSnapshot = null
        storedCompletion?.clear()
        storedCompletion = null
    }

    private fun isValidSnapshot(snapshot: Snapshot): Boolean =
        snapshot.textBeforeCursor.isNotBlank() &&
            snapshot.textBeforeCursor.length <= MAX_TEXT_CHARS &&
            snapshot.selectionStart == snapshot.selectionEnd &&
            snapshot.selectionStart == snapshot.textBeforeCursor.length

    private fun isValidSuffix(suffix: String): Boolean =
        suffix.isNotBlank() &&
            suffix.length <= MAX_SUFFIX_CHARS &&
            suffix.none { it == '\r' || it == '\n' || it.category == CONTROL || it.category == FORMAT }

    private companion object {
        const val MAX_TEXT_CHARS = 2048
        const val MAX_SUFFIX_CHARS = 120
        const val CACHE_TTL_MS = 30_000L
    }
}
