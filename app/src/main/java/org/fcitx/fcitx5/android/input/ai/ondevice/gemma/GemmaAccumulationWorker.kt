/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.BackgroundProgressNotifier

class GemmaAccumulationWorker(
    appContext: Context,
    parameters: WorkerParameters
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val store = GemmaAccumulationStore.get(applicationContext)
        var processLockHeld = false
        try {
            store.load()
            if (!processMutex.tryLock()) {
                return deferredResult(store)
            }
            processLockHeld = true
            currentCoroutineContext().ensureActive()
            if (isStopped || GemmaAccumulationScheduler.isPausedForInstrumentation() ||
                !GemmaOpenMaterialTransition.canGenerate(store.state.value)
            ) {
                return Result.success()
            }
            val mode = if (store.state.value.manualRequested) {
                GemmaGenerationMode.MANUAL
            } else {
                GemmaGenerationMode.AUTOMATIC
            }
            val startSnapshot = GemmaGenerationEligibility.snapshot(applicationContext)
            var runContextLimit = GemmaGenerationEligibility.reduceContextLimit(
                mode.maxContexts,
                startSnapshot.thermalStatus,
                mode
            )
            val startWaitingReason = GemmaGenerationEligibility.evaluate(startSnapshot, mode)
            if (startWaitingReason != null) {
                store.recordBlocked(startWaitingReason.message)
                return deferredResult(store)
            }

            val model = GemmaModelFiles.modelFile(applicationContext)
            if (!model.isFile) {
                val error = IllegalStateException("검증된 Gemma 모델이 없습니다.")
                store.recordFailure(error)
                notifyAlertFailure(error)
                return Result.failure()
            }

            val addedBefore = store.state.value.added
            notifyProgress(store, 0, runContextLimit)

            val generator = GemmaMaterialGenerator(applicationContext)
            GemmaAccumulationRuntime.register(generator)
            try {
                val startedAt = SystemClock.elapsedRealtime()
                var generatedContexts = 0
                while (SystemClock.elapsedRealtime() - startedAt < mode.startBudgetMillis) {
                    currentCoroutineContext().ensureActive()
                    val requestSnapshot = GemmaGenerationEligibility.snapshot(applicationContext)
                    runContextLimit = GemmaGenerationEligibility.reduceContextLimit(
                        runContextLimit,
                        requestSnapshot.thermalStatus,
                        mode
                    )
                    val waitingReason = GemmaGenerationEligibility.evaluate(requestSnapshot, mode)
                    if (isStopped || GemmaAccumulationScheduler.isPausedForInstrumentation() ||
                        !GemmaOpenMaterialTransition.canGenerate(store.state.value) ||
                        waitingReason != null
                    ) {
                        store.recordBlocked(waitingReason?.message ?: blockedMessage(mode))
                        notifyCancelled()
                        return deferredResult(store)
                    }
                    if (generatedContexts >= runContextLimit) break
                    val plan = store.nextOpenPlan()
                    if (plan == null) {
                        notifyFinish(store, addedBefore)
                        return Result.success()
                    }
                    if (!store.markOpenAttemptStarted(plan)) {
                        notifyCancelled()
                        return deferredResult(store)
                    }

                    try {
                        val result = generateWithWatchdog(store, generator, model, plan.prompt, mode)
                        currentCoroutineContext().ensureActive()
                        if (isStopped) {
                            throw CancellationException("WorkManager가 축적 작업을 중지했습니다.")
                        }
                        val resultSnapshot = GemmaGenerationEligibility.snapshot(applicationContext)
                        runContextLimit = GemmaGenerationEligibility.reduceContextLimit(
                            runContextLimit,
                            resultSnapshot.thermalStatus,
                            mode
                        )
                        val waitingReason = GemmaGenerationEligibility.evaluate(resultSnapshot, mode)
                        if (!GemmaOpenMaterialTransition.canGenerate(store.state.value) ||
                            waitingReason != null
                        ) {
                            throw CancellationException("안전 조건이 바뀌어 생성 결과를 저장하지 않습니다.")
                        }
                        val committed = store.commitOpenIfEnabled(plan) { bank ->
                            bank.addGeneratedOpen(
                                result.text,
                                GemmaModelFiles.MODEL_ID,
                                GemmaModelFiles.MODEL_SHA256
                            )
                        }
                        if (committed == null) {
                            throw CancellationException("축적이 꺼져 생성 결과를 저장하지 않습니다.")
                        }
                        generatedContexts++
                        notifyProgress(store, generatedContexts, runContextLimit)
                    } catch (error: CancellationException) {
                        if (isStopped || !currentCoroutineContext().isActive) throw error
                        store.recordBlocked(blockedMessage(mode))
                        notifyCancelled()
                        return deferredResult(store)
                    } catch (error: IllegalStateException) {
                        if (isStopped || !GemmaOpenMaterialTransition.canGenerate(store.state.value) ||
                            waitingReason(mode) != null ||
                            error.message?.contains("사용 중") == true
                        ) {
                            store.recordBlocked(blockedMessage(mode))
                            notifyCancelled()
                            return deferredResult(store)
                        }
                        store.recordFailure(error)
                        notifyAlertFailure(error)
                        return Result.failure()
                    } catch (error: Exception) {
                        store.recordFailure(error)
                        notifyAlertFailure(error)
                        return Result.failure()
                    }
                }
                store.finishOpenRun()
                notifyFinish(store, addedBefore)
                return Result.success()
            } finally {
                GemmaAccumulationRuntime.unregister(generator)
                generator.cancel()
            }
        } catch (error: CancellationException) {
            notifyCancelled()
            throw error
        } catch (error: Exception) {
            try {
                store.recordFailure(error)
            } catch (recordingCancellation: CancellationException) {
                recordingCancellation.addSuppressed(error)
                throw recordingCancellation
            } catch (recordingError: Exception) {
                recordingError.addSuppressed(error)
                throw recordingError
            }
            notifyAlertFailure(error)
            return Result.failure()
        } finally {
            if (processLockHeld) processMutex.unlock()
        }
    }

    private fun notifyProgress(store: GemmaAccumulationStore, current: Int, total: Int) {
        BackgroundProgressNotifier.progress(
            applicationContext,
            BackgroundProgressNotifier.ID_GEMMA_ACCUMULATION,
            BackgroundProgressNotifier.ProgressSpec(
                title = applicationContext.getString(R.string.gemma_accumulation_notify_progress_title),
                text = applicationContext.getString(
                    R.string.gemma_accumulation_notify_progress_text,
                    current,
                    total,
                    store.state.value.stored
                ),
                current = current,
                total = total
            )
        )
    }

    private fun notifyFinish(store: GemmaAccumulationStore, addedBefore: Int) {
        val addedThisRun = store.state.value.added - addedBefore
        if (GemmaAccumulationNotificationDecision.shouldAnnounceDone(addedThisRun)) {
            BackgroundProgressNotifier.done(
                applicationContext,
                BackgroundProgressNotifier.ID_GEMMA_ACCUMULATION,
                applicationContext.getString(R.string.gemma_accumulation_notify_done_title, addedThisRun),
                applicationContext.getString(R.string.gemma_accumulation_notify_done_text, store.state.value.stored)
            )
        } else {
            notifyCancelled()
        }
    }

    private fun notifyAlertFailure(error: Throwable) {
        BackgroundProgressNotifier.alert(
            applicationContext,
            BackgroundProgressNotifier.ID_GEMMA_ACCUMULATION,
            applicationContext.getString(R.string.gemma_accumulation_notify_failed_title),
            error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
        )
    }

    private fun notifyCancelled() {
        BackgroundProgressNotifier.cancel(applicationContext, BackgroundProgressNotifier.ID_GEMMA_ACCUMULATION)
    }

    private suspend fun generateWithWatchdog(
        store: GemmaAccumulationStore,
        generator: GemmaMaterialGenerator,
        model: java.io.File,
        prompt: String,
        mode: GemmaGenerationMode
    ): GemmaMaterialGenerator.GenerationResult = coroutineScope {
        val watchdog = launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (isStopped || !GemmaOpenMaterialTransition.canGenerate(store.state.value) ||
                    waitingReason(mode) != null
                ) {
                    generator.cancel()
                    return@launch
                }
            }
        }
        try {
            generator.generate(
                modelFile = model,
                useGpu = false,
                prompt = prompt
            )
        } finally {
            watchdog.cancelAndJoin()
        }
    }

    private fun waitingReason(mode: GemmaGenerationMode): GemmaGenerationWaitReason? =
        GemmaGenerationEligibility.evaluate(GemmaGenerationEligibility.snapshot(applicationContext), mode)

    private fun blockedMessage(mode: GemmaGenerationMode): String =
        waitingReason(mode)?.message ?: "안전 조건이 충족되지 않았습니다."

    private fun deferredResult(store: GemmaAccumulationStore): Result =
        if (GemmaOpenMaterialTransition.canGenerate(store.state.value) &&
            store.state.value.manualRequested
        ) {
            Result.retry()
        } else {
            Result.success()
        }

    private companion object {
        val processMutex = Mutex()
        const val WATCHDOG_INTERVAL_MS = 250L
    }
}

/** Pure decision of whether finishing a run is worth a "done" notification. No I/O; easy to unit test. */
internal object GemmaAccumulationNotificationDecision {
    fun shouldAnnounceDone(addedThisRun: Int): Boolean = addedThisRun > 0
}

internal object GemmaAccumulationRuntime {
    private val lock = Any()
    private var activeGenerator: GemmaMaterialGenerator? = null

    fun register(generator: GemmaMaterialGenerator) {
        synchronized(lock) { activeGenerator = generator }
    }

    fun unregister(generator: GemmaMaterialGenerator) {
        synchronized(lock) {
            if (activeGenerator === generator) activeGenerator = null
        }
    }

    fun cancelActiveGenerator() {
        synchronized(lock) { activeGenerator }?.cancel()
    }

    suspend fun awaitStopped(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val active = synchronized(lock) { activeGenerator != null }
            if (!active) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(STOP_POLL_INTERVAL_MS)
        }
    }

    val isInferring: Boolean
        get() = synchronized(lock) { activeGenerator?.isInferring == true }

    private const val STOP_POLL_INTERVAL_MS = 50L
}
