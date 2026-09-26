/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationRuntime
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationScheduler
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationStore
import org.fcitx.fcitx5.android.debug.gemma.GemmaExperimentActivity
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGenerationEligibility
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGenerationMetrics
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGenerationSnapshot
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaMaterialGenerator
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelFiles
import org.fcitx.fcitx5.android.input.ai.KoreanPiiScrubber
import org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedMaterialPolicy
import org.fcitx.fcitx5.android.input.ai.ondevice.IngestionRejectionReason
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class GemmaMaterialPerformanceDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun measurePublicMaterialGenerationOnDevice() = runBlocking(Dispatchers.Default) {
        val context = instrumentation.targetContext
        val app = context.applicationContext as FcitxApplication
        val sentenceCount = argument("sentenceCount", DEFAULT_SENTENCE_COUNT) { it == 1 || it == 2 }
        val intervalMs = argument("intervalMs", DEFAULT_INTERVAL_MS) { it == 0 || it == 10_000 }
        val maxRequests = argument("maxRequests", DEFAULT_MAX_REQUESTS) { it in 1..GeneratedMaterialPolicy.PREFIXES.size }
        val prefixStart = argument("prefixStart", DEFAULT_PREFIX_START) { it in GeneratedMaterialPolicy.PREFIXES.indices }
        val shouldReviewFreshPublicResponse = booleanArgument("reviewFreshPublicResponse", false)
        val store = GemmaAccumulationStore.get(context)
        val priorEnabled = store.load().enabled
        var experiment: GemmaExperimentActivity? = null
        val runStartedAt = SystemClock.elapsedRealtime()
        val measurements = JSONArray()
        var runFailure: Throwable? = null

        try {
            GemmaAccumulationScheduler.setEnabled(context, false)
            assertTrue(
                "자동 축적 native 생성이 30초 안에 멈추지 않았습니다.",
                GemmaAccumulationRuntime.awaitStopped(STOP_TIMEOUT_MS)
            )
            assertFalse("자동 축적 native 생성이 남아 있습니다.", OnDeviceGenerationControl.isGenerating)

            experiment = instrumentation.startActivitySync(
                Intent(context, GemmaExperimentActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            ) as GemmaExperimentActivity
            await("키보드가 비활성 상태가 되지 않았습니다.", KEYBOARD_TIMEOUT_MS) {
                !OnDeviceGenerationControl.isKeyboardActive
            }

            val model = GemmaModelFiles.modelFile(context)
            assertTrue("검증할 Gemma 모델이 없습니다.", model.isFile)
            assertTrue("Gemma 모델 크기가 기준과 다릅니다.", model.length() == GemmaModelFiles.MODEL_BYTES)
            app.generatedSentenceBank.load()
            val beforeCount = app.generatedSentenceBank.sentenceCount
            val requestedPrefixes = List(maxRequests) { requestIndex ->
                GeneratedMaterialPolicy.PREFIXES[(prefixStart + requestIndex) % GeneratedMaterialPolicy.PREFIXES.size]
            }

            requestedPrefixes.forEachIndexed { requestIndex, prefix ->
                if (requestIndex > 0 && intervalMs > 0) delay(intervalMs.toLong())
                assertTrue(
                    "새 요청 시작 120초 예산을 넘겼습니다.",
                    SystemClock.elapsedRealtime() - runStartedAt < START_BUDGET_MS
                )
                val guardAtStart = GemmaGenerationEligibility.snapshot(context)
                assertGuard("요청 시작", guardAtStart)
                val beforePrefixCount = app.generatedSentenceBank.exactPrefixCount(prefix)
                val generator = GemmaMaterialGenerator(context)
                val guardFailure = AtomicReference<GemmaGenerationSnapshot?>(null)
                val watchdog = launch {
                    while (isActive) {
                        delay(WATCHDOG_INTERVAL_MS)
                        val watched = GemmaGenerationEligibility.snapshot(context)
                        if (!GemmaGenerationEligibility.canGenerate(watched)) {
                            guardFailure.compareAndSet(null, watched)
                            generator.cancel()
                            return@launch
                        }
                    }
                }
                val requestStartedAt = SystemClock.elapsedRealtime()
                var requestFailure: Throwable? = null
                try {
                    val result = generator.generate(
                        modelFile = model,
                        useGpu = false,
                        prompt = GeneratedMaterialPolicy.promptFor(prefix, sentenceCount)
                    )
                    val guardBeforeSave = GemmaGenerationEligibility.snapshot(context)
                    assertGuard("저장 직전", guardBeforeSave)
                    guardFailure.get()?.let { snapshot ->
                        throw AssertionError("생성 중 안전 조건이 바뀌었습니다: ${eligibilitySummary(snapshot)}")
                    }
                    val freshPublicReview = if (shouldReviewFreshPublicResponse) {
                        reviewFreshPublicResponse(result.text)
                    } else {
                        null
                    }
                    val saveStartedAt = SystemClock.elapsedRealtime()
                    val actualResponseCount = responseCount(result.text)
                    val report = app.generatedSentenceBank.addGeneratedForPrefix(
                        response = result.text,
                        prefix = prefix,
                        modelId = GemmaModelFiles.MODEL_ID,
                        modelSha256 = GemmaModelFiles.MODEL_SHA256
                    )
                    val saveMs = SystemClock.elapsedRealtime() - saveStartedAt
                    val callWallAndSaveMs = SystemClock.elapsedRealtime() - requestStartedAt
                    val reloadStartedAt = SystemClock.elapsedRealtime()
                    app.generatedSentenceBank.load()
                    val reloadMs = SystemClock.elapsedRealtime() - reloadStartedAt
                    val afterPrefixCount = app.generatedSentenceBank.exactPrefixCount(prefix)
                    assertTrue(
                        "저장 뒤 prefix 재료 수가 감소했습니다.",
                        beforePrefixCount <= afterPrefixCount
                    )
                    measurements.put(
                        measurementJson(
                            requestIndex = requestIndex,
                            prefixIndex = (prefixStart + requestIndex) % GeneratedMaterialPolicy.PREFIXES.size,
                            sentenceCount = sentenceCount,
                            intervalMs = intervalMs,
                            startCharging = guardAtStart.isCharging,
                            startBatteryPercent = guardAtStart.batteryPercent,
                            startPowerSaveMode = guardAtStart.powerSaveMode,
                            beforePrefixCount = beforePrefixCount,
                            afterPrefixCount = afterPrefixCount,
                            saveMs = saveMs,
                            reloadMs = reloadMs,
                            added = report.added,
                            duplicate = report.duplicate,
                            rejected = report.rejected,
                            responseCount = actualResponseCount,
                            requestedCountMatched = actualResponseCount == sentenceCount,
                            rejectionReasons = report.rejectionReasons,
                            callWallAndSaveMs = callWallAndSaveMs,
                            metrics = result.metrics,
                            freshPublicReview = freshPublicReview
                        )
                    )
                } catch (error: Throwable) {
                    requestFailure = error
                    generator.lastMetrics?.let { metrics ->
                        measurements.put(
                            measurementJson(
                                requestIndex = requestIndex,
                                prefixIndex = (prefixStart + requestIndex) % GeneratedMaterialPolicy.PREFIXES.size,
                                sentenceCount = sentenceCount,
                                intervalMs = intervalMs,
                                startCharging = guardAtStart.isCharging,
                                startBatteryPercent = guardAtStart.batteryPercent,
                                startPowerSaveMode = guardAtStart.powerSaveMode,
                                beforePrefixCount = beforePrefixCount,
                                afterPrefixCount = app.generatedSentenceBank.exactPrefixCount(prefix),
                                saveMs = null,
                                reloadMs = null,
                                added = null,
                                duplicate = null,
                                rejected = null,
                                responseCount = null,
                                requestedCountMatched = null,
                                rejectionReasons = null,
                                callWallAndSaveMs = SystemClock.elapsedRealtime() - requestStartedAt,
                                metrics = metrics,
                                freshPublicReview = null
                            )
                        )
                    }
                    throw error
                } finally {
                    var cleanupFailure: Throwable? = null
                    suspend fun cleanup(action: suspend () -> Unit) {
                        try {
                            action()
                        } catch (error: Throwable) {
                            val previous = cleanupFailure
                            if (previous == null) cleanupFailure = error else previous.addSuppressed(error)
                        }
                    }
                    withContext(NonCancellable) {
                        cleanup { watchdog.cancelAndJoin() }
                        cleanup { generator.cancel() }
                        cleanup {
                            await("Gemma native 생성이 종료되지 않았습니다.", STOP_TIMEOUT_MS) {
                                !generator.isRunning && !OnDeviceGenerationControl.isGenerating
                            }
                        }
                    }
                    cleanupFailure?.let { error ->
                        requestFailure?.addSuppressed(error) ?: throw error
                    }
                }
            }

            app.generatedSentenceBank.load()
            val afterCount = app.generatedSentenceBank.sentenceCount
            assertTrue("생성 전 은행 수보다 저장 후 은행 수가 작습니다.", beforeCount <= afterCount)
            instrumentation.sendStatus(0, Bundle().apply {
                putString(
                    "gemmaMaterialPerformanceJson",
                    JSONObject()
                        .put("source", "public_generated_material_only")
                        .put("backend", "cpu")
                        .put("sentenceCount", sentenceCount)
                        .put("intervalMs", intervalMs)
                        .put("maxRequests", maxRequests)
                        .put("prefixStart", prefixStart)
                        .put("reviewFreshPublicResponse", shouldReviewFreshPublicResponse)
                        .put("freshPublicCandidateSource", "fresh_generator_return_public_prompt_only")
                        .put("beforeCount", beforeCount)
                        .put("afterCount", afterCount)
                        .put("requestCount", measurements.length())
                        .put("bankUniqueCostScope", "existing_bank_order_dependent")
                        .put("runWallIncludingIntervalsMs", SystemClock.elapsedRealtime() - runStartedAt)
                        .put("measurements", measurements)
                        .put("coverageClaim", "not_coverage_verification")
                        .toString()
                )
            })
        } catch (error: Throwable) {
            runFailure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            suspend fun cleanup(action: suspend () -> Unit) {
                try {
                    action()
                } catch (error: Throwable) {
                    val previous = cleanupFailure
                    if (previous == null) cleanupFailure = error else previous.addSuppressed(error)
                }
            }
            withContext(NonCancellable) {
                if (runFailure != null && measurements.length() > 0) cleanup {
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString(
                            "gemmaMaterialPerformanceJson",
                            JSONObject()
                                .put("outcome", "failed")
                                .put("requestCount", measurements.length())
                                .put("bankUniqueCostScope", "existing_bank_order_dependent")
                                .put("runWallIncludingIntervalsMs", SystemClock.elapsedRealtime() - runStartedAt)
                                .put("measurements", measurements)
                                .toString()
                        )
                    })
                }
                cleanup {
                    GemmaAccumulationScheduler.setEnabled(context, false)
                    assertTrue(
                        "정리 중 Gemma native 생성이 멈추지 않았습니다.",
                        GemmaAccumulationRuntime.awaitStopped(STOP_TIMEOUT_MS)
                    )
                }
                cleanup { experiment?.let { activity -> instrumentation.runOnMainSync { activity.finish() } } }
                cleanup { GemmaAccumulationScheduler.setEnabled(context, priorEnabled) }
            }
            cleanupFailure?.let { error -> runFailure?.addSuppressed(error) ?: throw error }
        }
    }

    @Test
    fun verifyExistingPublicCoverageWithoutGeneration() {
        val context = instrumentation.targetContext
        val app = context.applicationContext as FcitxApplication
        assertFalse("외부 온디바이스 생성 중에는 coverage를 확인할 수 없습니다.", OnDeviceGenerationControl.isGenerating)

        app.generatedSentenceBank.load()
        val firstCounts = GeneratedMaterialPolicy.PREFIXES.associateWith(app.generatedSentenceBank::exactPrefixCount)
        val firstTotalStored = app.generatedSentenceBank.sentenceCount

        app.generatedSentenceBank.load()
        val reloadedCounts = GeneratedMaterialPolicy.PREFIXES.associateWith(app.generatedSentenceBank::exactPrefixCount)
        val reloadedTotalStored = app.generatedSentenceBank.sentenceCount
        assertTrue("재로드 뒤 공개 prefix 재료 수가 달라졌습니다.", firstCounts == reloadedCounts)
        assertTrue("재로드 뒤 은행 저장 수가 감소했습니다.", firstTotalStored <= reloadedTotalStored)
        assertFalse("coverage 확인 중 온디바이스 생성이 시작됐습니다.", OnDeviceGenerationControl.isGenerating)

        instrumentation.sendStatus(0, Bundle().apply {
            putString(
                "gemmaExistingPublicCoverageJson",
                JSONObject()
                    .put(
                        "prefixCounts",
                        JSONArray(GeneratedMaterialPolicy.PREFIXES.mapIndexed { index, prefix ->
                            JSONObject()
                                .put("prefixIndex", index)
                                .put("count", reloadedCounts.getValue(prefix))
                        })
                    )
                    .put("totalStored", reloadedTotalStored)
                    .put("coveredPrefixCount", reloadedCounts.values.count { it >= 1 })
                    .put("reloaded", true)
                    .put("generationActive", OnDeviceGenerationControl.isGenerating)
                    .put("evidenceScope", "existing_public_coverage_reload_gate_not_generation_success")
                    .toString()
            )
        })
        assertTrue(
            "공개 20문맥 중 재료가 없는 문맥이 있습니다: ${reloadedCounts.values.count { it < 1 }}개",
            reloadedCounts.values.all { it >= 1 }
        )
    }

    private fun measurementJson(
        requestIndex: Int,
        prefixIndex: Int,
        sentenceCount: Int,
        intervalMs: Int,
        startCharging: Boolean,
        startBatteryPercent: Int?,
        startPowerSaveMode: Boolean,
        beforePrefixCount: Int,
        afterPrefixCount: Int,
        saveMs: Long?,
        reloadMs: Long?,
        added: Int?,
        duplicate: Int?,
        rejected: Int?,
        responseCount: Int?,
        requestedCountMatched: Boolean?,
        rejectionReasons: Map<IngestionRejectionReason, Int>?,
        callWallAndSaveMs: Long,
        metrics: GemmaGenerationMetrics,
        freshPublicReview: FreshPublicReview?
    ): JSONObject = JSONObject()
        .put("requestIndex", requestIndex)
        .put("prefixIndex", prefixIndex)
        .put("sentenceCount", sentenceCount)
        .put("intervalMs", intervalMs)
        .put("startCharging", startCharging)
        .put("startBatteryPercent", startBatteryPercent ?: JSONObject.NULL)
        .put("startPowerSaveMode", startPowerSaveMode)
        .put("beforePrefixCount", beforePrefixCount)
        .put("afterPrefixCount", afterPrefixCount)
        .put("wallMs", metrics.wallMs)
        .put("verificationMs", metrics.verificationMs)
        .put("initializationMs", metrics.initializationMs)
        .put("conversationMs", metrics.conversationMs)
        .put("generationMs", metrics.generationMs)
        .put("closeMs", metrics.closeMs)
        .put("preparationMs", metrics.verificationMs + metrics.initializationMs + metrics.conversationMs)
        .put("startThermalStatus", metrics.startThermalStatus ?: JSONObject.NULL)
        .put("endThermalStatus", metrics.endThermalStatus ?: JSONObject.NULL)
        .put("peakSampledPssKb", metrics.peakSampledPssKb ?: JSONObject.NULL)
        .put("minAvailableMemoryBytes", metrics.minAvailableMemoryBytes ?: JSONObject.NULL)
        .put("firstThermalLimitedMs", metrics.firstThermalLimitedMs ?: JSONObject.NULL)
        .put("outcome", metrics.outcome.name)
        .put("saveMs", saveMs ?: JSONObject.NULL)
        .put("reloadMs", reloadMs ?: JSONObject.NULL)
        .put("callWallAndSaveMs", callWallAndSaveMs)
        .put("added", added ?: JSONObject.NULL)
        .put("duplicate", duplicate ?: JSONObject.NULL)
        .put("rejected", rejected ?: JSONObject.NULL)
        .put("responseCount", responseCount ?: JSONObject.NULL)
        .put("requestedCountMatched", requestedCountMatched ?: JSONObject.NULL)
        .put("rejectionReasons", rejectionReasonsJson(rejectionReasons))
        .put("wallMsPerAdded", added?.takeIf { it > 0 }?.let { callWallAndSaveMs.toDouble() / it } ?: JSONObject.NULL)
        .put("preparationMsPerAdded", added?.takeIf { it > 0 }?.let { preparation ->
            (metrics.verificationMs + metrics.initializationMs + metrics.conversationMs).toDouble() / preparation
        } ?: JSONObject.NULL)
        .put("generationMsPerAdded", added?.takeIf { it > 0 }?.let { metrics.generationMs.toDouble() / it } ?: JSONObject.NULL)
        .put("closeMsPerAdded", added?.takeIf { it > 0 }?.let { metrics.closeMs.toDouble() / it } ?: JSONObject.NULL)
        .put("saveMsPerAdded", added?.takeIf { it > 0 }?.let { save -> saveMs!!.toDouble() / save } ?: JSONObject.NULL)
        .also { json -> freshPublicReview?.writeTo(json) }

    private fun responseCount(response: String): Int =
        JSONArray(checkNotNull(stripJsonFenceForReview(response))).length()

    private fun rejectionReasonsJson(rejectionReasons: Map<IngestionRejectionReason, Int>?): Any =
        rejectionReasons?.let { reasons ->
            JSONObject().apply {
                reasons.entries.sortedBy { it.key.name }.forEach { (reason, count) ->
                    put(reason.name, count)
                }
            }
        } ?: JSONObject.NULL

    private fun reviewFreshPublicResponse(response: String): FreshPublicReview {
        val payload = stripJsonFenceForReview(response) ?: return FreshPublicReview(parseSucceeded = false)
        val array = try {
            JSONArray(payload)
        } catch (_: Exception) {
            return FreshPublicReview(parseSucceeded = false)
        }
        val candidates = JSONArray()
        var piiRejectedCount = 0
        var nonStringCandidateCount = 0
        for (index in 0 until array.length()) {
            val candidate = array.opt(index) as? String
            if (candidate == null) {
                nonStringCandidateCount++
            } else if (KoreanPiiScrubber.containsPii(candidate)) {
                piiRejectedCount++
            } else {
                candidates.put(candidate)
            }
        }
        return FreshPublicReview(
            parseSucceeded = true,
            candidates = candidates,
            piiRejectedCount = piiRejectedCount,
            nonStringCandidateCount = nonStringCandidateCount
        )
    }

    private fun stripJsonFenceForReview(response: String): String? {
        val trimmed = response.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstLineEnd = trimmed.indexOf('\n')
        if (firstLineEnd < 0 || !trimmed.endsWith("```")) return null
        val language = trimmed.substring(3, firstLineEnd).trim()
        if (language.isNotEmpty() && !language.equals("json", ignoreCase = true)) return null
        return trimmed.substring(firstLineEnd + 1, trimmed.length - 3).trim()
    }

    private fun assertGuard(phase: String, snapshot: GemmaGenerationSnapshot) {
        val reason = GemmaGenerationEligibility.evaluate(snapshot)
        assertTrue("$phase 안전 조건이 충족되지 않았습니다: ${reason?.message}", reason == null)
    }

    private fun argument(name: String, default: Int, allowed: (Int) -> Boolean): Int {
        val raw = InstrumentationRegistry.getArguments().getString(name) ?: return default
        val value = raw.toIntOrNull()
        require(value != null && allowed(value)) { "$name 인자가 허용 범위를 벗어났습니다: $raw" }
        return value
    }

    private fun booleanArgument(name: String, default: Boolean): Boolean {
        val raw = InstrumentationRegistry.getArguments().getString(name) ?: return default
        return when (raw) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("$name 인자는 true 또는 false여야 합니다: $raw")
        }
    }

    private suspend fun await(message: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            delay(POLL_INTERVAL_MS)
        }
        throw AssertionError(message)
    }

    private fun eligibilitySummary(snapshot: GemmaGenerationSnapshot): String =
        "charging=${snapshot.isCharging},batteryPercent=${snapshot.batteryPercent}," +
            "powerSaveMode=${snapshot.powerSaveMode},thermalStatus=${snapshot.thermalStatus}," +
            "lowMemory=${snapshot.lowMemory},inputViewVisible=${snapshot.inputViewVisible}"

    private data class FreshPublicReview(
        val parseSucceeded: Boolean,
        val candidates: JSONArray = JSONArray(),
        val piiRejectedCount: Int = 0,
        val nonStringCandidateCount: Int = 0
    ) {
        fun writeTo(json: JSONObject) {
            json.put("freshPublicCandidateSource", "fresh_generator_return_public_prompt_only")
            json.put("freshPublicResponseParsed", parseSucceeded)
            json.put("freshPublicCandidates", candidates)
            json.put("piiRejectedCount", piiRejectedCount)
            json.put("nonStringCandidateCount", nonStringCandidateCount)
        }
    }

    private companion object {
        const val DEFAULT_SENTENCE_COUNT = 2
        const val DEFAULT_INTERVAL_MS = 0
        const val DEFAULT_MAX_REQUESTS = 4
        const val DEFAULT_PREFIX_START = 0
        const val START_BUDGET_MS = 120_000L
        const val WATCHDOG_INTERVAL_MS = 250L
        const val POLL_INTERVAL_MS = 50L
        const val STOP_TIMEOUT_MS = 30_000L
        const val KEYBOARD_TIMEOUT_MS = 10_000L
    }
}
