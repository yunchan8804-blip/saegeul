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

/** Native backend for the automatic coordinator, gated per-device by [OnDeviceAiSupport]. */
class OnDeviceAutomaticSuggestionRuntime(
    context: Context,
    useGpu: Boolean = true
) : OnDeviceSuggestionCoordinator.Backend {

    private val appContext = context.applicationContext

    private val engine = OnDeviceSuggestionEngine(
        context = context,
        backend = if (useGpu) {
            OnDeviceSuggestionEngine.BackendSelection.Gpu
        } else {
            OnDeviceSuggestionEngine.BackendSelection.Cpu(threadCount = 2)
        }
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

    suspend fun warmUp() = invocationMutex.withLock {
        try {
            prepareIfNeeded()
        } catch (error: OnDeviceSuggestionEngine.OnDeviceSuggestionException) {
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

    /**
     * 키보드가 떠 있는 동안에는 차가운 엔진을 GPU로 초기화하지 않는다. GPU 가중치 변환이 화면 그리기와 GPU를
     * 다퉈 키보드가 1초 넘게 멈추기 때문이다. 초기화는 키보드가 숨겨진 뒤 워밍업이 맡는다.
     */
    override fun isReadyToGenerate(): Boolean =
        engine.isWarm || OnDeviceSharedEngine.isWarm || !OnDeviceGenerationControl.isInputViewVisible

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
