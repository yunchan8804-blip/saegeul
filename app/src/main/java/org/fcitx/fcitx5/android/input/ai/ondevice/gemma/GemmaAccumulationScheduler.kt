/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object GemmaAccumulationScheduler {
    /**
     * 계측(E2E) 테스트가 실행되는 동안 새 재료 생성을 막는다. 이미 진행 중인 생성은 그대로 끝나고,
     * 이 시점부터는 [GemmaAccumulationWorker]가 새 생성을 시작하지 않으며 [requestNow]/
     * [requestManual]/[setEnabled]의 새 작업 큐잉도 건너뛴다. 테스트 사이에 이전 실기기 세션에서
     * 남은 주기 작업이 깨어나 두 번째 native 엔진을 만드는 것을 막기 위함이다.
     */
    fun pauseForInstrumentation() {
        instrumentationPaused.set(true)
    }

    fun resumeAfterInstrumentation() {
        instrumentationPaused.set(false)
    }

    internal fun isPausedForInstrumentation(): Boolean = instrumentationPaused.get()

    suspend fun setEnabled(context: Context, enabled: Boolean, byUser: Boolean = true) = onIo {
        schedulerMutex.withLock {
            val applicationContext = context.applicationContext
            val store = GemmaAccumulationStore.get(applicationContext)
            try {
                store.setEnabled(enabled, byUser)
                val workManager = WorkManager.getInstance(applicationContext)
                if (!enabled) {
                    GemmaAccumulationRuntime.cancelActiveGenerator()
                    await(workManager.cancelUniqueWork(PERIODIC_WORK_NAME))
                    await(workManager.cancelUniqueWork(ONE_TIME_WORK_NAME))
                    check(GemmaAccumulationRuntime.awaitStopped(STOP_TIMEOUT_MS)) {
                        "Gemma 생성 종료를 30초 안에 확인하지 못했습니다."
                    }
                    return@withLock
                }
                await(
                    workManager.enqueueUniquePeriodicWork(
                        PERIODIC_WORK_NAME,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        PeriodicWorkRequestBuilder<GemmaAccumulationWorker>(15, TimeUnit.MINUTES)
                            .setConstraints(constraints())
                            .build()
                    )
                )
                requestNowLocked(applicationContext, store)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                store.recordFailure(error)
                throw error
            }
        }
    }

    suspend fun requestNow(context: Context) = onIo {
        schedulerMutex.withLock {
            val applicationContext = context.applicationContext
            val store = GemmaAccumulationStore.get(applicationContext)
            try {
                requestNowLocked(applicationContext, store)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                store.recordFailure(error)
                throw error
            }
        }
    }

    suspend fun requestManual(context: Context) = onIo {
        schedulerMutex.withLock {
            val applicationContext = context.applicationContext
            val store = GemmaAccumulationStore.get(applicationContext)
            try {
                if (store.requestManual()) {
                    enqueueManualLocked(applicationContext)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                store.recordFailure(error)
                throw error
            }
        }
    }

    suspend fun retryExhausted(context: Context) = onIo {
        schedulerMutex.withLock {
            val applicationContext = context.applicationContext
            val store = GemmaAccumulationStore.get(applicationContext)
            try {
                store.resetAttempts()
                requestNowLocked(applicationContext, store)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                store.recordFailure(error)
                throw error
            }
        }
    }

    suspend fun refreshPolicyIfEnabled(context: Context) = onIo {
        schedulerMutex.withLock {
            val applicationContext = context.applicationContext
            val store = GemmaAccumulationStore.get(applicationContext)
            try {
                store.load()
                if (!store.state.value.enabled) return@withLock
                val workManager = WorkManager.getInstance(applicationContext)
                await(
                    workManager.enqueueUniquePeriodicWork(
                        PERIODIC_WORK_NAME,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        PeriodicWorkRequestBuilder<GemmaAccumulationWorker>(15, TimeUnit.MINUTES)
                            .setConstraints(constraints())
                            .build()
                    )
                )
                workManager.getWorkInfosForUniqueWork(ONE_TIME_WORK_NAME)
                    .get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .filter { workInfo ->
                        (workInfo.state == WorkInfo.State.ENQUEUED || workInfo.state == WorkInfo.State.BLOCKED) &&
                            workInfo.constraints.requiresCharging()
                    }
                    .forEach { workInfo ->
                        workManager.updateWork(
                            OneTimeWorkRequestBuilder<GemmaAccumulationWorker>()
                                .setId(workInfo.id)
                                .setConstraints(constraints())
                                .build()
                        ).get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                store.recordFailure(error)
                throw error
            }
        }
    }

    private suspend fun requestNowLocked(context: Context, store: GemmaAccumulationStore) {
        store.load()
        if (!store.state.value.enabled) return
        enqueueNowLocked(context)
    }

    private suspend fun enqueueNowLocked(context: Context) {
        if (instrumentationPaused.get()) return
        await(
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<GemmaAccumulationWorker>()
                    .setConstraints(constraints())
                    .build()
            )
        )
    }

    private suspend fun enqueueManualLocked(context: Context) {
        if (instrumentationPaused.get()) return
        await(
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<GemmaAccumulationWorker>()
                    .setConstraints(constraints())
                    .build()
            )
        )
    }

    private fun constraints(): Constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .build()

    private fun await(operation: Operation) {
        operation.result.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private suspend fun <T> onIo(block: suspend () -> T): T =
        withContext(Dispatchers.IO + NonCancellable) { block() }

    private val schedulerMutex = Mutex()
    private val instrumentationPaused = AtomicBoolean(false)
    private const val STOP_TIMEOUT_MS = 30_000L
    private const val OPERATION_TIMEOUT_SECONDS = 30L
    private const val PERIODIC_WORK_NAME = "gemma-accumulation-periodic"
    private const val ONE_TIME_WORK_NAME = "gemma-accumulation-now"
}
