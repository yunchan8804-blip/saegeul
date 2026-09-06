/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.LinkedHashMap

/**
 * Asynchronous Background LLM Next-Sentence Prefetcher & Smart Cache.
 * When the user pauses typing or finishes a sentence, this prefetcher asynchronously queries
 * configured AI providers (OpenAI, Gemini, etc.) and caches LLM-quality next-sentence proposals,
 * so they appear instantaneously (0ms) in the candidate bar without blocking keyboard input.
 */
class AiSentenceCompletionPrefetcher(
    private val clientProvider: (() -> OpenAiResponsesClient?)? = null,
    private val maxCacheCapacity: Int = 30,
    var onPrefetchCompleted: ((context: String, suggestions: List<String>) -> Unit)? = null,
    private val networkAllowed: () -> Boolean = { true }
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    private val cache = object : LinkedHashMap<String, List<String>>(maxCacheCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean {
            return size > maxCacheCapacity
        }
    }

    /**
     * Gets cached LLM sentence predictions for the given context, if available.
     * Supports exact match and prefix match fallback when user continues typing.
     */
    @Synchronized
    fun getCachedPredictions(context: String): List<String>? {
        val key = normalizeContextKey(context)
        val exact = cache[key]
        if (exact != null) return exact
        // Prefix match fallback: If user continues typing following a prefetched context
        for ((cachedKey, suggestions) in cache.entries.reversed()) {
            if (key.startsWith(cachedKey) && key.length - cachedKey.length <= 8) {
                return suggestions
            }
        }
        return null
    }

    /**
     * Stores predictions in cache manually (useful for tests and synthetic feeds).
     */
    @Synchronized
    fun putPredictions(context: String, predictions: List<String>) {
        val key = normalizeContextKey(context)
        cache[key] = predictions
    }

    /**
     * Schedules asynchronous prefetch if the context is meaningful.
     * Uses Fast tier (AGY CLI / Gemini Flash) for ultra low-latency prefetching and notifies the IME UI upon completion.
     */
    fun schedulePrefetch(context: String, debounceMs: Long = 300L) {
        if (!networkAllowed()) return
        val clean = context.trim()
        if (clean.length < 2) return

        val key = normalizeContextKey(clean)
        synchronized(this) {
            if (cache.containsKey(key)) return
        }

        activeJob?.cancel()
        activeJob = scope.launch {
            delay(debounceMs)
            val client = clientProvider?.invoke() ?: return@launch
            try {
                android.util.Log.d("SaegeulAI", "schedulePrefetch: dispatching for '$clean'")
                val instruction = """
                    사용자가 작성 중인 한국어 텍스트 문맥을 고려하여 다음 3가지 후보를 작성하세요:
                    1. 사용자가 오타나 어색한 맞춤법을 입력한 경우 올바른 교정 단어/문장
                    2. 현재 문맥 뒤에 바로 이어질 자연스러운 다음 단어나 짧은 구(phrase)
                    3. 문맥과 어조에 어울리는 완성도 높은 다음 문장
                """.trimIndent()
                val result = client.generate(
                    action = AiAction.Custom,
                    input = clean,
                    customInstruction = instruction,
                    tierOverride = AiModelTier.Fast
                )
                android.util.Log.i("SaegeulAI", "schedulePrefetch: result received -> ${result.suggestions}")
                if (result.suggestions.isNotEmpty()) {
                    synchronized(this@AiSentenceCompletionPrefetcher) {
                        cache[key] = result.suggestions
                    }
                    onPrefetchCompleted?.invoke(key, result.suggestions)
                }
            } catch (e: Throwable) {
                android.util.Log.w("SaegeulAI", "schedulePrefetch: failed (${e.javaClass.simpleName}: ${e.message})")
            }
        }
    }

    fun normalizeContextKey(context: String): String {
        return context.trim()
            .replace(Regex("\\s+"), " ")
            .trimEnd('.', '?', '!', ',', '~', ' ', ';', ':')
            .takeLast(120)
    }

    fun clear() {
        activeJob?.cancel()
        synchronized(this) {
            cache.clear()
        }
    }
}
