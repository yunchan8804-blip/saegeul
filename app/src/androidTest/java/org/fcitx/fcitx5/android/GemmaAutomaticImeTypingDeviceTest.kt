/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.debug.AiEditorTestActivity
import org.fcitx.fcitx5.android.input.EditorIdentity
import org.fcitx.fcitx5.android.input.EditorSelection
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAutomaticSuggestionWarmupState
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionCoordinator
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionPolicy
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionSession
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationScheduler
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Collects, per scenario, how long the real IME takes to show an automatic sentence candidate after
 * a person types the text word by word. It records latency and candidate content only; quality is
 * judged by reading the result JSON, so no scenario can fail this test on quality.
 */
class GemmaAutomaticImeTypingDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()

    @Before
    fun pauseAccumulationForInstrumentation() {
        // 이전 실기기 세션에서 남은 축적 주기 작업이 측정 도중 깨어나 생성 lease를 잡는 것을 막는다.
        GemmaAccumulationScheduler.pauseForInstrumentation()
    }

    @After
    fun resumeAccumulationAfterInstrumentation() {
        GemmaAccumulationScheduler.resumeAfterInstrumentation()
    }

    @Test
    fun collectsAutomaticCandidateLatencyWhileTypingWordByWordThroughIme() = runBlocking<Unit> {
        val scenarios = filterScenarios(loadScenarios())
        val typingGapMs = longArgument(TYPING_GAP_ARGUMENT, DEFAULT_TYPING_GAP_MS, minimum = 0L)
        val candidateTimeoutMs = longArgument(CANDIDATE_TIMEOUT_ARGUMENT, DEFAULT_CANDIDATE_TIMEOUT_MS, minimum = 1L)
        val warmupTimeoutMs = longArgument(WARMUP_TIMEOUT_ARGUMENT, DEFAULT_WARMUP_TIMEOUT_MS, minimum = 1L)
        val optInPref = AppPrefs.getInstance().internal.automaticOnDeviceSuggestionsOptIn
        val priorOptIn = optInPref.getValue()
        val harness = prepareImeHarness()
        val samples = mutableListOf<JSONObject>()
        var warmupMs: Long? = null
        var activity: AiEditorTestActivity? = null
        var ime: FcitxInputMethodService? = null
        var priorEnabled: Boolean? = null
        var primaryFailure: Throwable? = null
        try {
            val testActivity = launchActivity()
            activity = testActivity
            val editor = selectNormalAndClear(testActivity)
            showKeyboard(editor)
            val service = waitForCurrentEditor(editorTarget(editor))
            ime = service
            showConnectedImeWindow(editor, harness.automation)
            assertTrue("자동 추천 debug 지원이 꺼져 있습니다.", onMain { service.automaticSuggestionsSupported })

            priorEnabled = onMain { service.automaticSuggestionsEnabled }
            val warmupStartedAt = SystemClock.elapsedRealtime()
            onMain { service.setAutomaticSuggestionsEnabled(true); Unit }
            // A cold GPU engine warms up only while the keyboard is hidden (initialization would stall
            // drawing), so hide it the way a user leaves a field and come back once it is warm.
            // `-e hideForWarmup false` checks that a CPU engine warms up with the keyboard shown.
            if (arguments.getString("hideForWarmup") != "false") onMain {
                (editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(editor.windowToken, 0)
                Unit
            }
            waitUntil("자동 추천 엔진이 ${warmupTimeoutMs}ms 안에 warm 상태가 되지 못했습니다.", warmupTimeoutMs) {
                onMain {
                    service.automaticSuggestionRuntimeWarm &&
                        service.automaticSuggestionWarmupState == OnDeviceAutomaticSuggestionWarmupState.Idle
                }
            }
            warmupMs = SystemClock.elapsedRealtime() - warmupStartedAt
            showConnectedImeWindow(editor, harness.automation)
            waitForCurrentEditor(editorTarget(editor))

            scenarios.forEachIndexed { index, scenario ->
                val sample = if (scenario.recentSentences.isNotEmpty()) {
                    skippedSample(scenario)
                } else {
                    measureScenario(
                        scenario = scenario,
                        activity = testActivity,
                        editor = editor,
                        ime = service,
                        automation = harness.automation,
                        typingGapMs = typingGapMs,
                        candidateTimeoutMs = candidateTimeoutMs,
                        captureScreen = index < SCREENSHOT_SCENARIO_COUNT
                    )
                }
                samples += sample
                instrumentation.sendStatus(0, Bundle().apply { putString(SAMPLE_EVIDENCE_KEY, sample.toString()) })
            }
            assertTrue(
                "후보가 화면에 보인 시나리오가 하나도 없습니다: ${outcomeCounts(samples)}",
                samples.any { it.getString("outcome") == OUTCOME_VISIBLE }
            )
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            try {
                val summary = summarize(samples, warmupMs, typingGapMs, candidateTimeoutMs, harness.evidence)
                val result = JSONObject().put("summary", summary).put("samples", JSONArray(samples))
                val file = persistResult(result)
                summary.put("resultFile", file)
                instrumentation.sendStatus(0, Bundle().apply { putString(SUMMARY_EVIDENCE_KEY, summary.toString()) })
            } catch (persistError: Throwable) {
                cleanupFailure = appendFailure(cleanupFailure, persistError)
            }
            try {
                val activeIme = ime
                val restoreEnabled = priorEnabled
                if (activeIme != null && restoreEnabled != null) {
                    onMain { activeIme.setAutomaticSuggestionsEnabled(restoreEnabled); Unit }
                    // Only an opt-out releases the warm engine; a restored opt-in legitimately keeps its lease.
                    if (!restoreEnabled) waitUntil("자동 추천 원복 뒤 native lease가 남아 있습니다.", STOP_TIMEOUT_MS) {
                        !OnDeviceGenerationControl.isGenerating
                    }
                }
            } catch (restoreError: Throwable) {
                cleanupFailure = appendFailure(cleanupFailure, restoreError)
            }
            try {
                activity?.let { onMain { it.finish(); Unit } }
            } catch (finishError: Throwable) {
                cleanupFailure = appendFailure(cleanupFailure, finishError)
            }
            try {
                optInPref.setValue(priorOptIn)
            } catch (restoreError: Throwable) {
                cleanupFailure = appendFailure(cleanupFailure, restoreError)
            }
            try {
                harness.restore()
            } catch (restoreError: Throwable) {
                cleanupFailure = appendFailure(cleanupFailure, restoreError)
            }
            primaryFailure?.let { primary -> cleanupFailure?.let(primary::addSuppressed) }
                ?: cleanupFailure?.let { throw it }
        }
    }

    private fun measureScenario(
        scenario: Scenario,
        activity: AiEditorTestActivity,
        editor: EditText,
        ime: FcitxInputMethodService,
        automation: UiAutomation,
        typingGapMs: Long,
        candidateTimeoutMs: Long,
        captureScreen: Boolean
    ): JSONObject {
        onMain { editor.setText(""); Unit }
        waitUntil("${scenario.id}: 편집기를 비우지 못했습니다.", SHORT_TIMEOUT_MS) { onMain { editor.text.toString().isEmpty() } }
        waitForCurrentEditor(editorTarget(editor))
        val idleWaitStartedAt = SystemClock.elapsedRealtime()
        val idleBeforeTyping = awaitGenerationIdle(ime, PRIOR_GENERATION_IDLE_TIMEOUT_MS)
        val priorGenerationWaitMs = SystemClock.elapsedRealtime() - idleWaitStartedAt

        val promptFile = File(instrumentation.targetContext.filesDir, PROMPT_DEBUG_FILE)
        val promptModifiedBefore = if (promptFile.exists()) promptFile.lastModified() else 0L

        val chunks = splitIntoWordChunks(scenario.text)
        assertEquals("${scenario.id}: 어절 분할이 원문을 보존하지 못했습니다.", scenario.text, chunks.joinToString(""))
        chunks.forEachIndexed { index, chunk ->
            assertTrue("${scenario.id}: 어절 '$chunk'을 IME commit 경로로 넣지 못했습니다.", onMain { ime.commitToEditor(chunk) })
            if (index < chunks.lastIndex) SystemClock.sleep(typingGapMs)
        }
        waitUntil("${scenario.id}: editor 원문이 입력 텍스트와 다릅니다.", SHORT_TIMEOUT_MS) {
            onMain { editor.text.toString() == scenario.text }
        }
        val startedAt = SystemClock.elapsedRealtime()

        var outcome = OUTCOME_TIMEOUT
        var latencyMs: Long? = null
        var generatingObservedMs: Long? = null
        var shown: List<OnDeviceSuggestionCoordinator.Candidate> = emptyList()
        var screenshot: JSONObject? = null
        var status = onMain { ime.automaticSuggestionStatus }
        var readyUnmatched: JSONObject? = null
        while (SystemClock.elapsedRealtime() - startedAt < candidateTimeoutMs) {
            status = onMain { ime.automaticSuggestionStatus }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            if (status.state == OnDeviceSuggestionCoordinator.State.GENERATING && generatingObservedMs == null) {
                generatingObservedMs = elapsedMs
            }
            if (status.state == OnDeviceSuggestionCoordinator.State.READY) {
                val candidates = onMain { ime.getAutomaticSuggestionCandidates() }
                    .filter { it.mode == OnDeviceSuggestionPolicy.Mode.SENTENCE || it.mode == OnDeviceSuggestionPolicy.Mode.WORD }
                if (candidates.isNotEmpty() && readyUnmatched == null) {
                    readyUnmatched = JSONObject()
                        .put("insertions", JSONArray(candidates.map { "${it.mode}:${it.insertion}|${it.origin}" }))
                        .put("labels", visibleImeLabels(automation))
                }
                if (candidates.isNotEmpty() && candidates.any { findAutomaticCandidateNode(automation, it) != null }) {
                    latencyMs = SystemClock.elapsedRealtime() - startedAt
                    shown = candidates
                    outcome = OUTCOME_VISIBLE
                    if (captureScreen) screenshot = captureScreenshot("typing-${scenario.id}", activity)
                    break
                }
            }
            if (status.state == OnDeviceSuggestionCoordinator.State.ERROR) {
                outcome = OUTCOME_ERROR
                break
            }
            if (generatingObservedMs != null && status.state == OnDeviceSuggestionCoordinator.State.NO_CANDIDATE) {
                outcome = OUTCOME_NO_CANDIDATE
                break
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        if (outcome != OUTCOME_VISIBLE) status = onMain { ime.automaticSuggestionStatus }
        val unmatchedLabels = if (outcome == OUTCOME_VISIBLE) null else visibleImeLabels(automation)

        val prompt = if (promptFile.exists() && promptFile.lastModified() > promptModifiedBefore) {
            promptFile.readText(Charsets.UTF_8)
        } else {
            null
        }
        return JSONObject()
            .put("id", scenario.id)
            .put("good", scenario.good)
            .put("text", scenario.text)
            .put("outcome", outcome)
            .put("visibleImeLabelsWhenUnmatched", unmatchedLabels ?: JSONObject.NULL)
            .put("firstReadyObservation", readyUnmatched ?: JSONObject.NULL)
            .put("latencyMs", latencyMs ?: JSONObject.NULL)
            .put("generatingObservedMs", generatingObservedMs ?: JSONObject.NULL)
            .put("finalState", status.state.name)
            .put("errorCode", status.errorCode ?: JSONObject.NULL)
            .put("sentenceInsertion", insertionOf(shown, OnDeviceSuggestionPolicy.Mode.SENTENCE))
            .put("wordInsertion", insertionOf(shown, OnDeviceSuggestionPolicy.Mode.WORD))
            .put("priorGenerationWaitMs", priorGenerationWaitMs)
            .put("priorGenerationIdle", idleBeforeTyping)
            .put("screenshot", screenshot ?: JSONObject.NULL)
            .put("prompt", prompt ?: JSONObject.NULL)
    }

    private fun skippedSample(scenario: Scenario): JSONObject = JSONObject()
        .put("id", scenario.id)
        .put("good", scenario.good)
        .put("text", scenario.text)
        .put("outcome", OUTCOME_SKIPPED)
        .put("skipReason", "recentSentences 시나리오는 실제 편집기 하나에서 재현할 수 없습니다.")
        .put("latencyMs", JSONObject.NULL)
        .put("generatingObservedMs", JSONObject.NULL)
        .put("finalState", JSONObject.NULL)
        .put("errorCode", JSONObject.NULL)
        .put("sentenceInsertion", JSONObject.NULL)
        .put("wordInsertion", JSONObject.NULL)
        .put("prompt", JSONObject.NULL)

    private fun insertionOf(
        candidates: List<OnDeviceSuggestionCoordinator.Candidate>,
        mode: OnDeviceSuggestionPolicy.Mode
    ): Any = candidates.firstOrNull { it.mode == mode }?.insertion ?: JSONObject.NULL

    /** A warm engine keeps holding its native lease, so idleness is read from the coordinator state instead. */
    private fun awaitGenerationIdle(ime: FcitxInputMethodService, timeoutMs: Long): Boolean {
        fun idle() = onMain { ime.automaticSuggestionStatus.state } !in BUSY_STATES
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (idle()) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return idle()
    }

    private fun splitIntoWordChunks(text: String): List<String> =
        WORD_CHUNK.findAll(text).map { it.value }.toList()

    private fun summarize(
        samples: List<JSONObject>,
        warmupMs: Long?,
        typingGapMs: Long,
        candidateTimeoutMs: Long,
        imeRebind: JSONObject
    ): JSONObject {
        val visibleLatencies = samples
            .filter { it.getString("outcome") == OUTCOME_VISIBLE }
            .map { it.getLong("latencyMs") }
            .sorted()
        val median = when {
            visibleLatencies.isEmpty() -> null
            visibleLatencies.size % 2 == 1 -> visibleLatencies[visibleLatencies.size / 2]
            else -> (visibleLatencies[visibleLatencies.size / 2 - 1] + visibleLatencies[visibleLatencies.size / 2]) / 2
        }
        return JSONObject()
            .put("scenarioCount", samples.size)
            .put("outcomes", outcomeCounts(samples))
            .put("visibleLatencyMedianMs", median ?: JSONObject.NULL)
            .put("visibleLatencyMaxMs", visibleLatencies.lastOrNull() ?: JSONObject.NULL)
            .put("warmupMs", warmupMs ?: JSONObject.NULL)
            .put("typingGapMs", typingGapMs)
            .put("candidateTimeoutMs", candidateTimeoutMs)
            .put("imeRebind", imeRebind)
    }

    private fun outcomeCounts(samples: List<JSONObject>): JSONObject = JSONObject().also { counts ->
        OUTCOMES.forEach { outcome -> counts.put(outcome, samples.count { it.getString("outcome") == outcome }) }
    }

    private fun persistResult(result: JSONObject): String {
        val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), WORK_DIR_NAME)
        if (!directory.exists()) assertTrue("결과 디렉터리를 만들지 못했습니다.", directory.mkdirs())
        val json = result.toString(2)
        val timestamped = File(directory, "ime-results-${System.currentTimeMillis()}.json")
        FileOutputStream(timestamped).bufferedWriter(Charsets.UTF_8).use { it.write(json) }
        FileOutputStream(File(directory, "ime-results-latest.json")).bufferedWriter(Charsets.UTF_8).use { it.write(json) }
        return timestamped.absolutePath
    }

    private fun loadScenarios(): List<Scenario> {
        val raw = instrumentation.context.assets.open(SCENARIOS_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val array = JSONArray(raw)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val recent = item.optJSONArray("recentSentences")
            Scenario(
                id = item.getString("id"),
                text = item.getString("text"),
                recentSentences = if (recent == null) emptyList() else List(recent.length()) { recent.getString(it) },
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

    private fun longArgument(name: String, default: Long, minimum: Long): Long {
        val raw = arguments.getString(name) ?: return default
        val value = raw.toLongOrNull()
        require(value != null && value >= minimum) { "$name 인자는 $minimum 이상의 정수여야 합니다: $raw" }
        return value
    }

    private fun findAutomaticCandidateNode(
        automation: UiAutomation,
        candidate: OnDeviceSuggestionCoordinator.Candidate
    ): AccessibilityNodeInfo? {
        val origin = automaticOriginLabel(candidate.origin)
        val labels = setOf(
            "${candidate.insertion} $origin",
            "${candidate.insertion.trim()} $origin",
            "$origin: ${candidate.insertion.trim()}"
        )
        return imeRoots(automation).firstNotNullOfOrNull { root ->
            root.findVisible { node -> node.text?.toString() in labels || node.contentDescription?.toString() in labels }
        }
    }

    private fun automaticOriginLabel(origin: OnDeviceSuggestionSession.Origin): String = when (origin) {
        OnDeviceSuggestionSession.Origin.GENERATED -> instrumentation.targetContext.getString(R.string.gemma_automatic_generated)
        OnDeviceSuggestionSession.Origin.CONTINUATION_CACHE -> instrumentation.targetContext.getString(R.string.gemma_automatic_continuation)
    }

    private fun imeRoots(automation: UiAutomation): Sequence<AccessibilityNodeInfo> = automation.windows.asSequence()
        .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        .mapNotNull { it.root }
        .filter { it.packageName?.toString() == instrumentation.targetContext.packageName }

    private fun visibleImeLabels(automation: UiAutomation): JSONArray = JSONArray().also { output ->
        fun collect(node: AccessibilityNodeInfo) {
            if (output.length() >= MAX_UNMATCHED_LABELS) return
            if (node.isVisibleToUser) {
                (node.contentDescription ?: node.text)?.toString()?.takeIf { it.isNotBlank() }?.let(output::put)
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
        }
        imeRoots(automation).forEach(::collect)
    }

    private fun AccessibilityNodeInfo.findVisible(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (isVisibleToUser && predicate(this)) return this
        for (index in 0 until childCount) getChild(index)?.findVisible(predicate)?.let { return it }
        return null
    }

    private suspend fun prepareImeHarness(): ImeHarness {
        val automation = instrumentation.uiAutomation.apply {
            val info = requireNotNull(serviceInfo) { "UiAutomation service info를 읽지 못했습니다." }
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
        }
        val context = instrumentation.targetContext
        val store = GemmaAccumulationStore.get(context)
        val prior = store.load()
        val originalIme = requireNotNull(selectedImeId()) { "원래 기본 입력기를 읽지 못했습니다." }
        val manager = requireNotNull(context.getSystemService(InputMethodManager::class.java)) { "InputMethodManager가 없습니다." }
        val enabled = manager.enabledInputMethodList
        val target = requireNotNull(enabled.singleOrNull {
            it.serviceInfo.packageName == context.packageName && it.serviceInfo.name == FcitxInputMethodService::class.java.name
        }) { "활성 입력기 목록에서 새글 IME를 찾지 못했습니다." }
        val alternate = requireNotNull(enabled.firstOrNull { it.id != target.id }) { "IME 재바인딩용 다른 입력기가 없습니다." }
        val evidence = JSONObject()
            .put("originalImeId", originalIme)
            .put("targetImeId", target.id)
            .put("alternateImeId", alternate.id)
            .put("priorPublicPreparationEnabled", prior.enabled)
            .put("priorPublicManualRequested", prior.manualRequested)
        try {
            assertFalse("수동 공개 준비 요청이 남아 있습니다.", prior.manualRequested)
            if (prior.enabled) GemmaAccumulationScheduler.setEnabled(context, false)
            assertFalse("IME 재바인딩 중 공개 준비가 활성 상태입니다.", store.load().enabled)
            evidence.put("alternateSetOutput", setSystemIme(automation, alternate.id))
            waitUntil("다른 IME 전환 뒤 새글 IME 인스턴스가 남아 있습니다.", SHORT_TIMEOUT_MS) {
                FcitxInputMethodService.activeInstance == null
            }
            evidence.put("targetSetOutput", setSystemIme(automation, target.id))
            waitUntil("새글 IME를 기본 입력기로 재선택하지 못했습니다.", SHORT_TIMEOUT_MS) { selectedImeId() == target.id }
            return ImeHarness(automation, originalIme, store, prior.enabled, evidence)
        } catch (error: Throwable) {
            try {
                setSystemIme(automation, originalIme)
                if (prior.enabled) GemmaAccumulationScheduler.setEnabled(context, true)
            } catch (restoreError: Throwable) {
                error.addSuppressed(restoreError)
            }
            throw error
        }
    }

    private fun launchActivity(): AiEditorTestActivity = instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, AiEditorTestActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    ) as AiEditorTestActivity

    private fun selectNormalAndClear(activity: AiEditorTestActivity): EditText = onMain {
        requireNotNull(activity.window.decorView.findByContentDescription("Select normal complete-editor mode")).performClick()
        requireNotNull(activity.window.decorView.findByContentDescription("Clear text")).performClick()
        (requireNotNull(activity.window.decorView.findByContentDescription("AI E2E normal editor")) as EditText).also { editor ->
            // 기기 autofill 서비스가 저장된 계정 등을 채워 넣으면 입력 측정이 오염된다.
            editor.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            activity.getSystemService(android.view.autofill.AutofillManager::class.java)?.cancel()
        }
    }

    private fun showKeyboard(editor: EditText) {
        waitUntil("editor Activity window focus를 확보하지 못했습니다.", SHORT_TIMEOUT_MS) { onMain { editor.hasWindowFocus() } }
        onMain {
            editor.requestFocus()
            (editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            Unit
        }
    }

    private fun showConnectedImeWindow(editor: EditText, automation: UiAutomation) {
        showKeyboard(editor)
        waitUntil("현재 연결된 새글 IME window가 visible 상태로 나타나지 않았습니다.", SHORT_TIMEOUT_MS) {
            imeRoots(automation).any { it.isVisibleToUser }
        }
    }

    private fun waitForCurrentEditor(target: EditorTarget): FcitxInputMethodService {
        val deadline = SystemClock.elapsedRealtime() + SHORT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val ime = FcitxInputMethodService.activeInstance
            if (ime != null && ime.matchesCurrentEditor(
                    EditorIdentity(target.packageName, target.fieldId, target.inputType),
                    EditorSelection(target.selectionStart, target.selectionEnd),
                    ime.currentInputSessionEpoch
                )
            ) return ime
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("새글 IME가 debug editor에 연결되지 않았습니다.")
    }

    private fun editorTarget(editor: EditText): EditorTarget = onMain {
        EditorTarget(editor.context.packageName, editor.id, editor.inputType, editor.selectionStart, editor.selectionEnd)
    }

    private fun captureScreenshot(stage: String, activity: AiEditorTestActivity): JSONObject {
        assertTrue(
            "screenshot 시 debug Activity가 화면에 붙어 있지 않습니다.",
            onMain { activity.window.decorView.isAttachedToWindow && !activity.isFinishing && !activity.isDestroyed }
        )
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "UiAutomation screenshot을 가져오지 못했습니다." }
        try {
            val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), WORK_DIR_NAME)
            if (!directory.exists()) assertTrue("스크린샷 디렉터리를 만들지 못했습니다.", directory.mkdirs())
            val target = File(directory, "$stage-${SystemClock.elapsedRealtime()}.png")
            FileOutputStream(target).use { output ->
                assertTrue("$stage screenshot을 저장하지 못했습니다.", screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            return JSONObject().put("path", target.absolutePath).put("saved", true)
        } finally {
            screenshot.recycle()
        }
    }

    private fun selectedImeId(): String? = Settings.Secure.getString(instrumentation.targetContext.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)

    private fun setSystemIme(automation: UiAutomation, imeId: String): String = automation.executeShellCommand("ime set $imeId").use { descriptor ->
        FileInputStream(descriptor.fileDescriptor).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun waitUntil(message: String, timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        assertTrue(message, predicate())
    }

    private fun <T : Any> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        return requireNotNull(result)
    }

    private fun View.findByContentDescription(description: String): View? {
        if (contentDescription?.toString() == description) return this
        val group = this as? ViewGroup ?: return null
        for (index in 0 until group.childCount) group.getChildAt(index).findByContentDescription(description)?.let { return it }
        return null
    }

    private fun appendFailure(current: Throwable?, next: Throwable): Throwable = current?.also { it.addSuppressed(next) } ?: next

    private data class Scenario(val id: String, val text: String, val recentSentences: List<String>, val good: String)

    private data class EditorTarget(val packageName: String, val fieldId: Int, val inputType: Int, val selectionStart: Int, val selectionEnd: Int)

    private inner class ImeHarness(
        val automation: UiAutomation,
        private val originalIme: String,
        private val store: GemmaAccumulationStore,
        private val priorPublicPreparationEnabled: Boolean,
        val evidence: JSONObject
    ) {
        private var restored = false

        suspend fun restore() {
            if (restored) return
            evidence.put("restoreOutput", setSystemIme(automation, originalIme))
            waitUntil("원래 기본 입력기를 복원하지 못했습니다.", SHORT_TIMEOUT_MS) { selectedImeId() == originalIme }
            if (priorPublicPreparationEnabled) {
                GemmaAccumulationScheduler.setEnabled(instrumentation.targetContext, true)
                assertTrue("원래 공개 준비 상태를 복원하지 못했습니다.", store.load().enabled)
            }
            restored = true
        }
    }

    private companion object {
        const val SAMPLE_EVIDENCE_KEY = "gemmaImeTypingSampleJson"
        const val SUMMARY_EVIDENCE_KEY = "gemmaImeTypingSummaryJson"
        const val SCENARIOS_ASSET = "gemma_quality_scenarios.json"
        const val WORK_DIR_NAME = "gemma-quality"
        const val PROMPT_DEBUG_FILE = "debug/ondevice_last_prompt.txt"
        const val TYPING_GAP_ARGUMENT = "typingGapMs"
        const val CANDIDATE_TIMEOUT_ARGUMENT = "candidateTimeoutMs"
        const val WARMUP_TIMEOUT_ARGUMENT = "warmupTimeoutMs"
        const val DEFAULT_TYPING_GAP_MS = 250L
        const val DEFAULT_CANDIDATE_TIMEOUT_MS = 60_000L
        const val DEFAULT_WARMUP_TIMEOUT_MS = 300_000L
        const val PRIOR_GENERATION_IDLE_TIMEOUT_MS = 60_000L
        const val POLL_INTERVAL_MS = 50L
        const val SHORT_TIMEOUT_MS = 10_000L
        const val STOP_TIMEOUT_MS = 30_000L
        const val SCREENSHOT_SCENARIO_COUNT = 3
        const val OUTCOME_VISIBLE = "visible"
        val BUSY_STATES = setOf(OnDeviceSuggestionCoordinator.State.DEBOUNCING, OnDeviceSuggestionCoordinator.State.GENERATING)
        const val MAX_UNMATCHED_LABELS = 40
        const val OUTCOME_NO_CANDIDATE = "noCandidate"
        const val OUTCOME_ERROR = "error"
        const val OUTCOME_TIMEOUT = "timeout"
        const val OUTCOME_SKIPPED = "skipped"
        val OUTCOMES = listOf(OUTCOME_VISIBLE, OUTCOME_NO_CANDIDATE, OUTCOME_ERROR, OUTCOME_TIMEOUT, OUTCOME_SKIPPED)
        val WORD_CHUNK = Regex("\\s*\\S+\\s*")
    }
}
