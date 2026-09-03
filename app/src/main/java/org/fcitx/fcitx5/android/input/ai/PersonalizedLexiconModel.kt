/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import java.util.LinkedHashMap

data class TransitionKey(
    val prevContext: String,
    val packageName: String
)

data class WordCandidate(
    val word: String,
    var frequency: Int,
    var lastUsedTimestamp: Long = System.currentTimeMillis()
)

/**
 * On-Device Personalized N-Gram Transition Matrix and Frequency Lexicon.
 * Automatically adapts to user phrasing styles per application context with LRU capacity protection.
 */
class PersonalizedLexiconModel(private val maxCapacity: Int = 1000) {

    private val transitions = object : LinkedHashMap<TransitionKey, MutableList<WordCandidate>>(maxCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TransitionKey, MutableList<WordCandidate>>?): Boolean {
            return size > maxCapacity
        }
    }

    private val globalFrequencies = object : LinkedHashMap<String, Int>(maxCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean {
            return size > maxCapacity
        }
    }

    fun size(): Int = transitions.size

    fun recordTransition(prevContext: String, nextWord: String, packageName: String) {
        val cleanPrev = prevContext.trim().lowercase()
        val cleanNext = nextWord.trim()
        if (cleanNext.isBlank()) return

        val key = TransitionKey(cleanPrev, packageName)
        val list = transitions.getOrPut(key) { mutableListOf() }
        val candidate = list.find { it.word == cleanNext }

        if (candidate != null) {
            candidate.frequency++
            candidate.lastUsedTimestamp = System.currentTimeMillis()
        } else {
            list.add(WordCandidate(cleanNext, frequency = 1))
        }

        globalFrequencies[cleanNext] = (globalFrequencies[cleanNext] ?: 0) + 1
    }

    fun getTransitions(prevContext: String, packageName: String): List<WordCandidate> {
        val cleanPrev = prevContext.trim().lowercase()
        val specific = transitions[TransitionKey(cleanPrev, packageName)]
        if (!specific.isNullOrEmpty()) {
            return specific.sortedByDescending { it.frequency }
        }

        // Fallback to app-agnostic transition
        val fallback = transitions[TransitionKey(cleanPrev, "*")]
        if (!fallback.isNullOrEmpty()) {
            return fallback.sortedByDescending { it.frequency }
        }

        return emptyList()
    }

    fun getFrequentWords(): List<Pair<String, Int>> {
        return globalFrequencies.entries.map { it.key to it.value }.sortedByDescending { it.second }
    }

    fun clear() {
        transitions.clear()
        globalFrequencies.clear()
    }
}
