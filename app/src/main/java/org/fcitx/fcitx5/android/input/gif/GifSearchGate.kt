/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

sealed interface GifSearchOutcome {
    data object Blocked : GifSearchOutcome
    data object SafeSearchBlocked : GifSearchOutcome
    data class Results(
        val items: List<GifResult>,
        val hasNext: Boolean = false
    ) : GifSearchOutcome
}

/** Keeps the privacy decision on the call boundary so a blocked editor cannot reach a provider. */
class GifSearchGate(private val provider: GifProvider) {
    suspend fun search(
        allowed: Boolean,
        query: String,
        page: Int = 1,
        limit: Int = 24
    ): GifSearchOutcome = when {
        !allowed -> GifSearchOutcome.Blocked
        !GifSafeSearchPolicy.isAllowedQuery(query) -> GifSearchOutcome.SafeSearchBlocked
        provider is PagedGifProvider -> {
            val result = provider.searchPage(query, page.coerceAtLeast(1), limit)
            GifSearchOutcome.Results(result.items, result.hasNext)
        }
        page <= 1 -> GifSearchOutcome.Results(provider.search(query, limit))
        else -> GifSearchOutcome.Results(emptyList())
    }
}
