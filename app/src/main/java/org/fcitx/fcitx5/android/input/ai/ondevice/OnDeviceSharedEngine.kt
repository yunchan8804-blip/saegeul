/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors

/**
 * 프로세스에 하나뿐인 온디바이스 Gemma [Engine].
 *
 * 자동 추천, 문맥 완성·재료 생성, 개인 그래프 보강이 모두 이 엔진을 함께 쓴다. 누가 생성하는지는 여전히
 * [OnDeviceGenerationControl]의 lease가 정하지만, lease가 넘어가도 엔진은 닫지 않는다. 예전에는 목적마다
 * 엔진을 새로 만들고 닫아서, 목적이 바뀔 때마다 GPU 초기화(수 초~십여 초)를 다시 치렀고, 그동안 GPU를 다퉈
 * 키보드 화면이 멈췄다.
 *
 * 엔진을 닫는 경우: 메모리 부족 신호, 자원 조건(배터리·발열) 위반, 엔진 수준 오류, 모델 파일 변경,
 * 자동 추천 끄기, 서비스 종료. 쓰는 곳이 남아 있으면 마지막 사용이 끝난 뒤에 닫는다.
 */
object OnDeviceSharedEngine {

    data class ModelKey(val path: String, val length: Long, val modifiedAt: Long)

    sealed interface Flavor {
        data object Gpu : Flavor
        data class Cpu(val threadCount: Int? = null) : Flavor
    }

    /** 엔진이 초기화되지 못했다(`isInitialized()`가 거짓). GPU→CPU 대체 판단에 쓰인다. */
    class InitializationFailedException(cause: Throwable) : IllegalStateException("ENGINE_INITIALIZATION_FAILED", cause)

    typealias Use = SharedWarmResource.Use<Engine, Flavor>

    private const val MAX_NUM_TOKENS = 2048

    private val resource = SharedWarmResource<ModelKey, Engine, Flavor>(
        create = ::createEngine,
        close = { engine ->
            try {
                engine.close()
            } catch (error: Throwable) {
                Timber.w("Shared Gemma engine close failed: %s", error.javaClass.simpleName)
            }
        },
        // GPU를 원하는데 CPU 엔진만 있고 아무도 쓰지 않으면 GPU로 바꾼다. 단 키보드가 떠 있을 때는 바꾸지 않는다:
        // GPU 초기화가 화면 그리기와 GPU를 다퉈 키보드가 멈춘다. 그 반대(CPU를 원하는데 GPU가 따뜻함)는
        // 그대로 쓴다: 더 빠르고, 다시 만드는 비용이 훨씬 크다.
        shouldReplace = { current, preferred ->
            preferred is Flavor.Gpu && current !is Flavor.Gpu && !OnDeviceGenerationControl.isInputViewVisible
        },
        closeExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "SharedGemmaEngineClose").apply { isDaemon = true }
        }
    )

    val isWarm: Boolean
        get() = resource.isWarm

    val warmFlavor: Flavor?
        get() = resource.warmFlavor

    fun keyOf(model: File): ModelKey {
        val canonical = model.canonicalFile
        return ModelKey(canonical.path, canonical.length(), canonical.lastModified())
    }

    fun isWarmFor(model: File): Boolean = resource.isWarmFor(keyOf(model))

    /**
     * [model]의 초기화된 엔진을 돌려준다. 이미 따뜻하면 즉시, 아니면 만들어 초기화한다(블로킹, IO 스레드 전용).
     * 다 쓰면 반드시 [Use.release]한다.
     */
    fun acquire(model: File, preferred: Flavor): Use =
        resource.acquire(keyOf(model), preferred, SystemClock::elapsedRealtime)

    /** 엔진을 닫아 달라고 요청한다. 블로킹하지 않으며, 쓰는 곳이 있으면 다 쓴 뒤에 닫는다. */
    fun requestClose(reason: String) {
        Timber.i("Shared Gemma engine close requested reason=%s", reason)
        resource.requestClose(reason)
    }

    private fun createEngine(key: ModelKey, flavor: Flavor): Engine {
        val engine = Engine(
            EngineConfig(
                modelPath = key.path,
                backend = when (flavor) {
                    Flavor.Gpu -> Backend.GPU()
                    is Flavor.Cpu -> Backend.CPU(flavor.threadCount)
                },
                maxNumTokens = MAX_NUM_TOKENS,
                cacheDir = NO_CACHE_DIRECTORY
            )
        )
        try {
            engine.initialize()
        } catch (error: Throwable) {
            val initialized = runCatching { engine.isInitialized() }.getOrDefault(false)
            runCatching { engine.close() }
            if (!initialized) throw InitializationFailedException(error)
            throw error
        }
        Timber.i("Shared Gemma engine initialized backend=%s", flavor)
        return engine
    }
}
