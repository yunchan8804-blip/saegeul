/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import android.os.Handler
import android.os.Looper

enum class AiReplySourceOrigin {
    Shared,
    Clipboard
}

data class AiReplySource(
    val text: String,
    val origin: AiReplySourceOrigin,
    val receivedAtMillis: Long
)

/**
 * Process-memory-only handoff for text explicitly sent through Android Sharesheet.
 *
 * A blocked editor does not consume the pending item. Process death and TTL expiry intentionally
 * discard it; there is no SharedPreferences, file, backup, analytics, or log fallback.
 */
internal class AiPendingReplySourceStore(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS
) {
    private var pending: AiReplySource? = null

    @Synchronized
    fun offerShared(
        action: String?,
        mimeType: String?,
        text: CharSequence?,
        nowMillis: Long
    ): Boolean {
        if (action != ACTION_SEND || !mimeType.equals(TEXT_PLAIN, ignoreCase = true)) return false
        val normalized = AiReplySourcePolicy.normalize(text) ?: return false
        pending = AiReplySource(normalized, AiReplySourceOrigin.Shared, nowMillis)
        return true
    }

    @Synchronized
    fun consumeIfAllowed(allowed: Boolean, nowMillis: Long): AiReplySource? {
        val source = pending ?: return null
        if (nowMillis < source.receivedAtMillis || nowMillis - source.receivedAtMillis > ttlMillis) {
            pending = null
            return null
        }
        if (!allowed) return null
        pending = null
        return source
    }

    @Synchronized
    fun clear() {
        pending = null
    }

    companion object {
        const val ACTION_SEND = "android.intent.action.SEND"
        const val TEXT_PLAIN = "text/plain"
        const val DEFAULT_TTL_MILLIS = 5L * 60L * 1000L
    }
}

/** Single process handoff shared by the minimal ACTION_SEND activity and the IME window. */
object AiReplyIntake {
    private val shared = AiPendingReplySourceStore()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val expire = Runnable { shared.clear() }

    fun receiveSharedText(
        action: String?,
        mimeType: String?,
        text: CharSequence?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val accepted = shared.offerShared(action, mimeType, text, nowMillis)
        if (accepted) {
            mainHandler.removeCallbacks(expire)
            mainHandler.postDelayed(expire, AiPendingReplySourceStore.DEFAULT_TTL_MILLIS)
        }
        return accepted
    }

    fun consumeSharedText(
        allowed: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): AiReplySource? = shared.consumeIfAllowed(allowed, nowMillis).also { source ->
        if (source != null) mainHandler.removeCallbacks(expire)
    }

    internal fun clear() {
        mainHandler.removeCallbacks(expire)
        shared.clear()
    }
}

data class AiClipboardCandidate(
    val id: Int,
    val text: String,
    val sensitive: Boolean,
    val deleted: Boolean
)

/** Double-checks the database query contract and requires selection by a concrete row id. */
object AiClipboardIntakePolicy {
    const val MAX_CHOICES = 12

    fun choices(entries: List<AiClipboardCandidate>): List<AiClipboardCandidate> = entries
        .asSequence()
        .filter { !it.sensitive && !it.deleted }
        .filter { AiReplySourcePolicy.normalize(it.text) != null }
        .distinctBy(AiClipboardCandidate::id)
        .take(MAX_CHOICES)
        .toList()

    fun select(
        choices: List<AiClipboardCandidate>,
        selectedId: Int,
        allowed: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): AiReplySource? {
        if (!allowed) return null
        val selected = choices.firstOrNull { it.id == selectedId } ?: return null
        if (selected.sensitive || selected.deleted) return null
        val normalized = AiReplySourcePolicy.normalize(selected.text) ?: return null
        return AiReplySource(normalized, AiReplySourceOrigin.Clipboard, nowMillis)
    }
}

object AiReplySourcePolicy {
    fun normalize(text: CharSequence?): String? = text
        ?.toString()
        ?.take(AiTextSource.MAX_CHARACTERS)
        ?.takeIf(String::isNotBlank)

    /** External source text is context, never editor text to replace. Bind only to a plain cursor. */
    fun bindToEditor(source: AiReplySource, editor: AiEditorTarget): AiInputSnapshot? {
        if (editor.selectionStart < 0 || editor.selectionStart != editor.selectionEnd) return null
        return AiInputSnapshot(editor, source.text, AiSourceKind.BeforeCursor)
    }
}

/** Claims one reviewed editor mutation; only a successful undo or a new review resets the claim. */
internal class AiExactlyOnceApplyGate {
    private var consumed = false

    @Synchronized
    fun claim(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }

    @Synchronized
    fun resetForReviewedResult() {
        consumed = false
    }

    @Synchronized
    fun resetAfterUndo() {
        consumed = false
    }
}
