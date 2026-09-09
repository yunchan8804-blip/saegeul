/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.sentencepack

import org.fcitx.fcitx5.android.input.ai.ContextualAppend

enum class MatchEvidence {
    PREFIX,
    CONTEXT_SUFFIX,
    LAST_WORD
}

data class SentencePackMatch(
    val suffix: String,
    val joinMode: ContextualAppend.JoinMode,
    val matchedTokens: Int,
    val evidence: MatchEvidence = MatchEvidence.PREFIX
)

internal class SentencePackIndex private constructor(
    private val rootEntries: List<Entry>,
    private val suffixEntries: List<Entry>,
    val sentenceCount: Int
) {
    fun complete(rawContext: String, limit: Int): List<SentencePackMatch> {
        if (limit <= 0 || rawContext.isBlank() || !SentencePackText.isQuerySafe(rawContext)) return emptyList()
        val tail = rawContext.substring(lastSentenceStart(rawContext))
        if (tail.isBlank() || tail.trimEnd().lastOrNull().let(::isSentenceTerminal)) return emptyList()
        val endsAtBoundary = tail.last().isWhitespace()
        val normalized = SentencePackText.normalize(tail)
        if (normalized.isBlank()) return emptyList()

        val rootMatches = lookup(rootEntries, normalized, endsAtBoundary, limit, MatchEvidence.PREFIX)
        if (rootMatches.isNotEmpty()) return rootMatches

        val tokens = normalized.split(' ')
        val maximum = minOf(tokens.size, MAX_TOKENS)
        for (count in maximum downTo 2) {
            val query = tokens.takeLast(count).joinToString(" ")
            val matches = lookup(suffixEntries, query, endsAtBoundary, limit, MatchEvidence.CONTEXT_SUFFIX)
            if (matches.isNotEmpty()) return matches
        }
        if (isEligibleLastWord(tokens.last())) {
            return lookup(
                suffixEntries,
                tokens.last(),
                endsAtBoundary = true,
                limit = limit,
                evidence = MatchEvidence.LAST_WORD,
                maxSuffixTokens = LAST_WORD_MAX_SUFFIX_TOKENS,
                requiresTerminalSuffix = true
            )
        }
        return emptyList()
    }

    private fun lookup(
        entries: List<Entry>,
        query: String,
        endsAtBoundary: Boolean,
        limit: Int,
        evidence: MatchEvidence,
        maxSuffixTokens: Int? = null,
        requiresTerminalSuffix: Boolean = false
    ): List<SentencePackMatch> {
        val start = lowerBound(entries, query)
        if (start == entries.size) return emptyList()
        val matchedTokens = query.split(' ').size
        val results = LinkedHashMap<Pair<String, ContextualAppend.JoinMode>, SentencePackMatch>()
        for (index in start until entries.size) {
            val entry = entries[index]
            if (!entry.text.startsWith(query)) break
            val remaining = entry.text.removePrefix(query)
            if (remaining.isEmpty()) continue
            if (endsAtBoundary && !remaining.first().isWhitespace()) continue
            val suffix = remaining.trimStart()
            if (suffix.isBlank()) continue
            val joinMode = if (remaining.first().isWhitespace()) {
                ContextualAppend.JoinMode.NEXT_WORD
            } else {
                ContextualAppend.JoinMode.ATTACH
            }
            if (endsAtBoundary && joinMode == ContextualAppend.JoinMode.ATTACH) continue
            if (maxSuffixTokens != null && suffix.split(' ').size > maxSuffixTokens) continue
            if (requiresTerminalSuffix && !isSentenceTerminal(suffix.lastOrNull())) continue
            results.putIfAbsent(
                suffix to joinMode,
                SentencePackMatch(suffix, joinMode, matchedTokens, evidence)
            )
            if (results.size == limit) break
        }
        return results.values.toList()
    }

    private fun isEligibleLastWord(token: String): Boolean = token.length >= 2 &&
        token !in excludedLastWords && token.all { it in '가'..'힣' }

    private fun lastSentenceStart(context: String): Int {
        val index = context.indexOfLast { isSentenceTerminal(it) || it == '\n' || it == '\r' }
        return index + 1
    }

    private fun lowerBound(entries: List<Entry>, key: String): Int {
        var low = 0
        var high = entries.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (entries[middle].text < key) low = middle + 1 else high = middle
        }
        return low
    }

    private data class Entry(val text: String)

    companion object {
        private const val MAX_TOKENS = 12
        private const val LAST_WORD_MAX_SUFFIX_TOKENS = 4
        private val excludedLastWords = setOf(
            "것은", "것을", "것이", "있는", "없는", "하는", "되는", "그리고", "하지만", "그래서", "정말", "너무", "부탁"
        )

        fun build(sentences: Collection<String>): SentencePackIndex {
            val normalized = sentences.mapNotNull(SentencePackText::normalizeAccepted).distinct().sorted()
            val roots = normalized.map(::Entry)
            val suffixes = buildList {
                normalized.forEach { sentence ->
                    val tokens = sentence.split(' ')
                    for (start in 0 until tokens.lastIndex) {
                        add(Entry(tokens.drop(start).joinToString(" ")))
                    }
                }
            }.distinctBy(Entry::text).sortedBy(Entry::text)
            return SentencePackIndex(roots, suffixes, normalized.size)
        }

        private fun isSentenceTerminal(character: Char?): Boolean = character == '.' || character == '?' || character == '!'
    }
}
