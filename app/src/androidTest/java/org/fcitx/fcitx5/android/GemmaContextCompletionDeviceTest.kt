/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationStore
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGenerationEligibility
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGenerationMode
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGenerationSnapshot
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaMaterialGenerator
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelFiles
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaContextCompletionDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun measureSyntheticContextCompletion() = runBlocking(Dispatchers.Default) {
        val context = instrumentation.targetContext
        val app = context.applicationContext as FcitxApplication
        val bank = app.generatedSentenceBank
        val store = GemmaAccumulationStore.get(context)
        val requiresOffline = requiresOffline()
        val samples = JSONArray()
        var beforeBank: BankSnapshot? = null
        var beforeState: StateSnapshot? = null
        var finalBank: BankSnapshot? = null
        var finalState: StateSnapshot? = null
        var latestNetwork: NetworkSnapshot? = null

        try {
            val initialNetwork = networkSnapshot(context)
            latestNetwork = initialNetwork
            assertOfflineProof(requiresOffline, initialNetwork)

            val initialState = store.load()
            assertInactiveState("시작", initialState)
            assertFalse("문맥 완성 측정 시작 중 native 생성이 실행 중입니다.", OnDeviceGenerationControl.isGenerating)
            assertResourcePrecondition("시작", GemmaGenerationEligibility.snapshot(context))

            val model = GemmaModelFiles.modelFile(context)
            assertTrue("검증할 내부 Gemma 모델이 없습니다: ${model.absolutePath}", model.isFile)

            bank.load()
            val bankAtStart = BankSnapshot(bank.sentenceCount, bank.revision)
            val stateAtStart = StateSnapshot(
                enabled = initialState.enabled,
                manualRequested = initialState.manualRequested,
                openSequence = initialState.openSequence
            )
            beforeBank = bankAtStart
            beforeState = stateAtStart

            SYNTHETIC_CONTEXTS.forEachIndexed { index, syntheticContext ->
                val stateAtStart = store.load()
                assertInactiveState("샘플 ${index + 1} 시작", stateAtStart)
                assertFalse("샘플 ${index + 1} 시작 중 native 생성이 실행 중입니다.", OnDeviceGenerationControl.isGenerating)
                assertResourcePrecondition("샘플 ${index + 1} 시작", GemmaGenerationEligibility.snapshot(context))
                val networkAtStart = networkSnapshot(context)
                latestNetwork = networkAtStart
                assertOfflineProof(requiresOffline, networkAtStart)

                val prompt = promptFor(syntheticContext)
                val measurement = JSONObject()
                    .put("sampleIndex", index)
                    .put("syntheticContext", syntheticContext)
                    .put("prompt", prompt)
                    .put("backend", "cpu")
                    .put("purpose", "EXPLICIT_CONTEXT")
                    .put("sampleTimeoutMs", SAMPLE_TIMEOUT_MS)
                    .put("networkAtStart", networkAtStart.toJson())
                samples.put(measurement)

                val result = generateWithinBudget(
                    context = context,
                    model = model,
                    prompt = prompt,
                    measurement = measurement
                )
                measurement
                    .put("generatedText", result.text)
                    .put("initializationMs", result.initializationMs)
                    .put("generationMs", result.generationMs)
                    .put("metrics", JSONObject(result.metrics.toJson()))
                assertTrue("샘플 ${index + 1} Gemma 응답이 비어 있습니다.", result.text.isNotBlank())
                assertNotNull("샘플 ${index + 1} firstTextMs 계측이 없습니다.", result.metrics.firstTextMs)
                val firstTextMs = requireNotNull(result.metrics.firstTextMs)
                assertTrue(
                    "샘플 ${index + 1} firstTextMs가 wallMs 범위를 벗어났습니다: $firstTextMs/${result.metrics.wallMs}",
                    firstTextMs in 0..result.metrics.wallMs
                )
                measurement
                    .put("completed", true)
                report("sample_completed", requiresOffline, samples, beforeBank, beforeState, null)

                val stateAfterSample = store.load()
                assertInactiveState("샘플 ${index + 1} 종료", stateAfterSample)
                assertFalse("샘플 ${index + 1} 종료 뒤 native 생성이 남아 있습니다.", OnDeviceGenerationControl.isGenerating)
                assertEquals(
                    "샘플 ${index + 1}가 공개 문맥 순번을 변경했습니다.",
                    stateAtStart.openSequence,
                    stateAfterSample.openSequence
                )
                assertEquals(
                    "샘플 ${index + 1}가 생성 은행 수를 변경했습니다.",
                    bankAtStart.count,
                    bank.sentenceCount
                )
                assertEquals(
                    "샘플 ${index + 1}가 생성 은행 revision을 변경했습니다.",
                    bankAtStart.revision,
                    bank.revision
                )
            }

            val networkAtEnd = networkSnapshot(context)
            latestNetwork = networkAtEnd
            assertOfflineProof(requiresOffline, networkAtEnd)
            val endingState = store.load()
            assertInactiveState("종료", endingState)
            assertFalse("문맥 완성 측정 종료 뒤 native 생성이 남아 있습니다.", OnDeviceGenerationControl.isGenerating)
            assertEquals("문맥 완성 측정이 공개 문맥 순번을 변경했습니다.", stateAtStart.openSequence, endingState.openSequence)
            val bankAtEnd = BankSnapshot(bank.sentenceCount, bank.revision)
            val stateAtEnd = StateSnapshot(
                enabled = endingState.enabled,
                manualRequested = endingState.manualRequested,
                openSequence = endingState.openSequence
            )
            finalBank = bankAtEnd
            finalState = stateAtEnd
            assertEquals("문맥 완성 측정이 생성 은행 수를 변경했습니다.", bankAtStart.count, bankAtEnd.count)
            assertEquals("문맥 완성 측정이 생성 은행 revision을 변경했습니다.", bankAtStart.revision, bankAtEnd.revision)
            report("completed", requiresOffline, samples, beforeBank, beforeState, null, bankAtEnd, stateAtEnd, networkAtEnd)
        } catch (error: Throwable) {
            try {
                report("failed", requiresOffline, samples, beforeBank, beforeState, error, finalBank, finalState, latestNetwork)
            } catch (reportError: Throwable) {
                error.addSuppressed(reportError)
            }
            throw error
        }
    }

    private suspend fun generateWithinBudget(
        context: Context,
        model: java.io.File,
        prompt: String,
        measurement: JSONObject
    ): GemmaMaterialGenerator.GenerationResult {
        val generator = GemmaMaterialGenerator(context)
        val generationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val deferred = generationScope.async {
            generator.generate(
                modelFile = model,
                useGpu = false,
                purpose = OnDeviceGenerationControl.Purpose.EXPLICIT_CONTEXT,
                prompt = prompt
            )
        }
        var failure: Throwable? = null
        var cancellationAttempted = false
        try {
            return try {
                withTimeout(SAMPLE_TIMEOUT_MS) { deferred.await() }
            } catch (timeout: TimeoutCancellationException) {
                measurement.put("timedOut", true)
                cancellationAttempted = true
                try {
                    stopWithinBudget(generator, deferred)
                } catch (stopError: Throwable) {
                    throw AssertionError("Gemma 샘플이 120초를 넘겼고 native 생성도 30초 안에 종료하지 않았습니다.", timeout).also {
                        it.addSuppressed(stopError)
                    }
                }
                throw AssertionError("Gemma 샘플이 120초 안에 완료하지 않았습니다.", timeout)
            }
        } catch (error: Throwable) {
            failure = error
            measurement
                .put("completed", false)
                .put("exceptionClass", error.javaClass.name)
                .put("exceptionMessage", error.message ?: JSONObject.NULL)
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            if (!deferred.isCompleted && !cancellationAttempted) {
                cancellationAttempted = true
                try {
                    stopWithinBudget(generator, deferred)
                } catch (error: Throwable) {
                    cleanupFailure = error
                }
            }
            generator.cancel()
            generationScope.cancel()
            if (deferred.isCompleted && !cancellationAttempted) {
                try {
                    withContext(NonCancellable) {
                        withTimeout(STOP_TIMEOUT_MS) { awaitGeneratorStopped(generator) }
                    }
                } catch (error: Throwable) {
                    val previous = cleanupFailure
                    if (previous == null) cleanupFailure = error else previous.addSuppressed(error)
                }
            }
            cleanupFailure?.let { error -> failure?.addSuppressed(error) ?: throw error }
        }
    }

    private suspend fun stopWithinBudget(
        generator: GemmaMaterialGenerator,
        deferred: kotlinx.coroutines.Deferred<*>
    ) = withContext(NonCancellable) {
        generator.cancel()
        deferred.cancel()
        withTimeout(STOP_TIMEOUT_MS) {
            deferred.join()
            awaitGeneratorStopped(generator)
        }
    }

    private suspend fun awaitGeneratorStopped(generator: GemmaMaterialGenerator) {
        while (generator.isRunning || OnDeviceGenerationControl.isGenerating) {
            delay(POLL_INTERVAL_MS)
        }
        assertFalse("Gemma generator가 종료 뒤에도 실행 중입니다.", generator.isRunning)
        assertFalse("Gemma native 생성 lease가 종료 뒤에도 남아 있습니다.", OnDeviceGenerationControl.isGenerating)
    }

    private fun assertInactiveState(phase: String, state: org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationState) {
        assertFalse("$phase 자동 준비가 켜져 있습니다.", state.enabled)
        assertFalse("$phase 수동 강화 요청이 남아 있습니다.", state.manualRequested)
    }

    private fun assertResourcePrecondition(phase: String, snapshot: GemmaGenerationSnapshot) {
        assertTrue("$phase 배터리 잔량을 확인할 수 없습니다.", snapshot.batteryPercent != null)
        assertTrue("$phase 배터리가 20% 미만입니다: ${snapshot.batteryPercent}", snapshot.batteryPercent!! >= 20)
        assertTrue(
            "$phase 열 상태를 확인할 수 없거나 MODERATE 이상입니다: ${snapshot.thermalStatus}",
            snapshot.thermalStatus?.let { it < PowerManager.THERMAL_STATUS_MODERATE } == true
        )
        val reason = GemmaGenerationEligibility.evaluate(snapshot, GemmaGenerationMode.MANUAL)
        assertTrue("$phase 수동 생성 안전 조건이 충족되지 않았습니다: ${reason?.message}", reason == null)
    }

    private fun networkSnapshot(context: Context): NetworkSnapshot {
        val manager = requireNotNull(context.getSystemService(ConnectivityManager::class.java)) {
            "ConnectivityManager를 가져올 수 없어 네트워크 격리 상태를 증명할 수 없습니다."
        }
        val activeNetwork = manager.activeNetwork ?: return NetworkSnapshot(activeNetworkPresent = false)
        val capabilities = manager.getNetworkCapabilities(activeNetwork)
            ?: return NetworkSnapshot(activeNetworkPresent = true, capabilitiesAvailable = false)
        return NetworkSnapshot(
            activeNetworkPresent = true,
            capabilitiesAvailable = true,
            hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            hasValidatedInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            transports = listOf(
                NetworkCapabilities.TRANSPORT_WIFI to "wifi",
                NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
                NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
                NetworkCapabilities.TRANSPORT_VPN to "vpn"
            ).filter { (transport, _) -> capabilities.hasTransport(transport) }.map { it.second }
        )
    }

    private fun assertOfflineProof(requiresOffline: Boolean, snapshot: NetworkSnapshot) {
        if (!requiresOffline) return
        if (!snapshot.activeNetworkPresent) return
        assertTrue(
            "활성 네트워크 capability가 없어 오프라인 상태를 증명할 수 없습니다.",
            snapshot.capabilitiesAvailable == true
        )
        assertFalse(
            "requiresOffline=true인데 활성 검증 인터넷 연결이 있습니다.",
            snapshot.hasValidatedInternet == true
        )
    }

    private fun promptFor(context: String): String =
        "Continue the following Korean text from the same writer’s perspective. If it is unfinished, finish the thought; if it ends in punctuation, start a relevant next sentence. Return only the text to append, without repeating the input, quotation marks, explanations, or labels. Use one short natural Korean sentence, at most 60 characters. Input:\n$context\nContinuation:"

    private fun requiresOffline(): Boolean = when (
        InstrumentationRegistry.getArguments().getString(REQUIRES_OFFLINE_ARGUMENT) ?: "true"
    ) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("$REQUIRES_OFFLINE_ARGUMENT 인자는 true 또는 false여야 합니다.")
    }

    private fun report(
        event: String,
        requiresOffline: Boolean,
        samples: JSONArray,
        beforeBank: BankSnapshot?,
        beforeState: StateSnapshot?,
        error: Throwable?,
        finalBank: BankSnapshot? = null,
        finalState: StateSnapshot? = null,
        network: NetworkSnapshot? = null
    ) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString(
                EVIDENCE_KEY,
                JSONObject()
                    .put("event", event)
                    .put("scope", "public_synthetic_raw_gemma_context_completion")
                    .put("actualUserInput", false)
                    .put("providedToPublicGenerationFixtures", false)
                    .put("providedToEvaluationFixtures", false)
                    .put("providedToGeneratedSentenceBank", false)
                    .put("personalTypingDnaVaultOrGraphAccessed", false)
                    .put("imeIntegration", false)
                    .put("backend", "cpu")
                    .put("requiresOffline", requiresOffline)
                    .put("offlineModeUsedAsProof", false)
                    .put("network", network?.toJson() ?: JSONObject.NULL)
                    .put("beforeBank", beforeBank?.toJson() ?: JSONObject.NULL)
                    .put("afterBank", finalBank?.toJson() ?: JSONObject.NULL)
                    .put("beforeState", beforeState?.toJson() ?: JSONObject.NULL)
                    .put("afterState", finalState?.toJson() ?: JSONObject.NULL)
                    .put("samples", samples)
                    .put("exceptionClass", error?.javaClass?.name ?: JSONObject.NULL)
                    .put("exceptionMessage", error?.message ?: JSONObject.NULL)
                    .toString()
            )
        })
    }

    private data class BankSnapshot(val count: Int, val revision: Long) {
        fun toJson(): JSONObject = JSONObject().put("count", count).put("revision", revision)
    }

    private data class StateSnapshot(
        val enabled: Boolean,
        val manualRequested: Boolean,
        val openSequence: Long
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("enabled", enabled)
            .put("manualRequested", manualRequested)
            .put("openSequence", openSequence)
    }

    private data class NetworkSnapshot(
        val activeNetworkPresent: Boolean,
        val capabilitiesAvailable: Boolean? = null,
        val hasInternet: Boolean? = null,
        val hasValidatedInternet: Boolean? = null,
        val transports: List<String> = emptyList()
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("activeNetworkPresent", activeNetworkPresent)
            .put("capabilitiesAvailable", capabilitiesAvailable ?: JSONObject.NULL)
            .put("hasInternet", hasInternet ?: JSONObject.NULL)
            .put("hasValidatedInternet", hasValidatedInternet ?: JSONObject.NULL)
            .put("transports", JSONArray(transports))
    }

    private companion object {
        const val EVIDENCE_KEY = "gemmaContextCompletionEvidence"
        const val REQUIRES_OFFLINE_ARGUMENT = "requiresOffline"
        const val SAMPLE_TIMEOUT_MS = 120_000L
        const val STOP_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 50L
        val SYNTHETIC_CONTEXTS = listOf(
            "우산을 안 챙겼는데 ",
            "보고서를 다 읽었어. ",
            "주말에 도서관에 가서 "
        )
    }
}
