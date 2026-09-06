/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import java.util.concurrent.ConcurrentHashMap

/**
 * On-Device Secure Staging Vault for User Typing DNA Accumulation.
 * Buffers PII-scrubbed sentences per application persona category.
 * When the accumulation threshold is reached, triggers background LLM/on-device
 * profiling, and safely purges raw staging buffers immediately upon knowledge compilation.
 */
class TypingDnaVault(
    private val thresholdPerCategory: Int = 15,
    private val maxCapacityPerCategory: Int = 50,
    private val onBatchReady: ((category: String, sentences: List<String>) -> Unit)? = null
) {

    companion object {
        const val CATEGORY_MESSENGER = "messenger"
        const val CATEGORY_WORK = "work"
        const val CATEGORY_GENERAL = "general"

        private val MESSENGER_PACKAGES = setOf(
            "com.kakao.talk",
            "org.telegram.messenger",
            "com.instagram.android",
            "com.facebook.orca",
            "jp.naver.line.android",
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging"
        )

        private val WORK_PACKAGES = setOf(
            "com.slack",
            "com.google.android.gm",
            "com.microsoft.office.outlook",
            "com.microsoft.teams",
            "com.jandi.android",
            "com.notion.id",
            "com.atlassian.jira",
            "com.github.android"
        )

        fun categorizePackage(packageName: String): String {
            val lower = packageName.lowercase()
            return when {
                MESSENGER_PACKAGES.any { lower.contains(it) } -> CATEGORY_MESSENGER
                WORK_PACKAGES.any { lower.contains(it) } -> CATEGORY_WORK
                lower.contains("talk") || lower.contains("chat") || lower.contains("message") -> CATEGORY_MESSENGER
                lower.contains("mail") || lower.contains("work") || lower.contains("team") -> CATEGORY_WORK
                else -> CATEGORY_GENERAL
            }
        }
    }

    // Category -> Deque of scrubbed sentences
    private val categoryBuffers = ConcurrentHashMap<String, ArrayDeque<String>>()

    /**
     * Records a committed sentence into the vault after scrubbing all PII.
     * Returns true if a batch threshold was reached and callback was dispatched.
     */
    @Synchronized
    fun recordSentence(packageName: String, sentence: String): Boolean {
        val clean = sentence.trim()
        if (clean.length < 4) return false

        // Zero-leak: Scrub PII immediately before buffering
        val scrubbed = KoreanPiiScrubber.scrub(clean)

        val category = categorizePackage(packageName)
        val deque = categoryBuffers.getOrPut(category) { ArrayDeque() }

        // Deduplicate recent consecutive identical sentences
        if (deque.lastOrNull() != scrubbed) {
            deque.addLast(scrubbed)
        }

        while (deque.size > maxCapacityPerCategory) {
            deque.removeFirst()
        }

        if (deque.size >= thresholdPerCategory) {
            val batch = deque.toList()
            onBatchReady?.invoke(category, batch)
            return true
        }

        return false
    }

    /**
     * Gets a read-only snapshot of all currently buffered sentences per category.
     */
    @Synchronized
    fun snapshot(): Map<String, List<String>> {
        return categoryBuffers.mapValues { it.value.toList() }
    }

    /**
     * Returns the total number of sentences currently waiting across all categories.
     */
    @Synchronized
    fun totalBufferedCount(): Int {
        return categoryBuffers.values.sumOf { it.size }
    }

    /**
     * Returns sentences for a specific category.
     */
    @Synchronized
    fun getSentences(category: String): List<String> {
        return categoryBuffers[category]?.toList() ?: emptyList()
    }

    /**
     * Drains all staged sentences without dispatching [onBatchReady].
     * Used by instant dashboard sync so compilation happens exactly once.
     */
    @Synchronized
    fun drain(): Map<String, List<String>> {
        val snapshot = snapshot()
        purge()
        return snapshot
    }

    /**
     * Flushes all staged sentences immediately across all categories,
     * dispatches the batch callbacks, and purges the staging buffer.
     */
    @Synchronized
    fun flushAll(): Map<String, List<String>> {
        val snapshot = snapshot()
        snapshot.forEach { (category, sentences) ->
            if (sentences.isNotEmpty()) {
                onBatchReady?.invoke(category, sentences)
            }
        }
        purge()
        return snapshot
    }

    /**
     * Zero-Knowledge Purge:
     * Irreversibly purges the raw staging buffers once knowledge has been compiled.
     */
    @Synchronized
    fun purge(category: String? = null) {
        if (category != null) {
            categoryBuffers[category]?.clear()
            categoryBuffers.remove(category)
        } else {
            categoryBuffers.values.forEach { it.clear() }
            categoryBuffers.clear()
        }
    }
}
