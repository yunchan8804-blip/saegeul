/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.persona.PersonaRegistry
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
 * When the accumulation threshold is reached, triggers background on-device profiling.
 * Pending sentences are removed only after their processor returns normally.
 */
class TypingDnaVault(
    val thresholdPerCategory: Int = 10,
    private val maxCapacityPerCategory: Int = 50,
    private val stagingFile: File? = null,
    onBatchReady: ((category: String, sentences: List<String>) -> Unit)? = null,
    private val cipher: VaultCipher = PlainVaultCipher,
    private val diagnostics: CollectionDiagnostics? = null
) {

    companion object {
        // Aliases kept for source compatibility; the registry id is the source of truth.
        const val CATEGORY_MESSENGER = "messenger"
        const val CATEGORY_WORK = "work"
        const val CATEGORY_GENERAL = "general"

        /** [personaOverride] is a per-app persona chosen by the user; null defers to auto-detection. */
        fun categorizePackage(packageName: String, personaOverride: String? = null): String =
            PersonaRegistry.classify(packageName, personaOverride)
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
     * [personaOverride] is a per-app persona chosen by the user; null defers to auto-detection.
     * Returns true if a batch threshold was reached and callback was dispatched.
     */
    @Synchronized
    fun recordSentence(packageName: String, sentence: String, personaOverride: String? = null): Boolean {
        val clean = sentence.trim()
        if (clean.length < 4) {
            diagnostics?.dropped("short")
            return false
        }

        // Zero-leak: Scrub PII immediately before buffering
        val scrubbed = KoreanPiiScrubber.scrub(clean)

        val category = categorizePackage(packageName, personaOverride)
        val deque = categoryBuffers.getOrPut(category) { ArrayDeque() }

        // Deduplicate recent consecutive identical sentences
        if (deque.lastOrNull() != scrubbed) {
            deque.addLast(scrubbed)
            diagnostics?.emitted(category, scrubbed.length)
        } else {
            diagnostics?.dropped("duplicate")
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
     * Returns the number of sentences currently waiting per category. Categories with an
     * empty (or absent) buffer are omitted.
     */
    @Synchronized
    fun pendingByCategory(): Map<String, Int> {
        return categoryBuffers
            .filterValues { it.isNotEmpty() }
            .mapValues { it.value.size }
    }

    /**
     * Returns sentences for a specific category.
     */
    @Synchronized
    fun getSentences(category: String): List<String> {
        return categoryBuffers[category]?.toList() ?: emptyList()
    }

    /**
     * Processes current pending sentences under the vault lock.
     * A category is removed only after [processor] returns normally. New records wait for the
     * current processor and remain pending for a subsequent call.
     */
    @Synchronized
    fun processPending(
        category: String? = null,
        processor: (String, List<String>) -> Unit
    ): Int {
        val categories = when (category) {
            null -> categoryBuffers.keys.toList().sorted()
            else -> listOf(category)
        }
        var processedCount = 0
        for (currentCategory in categories) {
            val sentences = categoryBuffers[currentCategory]?.toList().orEmpty()
            if (sentences.isEmpty()) continue

            processor(currentCategory, sentences)
            categoryBuffers.remove(currentCategory)
            persistStaging()
            processedCount += sentences.size
        }
        return processedCount
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
            val raw = vf.readTextAndMigrate() ?: return
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
