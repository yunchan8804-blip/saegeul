/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.LinkedHashMap

enum class AiPrefetchConnectionState {
    UNKNOWN,
    READY,
    PROVIDER_MISSING,
    REAUTH_REQUIRED
}

/**
 * Background LLM continuation prefetcher and exact-context cache.
 * When the user pauses typing or finishes a sentence, this prefetcher queries configured AI
 * providers in the background and caches suggestions for the matching context.
 */
class AiSentenceCompletionPrefetcher(
    private val clientProvider: (() -> OpenAiResponsesClient?)? = null,
    private val maxCacheCapacity: Int = 30,
    var onPrefetchCompleted: ((requestKey: RequestKey, suggestions: List<String>) -> Unit)? = null,
    private val networkAllowed: () -> Boolean = { true },
    private val diagnostics: (String) -> Unit = { android.util.Log.d("SaegeulAI", it) },
    private val onConnectionStateChanged: (() -> Unit)? = null
) {

    @Volatile
    var connectionState: AiPrefetchConnectionState = AiPrefetchConnectionState.UNKNOWN
        private set

    data class Scope(
        val packageName: String = "",
        val inputSessionEpoch: Long = 0L
    )

    data class RequestKey(
        val scope: Scope,
        val normalizedContext: String
    )

    private data class Request(
        val key: RequestKey,
        val cleanInput: String,
        val notBeforeMs: Long,
        val revision: Long
    )

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var pumpJob: Job? = null
    private var desired: Request? = null
    private var revision = 0L

    private val cache = object : LinkedHashMap<RequestKey, List<String>>(maxCacheCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RequestKey, List<String>>?): Boolean {
            return size > maxCacheCapacity
        }
    }

    /** Gets cached LLM sentence predictions only for the exact normalized context. */
    fun getCachedPredictions(context: String, scope: Scope = Scope()): List<String>? = synchronized(lock) {
        cache[RequestKey(scope, normalizeContextKey(context))]
    }

    /**
     * Stores predictions in cache manually (useful for tests and synthetic feeds).
     */
    fun putPredictions(context: String, predictions: List<String>, scope: Scope = Scope()) {
        val key = RequestKey(scope, normalizeContextKey(context))
        synchronized(lock) {
            cache[key] = predictions
        }
    }

    /**
     * Schedules asynchronous prefetch if the context is meaningful.
     * Uses the Fast tier and notifies the IME UI when a matching request completes.
     */
    fun schedulePrefetch(context: String, debounceMs: Long = 300L, scope: Scope = Scope()) {
        if (!networkAllowed()) {
            invalidateDesired()
            return
        }
        val clean = context.trimStart()
        if (clean.trimEnd().length < 2) {
            invalidateDesired()
            return
        }

        val key = RequestKey(scope, normalizeContextKey(clean))
        val jobToStart = synchronized(lock) {
            if (cache.containsKey(key)) {
                revision++
                desired = null
                null
            } else if (desired?.key == key) {
                null
            } else {
                val requestRevision = ++revision
                desired = Request(
                    key = key,
                    cleanInput = clean,
                    notBeforeMs = monotonicMs() + debounceMs.coerceAtLeast(0L),
                    revision = requestRevision
                )
                registerPumpLocked()
            }
        }
        jobToStart?.start()
    }

    fun normalizeContextKey(context: String): String =
        context.trimStart().replace(Regex("\\s+"), " ")

    fun clear() {
        synchronized(lock) {
            revision++
            desired = null
            cache.clear()
        }
    }

    private fun invalidateDesired() {
        synchronized(lock) {
            revision++
            desired = null
        }
    }

    private fun registerPumpLocked(): Job? {
        if (pumpJob != null) return null
        return coroutineScope.launch(start = CoroutineStart.LAZY) {
            runPump()
        }.also { pumpJob = it }
    }

    private suspend fun runPump() {
        val thisPump = currentCoroutineContext()[Job]
        try {
            while (true) {
                val request = synchronized(lock) { desired } ?: break
                val remainingDelay = request.notBeforeMs - monotonicMs()
                if (remainingDelay > 0L) delay(remainingDelay)

                val dispatchRequest = synchronized(lock) {
                    if (!isLatestLocked(request)) {
                        null
                    } else if (!networkAllowed()) {
                        desired = null
                        revision++
                        null
                    } else {
                        request
                    }
                } ?: continue

                try {
                    val client = clientProvider?.invoke()
                    if (client == null) {
                        updateConnectionState(AiPrefetchConnectionState.PROVIDER_MISSING)
                        consumeIfLatest(dispatchRequest)
                    } else {
                        diagnostics("schedulePrefetch: dispatching")
                        val result = client.generate(
                            action = AiAction.ContinueTyping,
                            input = dispatchRequest.cleanInput
                        )
                        updateConnectionState(AiPrefetchConnectionState.READY)
                        diagnostics("schedulePrefetch: result received (suggestionCount=${result.suggestions.size})")
                        val rawSuggestions = result.suggestions
                        val suggestions = PrefetchedContinuation.parse(
                            rawSuggestions,
                            dispatchRequest.cleanInput
                        ).map(PrefetchedContinuation::toWireFormat)
                        val published = synchronized(lock) {
                            val latest = isLatestLocked(dispatchRequest)
                            val allowed = networkAllowed()
                            if (latest) desired = null
                            if (latest && allowed && (rawSuggestions.isEmpty() || suggestions.isNotEmpty())) {
                                cache[dispatchRequest.key] = suggestions
                                onPrefetchCompleted?.invoke(dispatchRequest.key, suggestions)
                                true
                            } else {
                                false
                            }
                        }
                        if (!published) diagnostics("schedulePrefetch: discarded")
                    }
                } catch (e: CancellationException) {
                    consumeIfLatest(dispatchRequest)
                    diagnostics("schedulePrefetch: cancelled")
                    throw e
                } catch (e: AiReauthenticationRequiredException) {
                    updateConnectionState(AiPrefetchConnectionState.REAUTH_REQUIRED)
                    consumeIfLatest(dispatchRequest)
                    diagnostics("schedulePrefetch: failed (${e.javaClass.simpleName})")
                } catch (e: Throwable) {
                    consumeIfLatest(dispatchRequest)
                    val providerMetadata = (e as? AiProviderException)?.let { failure ->
                        buildString {
                            append(" (kind=${failure.failureKind}")
                            failure.httpStatus?.let { append(", httpStatus=$it") }
                            append(')')
                        }
                    }.orEmpty()
                    diagnostics("schedulePrefetch: failed (${e.javaClass.simpleName})$providerMetadata")
                }
            }
        } finally {
            val jobToStart = synchronized(lock) {
                if (pumpJob === thisPump) {
                    pumpJob = null
                    if (desired != null) registerPumpLocked() else null
                } else {
                    null
                }
            }
            jobToStart?.start()
        }
    }

    private fun isLatestLocked(request: Request): Boolean =
        desired?.key == request.key && desired?.revision == request.revision

    private fun consumeIfLatest(request: Request) {
        synchronized(lock) {
            if (isLatestLocked(request)) desired = null
        }
    }

    private fun updateConnectionState(state: AiPrefetchConnectionState) {
        if (connectionState == state) return
        connectionState = state
        onConnectionStateChanged?.invoke()
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L
}
