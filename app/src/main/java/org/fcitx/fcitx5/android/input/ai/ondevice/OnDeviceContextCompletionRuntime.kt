/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaMaterialGenerator
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelFiles
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class OnDeviceContextCompletionRuntime(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var activeRun: Run? = null
    private var nextRunId = 0L
    private var terminalFailureCode: String? = null

    val supported: Boolean = OnDeviceAiSupport.isSupported

    val isRunning: Boolean
        get() = synchronized(lock) { activeRun != null }

    val isInferring: Boolean
        get() = synchronized(lock) { activeRun?.generator?.isInferring == true }

    suspend fun complete(text: String): OnDeviceContextCompletionResult {
        if (!OnDeviceContextCompletionPolicy.isIncompleteContext(text)) {
            throw ContextCompletionException("INVALID_CONTEXT")
        }
        if (!GemmaModelFiles.modelFile(appContext).isFile) {
            throw ContextCompletionException("MODEL_MISSING")
        }
        val run = synchronized(lock) {
            terminalFailureCode?.let { throw ContextCompletionException(it) }
            if (activeRun != null) throw ContextCompletionException("ALREADY_RUNNING")
            Run(++nextRunId, GemmaMaterialGenerator(appContext)).also { activeRun = it }
        }
        val worker = scope.async(start = CoroutineStart.LAZY) { execute(run, text) }
        run.worker = worker
        worker.start()
        return try {
            worker.await()
        } catch (error: CancellationException) {
            cancelRun(run)
            var stopTimedOut = false
            withContext(NonCancellable) {
                try {
                    withTimeout(NATIVE_STOP_BUDGET_MS) { worker.join() }
                } catch (_: TimeoutCancellationException) {
                    latchTerminalFailure("NATIVE_STOP_TIMEOUT")
                    stopTimedOut = true
                }
            }
            if (stopTimedOut) throw ContextCompletionException("NATIVE_STOP_TIMEOUT")
            throw error
        }
    }

    fun cancel() {
        synchronized(lock) { activeRun }?.let(::cancelRun)
    }

    private suspend fun execute(run: Run, text: String): OnDeviceContextCompletionResult {
        val startedAt = SystemClock.elapsedRealtime()
        var generation: Deferred<GemmaMaterialGenerator.GenerationResult>? = null
        var resourceMonitor: Job? = null
        var result: OnDeviceContextCompletionResult? = null
        var failure: ContextCompletionException? = null
        try {
            requireEligibleResources()
            ensureNotCancelled(run)
            val prompt = OnDeviceContextCompletionPolicy.promptFor(text)
            val generationJob = scope.async(start = CoroutineStart.LAZY) {
                run.generator.generate(
                    modelFile = GemmaModelFiles.modelFile(appContext),
                    useGpu = false,
                    prompt = prompt,
                    purpose = OnDeviceGenerationControl.Purpose.EXPLICIT_CONTEXT
                )
            }
            generation = generationJob
            run.generation = generationJob
            if (run.cancelled.get()) {
                generationJob.cancel()
                throw CancellationException()
            }
            generationJob.start()
            resourceMonitor = scope.launch {
                try {
                    while (isActive && !generationJob.isCompleted) {
                        delay(RESOURCE_CHECK_INTERVAL_MS)
                        if (!hasEligibleResources()) {
                            run.resourceGateFailed.set(true)
                            cancelRun(run)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    run.resourceGateCode = "RESOURCE_MONITOR_${error.javaClass.simpleName}"
                    run.resourceGateFailed.set(true)
                    cancelRun(run)
                }
            }
            val generated = try {
                withTimeout(GENERATION_BUDGET_MS) { generationJob.await() }
            } catch (_: TimeoutCancellationException) {
                failure = ContextCompletionException("GENERATION_TIMEOUT")
                null
            } catch (error: CancellationException) {
                // CancellationException 자체는 부가 정보가 없다: 취소 사유는 run의 상태 플래그로만 구분한다.
                failure = when {
                    run.resourceGateFailed.get() -> ContextCompletionException(run.resourceGateCode)
                    run.cancelled.get() -> ContextCompletionException("CANCELLED")
                    else -> ContextCompletionException("GENERATION_CANCELLED")
                }
                null
            } catch (error: Throwable) {
                failure = ContextCompletionException(generationFailureCode(error))
                null
            }
            if (generated != null) {
                if (run.resourceGateFailed.get()) {
                    failure = ContextCompletionException(run.resourceGateCode)
                } else if (run.cancelled.get()) {
                    failure = ContextCompletionException("CANCELLED")
                } else {
                    try {
                        requireEligibleResources()
                    } catch (error: ContextCompletionException) {
                        run.resourceGateCode = error.message ?: "RESOURCE_GATE"
                        run.resourceGateFailed.set(true)
                        failure = error
                    }
                    if (failure == null) {
                        val suffix = OnDeviceContextCompletionPolicy.parseCompletion(text, generated.text)
                        if (suffix == null) {
                            failure = ContextCompletionException("INVALID_COMPLETION")
                        } else {
                            result = OnDeviceContextCompletionResult(
                                suffix = suffix,
                                firstTextMs = generated.metrics.firstTextMs,
                                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                                nativeStopped = false
                            )
                        }
                    }
                }
            }
        } catch (error: ContextCompletionException) {
            failure = error
        } catch (error: CancellationException) {
            // CancellationException 자체는 부가 정보가 없다: 취소 사유는 run의 상태 플래그로만 구분한다.
            failure = when {
                run.resourceGateFailed.get() -> ContextCompletionException(run.resourceGateCode)
                run.cancelled.get() -> ContextCompletionException("CANCELLED")
                else -> ContextCompletionException("GENERATION_CANCELLED")
            }
        } catch (error: Throwable) {
            failure = ContextCompletionException("RUNTIME_FAILED_${error.javaClass.simpleName}")
        } finally {
            resourceMonitor?.cancel()
            val stopped = generation?.let { drainGenerator(run, it) } ?: true
            if (stopped) {
                run.nativeStopped.set(true)
                finishRun(run)
            } else {
                failure = ContextCompletionException("NATIVE_STOP_TIMEOUT")
                latchTerminalFailure("NATIVE_STOP_TIMEOUT")
                waitForLateNativeStop(run, generation)
            }
        }
        failure?.let { throw it }
        return checkNotNull(result).copy(
            elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            nativeStopped = run.nativeStopped.get()
        )
    }

    private fun cancelRun(run: Run) {
        run.cancelled.set(true)
        run.generator.cancel()
        run.generation?.cancel()
    }

    private suspend fun drainGenerator(
        run: Run,
        generation: Deferred<GemmaMaterialGenerator.GenerationResult>
    ): Boolean {
        run.generator.cancel()
        generation.cancel()
        return awaitOwnGenerationStopped(
            generation = generation,
            isOwnGenerationRunning = { run.generator.isRunning },
            budgetMs = NATIVE_STOP_BUDGET_MS,
            pollIntervalMs = NATIVE_STOP_POLL_INTERVAL_MS
        )
    }

    private fun waitForLateNativeStop(
        run: Run,
        generation: Deferred<GemmaMaterialGenerator.GenerationResult>?
    ) {
        scope.launch {
            generation?.join()
            while (run.generator.isRunning) {
                delay(NATIVE_STOP_POLL_INTERVAL_MS)
            }
            run.nativeStopped.set(true)
            finishRun(run)
        }
    }

    private fun finishRun(run: Run) {
        synchronized(lock) {
            if (activeRun === run) activeRun = null
        }
    }

    private fun latchTerminalFailure(code: String) {
        synchronized(lock) {
            terminalFailureCode = code
        }
    }

    private fun ensureNotCancelled(run: Run) {
        if (run.cancelled.get()) throw CancellationException()
    }

    private fun generationFailureCode(error: Throwable): String = when {
        error is IOException && !GemmaModelFiles.modelFile(appContext).isFile -> "MODEL_MISSING"
        else -> "GENERATION_FAILED_${error.javaClass.simpleName}"
    }

    private fun hasEligibleResources(): Boolean =
        OnDeviceResourceSnapshot.foregroundBlockCode(appContext) == null

    private fun requireEligibleResources() {
        OnDeviceResourceSnapshot.foregroundBlockCode(appContext)
            ?.let { throw ContextCompletionException(it) }
    }

    private class Run(
        val id: Long,
        val generator: GemmaMaterialGenerator
    ) {
        val cancelled = AtomicBoolean(false)
        val resourceGateFailed = AtomicBoolean(false)
        val nativeStopped = AtomicBoolean(false)
        @Volatile
        var resourceGateCode: String = "RESOURCE_GATE"

        @Volatile
        var worker: Deferred<OnDeviceContextCompletionResult>? = null

        @Volatile
        var generation: Deferred<GemmaMaterialGenerator.GenerationResult>? = null
    }

    private class ContextCompletionException(code: String) : IllegalStateException(code)

    private companion object {
        const val RESOURCE_CHECK_INTERVAL_MS = 100L
        const val GENERATION_BUDGET_MS = 120_000L
        const val NATIVE_STOP_BUDGET_MS = 30_000L
        const val NATIVE_STOP_POLL_INTERVAL_MS = 50L
    }
}

/**
 * 이 run 자신의 생성이 끝나기를 [budgetMs]까지 기다린다. 끝나면 true, 못 끝내면 false.
 *
 * 프로세스 전역 lease([OnDeviceGenerationControl.isGenerating])는 기다리지 않는다. 웜 AUTO_CONTEXT 엔진은
 * 유휴 중에도 lease를 쥐고 있어서, 이어쓰기가 lease를 못 받은 run까지 그 lease를 기다리면 멈춘 적이 없는
 * 네이티브를 멈추지 않았다고 판단해 이후 이어쓰기가 영구히 실패한다. run이 lease를 얻었다면
 * [GemmaMaterialGenerator.isRunning]은 그 lease를 반납한 뒤에야 거짓이 되므로 이것만 보면 된다.
 */
internal suspend fun awaitOwnGenerationStopped(
    generation: Job,
    isOwnGenerationRunning: () -> Boolean,
    budgetMs: Long,
    pollIntervalMs: Long
): Boolean = try {
    withContext(NonCancellable) {
        withTimeout(budgetMs) {
            generation.join()
            while (isOwnGenerationRunning()) {
                delay(pollIntervalMs)
            }
        }
    }
    true
} catch (_: TimeoutCancellationException) {
    false
}
