/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.debug.gemma

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object GemmaAccumulationScheduler {
    suspend fun setEnabled(context: Context, enabled: Boolean) = onIo {
        schedulerMutex.withLock {
            val applicationContext = context.applicationContext
            val store = GemmaAccumulationStore.get(applicationContext)
            try {
                store.setEnabled(enabled)
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
                        ExistingPeriodicWorkPolicy.KEEP,
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

    private suspend fun requestNowLocked(context: Context, store: GemmaAccumulationStore) {
        store.load()
        if (!store.state.value.enabled) return
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

    private fun constraints(): Constraints = Constraints.Builder()
        .setRequiresCharging(true)
        .setRequiresBatteryNotLow(true)
        .build()

    private fun await(operation: Operation) {
        operation.result.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private suspend fun <T> onIo(block: suspend () -> T): T =
        withContext(Dispatchers.IO + NonCancellable) { block() }

    private val schedulerMutex = Mutex()
    private const val STOP_TIMEOUT_MS = 30_000L
    private const val OPERATION_TIMEOUT_SECONDS = 30L
    private const val PERIODIC_WORK_NAME = "gemma-accumulation-periodic"
    private const val ONE_TIME_WORK_NAME = "gemma-accumulation-now"
}
