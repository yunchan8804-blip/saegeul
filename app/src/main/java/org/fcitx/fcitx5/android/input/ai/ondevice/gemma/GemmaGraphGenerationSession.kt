/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceBackendFallbackPolicy
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSharedEngine
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentLeaseWaiter
import timber.log.Timber
import java.io.File

/**
 * Keeps a use of the process-wide [OnDeviceSharedEngine] across an entire graph-enrichment worker run
 * (many chunks). The engine is shared with the keyboard's automatic suggestions and the material
 * generator, so a graph run that starts right after the keyboard hid reuses the already-warm engine
 * instead of paying a fresh initialization, and closing this session leaves it warm for the next purpose.
 * The worker already serializes its own chunk loop (never more than one [generate] in flight at a time).
 *
 * Holds the [OnDeviceGenerationControl.Purpose.PERSONAL_GRAPH] lease for as long as the session stays
 * open (acquired once by [ensureReady], not re-acquired per chunk) and releases it the moment the input
 * view appears or another purpose needs it - the same cancellation boundary [GemmaMaterialGenerator]
 * uses - so the keyboard and material generation are never blocked behind a graph run. Prefers the GPU
 * backend, falling back to CPU once via [OnDeviceBackendFallbackPolicy] (the same policy the automatic
 * suggestion engine uses).
 */
class GemmaGraphGenerationSession {

    /**
     * A generation was interrupted mid-flight ([cancel] called, either the keyboard preempting the
     * lease or the worker's own watchdog reacting to a broken device condition) - never a real
     * `CancellationException`. The worker decides what that means (retry once the lease frees up
     * again for a manual run, or pause with the concrete reason for an automatic one); letting a
     * genuine `CancellationException` escape here instead made `WorkManager` treat every keyboard
     * tap as a hard, non-retryable cancellation of the whole job (see the class doc's on-device trace).
     */
    class LeaseLostException(message: String, cause: Throwable? = null) : Exception(message, cause)

    @Volatile
    private var engineUse: OnDeviceSharedEngine.Use? = null
    private var lease: OnDeviceGenerationControl.Lease? = null

    @Volatile
    private var activeConversation: Conversation? = null

    @Volatile
    private var cancelled = false

    var usedGpu: Boolean = false
        private set

    val isReady: Boolean
        get() = engineUse != null && lease != null

    /**
     * Acquires the lease and opens an engine if this session does not already have both ready
     * (a no-op otherwise, so calling this once per chunk is cheap once warm). Retries lease
     * contention via [GraphEnrichmentLeaseWaiter]'s policy; see its parameters for what each does.
     */
    suspend fun ensureReady(
        modelFile: File,
        manual: Boolean,
        shouldAbort: suspend () -> Boolean,
        onWaitingChanged: suspend (Boolean) -> Unit
    ): GraphEnrichmentLeaseWaiter.Result<Unit> {
        if (isReady) return GraphEnrichmentLeaseWaiter.Result.Acquired(Unit)
        return GraphEnrichmentLeaseWaiter.waitForLease(
            manual = manual,
            shouldAbort = shouldAbort,
            isBusy = { it is IllegalStateException && it.message?.contains("사용 중") == true },
            onWaitingChanged = onWaitingChanged,
            attempt = { openEngine(modelFile, manual) }
        )
    }

    /**
     * [manual] is passed straight to [OnDeviceGenerationControl.tryBegin]'s `preemptBackground`: a
     * manual (user button) request immediately cancels an in-progress material generator and takes
     * the lease; an automatic run does not and keeps waiting for material to finish on its own - see
     * that parameter's doc.
     */
    private fun openEngine(modelFile: File, manual: Boolean) {
        val newLease = OnDeviceGenerationControl.tryBegin(
            purpose = OnDeviceGenerationControl.Purpose.PERSONAL_GRAPH,
            preemptBackground = manual,
            cancel = ::requestCancel
        ) ?: throw IllegalStateException("키보드 또는 다른 온디바이스 생성 작업이 사용 중입니다.")
        try {
            cancelled = false
            val (opened, gpu) = acquireEngineWithFallback(modelFile)
            engineUse = opened
            usedGpu = gpu
            lease = newLease
            Timber.tag(LOG_TAG).i("engine opened backend=%s", if (gpu) "GPU" else "CPU")
        } catch (error: Throwable) {
            OnDeviceGenerationControl.end(newLease)
            throw error
        }
    }

