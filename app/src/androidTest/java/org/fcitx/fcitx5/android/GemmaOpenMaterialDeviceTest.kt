/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
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
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGenerationSnapshot
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaMaterialGenerator
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelFiles
import org.fcitx.fcitx5.android.input.ai.KoreanPiiScrubber
import org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedMaterialPolicy
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class GemmaOpenMaterialDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun generateAndReloadNewPublicContexts() = runBlocking(Dispatchers.Default) {
        runPublicGeneration(requireNewTouchCandidate = true)
    }

    @Test
    fun measurePublicContextExpansion() = runBlocking(Dispatchers.Default) {
        runPublicGeneration(requireNewTouchCandidate = false)
    }

    private suspend fun runPublicGeneration(requireNewTouchCandidate: Boolean) = coroutineScope {
        val context = instrumentation.targetContext
        val app = context.applicationContext as FcitxApplication
        val bank = app.generatedSentenceBank
        val store = GemmaAccumulationStore.get(context)
        val maxRequests = requireMaxRequests()
        val measurements = JSONArray()
        val storedPublicCandidates = linkedSetOf<String>()
        val batchFirstTwoWordStarts = mutableListOf<String>()
        var batchFreshResponseCount = 0
        var excludedStartConflictCount = 0
        var priorEnabled: Boolean? = null
        var activity: GemmaExperimentActivity? = null
        var activeGenerator: GemmaMaterialGenerator? = null
        var runFailure: Throwable? = null

        try {
            val model = GemmaModelFiles.modelFile(context)
            assertTrue("검증할 Gemma 모델이 없습니다.", model.isFile)
            assertEquals("Gemma 모델 크기가 기준과 다릅니다.", GemmaModelFiles.MODEL_BYTES, model.length())

            val initialState = store.load()
            val sequenceStart = requireSequenceStart(initialState.openSequence, maxRequests)
            priorEnabled = initialState.enabled
            GemmaAccumulationScheduler.setEnabled(context, false)
            assertTrue(
                "자동 축적 native 생성이 30초 안에 멈추지 않았습니다.",
                GemmaAccumulationRuntime.awaitStopped(STOP_TIMEOUT_MS)
            )
            assertFalse("자동 축적 native 생성이 남아 있습니다.", OnDeviceGenerationControl.isGenerating)
            assertFalse("수동 공개 문맥 실험은 자동 준비가 꺼진 상태에서만 실행합니다.", store.load().enabled)

            activity = instrumentation.startActivitySync(
                Intent(context, GemmaExperimentActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            ) as GemmaExperimentActivity
            await("키보드가 비활성 상태가 되지 않았습니다.", KEYBOARD_TIMEOUT_MS) {
                !OnDeviceGenerationControl.isKeyboardActive
            }

            bank.load()
            val beforeCount = bank.sentenceCount
            repeat(maxRequests) { requestIndex ->
                val sequence = sequenceStart + requestIndex
                val excludedStarts = bank.recentPublicStarts()
                val descriptor = GeneratedMaterialPolicy.openMaterialDescriptorFor(sequence)
                val prompt = GeneratedMaterialPolicy.openPromptFor(sequence, excludedStarts)
                val guardAtStart = GemmaGenerationEligibility.snapshot(context)
                assertGuard("요청 시작", guardAtStart)
                assertFalse("공개 문맥 요청 시작 중 키보드가 활성화되었습니다.", OnDeviceGenerationControl.isKeyboardActive)
                assertFalse("공개 문맥 요청 시작 중 자동 준비가 활성화되었습니다.", store.load().enabled)
                val beforeRequestCount = bank.sentenceCount
                val generator = GemmaMaterialGenerator(context)
                activeGenerator = generator
                val guardFailure = AtomicReference<GemmaGenerationSnapshot?>(null)
                val automaticEnabledDuringGeneration = AtomicBoolean(false)
                val watchdog = launch {
                    while (isActive) {
                        delay(WATCHDOG_INTERVAL_MS)
                        val snapshot = GemmaGenerationEligibility.snapshot(context)
                        if (!GemmaGenerationEligibility.canGenerate(snapshot) || store.state.value.enabled) {
                            guardFailure.compareAndSet(null, snapshot)
                            if (store.state.value.enabled) automaticEnabledDuringGeneration.set(true)
                            generator.cancel()
                            return@launch
                        }
                    }
                }
                var requestFailure: Throwable? = null
                try {
                    val result = generator.generate(model, useGpu = false, prompt = prompt)
                    assertFalse("Gemma 공개 응답에 PII가 포함되었습니다.", KoreanPiiScrubber.containsPii(result.text))
                    val freshPublicCandidates = parseFreshPublicCandidates(result.text)
                    val freshFirstTwoWordStarts = freshPublicCandidates.map(::firstTwoWordsStart)
                    val validFirstTwoWordStarts = freshFirstTwoWordStarts.filterNotNull()
                    val excludedStartConflicts = validFirstTwoWordStarts.filter(excludedStarts::contains)
                    batchFirstTwoWordStarts += validFirstTwoWordStarts
                    batchFreshResponseCount += freshPublicCandidates.size
                    excludedStartConflictCount += excludedStartConflicts.size
                    val guardBeforeSave = GemmaGenerationEligibility.snapshot(context)
                    assertGuard("저장 직전", guardBeforeSave)
                    assertFalse("저장 직전에 키보드가 활성화되었습니다.", OnDeviceGenerationControl.isKeyboardActive)
                    assertFalse("저장 직전에 자동 준비가 활성화되었습니다.", store.load().enabled)
                    assertFalse("생성 중 자동 준비가 활성화되어 수동 생성을 취소했습니다.", automaticEnabledDuringGeneration.get())
                    guardFailure.get()?.let { snapshot ->
                        throw AssertionError("생성 중 안전 조건이 바뀌었습니다: ${eligibilitySummary(snapshot)}")
                    }

                    val existingCandidates = freshPublicCandidates.filter { candidate ->
                        hasStoredCandidate(bank, candidate)
                    }.toSet()
                    val report = bank.addGeneratedOpen(
                        response = result.text,
                        modelId = GemmaModelFiles.MODEL_ID,
                        modelSha256 = GemmaModelFiles.MODEL_SHA256
                    )
                    assertTrue("공개 문맥 저장 뒤 은행 수가 감소했습니다.", bank.sentenceCount >= beforeRequestCount)
                    bank.load()
                    val newStoredCandidates = freshPublicCandidates.filter { candidate ->
                        candidate !in existingCandidates && hasStoredCandidate(bank, candidate)
                    }
                    newStoredCandidates.forEach(storedPublicCandidates::add)
                    val measurement = JSONObject()
                            .put("requestIndex", requestIndex)
                            .put("requireNewTouchCandidate", requireNewTouchCandidate)
                            .put("experimentSequence", sequence)
                            .put("sequenceStart", sequenceStart)
                            .put("automaticOpenSequence", initialState.openSequence)
                            .put("descriptor", JSONObject()
                                .put("situation", descriptor.situation)
                                .put("intent", descriptor.intent)
                                .put("cycle", descriptor.cycle))
                            .put("semanticAssessment", "manual_review_required")
                            .put("intentAssessment", "manual_review_required")
                            .put("excludedStartCount", excludedStarts.size)
                            .put("excludedStartConflictCount", excludedStartConflicts.size)
                            .put("excludedStartConflicts", JSONArray(excludedStartConflicts))
                            .put("responsePiiFree", true)
                            .put("responseCount", freshPublicCandidates.size)
                            .put("freshFirstTwoWordStarts", JSONArray().apply {
                                freshFirstTwoWordStarts.forEach { start -> put(start ?: JSONObject.NULL) }
                            })
                            .put("requestedCountMatched", freshPublicCandidates.size == REQUESTED_SENTENCE_COUNT)
                            .put("added", report.added)
                            .put("duplicate", report.duplicate)
                            .put("rejected", report.rejected)
                            .put("beforeCount", beforeRequestCount)
                            .put("afterCount", bank.sentenceCount)
                            .put("freshPublicCandidates", JSONArray(freshPublicCandidates))
                            .put("newStoredCandidates", JSONArray(newStoredCandidates))
                            .put("wallMs", result.metrics.wallMs)
                            .put("generationMs", result.metrics.generationMs)
                    measurements.put(measurement)
                    reportRequestEvidence(measurement)
                } catch (error: Throwable) {
                    requestFailure = error
                    try {
                        reportRequestFailure(
                            requestIndex = requestIndex,
                            sequence = sequence,
                            error = error,
                            guardAtStart = guardAtStart,
                            guardFailure = guardFailure.get(),
                            automaticEnabledDuringGeneration = automaticEnabledDuringGeneration.get()
                        )
                    } catch (evidenceError: Throwable) {
                        error.addSuppressed(evidenceError)
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
                    activeGenerator = null
                    cleanupFailure?.let { error -> requestFailure?.addSuppressed(error) ?: throw error }
                }
            }

            bank.load()
            val afterCount = bank.sentenceCount
            assertTrue("공개 문맥 생성 뒤 은행 수가 감소했습니다.", afterCount >= beforeCount)
            assertEquals(
                "별도 공개 문맥 실험이 자동 순번을 변경했습니다.",
                initialState.openSequence,
                store.load().openSequence
            )
            val candidateForTouch = storedPublicCandidates.firstOrNull { candidate ->
                GeneratedMaterialPolicy.PREFIXES.none(candidate::startsWith)
            }
            val prefixForTouch = candidateForTouch?.let(::firstTwoWordsPrefix)
            if (requireNewTouchCandidate) {
                assertTrue(
                    "기존 20개 prefix로 시작하지 않는 새 공개 문장과 reload suffix 후보가 하나 이상 필요합니다.",
                    candidateForTouch != null && hasStoredCandidate(bank, requireNotNull(candidateForTouch))
                )
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString(
                    "gemmaOpenMaterialEvidence",
                    JSONObject()
                        .put("source", "fresh_public_ondevice_generation")
                        .put("backend", "cpu")
                        .put("maxRequests", maxRequests)
                        .put("requireNewTouchCandidate", requireNewTouchCandidate)
                        .put("measurementOnlyNotQualityPass", !requireNewTouchCandidate)
                        .put("testSuccessDoesNotEstablishSemanticOrIntentQuality", true)
                        .put("sequenceStart", sequenceStart)
                        .put("beforeCount", beforeCount)
                        .put("afterCount", afterCount)
                        .put("automaticOpenSequenceBefore", initialState.openSequence)
                        .put("automaticOpenSequenceAfter", store.load().openSequence)
                        .put("batchFirstTwoWordStarts", JSONArray(batchFirstTwoWordStarts))
                        .put("batchFreshResponseDenominator", batchFreshResponseCount)
                        .put("batchFirstTwoWordStartAvailableCount", batchFirstTwoWordStarts.size)
                        .put("batchFirstTwoWordStartDuplicateRateDenominator", batchFirstTwoWordStarts.size)
                        .put("batchFirstTwoWordStartDuplicateCount", batchFirstTwoWordStarts.size - batchFirstTwoWordStarts.toSet().size)
                        .put("excludedStartConflictCount", excludedStartConflictCount)
                        .put("semanticAssessment", "manual_review_required")
                        .put("intentAssessment", "manual_review_required")
                        .put("newTouchCandidateAvailable", candidateForTouch != null)
                        .put("publicPrefixBase64ForTouch", prefixForTouch?.let(::encodePublicPrefix) ?: JSONObject.NULL)
                        .put("freshPublicCandidatesForTouch", JSONArray().apply {
                            if (candidateForTouch != null) put(candidateForTouch)
                        })
                        .put("measurements", measurements)
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
                cleanup { activeGenerator?.cancel() }
                cleanup {
                    await("정리 중 Gemma native 생성이 종료되지 않았습니다.", STOP_TIMEOUT_MS) {
                        !OnDeviceGenerationControl.isGenerating
                    }
                }
                cleanup { priorEnabled?.let { GemmaAccumulationScheduler.setEnabled(context, it) } }
                cleanup { activity?.let { current -> instrumentation.runOnMainSync { current.finish() } } }
            }
            cleanupFailure?.let { error -> runFailure?.addSuppressed(error) ?: throw error }
        }
    }

    private fun requireMaxRequests(): Int {
        val raw = InstrumentationRegistry.getArguments().getString(MAX_REQUESTS_ARGUMENT)
        val value = raw?.toIntOrNull()
        require(value != null && value in 1..6) {
            "$MAX_REQUESTS_ARGUMENT 인자를 1..6 범위로 반드시 지정해야 합니다."
        }
        return requireNotNull(value)
    }

    private fun requireSequenceStart(defaultStart: Long, maxRequests: Int): Long {
        val raw = InstrumentationRegistry.getArguments().getString(SEQUENCE_START_ARGUMENT)
        val sequenceStart = if (raw == null) {
            defaultStart
        } else {
            requireNotNull(raw.toLongOrNull()) {
                "$SEQUENCE_START_ARGUMENT 인자는 0 이상의 Long이어야 합니다."
            }
        }
        require(sequenceStart >= 0L) { "$SEQUENCE_START_ARGUMENT 인자는 음수일 수 없습니다." }
        require(sequenceStart <= Long.MAX_VALUE - (maxRequests - 1).toLong()) {
            "${SEQUENCE_START_ARGUMENT}와 $MAX_REQUESTS_ARGUMENT 조합이 Long 범위를 초과합니다."
        }
        return sequenceStart
    }

    private fun reportRequestEvidence(measurement: JSONObject) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString(
                "gemmaOpenMaterialRequestEvidence",
                JSONObject()
                    .put("event", "fresh_public_response_stored")
                    .put("measurement", measurement)
                    .toString()
            )
        })
    }

    private fun reportRequestFailure(
        requestIndex: Int,
        sequence: Long,
        error: Throwable,
        guardAtStart: GemmaGenerationSnapshot,
        guardFailure: GemmaGenerationSnapshot?,
        automaticEnabledDuringGeneration: Boolean
    ) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString(
                "gemmaOpenMaterialRequestFailureEvidence",
                JSONObject()
                    .put("requestIndex", requestIndex)
                    .put("experimentSequence", sequence)
                    .put("exceptionClass", error.javaClass.name)
                    .put("guardAtStartResources", resourceSummary(guardAtStart))
                    .put("guardFailureResources", guardFailure?.let(::resourceSummary) ?: JSONObject.NULL)
                    .put("automaticEnabledDuringGeneration", automaticEnabledDuringGeneration)
                    .put("onDeviceGenerationControl", JSONObject()
                        .put("keyboardActive", OnDeviceGenerationControl.isKeyboardActive)
                        .put("generating", OnDeviceGenerationControl.isGenerating))
                    .toString()
            )
        })
    }

    private fun resourceSummary(snapshot: GemmaGenerationSnapshot): JSONObject = JSONObject()
        .put("isCharging", snapshot.isCharging)
        .put("batteryPercent", snapshot.batteryPercent)
        .put("powerSaveMode", snapshot.powerSaveMode)
        .put("thermalStatus", snapshot.thermalStatus)
        .put("lowMemory", snapshot.lowMemory)
        .put("inputViewVisible", snapshot.inputViewVisible)

    private fun assertGuard(phase: String, snapshot: GemmaGenerationSnapshot) {
        val reason = GemmaGenerationEligibility.evaluate(snapshot)
        assertTrue("$phase 안전 조건이 충족되지 않았습니다: ${reason?.message}", reason == null)
    }

    private fun parseFreshPublicCandidates(response: String): List<String> {
        val payload = requireNotNull(stripJsonFence(response)) { "Gemma 공개 응답 JSON fence가 올바르지 않습니다." }
        val array = try {
            JSONArray(payload)
        } catch (error: Exception) {
            throw AssertionError("Gemma 공개 응답이 JSON 배열이 아닙니다.", error)
        }
        return List(array.length()) { index ->
            val candidate = requireNotNull(array.opt(index) as? String) {
                "Gemma 공개 응답 배열에는 문자열만 포함할 수 있습니다."
            }
            require(!KoreanPiiScrubber.containsPii(candidate)) {
                "Gemma 공개 응답 후보에 PII가 포함되었습니다."
            }
            candidate
        }
    }

    private fun stripJsonFence(response: String): String? {
        val trimmed = response.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstLineEnd = trimmed.indexOf('\n')
        if (firstLineEnd < 0 || !trimmed.endsWith("```")) return null
        val language = trimmed.substring(3, firstLineEnd).trim()
        if (language.isNotEmpty() && !language.equals("json", ignoreCase = true)) return null
        return trimmed.substring(firstLineEnd + 1, trimmed.length - 3).trim()
    }

    private fun hasStoredCandidate(
        bank: org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedSentenceBank,
        candidate: String
    ): Boolean {
        if (candidate.split(' ').count(String::isNotBlank) < 3) return false
        val prefix = firstTwoWordsPrefix(candidate)
        val suffix = candidate.removePrefix(prefix)
        return bank.complete(prefix, 8).any { it.suffix == suffix }
    }

    private fun firstTwoWordsPrefix(candidate: String): String {
        val words = candidate.split(' ').filter(String::isNotBlank)
        require(words.size >= 3) { "공개 후보는 세 어절 이상이어야 합니다." }
        return words.take(2).joinToString(separator = " ", postfix = " ")
    }

    private fun firstTwoWordsStart(candidate: String): String? {
        val words = candidate.split(' ').filter(String::isNotBlank)
        return words.take(2).takeIf { it.size == 2 }?.joinToString(separator = " ", postfix = " ")
    }

    private suspend fun await(description: String, timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            delay(POLL_INTERVAL_MS)
        }
        assertTrue(description, predicate())
    }

    private fun eligibilitySummary(snapshot: GemmaGenerationSnapshot): String =
        "charging=${snapshot.isCharging},batteryPercent=${snapshot.batteryPercent}," +
            "powerSaveMode=${snapshot.powerSaveMode},thermalStatus=${snapshot.thermalStatus}," +
            "lowMemory=${snapshot.lowMemory},inputViewVisible=${snapshot.inputViewVisible}"

    private fun encodePublicPrefix(prefix: String): String =
        Base64.encodeToString(prefix.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private companion object {
        const val MAX_REQUESTS_ARGUMENT = "maxRequests"
        const val SEQUENCE_START_ARGUMENT = "sequenceStart"
        const val KEYBOARD_TIMEOUT_MS = 10_000L
        const val STOP_TIMEOUT_MS = 30_000L
        const val WATCHDOG_INTERVAL_MS = 250L
        const val POLL_INTERVAL_MS = 250L
        const val REQUESTED_SENTENCE_COUNT = 2
    }
}
