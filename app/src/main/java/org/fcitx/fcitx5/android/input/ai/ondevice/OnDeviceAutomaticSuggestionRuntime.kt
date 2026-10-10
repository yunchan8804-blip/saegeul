/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Native backend for the automatic coordinator, gated per-device by [OnDeviceAiSupport].
 *
 * [useGpu] is the saved GPU setting. The backend of each fresh shared-engine acquisition is chosen at
 * that moment by [AutomaticSuggestionBackendPolicy]: a cold engine is warmed on CPU while the keyboard
 * is visible and on GPU once it is hidden. The choice never changes the saved setting.
 */
class OnDeviceAutomaticSuggestionRuntime(
    context: Context,
    private val useGpu: Boolean = true
) : OnDeviceSuggestionCoordinator.Backend {

    private val appContext = context.applicationContext

    @Volatile
    private var selectedChoice: AutomaticSuggestionBackendPolicy.Choice? = null

    private val engine = OnDeviceSuggestionEngine(
        context = context,
        backendProvider = ::selectBackend
    )
    private val preparationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preparationLock = Any()
    private val invocationMutex = Mutex()
    private var preparation: Deferred<OnDeviceSuggestionEngine.PreparationResult>? = null

    val supported: Boolean = OnDeviceAiSupport.isSupported

    val isPreparing: Boolean
        get() = engine.isPreparing

    val isRunning: Boolean
        get() = engine.isRunning

    val isWarm: Boolean
        get() = engine.isWarm

    val terminalFailureCode: String?
        get() = engine.terminalFailureCode

    /** 마지막으로 공유 엔진을 얻을 때 GPU를 골랐는지. GPU 초기화 실패 판단에만 쓴다. */
    val attemptedGpu: Boolean
        get() = selectedChoice == AutomaticSuggestionBackendPolicy.Choice.Gpu

    /** 지금 쓰는(또는 마지막으로 고른) 백엔드 이름. 상태 기록용이다. */
    val backendLabel: String
        get() = when (engine.heldFlavor) {
            is OnDeviceSharedEngine.Flavor.Cpu -> "cpu"
            OnDeviceSharedEngine.Flavor.Gpu -> "gpu"
            null -> if (selectedChoice?.let { attemptedGpu } ?: useGpu) "gpu" else "cpu"
        }

    private fun selectBackend(): OnDeviceSuggestionEngine.BackendSelection {
        val decision = AutomaticSuggestionBackendPolicy.choose(
            gpuEnabled = useGpu,
            engineWarm = OnDeviceSharedEngine.isWarm,
            keyboardVisible = OnDeviceGenerationControl.isInputViewVisible
        )
        selectedChoice = decision.choice
        Timber.i("Automatic suggestion backend: %s", decision.reason.logText)
        return when (decision.choice) {
            AutomaticSuggestionBackendPolicy.Choice.Gpu -> OnDeviceSuggestionEngine.BackendSelection.Gpu
            AutomaticSuggestionBackendPolicy.Choice.Cpu ->
                OnDeviceSuggestionEngine.BackendSelection.Cpu(AutomaticSuggestionBackendPolicy.CPU_THREAD_COUNT)
        }
    }

    /**
     * 키보드가 숨겨졌고 이 런타임이 CPU 엔진 사용을 쥐고 있으며 GPU 설정이 켜져 있으면 그 사용을 놓는다.
     * 생성·예열이 진행 중이면 끝난 뒤에 다시 확인한다. 놓았으면 true. 다음 획득이 숨김 상태에서 GPU로
     * 교체한다(다른 목적이 엔진을 쓰고 있으면 교체되지 않고 CPU로 계속 쓴다).
     */
    suspend fun releaseCpuForGpu(): Boolean = invocationMutex.withLock {
        if (!AutomaticSuggestionBackendPolicy.shouldReleaseCpuForGpu(
                gpuEnabled = useGpu,
                heldFlavor = engine.heldFlavor,
                keyboardVisible = OnDeviceGenerationControl.isInputViewVisible
            )
        ) {
            return@withLock false
        }
        Timber.i("Automatic suggestion backend: keyboard hidden -> release CPU for GPU")
        try {
            engine.close()
        } catch (error: OnDeviceSuggestionEngine.OnDeviceSuggestionException) {
            Timber.w(error, "OnDeviceAutomaticSuggestionRuntime release failed code=%s", error.code)
            throw OnDeviceSuggestionCoordinator.BackendException(error.code)
        }
        true
    }

    suspend fun warmUp() = invocationMutex.withLock {
        try {
            prepareIfNeeded()
        } catch (error: OnDeviceSuggestionEngine.OnDeviceSuggestionException) {
            Timber.w(error, "OnDeviceAutomaticSuggestionRuntime warmUp failed code=%s", error.code)
            throw OnDeviceSuggestionCoordinator.BackendException(error.code)
        }
    }

    override suspend fun generate(prompt: String): String = invocationMutex.withLock {
        try {
            prepareIfNeeded()
            val response = engine.suggest(prompt).text
            writeLastPromptDebugFile(prompt, response)
            response
        } catch (error: OnDeviceSuggestionEngine.OnDeviceSuggestionException) {
            Timber.w(error, "OnDeviceAutomaticSuggestionRuntime generate failed code=%s", error.code)
            throw OnDeviceSuggestionCoordinator.BackendException(error.code)
        }
    }

    /**
     * Debug-only aid: overwrites a single internal-storage file with the most recent prompt/raw
     * response pair so it can be pulled off a debug build for inspection. Never external storage,
     * never logcat, and a write failure must not affect the suggestion itself.
     */
    private suspend fun writeLastPromptDebugFile(prompt: String, response: String) {
        try {
            withContext(Dispatchers.IO) {
                val dir = File(appContext.filesDir, "debug").apply { mkdirs() }
                File(dir, "ondevice_last_prompt.txt")
                    .writeText("PROMPT:\n$prompt\n\nRESPONSE:\n$response\n")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.w(
                "OnDeviceAutomaticSuggestionRuntime debug prompt write failed code=%s",
                error.javaClass.simpleName
            )
        }
    }

    override fun cancel() {
        engine.cancel()
    }

    override suspend fun close() {
        val pending = synchronized(preparationLock) { preparation }
        pending?.cancel()
        invocationMutex.withLock {
            try {
                engine.close()
                pending?.join()
                synchronized(preparationLock) {
                    if (preparation === pending) preparation = null
                }
            } catch (error: OnDeviceSuggestionEngine.OnDeviceSuggestionException) {
                Timber.w(error, "OnDeviceAutomaticSuggestionRuntime close failed code=%s", error.code)
                throw OnDeviceSuggestionCoordinator.BackendException(error.code)
            }
        }
    }

    private suspend fun prepareIfNeeded() {
        if (engine.isWarm) return
        val pending = synchronized(preparationLock) {
            preparation?.takeIf { it.isActive } ?: preparationScope.async { engine.prepare() }.also {
                preparation = it
            }
        }
        pending.await()
    }
}
