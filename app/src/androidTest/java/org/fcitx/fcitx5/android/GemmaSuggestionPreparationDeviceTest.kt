/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionEngine
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measures native preparation and the keyboard-hide cancellation boundary.
 *
 * This is not actual IME execution or offline proof.
 */
class GemmaSuggestionPreparationDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()

    @Test
    fun prepareSurvivesTypingCancelAndHideStopsNativeInference() = runBlocking<Unit>(Dispatchers.Default) {
        val context = instrumentation.targetContext.applicationContext
        val backendArgument = requiredBackend()
        val cpuThreadsArgument = requiredCpuThreads()
        require(backendArgument == "cpu" || backendArgument == "gpu") {
            "backend 인자는 cpu 또는 gpu여야 합니다: $backendArgument"
        }
        require(backendArgument == "cpu" || cpuThreadsArgument == 0) {
            "backend=gpu에서는 cpuThreads=0만 허용합니다: $cpuThreadsArgument"
        }
        val backend = when (backendArgument) {
            "cpu" -> OnDeviceSuggestionEngine.BackendSelection.Cpu(
                threadCount = cpuThreadsArgument.takeUnless { it == 0 }
            )
            "gpu" -> OnDeviceSuggestionEngine.BackendSelection.Gpu
            else -> error("backend 인자가 허용 범위를 벗어났습니다: $backendArgument")
        }
        val originalKeyboardActive = OnDeviceGenerationControl.isKeyboardActive
        val evidence = JSONObject()
            .put("evidenceScope", "native_preparation_and_hide_gate_not_actual_ime")
            .put("actualIme", false)
            .put("offlineProof", false)
            .put("backend", backendArgument)
            .put("cpuThreadsArgument", cpuThreadsArgument)
            .put("preparingObserved", false)
            .put("typingCancelDidNotAbortPrepare", false)
            .put("nativeInferenceObserved", false)
            .put("prepareVerificationMs", JSONObject.NULL)
            .put("prepareInitializationMs", JSONObject.NULL)
            .put("prepareTotalMs", JSONObject.NULL)
            .put("prepareEngineReused", JSONObject.NULL)
            .put("hideStopMs", JSONObject.NULL)
            .put("actualSafeFailureCode", JSONObject.NULL)
        var engine: OnDeviceSuggestionEngine? = null
        var bodyFailure: Throwable? = null

        try {
            assertFalse("준비 측정 시작 전에 다른 native lease가 실행 중입니다.", OnDeviceGenerationControl.isGenerating)
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
            assertTrue("AUTO_CONTEXT 준비 측정은 테스트 키보드 gate가 활성 상태여야 합니다.", OnDeviceGenerationControl.isKeyboardActive)

            val createdEngine = OnDeviceSuggestionEngine(context, backend)
            engine = createdEngine
            val preparation = async {
                withTimeout(PREPARE_BUDGET_MS) { createdEngine.prepare() }
            }
            awaitCondition(PREPARE_BUDGET_MS) { createdEngine.isPreparing }
            evidence.put("preparingObserved", true)
            createdEngine.cancel()

            val preparationResult = preparation.await()
            evidence.put("prepareVerificationMs", preparationResult.verificationMs)
                .put("prepareInitializationMs", preparationResult.initializationMs)
                .put("prepareTotalMs", preparationResult.totalMs)
                .put("prepareEngineReused", preparationResult.engineReused)
                .put("typingCancelDidNotAbortPrepare", true)
            assertFalse("준비 완료 뒤 Engine이 실행 중입니다.", createdEngine.isRunning)
            assertFalse("준비 완료 뒤 Engine이 추론 중입니다.", createdEngine.isInferring)
            assertFalse("준비 완료 뒤 preparing 상태가 남아 있습니다.", createdEngine.isPreparing)
            assertTrue("준비 완료 뒤 warm Engine이 유지되지 않았습니다.", createdEngine.isWarm)
            assertTrue("준비 완료 뒤 native lease가 유지되지 않았습니다.", OnDeviceGenerationControl.isGenerating)

            val input = OnDeviceSuggestionPolicy.Input(
                textBeforeCursor = PUBLIC_INPUT,
                packageName = PUBLIC_PACKAGE_NAME,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                imeAction = EditorInfo.IME_ACTION_NONE,
                mode = OnDeviceSuggestionPolicy.Mode.WORD
            )
            val inference = async {
                runCatching { createdEngine.suggest(OnDeviceSuggestionPolicy.promptFor(input)) }
            }
            awaitCondition(PREPARE_BUDGET_MS) { createdEngine.isInferring }
            evidence.put("nativeInferenceObserved", true)
            val hideStartedAt = SystemClock.elapsedRealtime()
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
            val stopFailure = withTimeout(STOP_BUDGET_MS) {
                val inferenceResult = inference.await()
                assertTrue(
                    "키보드 hide 뒤 native 추론이 성공 응답을 반환했습니다.",
                    inferenceResult.isFailure
                )
                val failure = inferenceResult.exceptionOrNull()
                assertTrue(
                    "키보드 hide 뒤 OnDeviceSuggestionException 취소가 필요합니다: ${failure?.javaClass?.name}",
                    failure is OnDeviceSuggestionEngine.OnDeviceSuggestionException
                )
                val onDeviceFailure = failure as OnDeviceSuggestionEngine.OnDeviceSuggestionException
                evidence.put("actualSafeFailureCode", onDeviceFailure.code)
                assertEquals("키보드 hide 뒤 취소 코드는 CANCELLED여야 합니다.", "CANCELLED", onDeviceFailure.code)
                while (
                    createdEngine.isRunning ||
                    createdEngine.isInferring ||
                    createdEngine.isWarm ||
                    OnDeviceGenerationControl.isGenerating
                ) {
                    delay(POLL_INTERVAL_MS)
                }
                onDeviceFailure
            }
            evidence.put("hideStopMs", SystemClock.elapsedRealtime() - hideStartedAt)
                .put(
                    "actualSafeFailureCode",
                    (stopFailure as? OnDeviceSuggestionEngine.OnDeviceSuggestionException)?.code ?: JSONObject.NULL
                )
            assertTrue(
                "키보드 hide 뒤 OnDeviceSuggestionException 취소가 필요합니다: ${stopFailure.javaClass.name}",
                stopFailure is OnDeviceSuggestionEngine.OnDeviceSuggestionException
            )
            assertFalse("키보드 hide 뒤 Engine 실행이 남아 있습니다.", createdEngine.isRunning)
            assertFalse("키보드 hide 뒤 Engine 추론이 남아 있습니다.", createdEngine.isInferring)
            assertFalse("키보드 hide 뒤 warm Engine이 남아 있습니다.", createdEngine.isWarm)
            assertFalse("키보드 hide 뒤 native lease가 남아 있습니다.", OnDeviceGenerationControl.isGenerating)
        } catch (error: Throwable) {
            bodyFailure = error
            evidence.put("executionFailure", true)
                .put("exceptionClass", error.javaClass.name)
            (error as? OnDeviceSuggestionEngine.OnDeviceSuggestionException)?.let { failure ->
                evidence.put("actualSafeFailureCode", failure.code)
            }
            throw error
        } finally {
            withContext(NonCancellable) {
                var cleanupFailure: Throwable? = null
                suspend fun captureCleanup(action: suspend () -> Unit) {
                    try {
                        action()
                    } catch (error: Throwable) {
                        cleanupFailure?.addSuppressed(error) ?: run { cleanupFailure = error }
                    }
                }

                engine?.let { activeEngine ->
                    captureCleanup {
                        withTimeout(STOP_BUDGET_MS) { activeEngine.close() }
                    }
                    captureCleanup {
                        withTimeout(STOP_BUDGET_MS) { activeEngine.close() }
                    }
                    captureCleanup {
                        assertFalse("close 뒤 warm Engine이 남아 있습니다.", activeEngine.isWarm)
                        assertFalse("close 뒤 native 요청이 남아 있습니다.", activeEngine.isRunning)
                        assertFalse("close 뒤 준비 요청이 남아 있습니다.", activeEngine.isPreparing)
                        assertFalse("close 뒤 native 추론이 남아 있습니다.", activeEngine.isInferring)
                    }
                }
                captureCleanup {
                    assertFalse("close 뒤 native lease가 남아 있습니다.", OnDeviceGenerationControl.isGenerating)
                }
                captureCleanup {
                    OnDeviceGenerationControl.onKeyboardVisibilityChanged(originalKeyboardActive)
                    assertTrue(
                        "테스트 키보드 gate를 원래 상태로 복원하지 못했습니다.",
                        OnDeviceGenerationControl.isKeyboardActive == originalKeyboardActive
                    )
                }
                evidence.put("closed", engine?.let { !it.isWarm && !it.isRunning && !it.isPreparing && !it.isInferring } ?: true)
                    .put("leaseReleased", !OnDeviceGenerationControl.isGenerating)
                    .put("restored", OnDeviceGenerationControl.isKeyboardActive == originalKeyboardActive)
                captureCleanup {
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString(EVIDENCE_KEY, evidence.toString())
                    })
                }
                bodyFailure?.let { primary ->
                    cleanupFailure?.let(primary::addSuppressed)
                } ?: cleanupFailure?.let { throw it }
            }
        }
    }

    private suspend fun awaitCondition(timeoutMs: Long, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) {
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun requiredBackend(): String =
        arguments.getString("backend") ?: "cpu"

    private fun requiredCpuThreads(): Int {
        val raw = arguments.getString("cpuThreads") ?: "0"
        val value = raw.toIntOrNull()
        require(value != null && value in setOf(0, 2, 4)) {
            "cpuThreads 인자는 0, 2, 4 중 하나여야 합니다: $raw"
        }
        return value
    }

    private companion object {
        const val EVIDENCE_KEY = "gemmaSuggestionPreparationBenchmarkJson"
        const val PUBLIC_PACKAGE_NAME = "org.fcitx.fcitx5.android.publicbenchmark"
        const val PUBLIC_INPUT = "창문으로 햇빛이 들어와서 "
        const val PREPARE_BUDGET_MS = 120_000L
        const val STOP_BUDGET_MS = 30_000L
        const val POLL_INTERVAL_MS = 10L
    }
}
