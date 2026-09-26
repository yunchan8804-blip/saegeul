/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.text.InputType
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionEngine
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measures one debug-only, warm native suggestion host with fixed public inputs.
 *
 * This is a host benchmark, not automatic IME execution, candidate display proof, or Korean
 * quality evaluation. It deliberately leaves the public-material scheduler unchanged.
 */
class GemmaSuggestionEngineDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()

    @Test
    fun benchmarkWarmEngineWithFixedPublicInputs() = runBlocking<Unit>(Dispatchers.Default) {
        val context = instrumentation.targetContext.applicationContext
        val backendArgument = requiredBackend()
        val cpuThreadsArgument = requiredCpuThreads()
        val sampleCount = requiredSampleCount()
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
        val samples = JSONArray()
        val evidence = JSONObject()
            .put("evidenceScope", "warm_engine_host_benchmark_not_automatic_ime")
            .put("automaticImeConnected", false)
            .put("actualKeyboardChanged", false)
            .put("keyboardGateSource", "OnDeviceGenerationControl.onKeyboardVisibilityChanged(true) test subruntime")
            .put("fixedPublicInputsOnly", true)
            .put("offlineProof", false)
            .put("networkVerificationPerformed", false)
            .put("backend", backendArgument)
            .put("cpuThreadsArgument", cpuThreadsArgument)
            .put("requestedSamples", sampleCount)
            .put("samples", samples)
        var engine: OnDeviceSuggestionEngine? = null
        var bodyFailure: Throwable? = null

        try {
            assertFalse("warm Engine 측정 시작 전에 다른 native lease가 실행 중입니다.", OnDeviceGenerationControl.isGenerating)
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
            evidence.put("keyboardActiveForAutoContextGate", OnDeviceGenerationControl.isKeyboardActive)
            assertTrue("AUTO_CONTEXT 측정은 테스트 키보드 gate가 활성 상태여야 합니다.", OnDeviceGenerationControl.isKeyboardActive)

            val createdEngine = OnDeviceSuggestionEngine(context, backend)
            engine = createdEngine
            assertFalse("새 warm Engine은 시작 시 warm 상태일 수 없습니다.", createdEngine.isWarm)
            assertFalse("새 warm Engine은 시작 시 실행 중일 수 없습니다.", createdEngine.isRunning)

            PUBLIC_INPUTS.take(sampleCount).forEachIndexed { index, textBeforeCursor ->
                val input = OnDeviceSuggestionPolicy.Input(
                    textBeforeCursor = textBeforeCursor,
                    packageName = PUBLIC_PACKAGE_NAME,
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                    imeAction = EditorInfo.IME_ACTION_NONE,
                    mode = OnDeviceSuggestionPolicy.Mode.WORD
                )
                val sentenceInput = OnDeviceSuggestionPolicy.Input(
                    textBeforeCursor = textBeforeCursor,
                    packageName = PUBLIC_PACKAGE_NAME,
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                    imeAction = EditorInfo.IME_ACTION_NONE,
                    mode = OnDeviceSuggestionPolicy.Mode.SENTENCE
                )
                val result = withTimeout(GENERATION_BUDGET_MS) {
                    createdEngine.suggest(OnDeviceSuggestionPolicy.promptFor(input))
                }
                val wordCandidate = OnDeviceSuggestionPolicy.parseSuffix(input, result.text)
                val sentenceCandidate = OnDeviceSuggestionPolicy.parseSuffix(sentenceInput, result.text)
                val sample = JSONObject()
                    .put("sampleIndex", index + 1)
                    .put("textBeforeCursor", textBeforeCursor)
                    .put("rawResponse", result.text)
                    .put("projectionSource", "single_generated_suffix")
                    .put("wordCandidate", wordCandidate ?: JSONObject.NULL)
                    .put("wordCandidatePresent", wordCandidate != null)
                    .put("sentenceCandidate", sentenceCandidate ?: JSONObject.NULL)
                    .put("sentenceCandidatePresent", sentenceCandidate != null)
                    .put("candidate", wordCandidate ?: JSONObject.NULL)
                    .put("candidatePresent", wordCandidate != null)
                    .put("formatValid", wordCandidate != null)
                    .put("verificationMs", result.verificationMs)
                    .put("initializationMs", result.initializationMs)
                    .put("firstTextMs", result.firstTextMs ?: JSONObject.NULL)
                    .put("totalMs", result.totalMs)
                    .put("engineReused", result.engineReused)
                    .put("engineWarmAfterRequest", createdEngine.isWarm)
                    .put("engineRunningAfterRequest", createdEngine.isRunning)
                samples.put(sample)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString(
                        SAMPLE_EVIDENCE_KEY,
                        JSONObject()
                            .put("evidenceScope", "warm_engine_public_sample_not_automatic_ime")
                            .put("offlineProof", false)
                            .put("sample", sample)
                            .put("completedSampleCount", samples.length())
                            .toString()
                    )
                })

                if (index == 0) {
                    assertFalse("첫 요청은 cold Engine이어야 합니다.", result.engineReused)
                } else {
                    assertTrue("${index + 1}번째 정상 요청은 같은 warm Engine을 재사용해야 합니다.", result.engineReused)
                }
                assertFalse("${index + 1}번째 요청 후 native 요청이 남아 있습니다.", createdEngine.isRunning)
                assertTrue("${index + 1}번째 요청 후 Engine warm 상태가 유지되지 않았습니다.", createdEngine.isWarm)
            }
        } catch (error: Throwable) {
            bodyFailure = error
            evidence.put("executionFailure", true)
                .put("exceptionClass", error.javaClass.name)
                .put(
                    "failureCode",
                    (error as? OnDeviceSuggestionEngine.OnDeviceSuggestionException)?.code ?: JSONObject.NULL
                )
            throw error
        } finally {
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
                    withTimeout(CLOSE_BUDGET_MS) { activeEngine.close() }
                }
                captureCleanup {
                    assertFalse("close 뒤 warm Engine이 남아 있습니다.", activeEngine.isWarm)
                    assertFalse("close 뒤 native 요청이 남아 있습니다.", activeEngine.isRunning)
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
            evidence.put("engineClosed", engine?.let { !it.isWarm && !it.isRunning } ?: true)
                .put("nativeLeaseReleased", !OnDeviceGenerationControl.isGenerating)
                .put("keyboardGateRestored", OnDeviceGenerationControl.isKeyboardActive == originalKeyboardActive)
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

    private fun requiredSampleCount(): Int {
        val raw = arguments.getString("samples") ?: PUBLIC_INPUTS.size.toString()
        val value = raw.toIntOrNull()
        require(value != null && value in 1..PUBLIC_INPUTS.size) {
            "samples 인자는 1..${PUBLIC_INPUTS.size} 범위여야 합니다: $raw"
        }
        return value
    }

    private companion object {
        const val EVIDENCE_KEY = "gemmaSuggestionEngineBenchmarkJson"
        const val SAMPLE_EVIDENCE_KEY = "gemmaSuggestionEngineBenchmarkSampleJson"
        const val PUBLIC_PACKAGE_NAME = "org.fcitx.fcitx5.android.publicbenchmark"
        const val GENERATION_BUDGET_MS = 120_000L
        const val CLOSE_BUDGET_MS = 30_000L

        val PUBLIC_INPUTS = listOf(
            "물감이 아직 마르지 않아서 ",
            "행사장 입구가 붐비니 ",
            "초안을 읽고 나서 ",
            "창문으로 햇빛이 들어와서 ",
            "화분의 흙이 말라서 ",
            "도서관 책을 반납하고 "
        )
    }
}
