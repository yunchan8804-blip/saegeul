/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.prediction.ContextualSnapshotCache
import org.fcitx.fcitx5.android.input.ai.prediction.ContextualSnapshotKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ContextualSnapshotCacheTest {

    private val baseKey = ContextualSnapshotKey(
        editorStateRevision = 7L,
        predictionEpoch = 3L,
        sentencePackRevision = 2L,
        generatedSentenceRevision = 5L,
        allowsTextInspection = true,
        selectionStart = 4,
        selectionEnd = 4,
        limit = 10
    )

    private class Provider(private val results: List<String>) {
        var reads = 0
            private set

        /** Stands for reading the editor text and the preedit, which is what a hit must avoid. */
        fun read(): String = results[reads++]
    }

    private fun ContextualSnapshotCache<String>.resolve(key: ContextualSnapshotKey, provider: Provider): String =
        resolve(key, isReusable = { true }) { provider.read() }

    @Test
    fun `the same key reuses the snapshot without reading the editor again`() {
        val cache = ContextualSnapshotCache<String>()
        val provider = Provider(listOf("first", "second"))

        val first = cache.resolve(baseKey, provider)
        val second = cache.resolve(baseKey.copy(), provider)

        assertEquals("first", first)
        assertSame(first, second)
        assertEquals(1, provider.reads)
    }

    @Test
    fun `a new editor revision reads the editor again`() {
        val cache = ContextualSnapshotCache<String>()
        val provider = Provider(listOf("first", "second"))

        cache.resolve(baseKey, provider)
        val afterEdit = cache.resolve(baseKey.copy(editorStateRevision = 8L), provider)

        assertEquals("second", afterEdit)
        assertEquals(2, provider.reads)
    }

    @Test
    fun `a changed text inspection verdict misses`() {
        val cache = ContextualSnapshotCache<String>()
        val provider = Provider(listOf("allowed", "blocked"))

        cache.resolve(baseKey, provider)
        val blocked = cache.resolve(baseKey.copy(allowsTextInspection = false), provider)

        assertEquals("blocked", blocked)
        assertEquals(2, provider.reads)
    }

    @Test
    fun `every other part of the key also misses`() {
        val changedKeys = listOf(
            baseKey.copy(predictionEpoch = 4L),
            baseKey.copy(sentencePackRevision = 3L),
            baseKey.copy(generatedSentenceRevision = 6L),
            baseKey.copy(selectionStart = 5, selectionEnd = 5),
            baseKey.copy(limit = 4)
        )
        changedKeys.forEach { changed ->
            val cache = ContextualSnapshotCache<String>()
            val provider = Provider(listOf("first", "second"))

            cache.resolve(baseKey, provider)
            cache.resolve(changed, provider)

            assertEquals("$changed", 2, provider.reads)
        }
    }

    @Test
    fun `the held snapshot is replaced by the newest one`() {
        val cache = ContextualSnapshotCache<String>()
        val provider = Provider(listOf("first", "second"))
        val newer = baseKey.copy(editorStateRevision = 8L)

        cache.resolve(baseKey, provider)
        cache.resolve(newer, provider)

        assertNull(cache.lookup(baseKey))
        assertEquals("second", cache.lookup(newer))
    }

    @Test
    fun `an incomplete read is computed again on the next request`() {
        val cache = ContextualSnapshotCache<String>()
        val provider = Provider(listOf("incomplete", "complete"))

        val first = cache.resolve(baseKey, isReusable = { it != "incomplete" }) { provider.read() }
        val second = cache.resolve(baseKey, isReusable = { it != "incomplete" }) { provider.read() }
        val third = cache.resolve(baseKey, isReusable = { it != "incomplete" }) { provider.read() }

        assertEquals("incomplete", first)
        assertEquals("complete", second)
        assertEquals("complete", third)
        assertEquals(2, provider.reads)
    }

    @Test
    fun `invalidate forgets the snapshot for the same key`() {
        val cache = ContextualSnapshotCache<String>()
        val provider = Provider(listOf("first", "second"))

        cache.resolve(baseKey, provider)
        cache.invalidate()
        val after = cache.resolve(baseKey, provider)

        assertEquals("second", after)
        assertEquals(2, provider.reads)
    }
}
