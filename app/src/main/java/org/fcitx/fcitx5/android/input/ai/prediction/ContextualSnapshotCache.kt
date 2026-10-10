/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.prediction

/**
 * What the current editor state was when a contextual snapshot was built. Two snapshots with the
 * same key come from the same text, composition, selection, privacy verdict and prediction inputs,
 * so the later request can reuse the earlier answer instead of reading the editor again.
 */
internal data class ContextualSnapshotKey(
    val editorStateRevision: Long,
    val predictionEpoch: Long,
    val sentencePackRevision: Long,
    val generatedSentenceRevision: Long,
    val allowsTextInspection: Boolean,
    val selectionStart: Int,
    val selectionEnd: Int,
    val limit: Int
)

/**
 * Holds the one contextual snapshot built for the current [ContextualSnapshotKey], so the several
 * readers of a single candidate refresh share one computation. Used from the main thread only.
 */
internal class ContextualSnapshotCache<V : Any> {

    private var cachedKey: ContextualSnapshotKey? = null
    private var cachedValue: V? = null

    /**
     * The snapshot for [key]: the held one when its key matches, otherwise the result of [compute].
     * A computed value is held only when [isReusable] accepts it, so a read that was incomplete
     * is computed again by the next request instead of being replayed.
     */
    inline fun resolve(
        key: ContextualSnapshotKey,
        isReusable: (V) -> Boolean,
        compute: () -> V
    ): V {
        lookup(key)?.let { return it }
        val value = compute()
        store(key, value.takeIf(isReusable))
        return value
    }

    /** Forgets the held snapshot, for when what it was built from changed without a key change. */
    fun invalidate() {
        cachedKey = null
        cachedValue = null
    }

    fun lookup(key: ContextualSnapshotKey): V? = cachedValue.takeIf { cachedKey == key }

    fun store(key: ContextualSnapshotKey, value: V?) {
        cachedKey = key.takeIf { value != null }
        cachedValue = value
    }
}
