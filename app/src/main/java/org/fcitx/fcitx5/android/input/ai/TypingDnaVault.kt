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
import timber.log.Timber
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

        // A failed save keeps the sentence in memory, so the next successful save writes it too.
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
     * A category leaves the pending buffers only once [processor] returns normally: it is removed
     * and the staging file saved before [processor] runs, and both are rolled back if [processor]
     * throws. A single failure therefore neither drops nor double-processes a batch; a batch can
     * be lost only if [processor] fails, the rollback save also fails, and the process then dies
     * before the next successful save. New records wait for the current processor and remain
     * pending for a subsequent call.
     * Throws [TypingDnaPersistenceException], without running [processor], if the removal cannot
     * be saved; a [processor] failure is rethrown as is, with any rollback save failure suppressed.
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
            val buffer = categoryBuffers[currentCategory] ?: continue
            val sentences = buffer.toList()
            if (sentences.isEmpty()) continue

            categoryBuffers.remove(currentCategory)
            persistStaging()?.let { failure ->
                categoryBuffers[currentCategory] = buffer
                throw TypingDnaPersistenceException(failure)
            }
            try {
                processor(currentCategory, sentences)
            } catch (failure: Throwable) {
                categoryBuffers[currentCategory] = buffer
                persistStaging()?.let(failure::addSuppressed)
                throw failure
            }
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
     * Throws [TypingDnaPersistenceException] if the staging file cannot be rewritten; the purged
     * buffers are then restored, so memory keeps matching the file that still holds them.
     */
    @Synchronized
    fun purge(category: String? = null) {
        val purged = HashMap<String, ArrayDeque<String>>()
        if (category != null) {
            categoryBuffers.remove(category)?.let { purged[category] = it }
        } else {
            purged.putAll(categoryBuffers)
            categoryBuffers.clear()
        }
        persistStaging()?.let { failure ->
            categoryBuffers.putAll(purged)
            throw TypingDnaPersistenceException(failure)
        }
        purged.values.forEach { it.clear() }
    }

    /**
     * Writes the buffers to the staging file. A failure is logged and returned rather than thrown,
     * so each caller decides whether to keep its in-memory change or roll it back and report it.
     */
    private fun persistStaging(): Exception? {
        val vf = vaultFile ?: return null
        return try {
            val root = JSONObject()
            categoryBuffers.forEach { (category, deque) ->
                val arr = JSONArray()
                deque.forEach { arr.put(it) }
                root.put(category, arr)
            }
            vf.writeText(root.toString())
            null
        } catch (exception: Exception) {
            Timber.w(exception, "Typing DNA staging save failed")
            exception
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
