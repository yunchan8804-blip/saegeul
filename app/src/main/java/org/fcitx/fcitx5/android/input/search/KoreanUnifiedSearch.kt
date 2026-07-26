/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.search

object KoreanUnifiedSearch {
    const val DEFAULT_LIMIT = 60

    fun search(
        query: String,
        entries: List<KoreanSearchEntry>,
        limit: Int = DEFAULT_LIMIT
    ): List<KoreanSearchResult> {
        val normalizedQuery = KoreanInitials.compact(query)
        if (normalizedQuery.isEmpty() || limit <= 0) return emptyList()
        val initialQuery = KoreanInitials.isInitialQuery(normalizedQuery)

        return entries.asSequence()
            .filterNot(KoreanSearchEntry::sensitive)
            .mapIndexedNotNull { index, entry ->
                val score = entry.searchTerms
                    .mapNotNull { term -> matchScore(normalizedQuery, term, initialQuery) }
                    .minOrNull()
                    ?: return@mapIndexedNotNull null
                IndexedResult(KoreanSearchResult(entry, score), index)
            }
            .distinctBy {
                val entry = it.result.entry
                if (entry.source == KoreanSearchSource.Emotion ||
                    entry.source == KoreanSearchSource.Emoji
                ) {
                    "expression:${entry.commitText}"
                } else {
                    "${entry.source}:${entry.commitText}"
                }
            }
            .sortedWith(
                compareBy<IndexedResult> { it.result.score }
                    .thenBy { it.result.entry.source.rank }
                    .thenBy { it.index }
            )
            .take(limit)
            .map(IndexedResult::result)
            .toList()
    }

    private fun matchScore(query: String, term: String, initialQuery: Boolean): Int? {
        val candidate = if (initialQuery) {
            KoreanInitials.extract(term)
        } else {
            KoreanInitials.compact(term)
        }
        if (candidate.isEmpty()) return null
        return when {
            candidate == query -> 0
            candidate.startsWith(query) -> 10
            candidate.contains(query) -> 20
            else -> null
        }
    }

    private data class IndexedResult(
        val result: KoreanSearchResult,
        val index: Int
    )
}
