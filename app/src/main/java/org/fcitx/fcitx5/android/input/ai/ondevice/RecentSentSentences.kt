/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.fcitx.fcitx5.android.input.ai.KoreanPiiScrubber

/**
 * In-memory-only record of sentences this user recently sent, per app package, used to enrich the
 * automatic on-device suggestion prompt with "what I just said in this app". Nothing here is ever
 * written to disk: it lives only for the process lifetime, and each entry also expires quickly on
 * its own so a long-lived keyboard process does not keep resurfacing stale context.
 */
class RecentSentSentences(
    private val clockMs: () -> Long = System::currentTimeMillis
) {

    private data class Entry(val sentence: String, val recordedAtMs: Long)

    private val byPackage = HashMap<String, ArrayDeque<Entry>>()

    /** Records [sentence] for [packageName]. Blank or PII-bearing sentences are dropped, not stored. */
    @Synchronized
    fun record(packageName: String, sentence: String) {
        val trimmed = sentence.trim()
        if (trimmed.isEmpty() || KoreanPiiScrubber.containsPii(trimmed)) return
        val deque = byPackage.getOrPut(packageName) { ArrayDeque() }
        deque.addLast(Entry(trimmed, clockMs()))
        while (deque.size > MAX_PER_PACKAGE) deque.removeFirst()
    }

    /** Returns up to [limit] recent, non-expired sentences for [packageName], newest first. */
    @Synchronized
    fun recent(packageName: String, limit: Int = 3): List<String> {
        val deque = byPackage[packageName] ?: return emptyList()
        val now = clockMs()
        while (deque.isNotEmpty() && now - deque.first().recordedAtMs > EXPIRY_MS) {
            deque.removeFirst()
        }
        if (deque.isEmpty()) {
            byPackage.remove(packageName)
            return emptyList()
        }
        return deque.asReversed().asSequence().map { it.sentence }.take(limit).toList()
    }

    /** Drops everything in memory. Called wherever the user clears their personal data. */
    @Synchronized
    fun clear() {
        byPackage.clear()
    }

    private companion object {
        const val MAX_PER_PACKAGE = 5
        const val EXPIRY_MS = 10L * 60 * 1000
    }
}
