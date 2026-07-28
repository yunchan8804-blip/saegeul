/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dynamicphrase

import android.os.SystemClock
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.SensitivePhraseVault

data class SensitivePhraseAuthResumeResult(
    val target: DynamicPhraseEditorTarget,
    val vault: SensitivePhraseVault?
)

/**
 * One-shot, process-memory-only handoff between the IME and its authentication activity.
 *
 * Neither the authenticated cipher nor decrypted vault is parcelled or persisted. A result is
 * discarded on editor mismatch, replacement by a newer request, or timeout.
 */
internal class SensitivePhraseAuthResumeQueue<P>(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime
) {
    private data class Pending<P>(
        val id: Long,
        val target: DynamicPhraseEditorTarget,
        val payload: P,
        val createdAt: Long,
        val claimed: Boolean = false
    )

    private data class Completed(
        val id: Long,
        val result: SensitivePhraseAuthResumeResult,
        val createdAt: Long
    )

    private var pending: Pending<P>? = null
    private var completed: Completed? = null

    @Synchronized
    fun begin(id: Long, target: DynamicPhraseEditorTarget, payload: P) {
        pending = Pending(id, target, payload, elapsedRealtime())
        completed = null
    }

    @Synchronized
    fun claim(id: Long): P? {
        expireStale()
        val request = pending?.takeIf { it.id == id && !it.claimed } ?: return null
        pending = request.copy(claimed = true)
        return request.payload
    }

    @Synchronized
    fun complete(id: Long, vault: SensitivePhraseVault?): Boolean {
        expireStale()
        val request = pending?.takeIf { it.id == id && it.claimed } ?: return false
        pending = null
        completed = Completed(
            id = id,
            result = SensitivePhraseAuthResumeResult(request.target, vault),
            createdAt = request.createdAt
        )
        return true
    }

    @Synchronized
    fun cancel(id: Long) {
        if (pending?.id == id) pending = null
        if (completed?.id == id) completed = null
    }

    @Synchronized
    fun consumeForEditor(
        packageName: String?,
        fieldId: Int,
        inputType: Int
    ): SensitivePhraseAuthResumeResult? {
        expireStale()
        val result = completed?.result ?: return null
        completed = null
        return result.takeIf {
            packageName == it.target.packageName &&
                fieldId == it.target.fieldId &&
                inputType == it.target.inputType
        }
    }

    private fun expireStale() {
        val now = elapsedRealtime()
        if (pending?.let { now - it.createdAt >= ttlMillis } == true) pending = null
        if (completed?.let { now - it.createdAt >= ttlMillis } == true) completed = null
    }

    private companion object {
        const val DEFAULT_TTL_MILLIS = 60_000L
    }
}
