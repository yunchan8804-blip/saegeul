/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.debug.gemma

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedMaterialPolicy
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl

class GemmaAccumulationWorker(
    appContext: Context,
    parameters: WorkerParameters
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val store = GemmaAccumulationStore.get(applicationContext)
        if (!processMutex.tryLock()) {
            return Result.success()
        }
        try {
            currentCoroutineContext().ensureActive()
            store.load()
            if (isStopped || !store.state.value.enabled) return Result.success()
            if (!canStart()) {
                store.recordBlocked(blockedMessage())
                return Result.success()
            }

            val model = GemmaModelFiles.modelFile(applicationContext)
            if (!model.isFile) {
                store.recordFailure(IllegalStateException("검증된 Gemma 모델이 없습니다."))
                return Result.failure()
            }

            val generator = GemmaMaterialGenerator(applicationContext)
            GemmaAccumulationRuntime.register(generator)
            try {
                val startedAt = SystemClock.elapsedRealtime()
                var generatedContexts = 0
                while (
                    generatedContexts < MAX_CONTEXTS_PER_RUN &&
                    SystemClock.elapsedRealtime() - startedAt < START_BUDGET_MS
                ) {
                    currentCoroutineContext().ensureActive()
                    if (isStopped || !store.state.value.enabled || !canStart()) {
                        store.recordBlocked(blockedMessage())
                        return Result.success()
                    }
                    val plan = store.nextPlan() ?: return Result.success()
                    if (!store.markAttemptStarted(plan)) return Result.success()

                    var resultCommitted = false
                    try {
                        val result = generateWithWatchdog(store, generator, model, plan.prefix)
                        currentCoroutineContext().ensureActive()
                        if (isStopped) {
                            throw CancellationException("WorkManager가 축적 작업을 중지했습니다.")
                        }
                        if (!store.state.value.enabled || !canStart()) {
                            throw CancellationException("안전 조건이 바뀌어 생성 결과를 저장하지 않습니다.")
                        }
                        val committed = store.commitIfEnabled(plan.prefix) { bank ->
                            val report = bank.addGeneratedForPrefix(
                                result.text,
                                plan.prefix,
                                GemmaModelFiles.MODEL_ID,
                                GemmaModelFiles.MODEL_SHA256
                            )
                            resultCommitted = true
                            report
                        }
                        if (committed == null) {
                            throw CancellationException("축적이 꺼져 생성 결과를 저장하지 않습니다.")
                        }
                        generatedContexts++
                    } catch (error: CancellationException) {
                        if (!resultCommitted) rollbackCancelledAttempt(store, plan.prefix)
                        if (isStopped || !currentCoroutineContext().isActive) throw error
                        store.recordBlocked(blockedMessage())
                        return Result.success()
                    } catch (error: IllegalStateException) {
                        if (isStopped || !store.state.value.enabled || !canStart() ||
                            error.message?.contains("사용 중") == true
                        ) {
                            if (!resultCommitted) rollbackCancelledAttempt(store, plan.prefix)
                            store.recordBlocked(blockedMessage())
                            return Result.success()
                        }
                        store.recordFailure(error)
                        return Result.failure()
                    } catch (error: Exception) {
                        store.recordFailure(error)
                        return Result.failure()
                    }
                }
                store.finishRun()
                return Result.success()
            } finally {
                GemmaAccumulationRuntime.unregister(generator)
                generator.cancel()
            }
        } finally {
            processMutex.unlock()
        }
    }

    private suspend fun rollbackCancelledAttempt(store: GemmaAccumulationStore, prefix: String) {
        withContext(Dispatchers.IO + NonCancellable) {
            store.rollbackAttempt(prefix)
        }
    }

    private suspend fun generateWithWatchdog(
        store: GemmaAccumulationStore,
        generator: GemmaMaterialGenerator,
        model: java.io.File,
        prefix: String
    ): GemmaMaterialGenerator.GenerationResult = coroutineScope {
        val watchdog = launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (isStopped || !store.state.value.enabled || !canStart()) {
                    generator.cancel()
                    return@launch
                }
            }
        }
        try {
            generator.generate(
                modelFile = model,
                useGpu = false,
                prompt = GeneratedMaterialPolicy.promptFor(prefix)
            )
        } finally {
            watchdog.cancelAndJoin()
        }
    }

    private fun canStart(): Boolean =
        isChargingAndBatteryAboveMinimum() &&
            !isThermalLimited() &&
            !isLowMemory() &&
            !OnDeviceGenerationControl.isKeyboardActive

    private fun blockedMessage(): String = when {
        OnDeviceGenerationControl.isKeyboardActive -> "키보드 사용 중에는 생성하지 않습니다."
        !isChargingAndBatteryAboveMinimum() -> "충전 중이고 배터리가 15%를 넘어야 생성합니다."
        isThermalLimited() -> "기기 온도가 높아 생성을 미룹니다."
        isLowMemory() -> "메모리 부족 상태라 생성을 미룹니다."
        else -> "안전 조건이 충족되지 않았습니다."
    }

    private fun isChargingAndBatteryAboveMinimum(): Boolean {
        val battery = applicationContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return false
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0
        return plugged > 0 &&
            (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) &&
            percent > MIN_BATTERY_PERCENT
    }

    private fun isThermalLimited(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val power = applicationContext.getSystemService(PowerManager::class.java)
        return power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
    }

    private fun isLowMemory(): Boolean {
        val activityManager = applicationContext.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        return memory.lowMemory
    }

    private companion object {
        val processMutex = Mutex()
        const val MAX_CONTEXTS_PER_RUN = 4
        const val START_BUDGET_MS = 120_000L
        const val WATCHDOG_INTERVAL_MS = 250L
        const val MIN_BATTERY_PERCENT = 15
    }
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
