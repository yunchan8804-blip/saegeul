/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

/** Provider-isolated, stable-order page accumulation for the result grid. */
internal object GifResultAccumulator {
    fun replacement(incoming: List<GifResult>): List<GifResult> {
        val providerId = incoming.firstOrNull()?.providerId ?: return emptyList()
        val isolated = incoming.filter { it.providerId == providerId }
        return if (providerId == GiphyGifProvider.PROVIDER_ID) {
            // GIPHY contract forbids client-side filtering or reordering.
            isolated
        } else {
            deduplicate(isolated)
        }
    }

    fun additions(existing: List<GifResult>, incoming: List<GifResult>): List<GifResult> {
        if (existing.isEmpty()) return replacement(incoming)
        val providerId = existing.first().providerId
        val isolated = incoming.filter { it.providerId == providerId }
        if (providerId == GiphyGifProvider.PROVIDER_ID) return isolated

        val seenIds = existing.asSequence().map(GifResult::id).toHashSet()
        val seenMedia = existing.asSequence().map(GifResult::mediaUrl).toHashSet()
        return isolated.filter { result ->
            seenIds.add(result.id) && seenMedia.add(result.mediaUrl)
        }
    }

    private fun deduplicate(results: List<GifResult>): List<GifResult> {
        val seenIds = hashSetOf<Long>()
        val seenMedia = hashSetOf<String>()
        return results.filter { result ->
            seenIds.add(result.id) && seenMedia.add(result.mediaUrl)
        }
    }
}
