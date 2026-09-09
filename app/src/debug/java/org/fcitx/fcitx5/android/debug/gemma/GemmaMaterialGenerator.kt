/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.debug.gemma

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class GemmaMaterialGenerator(private val context: Context) {

    data class GenerationResult(
        val text: String,
        val initializationMs: Long,
        val generationMs: Long
    )

    private val stateLock = Any()
    private val nativeHandleLock = Any()
    private var nextRunToken = 0L

    @Volatile
    private var activeRunToken = NO_ACTIVE_RUN

    private var activeRun: RunState? = null
    private var activeConversation: NativeConversation? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var isInferring: Boolean = false
        private set

    suspend fun generate(
        modelFile: File,
        useGpu: Boolean,
        prompt: String = FIXED_PROMPT
    ): GenerationResult = coroutineScope {
        val generation = async(Dispatchers.IO) {
            generateNonCancellable(modelFile, useGpu, prompt)
        }
        try {
            generation.await()
        } catch (error: CancellationException) {
            cancel()
            withContext(NonCancellable) {
                generation.join()
            }
            throw error
        }
    }

    private suspend fun generateNonCancellable(
        modelFile: File,
        useGpu: Boolean,
        prompt: String
    ): GenerationResult = withContext(Dispatchers.IO + NonCancellable) {
            require(prompt.isNotBlank()) { "Gemma prompt는 비어 있을 수 없습니다." }
            require(prompt.toByteArray(Charsets.UTF_8).size <= MAX_PROMPT_UTF8_BYTES) {
                "Gemma prompt는 UTF-8 기준 8192바이트 이하여야 합니다."
            }
            val internalModel = GemmaModelFiles.modelFile(context).canonicalFile
            require(modelFile.canonicalFile == internalModel) {
                "검증된 내부 Gemma 모델 경로만 사용할 수 있습니다."
            }
            val run = synchronized(stateLock) {
                check(!isRunning) { "Gemma 생성이 이미 진행 중입니다." }
                val started = RunState(++nextRunToken)
                if (!OnDeviceGenerationControl.begin(::cancel)) {
                    throw IllegalStateException("키보드 또는 다른 온디바이스 생성 작업이 사용 중입니다.")
                }
                activeRun = started
                activeRunToken = started.token
                isRunning = true
                started
            }

            var engine: Engine? = null
            var createdConversation: Conversation? = null
            var failure: Throwable? = null
            try {
                val verifiedModel = GemmaModelFiles.requireVerifiedModel(context, run.cancelled::get)
                if (run.cancelled.get()) throw GenerationCancelledException()
                val initializationStartedAt = SystemClock.elapsedRealtime()
                val cacheDirectory = File(context.cacheDir, "gemma")
                if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
                    throw IllegalStateException("Gemma 캐시 폴더를 만들 수 없습니다.")
                }
                engine = Engine(
                    EngineConfig(
                        modelPath = verifiedModel.absolutePath,
                        backend = if (useGpu) Backend.GPU() else Backend.CPU(),
                        maxNumTokens = MAX_NUM_TOKENS,
                        cacheDir = cacheDirectory.absolutePath
                    )
                )
                engine.initialize()
                val initializationMs = SystemClock.elapsedRealtime() - initializationStartedAt
                if (run.cancelled.get()) throw GenerationCancelledException()

                val conversation = engine.createConversation()
                createdConversation = conversation
                if (run.cancelled.get()) {
                    throw GenerationCancelledException()
                }
                val published = synchronized(nativeHandleLock) {
                    if (activeRunToken == run.token && !run.cancelled.get()) {
                        activeConversation = NativeConversation(run.token, conversation)
                        true
                    } else {
                        false
                    }
                }
                if (!published) {
                    throw GenerationCancelledException()
                }
                if (run.cancelled.get()) {
                    requestNativeCancellation(run)
                    throw GenerationCancelledException()
                }
                val generationStartedAt = SystemClock.elapsedRealtime()
                val response = StringBuilder()
                isInferring = true
                try {
                    conversation.sendMessageAsync(prompt).collect { message ->
                        if (run.cancelled.get()) {
                            requestNativeCancellation(run)
                            return@collect
                        }
                        message.contents.contents
                            .filterIsInstance<Content.Text>()
                            .forEach { response.append(it.text) }
                    }
                } finally {
                    isInferring = false
                }
                val generationMs = SystemClock.elapsedRealtime() - generationStartedAt
                run.cancellationError.get()?.let { error ->
                    throw IllegalStateException("Gemma 취소 요청을 완료하지 못했습니다.", error)
                }
                if (run.cancelled.get()) throw GenerationCancelledException()
                if (response.isBlank()) throw IllegalStateException("Gemma가 텍스트 응답을 반환하지 않았습니다.")
                GenerationResult(response.toString(), initializationMs, generationMs)
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                isInferring = false
                var cleanupFailure: Throwable? = null
                fun captureCleanupFailure(action: () -> Unit) {
                    try {
                        action()
                    } catch (error: Throwable) {
                        val previous = cleanupFailure
                        if (previous == null) {
                            cleanupFailure = error
                        } else {
                            previous.addSuppressed(error)
                        }
                    }
                }
                captureCleanupFailure { closeConversation(run, createdConversation) }
                captureCleanupFailure { engine?.close() }
                captureCleanupFailure { OnDeviceGenerationControl.end() }
                synchronized(stateLock) {
                    if (activeRun === run) {
                        activeRun = null
                        activeRunToken = NO_ACTIVE_RUN
                        isRunning = false
                    }
                }
                cleanupFailure?.let { error ->
                    failure?.addSuppressed(error) ?: throw error
                }
            }
        }

    fun cancel() {
        val run = synchronized(stateLock) {
            activeRun?.also { it.cancelled.set(true) }
        }
        if (run == null) return
        FcitxApplication.getInstance().applicationScope.launch(Dispatchers.IO) {
            requestNativeCancellation(run)
        }
    }

    private fun requestNativeCancellation(run: RunState) {
        synchronized(nativeHandleLock) {
            val current = activeConversation
            if (activeRunToken != run.token || current?.token != run.token) return
            try {
                current.conversation.cancelProcess()
            } catch (error: Throwable) {
                run.cancellationError.compareAndSet(null, error)
            }
        }
    }

    private fun closeConversation(run: RunState, conversation: Conversation?) {
        synchronized(nativeHandleLock) {
            val current = activeConversation
            if (current?.token == run.token) activeConversation = null
            val target = current?.takeIf { it.token == run.token }?.conversation ?: conversation
            target?.close()
        }
    }

    private class GenerationCancelledException : CancellationException("Gemma 생성이 취소되었습니다.")

    private class RunState(val token: Long) {
        val cancelled = AtomicBoolean(false)
        val cancellationError = AtomicReference<Throwable?>(null)
    }

    private data class NativeConversation(val token: Long, val conversation: Conversation)

    private companion object {
        const val NO_ACTIVE_RUN = -1L
        const val MAX_NUM_TOKENS = 2048
        const val MAX_PROMPT_UTF8_BYTES = 8192
        val FIXED_PROMPT = """
            이것은 비개인적인 한국어 문장 재료 생성 실험입니다. 개인 정보, 사용자 입력, 대화 기록을 사용하지 마세요.
            한국어 일상·업무 상황의 자연스러운 완성 문장 8개를 JSON 문자열 배열 하나로만 반환하세요.
            배열의 1, 2번 문장은 정확히 "약속을 ", 3, 4번 문장은 정확히 "회의 자료를 ",
            5, 6번 문장은 정확히 "오늘 저녁 ", 7번 문장은 정확히 "약속을 ",
            8번 문장은 정확히 "회의 자료를 "로 시작해야 합니다.
            지정한 시작 구절을 문자 그대로 보존하고 조사 삭제나 다른 표현으로 대체하지 마세요. 나머지는 자연스러운 새 문장으로 생성하세요.
            각 문장은 3~12어절이고 반드시 . ? ! 중 하나로 끝나야 합니다.
            추론 과정, 설명, Markdown, 코드 블록, 배열 밖의 텍스트는 출력하지 마세요.
        """.trimIndent()
    }
}
