/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.search

import java.io.BufferedReader

data class KoreanDictionaryEntry(
    val word: String,
    val partOfSpeech: String,
    val definitions: List<String>
) {
    val sourceUrl: String
        get() = "https://ko.wiktionary.org/wiki/" + java.net.URLEncoder.encode(word, "UTF-8")
            .replace("+", "%20")
}

/** Pure extraction boundary for an explicitly opened, local-only dictionary lookup. */
object KoreanDictionaryQuery {
    const val MAX_LENGTH = 40
    private val headword = Regex("^[가-힣]+(?:[- ][가-힣]+)*$")
    private val cursorWord = Regex("[가-힣]+(?:-[가-힣]+)*$")
    private const val TRAILING_PUNCTUATION = ".,!?…:;'\"()[]{}<>"

    fun normalize(value: String?): String? {
        val normalized = value.orEmpty().trim().replace(Regex("\\s+"), " ")
        return normalized.takeIf {
            it.length in 1..MAX_LENGTH && headword.matches(it)
        }
    }

    fun extract(selectedText: String?, beforeCursor: String?): String? =
        normalize(selectedText) ?: cursorWord.find(
            beforeCursor.orEmpty().trimEnd().trimEnd { it in TRAILING_PUNCTUATION }
        )
            ?.value
            ?.let(::normalize)
}

class KoreanDictionaryIndex private constructor(
    private val entriesByWord: Map<String, List<KoreanDictionaryEntry>>,
    private val sortedWords: List<String>
) {
    fun lookup(query: String?, limit: Int = DEFAULT_LIMIT): List<KoreanDictionaryEntry> {
        val normalized = KoreanDictionaryQuery.normalize(query) ?: return emptyList()
        if (limit <= 0) return emptyList()
        entriesByWord[normalized]?.let { return it.take(limit) }

        val start = sortedWords.binarySearch(normalized).let { index ->
            if (index >= 0) index else -index - 1
        }
        val results = ArrayList<KoreanDictionaryEntry>(limit)
        for (index in start until sortedWords.size) {
            val word = sortedWords[index]
            if (!word.startsWith(normalized)) break
            for (entry in entriesByWord.getValue(word)) {
                results += entry
                if (results.size == limit) return results
            }
        }
        return results
    }

    companion object {
        const val DEFAULT_LIMIT = 24
        private const val MAX_LINES = 100_000
        private const val MAX_LINE_LENGTH = 8_192
        private const val MAX_DEFINITIONS = 4

        fun read(reader: BufferedReader): KoreanDictionaryIndex {
            val entries = linkedMapOf<String, MutableList<KoreanDictionaryEntry>>()
            var lineCount = 0
            while (true) {
                val line = reader.readLine() ?: break
                lineCount += 1
                if (lineCount > MAX_LINES) break
                if (line.isBlank() || line.startsWith('#') || line.length > MAX_LINE_LENGTH) continue
                val fields = line.split('\t')
                if (fields.size < 3) continue
                val word = KoreanDictionaryQuery.normalize(fields[0]) ?: continue
                val definitions = fields.drop(2)
                    .asSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .take(MAX_DEFINITIONS)
                    .toList()
                if (definitions.isEmpty()) continue
                val entry = KoreanDictionaryEntry(
                    word = word,
                    partOfSpeech = fields[1].trim(),
                    definitions = definitions
                )
                entries.getOrPut(word, ::mutableListOf).add(entry)
            }
            return KoreanDictionaryIndex(entries, entries.keys.sorted())
        }
    }
}