    /**
     * Borrows the process-wide [OnDeviceSharedEngine] (already warm if the keyboard or another purpose
     * used it), preferring GPU and falling back to CPU once when GPU initialization fails.
     */
    private fun acquireEngineWithFallback(modelFile: File): Pair<OnDeviceSharedEngine.Use, Boolean> {
        var useGpu = true
        var fallbackUsed = false
        while (true) {
            try {
                val use = OnDeviceSharedEngine.acquire(
                    modelFile,
                    if (useGpu) OnDeviceSharedEngine.Flavor.Gpu else OnDeviceSharedEngine.Flavor.Cpu()
                )
                return use to (use.flavor is OnDeviceSharedEngine.Flavor.Gpu)
            } catch (error: OnDeviceSharedEngine.InitializationFailedException) {
                if (OnDeviceBackendFallbackPolicy.shouldFallbackToCpu(
                        OnDeviceBackendFallbackPolicy.ENGINE_INITIALIZATION_FAILED, useGpu, fallbackUsed
                    )
                ) {
                    fallbackUsed = true
                    useGpu = false
                    continue
                }
                throw error
            }
        }
    }

    /** Sends [prompt] through a fresh [Conversation] on the already-open engine and returns the collected text. */
    suspend fun generate(prompt: String): String {
        val activeEngine = engineUse?.resource ?: throw IllegalStateException("Gemma 엔진이 준비되지 않았습니다.")
        if (cancelled) throw LeaseLostException("Gemma 생성이 중간에 중단되었습니다.")
        val conversation = activeEngine.createConversation()
        activeConversation = conversation
        try {
            return collectGenerated(
                conversation.sendMessageAsync(prompt).map { message ->
                    message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                }
            )
        } finally {
            activeConversation = null
            runCatching { conversation.close() }
        }
    }

    /**
     * Collects the generated text pieces from [responses]. A failure while collecting is only
     * reinterpreted as [LeaseLostException] when this session itself was cancelled ([cancelled] is
     * set by [requestCancel], i.e. the lease was preempted or the watchdog broke): cancelling the
     * native process makes the engine surface its own error (for example a `LiteRtLmJniException`)
     * from the collection, and that error is then the preemption, not a generation failure. An engine
     * error with no cancellation behind it propagates unchanged so a real failure stays a failure.
     */
    internal suspend fun collectGenerated(responses: Flow<String>): String {
        val response = StringBuilder()
        try {
            responses.collect { text ->
                if (cancelled) return@collect
                response.append(text)
            }
        } catch (realCancellation: CancellationException) {
            // requestCancel() cancels the native process from a side coroutine
            // (FcitxApplication.applicationScope), which can surface here as this collection's
            // own coroutine being cancelled - a genuine CancellationException, not the
            // LeaseLostException the flag checks below throw. [cancelled] is what tells the two
            // apart: true means WE caused this (keyboard/watchdog), so it is reinterpreted the
            // same way; false means the collecting coroutine itself was cancelled for some other,
            // real reason (e.g. WorkManager stopping the whole worker) and must propagate as-is.
            // On-device trace this fixes: a raw CancellationException reaching here escaped all
            // the way out of doWork(), which WorkManager then treated as a hard, non-retryable
            // cancellation of the whole job on every keyboard tap.
            if (cancelled) throw LeaseLostException("Gemma 생성이 중간에 중단되었습니다.", realCancellation)
            throw realCancellation
        } catch (engineError: Exception) {
            if (cancelled) throw LeaseLostException("Gemma 생성이 중간에 중단되었습니다.", engineError)
            throw engineError
        }
        if (cancelled) throw LeaseLostException("Gemma 생성이 중간에 중단되었습니다.")
        if (response.isBlank()) throw IllegalStateException("Gemma가 텍스트 응답을 반환하지 않았습니다.")
        return response.toString()
    }

    private fun requestCancel() {
        cancelled = true
        val conversation = activeConversation ?: return
        FcitxApplication.getInstance().applicationScope.launch(Dispatchers.IO) {
            runCatching { conversation.cancelProcess() }
        }
    }

    /** Cancels any in-flight generation without closing the engine (the caller decides whether to keep retrying). */
    fun cancel() {
        requestCancel()
    }

    /**
     * Returns the shared engine (it stays warm for the next purpose) and releases the lease. Safe to call
     * even if never opened, or more than once.
     */
    fun close() {
        engineUse?.release()
        engineUse = null
        lease?.let { OnDeviceGenerationControl.end(it) }
        lease = null
    }

    companion object {
        /** Unified log tag for both this session and [GemmaGraphEnrichmentWorker]; see their class docs for what is logged. */
        const val LOG_TAG = "GemmaGraph"
    }
}
