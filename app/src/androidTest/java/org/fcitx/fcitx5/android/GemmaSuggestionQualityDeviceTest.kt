/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionEngine
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionPolicy
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationScheduler
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Measures sentence-continuation quality and latency of one warm on-device engine with fixed
 * public scenarios.
 *
 * The test only checks that the run itself is sound. Whether a continuation is good is judged by a
 * person reading the saved results JSON, so no quality assertion is made here.
 */
class GemmaSuggestionQualityDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()

    @Before
    fun pauseAccumulationForInstrumentation() {
        GemmaAccumulationScheduler.pauseForInstrumentation()
    }

    @After
    fun resumeAccumulationAfterInstrumentation() {
        GemmaAccumulationScheduler.resumeAfterInstrumentation()
    }

    @Test
    fun measureSentenceContinuationQualityAndLatency() = runBlocking<Unit>(Dispatchers.Default) {
        val targetContext = instrumentation.targetContext
        val workDir = File(targetContext.getExternalFilesDir(null), WORK_DIR_NAME).apply { mkdirs() }
        val backendArgument = requiredBackend()
        val cpuThreads = requiredCpuThreads(backendArgument)
        val repeat = requiredRepeat()
        val templateName = optionalFileName("templateFile")
        val template = templateName?.let { readWorkFile(workDir, it) }
        val scenarios = filterScenarios(loadScenarios(workDir))
        val backend = when (backendArgument) {
            "cpu" -> OnDeviceSuggestionEngine.BackendSelection.Cpu(
                threadCount = cpuThreads.takeUnless { it == 0 }
            )
            else -> OnDeviceSuggestionEngine.BackendSelection.Gpu
        }
        val originalKeyboardActive = OnDeviceGenerationControl.isKeyboardActive
        val samples = JSONArray()
        var engine: OnDeviceSuggestionEngine? = null
        var bodyFailure: Throwable? = null

        try {
            assertFalse("측정 시작 전에 다른 native lease가 실행 중입니다.", OnDeviceGenerationControl.isGenerating)
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
            assertTrue("측정은 테스트 키보드 gate가 활성 상태여야 합니다.", OnDeviceGenerationControl.isKeyboardActive)

            val createdEngine = OnDeviceSuggestionEngine(targetContext.applicationContext, backend)
            engine = createdEngine
            assertFalse("새 Engine은 시작 시 warm 상태일 수 없습니다.", createdEngine.isWarm)
            assertFalse("새 Engine은 시작 시 실행 중일 수 없습니다.", createdEngine.isRunning)

            scenarioLoop@ for (scenario in scenarios) {
                for (repeatIndex in 1..repeat) {
                    val sample = measure(createdEngine, scenario, template, repeatIndex)
                    samples.put(sample)
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString(SAMPLE_EVIDENCE_KEY, sample.toString())
                    })
                    if (createdEngine.terminalFailureCode != null) break@scenarioLoop
                }
            }

            val summary = summarize(samples, backendArgument, cpuThreads, templateName)
            val result = JSONObject().put("summary", summary).put("samples", samples).toString()
            File(workDir, "results-latest.json").writeText(result, Charsets.UTF_8)
            File(workDir, "results-${System.currentTimeMillis()}.json").writeText(result, Charsets.UTF_8)
            instrumentation.sendStatus(0, Bundle().apply {
                putString(SUMMARY_EVIDENCE_KEY, summary.toString())
            })

            assertTrue(
                "오류 없이 끝난 샘플이 하나도 없습니다.",
                summary.getInt("totalSamples") - summary.getInt("errorCount") >= 1
            )
        } catch (error: Throwable) {
            bodyFailure = error
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
            bodyFailure?.let { primary ->
                cleanupFailure?.let(primary::addSuppressed)
            } ?: cleanupFailure?.let { throw it }
        }
    }

    private class Scenario(
        val id: String,
        val appCategory: String,
        val packageName: String,
        val text: String,
        val fieldHint: String?,
        val recentSentences: List<String>,
        val styleExamples: List<String>,
        val good: String
    )

    private suspend fun measure(
        engine: OnDeviceSuggestionEngine,
        scenario: Scenario,
        template: String?,
        repeatIndex: Int
    ): JSONObject {
        val sample = JSONObject()
            .put("id", scenario.id)
            .put("good", scenario.good)
            .put("text", scenario.text)
            .put("recentSentences", JSONArray(scenario.recentSentences))
            .put("repeatIndex", repeatIndex)
        val input = OnDeviceSuggestionPolicy.Input(
            textBeforeCursor = scenario.text,
            packageName = scenario.packageName,
            inputType = INPUT_TYPE,
            imeAction = IME_ACTION,
            mode = OnDeviceSuggestionPolicy.Mode.SENTENCE,
            appCategory = scenario.appCategory,
            fieldHint = scenario.fieldHint,
            recentSentences = scenario.recentSentences,
            styleExamples = scenario.styleExamples
        )
        var prompt: String? = null
        try {
            val builtPrompt = template?.let { renderTemplate(it, scenario) }
                ?: OnDeviceSuggestionPolicy.promptFor(input)
            prompt = builtPrompt
            val result = withTimeout(GENERATION_BUDGET_MS) { engine.suggest(builtPrompt) }
            val suffix = OnDeviceSuggestionPolicy.parseSuffix(input, result.text)
            return sample
                .put("promptChars", builtPrompt.length)
                .put("prompt", builtPrompt)
                .put("raw", result.text)
                .put("suffix", suffix ?: JSONObject.NULL)
                .put("rejection", OnDeviceSuggestionPolicy.rejectionReason(input, result.text) ?: JSONObject.NULL)
                .put("joined", suffix?.let { scenario.text + it } ?: JSONObject.NULL)
                .put("initializationMs", result.initializationMs)
                .put("firstTextMs", result.firstTextMs ?: JSONObject.NULL)
                .put("totalMs", result.totalMs)
                .put("engineReused", result.engineReused)
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            prompt?.let { sample.put("promptChars", it.length).put("prompt", it) }
            return sample.put(
                "error",
                JSONObject()
                    .put("exceptionClass", error.javaClass.name)
                    .put(
                        "code",
                        (error as? OnDeviceSuggestionEngine.OnDeviceSuggestionException)?.code
                            ?: JSONObject.NULL
                    )
            )
        }
    }

    private fun renderTemplate(template: String, scenario: Scenario): String {
        val meta = "{\"app\":\"${escapeJson(scenario.packageName)}\",\"appType\":\"${appTypeLabel(scenario.appCategory)}\"," +
            "\"inputType\":$INPUT_TYPE,\"imeAction\":$IME_ACTION}"
        val hint = scenario.fieldHint?.takeIf { it.isNotBlank() }
            ?.let { "[입력창 안내문] \"${escapeJson(it)}\"" }
            .orEmpty()
        val recent = sentenceBlock("[이 사용자가 이 앱에서 최근 보낸 문장]", scenario.recentSentences)
        val style = sentenceBlock(
            "[이 사용자가 평소 쓴 비슷한 문장 — 말투와 표현만 참고하고 그대로 베끼지 마세요]",
            scenario.styleExamples
        )
        return template
            .replace("{META}", meta)
            .replace("{HINT}", hint)
            .replace("{RECENT}", recent)
            .replace("{STYLE}", style)
            .replace("{TEXT}", escapeJson(scenario.text))
            .replace("{APPTYPE}", appTypeLabel(scenario.appCategory))
            .replace("{PREV}", escapeJson(previousSentences(scenario.text)))
            .replace("{CUR}", escapeJson(scenario.text.removePrefix(previousSentencesRaw(scenario.text)).trimStart()))
            .replace(Regex("\n{3,}"), "\n\n")
    }

    /** The finished sentences before the one being typed, ending at the last terminal mark followed by whitespace. */
    private fun previousSentencesRaw(text: String): String {
        val end = (text.length - 2 downTo 0).firstOrNull { index ->
            text[index] in ".?!\n" && text[index + 1].isWhitespace()
        } ?: return ""
        return text.substring(0, end + 1)
    }

    private fun previousSentences(text: String): String = previousSentencesRaw(text).trim()

    private fun sentenceBlock(title: String, sentences: List<String>): String {
        val clamped = sentences.take(MAX_PROMPT_SENTENCES).map { it.take(MAX_PROMPT_SENTENCE_CHARS) }
        if (clamped.isEmpty()) return ""
        return (listOf(title) + clamped.map { "- \"${escapeJson(it)}\"" }).joinToString("\n")
    }

    private fun summarize(
        samples: JSONArray,
        backendArgument: String,
        cpuThreads: Int,
        templateName: String?
    ): JSONObject {
        var suffixCount = 0
        var errorCount = 0
        var firstInitializationMs: Long? = null
        val rejections = sortedMapOf<String, Int>()
        val warmTotals = mutableListOf<Long>()
        for (index in 0 until samples.length()) {
            val sample = samples.getJSONObject(index)
            if (sample.has("error")) {
                errorCount++
                continue
            }
            if (firstInitializationMs == null) firstInitializationMs = sample.getLong("initializationMs")
            if (!sample.isNull("suffix")) suffixCount++
            if (!sample.isNull("rejection")) {
                rejections.merge(sample.getString("rejection"), 1, Int::plus)
            }
            if (sample.getBoolean("engineReused")) warmTotals += sample.getLong("totalMs")
        }
        warmTotals.sort()
        val median = when {
            warmTotals.isEmpty() -> null
            warmTotals.size % 2 == 1 -> warmTotals[warmTotals.size / 2].toDouble()
            else -> (warmTotals[warmTotals.size / 2 - 1] + warmTotals[warmTotals.size / 2]) / 2.0
        }
        return JSONObject()
            .put("totalSamples", samples.length())
            .put("suffixCount", suffixCount)
            .put("rejectionCounts", JSONObject(rejections as Map<*, *>))
            .put("errorCount", errorCount)
            .put("warmSampleCount", warmTotals.size)
            .put("warmTotalMsMedian", median ?: JSONObject.NULL)
            .put("warmTotalMsMax", warmTotals.lastOrNull() ?: JSONObject.NULL)
            .put("firstInitializationMs", firstInitializationMs ?: JSONObject.NULL)
            .put("backend", backendArgument)
            .put("cpuThreads", cpuThreads)
            .put("templateFile", templateName ?: "production")
    }

    private fun loadScenarios(workDir: File): List<Scenario> {
        val scenariosName = optionalFileName("scenariosFile")
        val raw = if (scenariosName != null) {
            readWorkFile(workDir, scenariosName)
        } else {
            instrumentation.context.assets.open(SCENARIOS_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        val array = JSONArray(raw)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Scenario(
                id = item.getString("id"),
                appCategory = item.getString("appCategory"),
                packageName = item.getString("packageName"),
                text = item.getString("text"),
                fieldHint = item.optString("fieldHint").takeIf { item.has("fieldHint") && it.isNotBlank() },
                recentSentences = stringList(item.optJSONArray("recentSentences")),
                styleExamples = stringList(item.optJSONArray("styleExamples")),
                good = item.optString("good")
            )
        }
    }

    private fun filterScenarios(scenarios: List<Scenario>): List<Scenario> {
        val ids = arguments.getString("ids")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: return scenarios
        val known = scenarios.map { it.id }.toSet()
        val unknown = ids.filterNot { it in known }
        require(unknown.isEmpty()) { "ids 인자에 없는 시나리오 id가 있습니다: $unknown" }
        return scenarios.filter { it.id in ids }
    }

    private fun stringList(array: JSONArray?): List<String> =
        array?.let { values -> List(values.length()) { values.getString(it) } }.orEmpty()

    private fun optionalFileName(key: String): String? {
        val value = arguments.getString(key)?.takeIf { it.isNotBlank() } ?: return null
        require('/' !in value && '\\' !in value && value != "..") { "$key 인자는 파일 이름만 허용합니다: $value" }
        return value
    }

    private fun readWorkFile(workDir: File, name: String): String {
        val file = File(workDir, name)
        require(file.isFile) { "파일이 없습니다: ${file.absolutePath}" }
        return file.readText(Charsets.UTF_8)
    }

    private fun requiredBackend(): String {
        val value = arguments.getString("backend") ?: "cpu"
        require(value == "cpu" || value == "gpu") { "backend 인자는 cpu 또는 gpu여야 합니다: $value" }
        return value
    }

    private fun requiredCpuThreads(backend: String): Int {
        if (backend == "gpu") return 0
        val raw = arguments.getString("cpuThreads") ?: "4"
        val value = raw.toIntOrNull()
        require(value != null && value in setOf(0, 2, 4)) {
            "cpuThreads 인자는 0, 2, 4 중 하나여야 합니다: $raw"
        }
        return value
    }

    private fun requiredRepeat(): Int {
        val raw = arguments.getString("repeat") ?: "1"
        val value = raw.toIntOrNull()
        require(value != null && value in 1..3) { "repeat 인자는 1..3 범위여야 합니다: $raw" }
        return value
    }

    private fun appTypeLabel(appCategory: String): String = when (appCategory) {
        "messenger" -> "메신저"
        "work" -> "업무 메신저"
        "email" -> "메일"
        "social" -> "SNS"
        "notes" -> "메모"
        "browser" -> "검색·웹"
        "commerce" -> "쇼핑"
        else -> "일반"
    }

    private fun escapeJson(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }

    private companion object {
        const val SAMPLE_EVIDENCE_KEY = "gemmaQualitySampleJson"
        const val SUMMARY_EVIDENCE_KEY = "gemmaQualitySummaryJson"
        const val SCENARIOS_ASSET = "gemma_quality_scenarios.json"
        const val WORK_DIR_NAME = "gemma-quality"
        const val INPUT_TYPE = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        const val IME_ACTION = EditorInfo.IME_ACTION_NONE
        const val GENERATION_BUDGET_MS = 180_000L
        const val CLOSE_BUDGET_MS = 30_000L
        const val MAX_PROMPT_SENTENCES = 3
        const val MAX_PROMPT_SENTENCE_CHARS = 80
    }
}
