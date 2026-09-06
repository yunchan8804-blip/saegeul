/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.vault.PlainVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    private val stagingFile: File? = null,
    onBatchReady: ((category: String, sentences: List<String>) -> Unit)? = null,
    private val cipher: VaultCipher = PlainVaultCipher
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

    @Volatile
    private var batchReadyCallback: ((category: String, sentences: List<String>) -> Unit)? = onBatchReady

    private val vaultFile: VaultFile? = stagingFile?.let { VaultFile(it, cipher, VaultFile.aadFor(it.name)) }

    init {
        rehydrateFromStaging()
    }

    fun setOnBatchReady(callback: ((category: String, sentences: List<String>) -> Unit)?) {
        batchReadyCallback = callback
    }

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

        persistStaging()

        if (deque.size >= thresholdPerCategory) {
            val batch = deque.toList()
            batchReadyCallback?.invoke(category, batch)
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
                batchReadyCallback?.invoke(category, sentences)
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
        persistStaging()
    }

    private fun persistStaging() {
        val vf = vaultFile ?: return
        runCatching {
            val root = JSONObject()
            categoryBuffers.forEach { (category, deque) ->
                val arr = JSONArray()
                deque.forEach { arr.put(it) }
                root.put(category, arr)
            }
            vf.writeText(root.toString())
        }
    }

    private fun rehydrateFromStaging() {
        val vf = vaultFile ?: return
        if (!vf.exists()) return
        runCatching {
            vf.migrateIfLegacy()
            val raw = vf.readText() ?: return
            if (raw.isBlank()) return
            val root = JSONObject(raw)
            val keys = root.keys()
            while (keys.hasNext()) {
                val category = keys.next()
                val arr = root.optJSONArray(category) ?: continue
                val deque = ArrayDeque<String>()
                for (i in 0 until arr.length()) {
                    val sentence = arr.optString(i, "").trim()
                    if (sentence.length >= 4) {
                        deque.addLast(sentence)
                    }
                }
                if (deque.isNotEmpty()) {
                    categoryBuffers[category] = deque
                }
            }
        }
    }
}
