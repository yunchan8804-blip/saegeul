/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSharedEngine
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class GemmaMaterialGenerator(private val context: Context) {

    data class GenerationResult(
        val text: String,
        val initializationMs: Long,
        val generationMs: Long,
        val metrics: GemmaGenerationMetrics
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

    @Volatile
    var lastMetrics: GemmaGenerationMetrics? = null
        private set

    suspend fun generate(
        modelFile: File,
        useGpu: Boolean,
        prompt: String = FIXED_PROMPT,
        purpose: OnDeviceGenerationControl.Purpose = OnDeviceGenerationControl.Purpose.PUBLIC_MATERIAL
    ): GenerationResult = coroutineScope {
        val runReference = AtomicReference<RunState?>(null)
        val requestCancel = AtomicBoolean(false)
        val generation = async(Dispatchers.IO) {
            generateNonCancellable(modelFile, useGpu, prompt, purpose, runReference, requestCancel)
        }
        try {
            generation.await()
        } catch (error: CancellationException) {
            requestCancel.set(true)
            runReference.get()?.let(::cancelRun)
            withContext(NonCancellable) {
                generation.join()
            }
            throw error
        }
    }

    private suspend fun generateNonCancellable(
        modelFile: File,
        useGpu: Boolean,
        prompt: String,
        purpose: OnDeviceGenerationControl.Purpose,
        runReference: AtomicReference<RunState?>,
        requestCancel: AtomicBoolean
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
            RunState(++nextRunToken, SystemClock.elapsedRealtime()).also { started ->
                activeRun = started
                activeRunToken = started.token
                isRunning = true
            }
        }
        runReference.set(run)
        if (requestCancel.get()) cancelRun(run)
        val lease = OnDeviceGenerationControl.tryBegin(purpose) {
            cancelRun(run)
        } ?: run {
            synchronized(stateLock) {
                if (activeRun === run) {
                    activeRun = null
                    activeRunToken = NO_ACTIVE_RUN
                    isRunning = false
                }
            }
            throw IllegalStateException("키보드 또는 다른 온디바이스 생성 작업이 사용 중입니다.")
        }
        synchronized(stateLock) {
            if (activeRun === run) lastMetrics = null
        }

        var engineUse: OnDeviceSharedEngine.Use? = null
        var createdConversation: Conversation? = null
        var metricsRecorder: GemmaGenerationMetricsRecorder? = null
        var sampler: kotlinx.coroutines.Job? = null
        val samplerFailure = AtomicReference<Throwable?>(null)
        var verificationMs = 0L
        var initializationMs = 0L
        var conversationMs = 0L
        var generationMs = 0L
        var closeMs = 0L
        var firstTextMs: Long? = null
        var responseText: String? = null
        var failure: Throwable? = null
        var completedMetrics: GemmaGenerationMetrics? = null

        fun captureFailure(error: Throwable) {
            val previous = failure
            if (previous == null) {
                failure = error
            } else {
                previous.addSuppressed(error)
            }
        }

        try {
            val recorder = GemmaGenerationMetricsRecorder(context, run.startedAt)
            metricsRecorder = recorder
            recorder.recordStart()
            sampler = launch(Dispatchers.IO) {
                try {
                    recorder.sampleEvery(METRICS_SAMPLE_INTERVAL_MS)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    samplerFailure.compareAndSet(null, error)
                }
            }

            val verificationStartedAt = SystemClock.elapsedRealtime()
            val verifiedModel = try {
                GemmaModelFiles.requireVerifiedModel(context, run.cancelled::get)
            } finally {
                verificationMs = SystemClock.elapsedRealtime() - verificationStartedAt
            }
            if (run.cancelled.get()) throw GenerationCancelledException()
            val initializationStartedAt = SystemClock.elapsedRealtime()
            try {
                // 공유 엔진이 이미 따뜻하면 초기화 없이 그대로 쓴다(자동 추천이 쓰던 GPU 엔진 포함).
                engineUse = OnDeviceSharedEngine.acquire(
                    verifiedModel,
                    if (useGpu) OnDeviceSharedEngine.Flavor.Gpu else OnDeviceSharedEngine.Flavor.Cpu()
                )
            } finally {
                initializationMs = SystemClock.elapsedRealtime() - initializationStartedAt
            }
            if (run.cancelled.get()) throw GenerationCancelledException()

            val conversationStartedAt = SystemClock.elapsedRealtime()
            val conversation = try {
                checkNotNull(engineUse).resource.createConversation().also { createdConversation = it }
            } finally {
                conversationMs = SystemClock.elapsedRealtime() - conversationStartedAt
            }
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
                        .forEach { text ->
                            if (firstTextMs == null && text.text.isNotEmpty()) {
                                firstTextMs = SystemClock.elapsedRealtime() - run.startedAt
                            }
                            response.append(text.text)
                        }
                }
            } finally {
                isInferring = false
                generationMs = SystemClock.elapsedRealtime() - generationStartedAt
            }
            run.cancellationError.get()?.let { error ->
                throw IllegalStateException("Gemma 취소 요청을 완료하지 못했습니다.", error)
            }
            if (run.cancelled.get()) throw GenerationCancelledException()
            if (response.isBlank()) throw IllegalStateException("Gemma가 텍스트 응답을 반환하지 않았습니다.")
            responseText = response.toString()
        } catch (error: Throwable) {
            captureFailure(error)
        } finally {
            isInferring = false
            fun captureCleanupFailure(action: () -> Unit) {
                try {
                    action()
                } catch (error: Throwable) {
                    captureFailure(error)
                }
            }

            val closeStartedAt = SystemClock.elapsedRealtime()
            try {
                closeConversation(run, createdConversation)
            } catch (error: Throwable) {
                captureFailure(error)
            }
            engineUse?.release()
            // 취소가 아닌 실패는 엔진이 망가졌을 수 있으므로 다음 사용 전에 새로 만들게 한다.
            if (failure != null && failure !is CancellationException) {
                OnDeviceSharedEngine.requestClose("MATERIAL_GENERATION_FAILURE")
            }
            closeMs = SystemClock.elapsedRealtime() - closeStartedAt
            try {
                sampler?.cancelAndJoin()
            } catch (error: Throwable) {
                captureFailure(error)
            }
            samplerFailure.get()?.let(::captureFailure)
            var endThermalStatus: Int? = null
            metricsRecorder?.let { recorder ->
                captureCleanupFailure { endThermalStatus = recorder.sampleNow() }
            }
            // The lease must be released regardless of close outcome, or a conversation/engine
            // close failure above would orphan it and block every later AUTO_CONTEXT request with
            // BUSY. A false return means another purpose already preempted it, which is normal.
            if (!OnDeviceGenerationControl.end(lease)) {
                Timber.i("Generation lease already released (preempted)")
            }
            run.cancellationError.get()?.let { error ->
                captureFailure(IllegalStateException("Gemma 취소 요청을 완료하지 못했습니다.", error))
            }
            if (run.cancelled.get() && failure == null) {
                captureFailure(GenerationCancelledException())
            }
            val outcome = when (failure) {
                null -> GemmaGenerationOutcome.SUCCESS
                is CancellationException -> GemmaGenerationOutcome.CANCELLED
                else -> GemmaGenerationOutcome.FAILED
            }
            completedMetrics = metricsRecorder?.build(
                wallMs = SystemClock.elapsedRealtime() - run.startedAt,
                verificationMs = verificationMs,
                initializationMs = initializationMs,
                conversationMs = conversationMs,
                generationMs = generationMs,
                closeMs = closeMs,
                endThermalStatus = endThermalStatus,
                outcome = outcome,
                firstTextMs = firstTextMs
            )
            synchronized(stateLock) {
                if (activeRun === run) {
                    lastMetrics = completedMetrics
                    activeRun = null
                    activeRunToken = NO_ACTIVE_RUN
                    isRunning = false
                }
            }
        }

        failure?.let { throw it }
        val metrics = checkNotNull(completedMetrics) { "Gemma 생성 계측을 완료하지 못했습니다." }
        GenerationResult(
            text = checkNotNull(responseText),
            initializationMs = initializationMs,
            generationMs = generationMs,
            metrics = metrics
        )
    }

    fun cancel() {
        val run = synchronized(stateLock) { activeRun }
        if (run == null) return
        cancelRun(run)
    }

    private fun cancelRun(run: RunState) {
        run.cancelled.set(true)
        if (synchronized(stateLock) { activeRun !== run }) return
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

    private class RunState(val token: Long, val startedAt: Long) {
        val cancelled = AtomicBoolean(false)
        val cancellationError = AtomicReference<Throwable?>(null)
    }

    private data class NativeConversation(val token: Long, val conversation: Conversation)

    private companion object {
        const val NO_ACTIVE_RUN = -1L
        const val MAX_PROMPT_UTF8_BYTES = 8192
        const val METRICS_SAMPLE_INTERVAL_MS = 250L
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
