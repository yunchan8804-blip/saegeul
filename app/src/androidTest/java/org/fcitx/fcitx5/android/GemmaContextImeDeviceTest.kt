/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import org.fcitx.fcitx5.android.input.ai.learning.PersonalLearningInstrumentationGate
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationScheduler
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationStore
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelFiles
import org.fcitx.fcitx5.android.input.EditorIdentity
import org.fcitx.fcitx5.android.input.EditorSelection
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.ai.AiInputCaptureResult
import org.fcitx.fcitx5.android.input.ai.AiSuggestionApplyResult
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Headed emulator collection for the explicit, CPU-native context-completion path.
 *
 * This deliberately reads only public synthetic fixtures and the visible IME surface. A test
 * pass records a collection contract; it does not assign Korean semantic or grammar quality.
 */
class GemmaContextImeDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun pauseAccumulationForInstrumentation() {
        // 이전 실기기 세션에서 남은 축적 주기 작업이 이 E2E 도중 깨어나 두 번째 native 엔진을
        // 만드는 것을 막는다. 진행 중인 생성은 그대로 끝나고, 새 생성만 막힌다.
        GemmaAccumulationScheduler.pauseForInstrumentation()
        PersonalLearningInstrumentationGate.pauseForInstrumentation()
    }

    @After
    fun resumeAccumulationAfterInstrumentation() {
        GemmaAccumulationScheduler.resumeAfterInstrumentation()
        PersonalLearningInstrumentationGate.resumeAfterInstrumentation()
    }

    @Test
    fun collectExistingIndependentPublicInputsThroughIme() = runBlocking<Unit> {
        collectFixture(
            fixtureSet = "independent_public_20260910",
            fixtureSha256 = legacyFixtureSha256(INDEPENDENT_INPUTS),
            cases = INDEPENDENT_INPUTS
        )
    }

    @Test
    fun collectHeldOutPublicInputsThroughIme() = runBlocking<Unit> {
        val bytes = instrumentation.context.assets.open(HELD_OUT_ASSET).use { it.readBytes() }
        assertEquals("held-out fixture SHA-256이 고정값과 다릅니다.", HELD_OUT_SHA256, sha256(bytes))
        val json = JSONArray(bytes.toString(Charsets.UTF_8))
        val cases = (0 until json.length()).map { index ->
            val value = json.getJSONObject(index)
            FixtureCase(
                id = value.getString("id"),
                boundary = value.getString("boundary"),
                input = value.getString("input")
            )
        }
        assertFixture(cases, "held_out_public_20260912")
        collectFixture("held_out_public_20260912", HELD_OUT_SHA256, cases)
    }

    @Test
    fun invalidationCancelAndWindowCloseDiscardLateNativeResults() = runBlocking<Unit> {
        val scenarios = listOf(
            SafetyScenario("initialization_cancel", null, CANCELLED, INITIALIZING),
            SafetyScenario("cursor", { editor -> editor.setSelection((editor.selectionStart - 1).coerceAtLeast(0)) }, EDITOR_CHANGED, DECODING),
            SafetyScenario("selection", { editor -> editor.setSelection(0, editor.text.length.coerceAtLeast(1)) }, EDITOR_CHANGED, DECODING),
            SafetyScenario("text", { editor -> editor.append("변경") }, EDITOR_CHANGED, DECODING),
            SafetyScenario("text_same_cursor", null, EDITOR_CHANGED, DECODING),
            SafetyScenario("session", null, null, DECODING),
            SafetyScenario("cancel", null, CANCELLED, DECODING),
            SafetyScenario("window_close", null, null, DECODING)
        )
        val imeHarness = prepareImeHarness()
        var bodyFailure: Throwable? = null
        try {
            scenarios.forEach { scenario -> runInvalidationScenario(scenario, imeHarness) }
        } catch (error: Throwable) {
            bodyFailure = error
            throw error
        } finally {
            try {
                imeHarness.restore()
            } catch (restoreError: Throwable) {
                bodyFailure?.addSuppressed(restoreError) ?: throw restoreError
            }
        }
    }

    @Test
    fun runtimeModelAbsentPreflightShowsExplicitUnavailableState() = runBlocking<Unit> {
        val context = instrumentation.targetContext
        val requiresOffline = requiresOffline()
        val networkBefore = networkSnapshot(context)
        assertOfflineIfRequired(requiresOffline, networkBefore)
        val model = GemmaModelFiles.modelFile(context)
        // 이 검증은 모델 파일을 삭제하지 않는다. 모델이 있는 runtime(정상 개발 기기)에서는 preflight가
        // 성립하지 않으므로 fail이 아니라 skip으로 보고한다. 모델 없는 runtime에서 실행할 때만 실제로
        // 검증된다(가드 의미는 유지).
        Assume.assumeFalse(
            "모델이 있는 런타임에서는 건너뛴다: ${model.absolutePath}",
            model.isFile
        )
        val imeHarness = prepareImeHarness()
        var activity: AiEditorTestActivity? = null
        val automation = imeHarness.automation
        val evidence = JSONObject()
            .put("requiredOffline", requiresOffline)
            .put("offlineProof", requiresOffline && networkBefore.isOfflineProof())
            .put("networkBefore", networkBefore.toJson())
            .put("imeRebind", imeHarness.evidence)
            .put("modelFilePresent", false)
            .put("modelPath", model.absolutePath)
        var bodyFailure: Throwable? = null
        try {
            val launchedActivity = launchActivity()
            activity = launchedActivity
            val editor = selectNormalAndClear(launchedActivity)
            showKeyboard(editor)
            val ime = waitForCurrentEditor(editorTarget(editor))
            showConnectedImeWindow(editor, automation, evidence, imeHarness)
            markEditorMatched(evidence, imeHarness, ime)
            assertTrue(onMain { ime.commitToEditor(MODEL_ABSENT_INPUT) })
            waitForEditorText(editor, MODEL_ABSENT_INPUT)
            val toolbarSetup = JSONObject()
            evidence.put("toolbarSetup", toolbarSetup)
            openContextCompletion(automation, toolbarSetup)
            requireVisibleNodeByDescription(automation, "$PREVIEW_DESCRIPTION_PREFIX$MODEL_ABSENT_INPUT", SHORT_TIMEOUT_MS)
            GemmaImeTestSupport.clickVisibleTextInPanel(automation, instrumentation.targetContext.packageName, COMPLETE, SHORT_TIMEOUT_MS)
            val deadline = SystemClock.elapsedRealtime() + SHORT_TIMEOUT_MS
            var nativeObservedDuringPreflight = false
            while (SystemClock.elapsedRealtime() < deadline &&
                findVisibleNodeByTextOrDescription(automation, MODEL_MISSING) == null
            ) {
                nativeObservedDuringPreflight = nativeObservedDuringPreflight || OnDeviceGenerationControl.isGenerating
                SystemClock.sleep(POLL_INTERVAL_MS)
            }
            requireVisibleNodeByTextOrDescription(automation, MODEL_MISSING, POLL_INTERVAL_MS)
            assertTrue("모델 없음 preflight에 후보가 표시되었습니다.", findCandidateNode(automation, MODEL_ABSENT_INPUT) == null)
            evidence.put("modelMissingVisible", true)
                .put("nativeObservedDuringPreflight", nativeObservedDuringPreflight)
                .put("candidatePresent", false)
        } catch (error: Throwable) {
            bodyFailure = error
            evidence.put("executionFailure", true)
                .put("exceptionClass", error.javaClass.name)
                .put("exceptionMessage", error.message ?: JSONObject.NULL)
            try {
                evidence.put("failureCapture", captureFailureState(automation, "model-absent", activity))
                evidence.put("failureEvidenceFile", persistPublicEvidence("model_absent", "preflight_failed", evidence))
            } catch (captureError: Throwable) {
                error.addSuppressed(captureError)
            }
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            try {
                val networkAfter = networkSnapshot(context)
                assertOfflineIfRequired(requiresOffline, networkAfter)
                closeContextWindow(automation)
                waitUntil("모델 없음 preflight 뒤 native 실행이 남았습니다.", STOP_TIMEOUT_MS) { !OnDeviceGenerationControl.isGenerating }
                evidence.put("networkAfter", networkAfter.toJson())
                    .put("offlineProofAfter", requiresOffline && networkAfter.isOfflineProof())
                    .put("nativeStoppedAtEnd", true)
            } catch (error: Throwable) {
                cleanupFailure = error
            } finally {
                try {
                    activity?.let { onMain { it.finish(); Unit } }
                } catch (finishError: Throwable) {
                    cleanupFailure = appendCleanupFailure(cleanupFailure, finishError)
                } finally {
                    try {
                        imeHarness.restore()
                    } catch (restoreError: Throwable) {
                        cleanupFailure = appendCleanupFailure(cleanupFailure, restoreError)
                    } finally {
                        try {
                            instrumentation.sendStatus(0, Bundle().apply { putString(MODEL_ABSENT_EVIDENCE_KEY, evidence.toString()) })
                        } catch (statusError: Throwable) {
                            cleanupFailure = appendCleanupFailure(cleanupFailure, statusError)
                        }
                    }
                }
            }
            bodyFailure?.let { primary ->
                cleanupFailure?.let(primary::addSuppressed)
            } ?: cleanupFailure?.let { throw it }
        }
    }

    private suspend fun collectFixture(fixtureSet: String, fixtureSha256: String, cases: List<FixtureCase>) {
        assertFixture(cases, fixtureSet)
        val requested = selectedIndices(cases.size)
        val results = JSONArray()
        val imeHarness = prepareImeHarness()
        var bodyFailure: Throwable? = null
        try {
            requested.forEach { index ->
                try {
                    val result = collectOne(fixtureSet, fixtureSha256, cases[index], imeHarness)
                    results.put(result)
                    report("sample_finished", fixtureSet, fixtureSha256, result)
                } catch (error: Throwable) {
                    val failed = JSONObject()
                        .put("fixtureSet", fixtureSet)
                        .put("id", cases[index].id)
                        .put("rawInput", cases[index].input)
                        .put("imeRebind", imeHarness.evidence)
                        .put("executionFailure", true)
                        .put("exceptionClass", error.javaClass.name)
                        .put("exceptionMessage", error.message ?: JSONObject.NULL)
                    results.put(failed)
                    try {
                        report("sample_failed", fixtureSet, fixtureSha256, failed)
                    } catch (reportError: Throwable) {
                        error.addSuppressed(reportError)
                    }
                    throw error
                }
            }
            report(
                "fixture_finished",
                fixtureSet,
                fixtureSha256,
                JSONObject()
                    .put("requestedSampleIndices", JSONArray(requested))
                    .put("fixtureSize", cases.size)
                    .put("imeRebind", imeHarness.evidence)
                    .put("samples", results)
            )
        } catch (error: Throwable) {
            bodyFailure = error
            throw error
        } finally {
            try {
                imeHarness.restore()
            } catch (restoreError: Throwable) {
                bodyFailure?.addSuppressed(restoreError) ?: throw restoreError
            }
        }
    }

    private suspend fun collectOne(
        fixtureSet: String,
        fixtureSha256: String,
        fixture: FixtureCase,
        imeHarness: ImeHarness
    ): JSONObject {
        val context = instrumentation.targetContext
        val requiresOffline = requiresOffline()
        val app = context.applicationContext as FcitxApplication
        val store = GemmaAccumulationStore.get(context)
        app.generatedSentenceBank.load()
        val bankBefore = bankSnapshot(context, app)
        val accumulationBefore = store.load()
        val stateBefore = StateSnapshot(
            accumulationBefore.enabled,
            accumulationBefore.manualRequested,
            accumulationBefore.openSequence
        )
        val networkBefore = networkSnapshot(context)
        assertOfflineIfRequired(requiresOffline, networkBefore)
        assertFalse("명시 문맥 완성 시작 전에 자동 공개 준비가 켜져 있습니다.", stateBefore.enabled)
        assertFalse("명시 문맥 완성 시작 전에 수동 공개 준비 요청이 남아 있습니다.", stateBefore.manualRequested)
        assertFalse("문맥 완성 시작 전에 native 실행이 남아 있습니다.", OnDeviceGenerationControl.isGenerating)

        val activity = launchActivity()
        val automation = imeHarness.automation
        val result = JSONObject()
            .put("fixtureSet", fixtureSet)
            .put("id", fixture.id)
            .put("boundary", fixture.boundary)
            .put("rawInput", fixture.input)
            .put("inputSha256", sha256(fixture.input))
            .put("requiredOffline", requiresOffline)
            .put("offlineProof", requiresOffline && networkBefore.isOfflineProof())
            .put("imeRebind", imeHarness.evidence)
            .put("networkBefore", networkBefore.toJson())
            .put("bankBefore", bankBefore.toJson())
            .put("openSequenceBefore", stateBefore.openSequence)
            .put("manualReview", manualReview())
        var bodyFailure: Throwable? = null
        try {
            val editor = selectNormalAndClear(activity)
            showKeyboard(editor)
            val ime = waitForCurrentEditor(editorTarget(editor))
            showConnectedImeWindow(editor, automation, result, imeHarness)
            markEditorMatched(result, imeHarness, ime)
            waitUntil("IME가 활성화되지 않았습니다.", SHORT_TIMEOUT_MS) { OnDeviceGenerationControl.isKeyboardActive }
            assertTrue("공개 합성 원문을 실제 IME 경로로 넣지 못했습니다.", onMain { ime.commitToEditor(fixture.input) })
            waitForEditorText(editor, fixture.input)

            val toolbarSetup = JSONObject()
            result.put("toolbarSetup", toolbarSetup)
            openContextCompletion(automation, toolbarSetup)
            val preview = requireVisibleNodeByDescription(automation, "$PREVIEW_DESCRIPTION_PREFIX${fixture.input}", SHORT_TIMEOUT_MS)
            result.put("previewVisible", preview.isVisibleToUser).put("previewBounds", boundsOf(preview))
            assertTrue("IME 원문 preview가 보이지 않습니다.", preview.isVisibleToUser)
            GemmaImeTestSupport.clickVisibleTextInPanel(automation, instrumentation.targetContext.packageName, COMPLETE, SHORT_TIMEOUT_MS)

            if (fixture.boundary == TERMINAL_BOUNDARY) {
                waitUntil("완성 문장 경계가 후보 생성을 거부하지 않았습니다.", SHORT_TIMEOUT_MS) {
                    findVisibleNodeByTextOrDescription(automation, TERMINAL) != null
                }
                assertFalse("완성 문장 뒤 공백에서 native 생성이 시작되었습니다.", OnDeviceGenerationControl.isGenerating)
                assertTrue("완성 문장 뒤 공백에서 후보가 표시되었습니다.", findCandidateNode(automation, fixture.input) == null)
                result.put("candidatePresent", false)
                    .put("boundaryRejected", true)
                    .put("nativeStarted", false)
                    .put("nativeStopped", true)
                    .put("status", TERMINAL)
                return result
            }

            val nativeStart = waitForNativeDecode(automation)
            result.put("nativeLeaseObserved", nativeStart.leaseObserved)
                .put("nativeDecodeObserved", nativeStart.phaseObserved)
            assertTrue("명시 문맥 완성은 EXPLICIT_CONTEXT lease를 관측해야 합니다.", nativeStart.leaseObserved)
            assertTrue("명시 문맥 완성은 실제 native decode 상태를 관측해야 합니다.", nativeStart.phaseObserved)
            val completed = waitForCompletionOutcome(automation, fixture.input)
            result.put("latency", completed.latency ?: JSONObject.NULL)
            result.put("status", completed.status)
            result.put("nativeStopped", !OnDeviceGenerationControl.isGenerating)
            assertFalse("후보 준비 뒤 native lease가 남아 있습니다.", OnDeviceGenerationControl.isGenerating)

            val candidate = completed.candidate
            if (candidate == null) {
                result.put("candidatePresent", false)
                    .put("fullCandidate", JSONObject.NULL)
                    .put("suffix", JSONObject.NULL)
                    .put("applied", false)
                return result
            }
            val full = candidate.contentDescription.toString().removePrefix(CANDIDATE_DESCRIPTION_PREFIX)
            assertTrue("표시 후보가 원문을 보존하지 않았습니다.", full.startsWith(fixture.input))
            val suffix = full.removePrefix(fixture.input)
            assertTrue("표시 후보 접미부가 비어 있습니다.", suffix.isNotBlank())
            result.put("candidatePresent", true)
                .put("candidateVisible", candidate.isVisibleToUser)
                .put("candidateBounds", boundsOf(candidate))
                .put("fullCandidate", full)
                .put("suffix", suffix)
            assertTrue("완성 후보가 화면에 보이지 않습니다.", candidate.isVisibleToUser)
            result.put("candidateScreenshot", captureCandidateScreenshot(automation, fixtureSet, fixture.id, activity))

            val applySnapshot = onMain {
                when (val captured = ime.captureOnDeviceContextSnapshot()) {
                    is AiInputCaptureResult.Captured -> captured.snapshot
                    else -> throw AssertionError("후보 적용 직전 editor snapshot을 다시 캡처하지 못했습니다: $captured")
                }
            }

            GemmaImeTestSupport.clickVisibleTextInPanel(automation, instrumentation.targetContext.packageName, APPEND, SHORT_TIMEOUT_MS)
            waitForEditorText(editor, full)
            assertEquals("후보 적용 뒤 editor 원문이 정확히 일치하지 않습니다.", full, onMain { editor.text.toString() })
            assertEquals(
                "같은 snapshot과 suffix의 두 번째 적용은 거부되어야 합니다.",
                AiSuggestionApplyResult.EditorChanged,
                onMain { ime.applyOnDeviceContextCompletion(applySnapshot, suffix) }
            )
            assertEquals("거부된 두 번째 적용이 editor를 변경했습니다.", full, onMain { editor.text.toString() })
            waitUntil("적용 뒤 적용 버튼이 남아 있습니다.", SHORT_TIMEOUT_MS) {
                findVisibleNodeByTextOrDescription(automation, APPEND) == null
            }
            val appliedStatus = instrumentation.targetContext.getString(R.string.gemma_context_applied)
            assertTrue(
                "적용 완료 상태가 표시되지 않았습니다.",
                GemmaImeTestSupport.waitForVisibleTextInPanel(
                    automation,
                    instrumentation.targetContext.packageName,
                    appliedStatus,
                    SHORT_TIMEOUT_MS
                )
            )
            result.put("secondApplyRejected", true).put("appendButtonRemovedAfterApply", true)
            result.put("appliedStatusVisible", true)
                .put("appliedScreenshot", captureCandidateScreenshot(automation, fixtureSet, "${fixture.id}-applied", activity))
            result.put("applied", true).put("editorAfter", onMain { editor.text.toString() })
            return result
        } catch (error: Throwable) {
            bodyFailure = error
            result.put("executionFailure", true)
                .put("exceptionClass", error.javaClass.name)
                .put("exceptionMessage", error.message ?: JSONObject.NULL)
            try {
                result.put("failureCapture", captureFailureState(automation, "sample", activity))
            } catch (captureError: Throwable) {
                error.addSuppressed(captureError)
            }
            try {
                report("sample_execution_failed", fixtureSet, fixtureSha256, result)
            } catch (reportError: Throwable) {
                error.addSuppressed(reportError)
            }
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            try {
                val networkAfter = networkSnapshot(context)
                result.put("networkAfter", networkAfter.toJson())
                result.put("offlineProofAfter", requiresOffline && networkAfter.isOfflineProof())
                assertOfflineIfRequired(requiresOffline, networkAfter)
                closeContextWindow(automation)
                waitUntil("창을 닫은 뒤 native 실행이 30초 안에 종료하지 않았습니다.", STOP_TIMEOUT_MS) {
                    !OnDeviceGenerationControl.isGenerating
                }
                app.generatedSentenceBank.load()
                val bankAfter = bankSnapshot(context, app)
                val accumulationAfter = store.load()
                val stateAfter = StateSnapshot(
                    accumulationAfter.enabled,
                    accumulationAfter.manualRequested,
                    accumulationAfter.openSequence
                )
                result.put("bankAfter", bankAfter.toJson()).put("accumulationBefore", stateBefore.toJson())
                    .put("accumulationAfter", stateAfter.toJson())
                assertEquals("명시 문맥 완성이 공개 은행 수를 변경했습니다.", bankBefore.count, bankAfter.count)
                assertEquals("명시 문맥 완성이 공개 은행 파일 존재 여부를 변경했습니다.", bankBefore.persistedExists, bankAfter.persistedExists)
                assertEquals("명시 문맥 완성이 공개 은행 파일 크기를 변경했습니다.", bankBefore.persistedBytes, bankAfter.persistedBytes)
                assertEquals("명시 문맥 완성이 공개 은행 파일 SHA-256을 변경했습니다.", bankBefore.persistedSha256, bankAfter.persistedSha256)
                assertEquals("명시 문맥 완성이 공개 준비 순번을 변경했습니다.", stateBefore.openSequence, stateAfter.openSequence)
                assertEquals("명시 문맥 완성이 자동 공개 준비 상태를 변경했습니다.", stateBefore.enabled, stateAfter.enabled)
                assertEquals("명시 문맥 완성이 수동 공개 준비 요청을 변경했습니다.", stateBefore.manualRequested, stateAfter.manualRequested)
            } catch (error: Throwable) {
                cleanupFailure = error
            } finally {
                try {
                    onMain { activity.finish(); Unit }
                } catch (finishError: Throwable) {
                    cleanupFailure = appendCleanupFailure(cleanupFailure, finishError)
                }
            }
            bodyFailure?.let { primary ->
                cleanupFailure?.let(primary::addSuppressed)
            } ?: cleanupFailure?.let { throw it }
        }
    }

    private fun runInvalidationScenario(scenario: SafetyScenario, imeHarness: ImeHarness) {
        val raw = "내일 오전 일정은 "
        val requiresOffline = requiresOffline()
        val networkBefore = networkSnapshot(instrumentation.targetContext)
        assertOfflineIfRequired(requiresOffline, networkBefore)
        val activity = launchActivity()
        val automation = imeHarness.automation
        val evidence = JSONObject()
            .put("scenario", scenario.id)
            .put("rawInput", raw)
            .put("requiredOffline", requiresOffline)
            .put("offlineProof", requiresOffline && networkBefore.isOfflineProof())
            .put("networkBefore", networkBefore.toJson())
            .put("imeRebind", imeHarness.evidence)
        var bodyFailure: Throwable? = null
        try {
            val editor = selectNormalAndClear(activity)
            showKeyboard(editor)
            val ime = waitForCurrentEditor(editorTarget(editor))
            showConnectedImeWindow(editor, automation, evidence, imeHarness)
            markEditorMatched(evidence, imeHarness, ime)
            assertTrue(onMain { ime.commitToEditor(raw) })
            waitForEditorText(editor, raw)
            val toolbarSetup = JSONObject()
            evidence.put("toolbarSetup", toolbarSetup)
            openContextCompletion(automation, toolbarSetup)
            GemmaImeTestSupport.clickVisibleTextInPanel(automation, instrumentation.targetContext.packageName, COMPLETE, SHORT_TIMEOUT_MS)
            val phase = if (scenario.phase == INITIALIZING) {
                waitForNativePreparing(automation)
            } else {
                waitForNativeDecode(automation)
            }
            assertTrue("${scenario.id}: native lease를 관측하지 못했습니다.", phase.leaseObserved)
            assertTrue("${scenario.id}: ${scenario.phase} 상태를 관측하지 못했습니다.", phase.phaseObserved)
            evidence.put("cancelPhase", scenario.phase).put("nativeLeaseObserved", phase.leaseObserved)

            var evidenceEditor = editor
            var oldEditorForSession: EditText? = null
            var oldEditorTextBeforeSession: String? = null
            when (scenario.id) {
                "cancel", "initialization_cancel" -> GemmaImeTestSupport.clickVisibleTextInPanel(
                    automation, instrumentation.targetContext.packageName, CANCEL, SHORT_TIMEOUT_MS
                )
                "window_close" -> {
                    assertTrue("창 닫기 Back action이 거부되었습니다.", automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                    waitUntil("창 닫기 뒤 문맥 완성 창이 남아 있습니다.", SHORT_TIMEOUT_MS) {
                        findVisibleNodeByTextOrDescription(automation, TITLE) == null
                    }
                }
                "text_same_cursor" -> onMain {
                    val selection = editor.selectionStart to editor.selectionEnd
                    editor.text.replace(0, 1, "다")
                    assertEquals("same-cursor 원문 변경이 selection을 바꾸면 안 됩니다.", selection, editor.selectionStart to editor.selectionEnd)
                    Unit
                }
                "session" -> {
                    oldEditorForSession = editor
                    oldEditorTextBeforeSession = onMain { editor.text.toString() }
                    val emailEditor = onMain {
                        val switchButton = requireNotNull(
                            activity.window.decorView.findByContentDescription("Email input field test")
                        )
                        assertTrue("이메일 editor 전환 버튼 클릭이 거부되었습니다.", switchButton.performClick())
                        requireNotNull(activity.window.decorView.findByContentDescription("AI E2E email input editor")) as EditText
                    }
                    assertTrue("session 전환이 기존 editor 인스턴스를 재사용했습니다.", emailEditor !== editor)
                    assertTrue("session 전환이 기존 field ID를 재사용했습니다.", emailEditor.id != editor.id)
                    showKeyboard(emailEditor)
                    val sessionIme = waitForCurrentEditor(editorTarget(emailEditor))
                    showConnectedImeWindow(emailEditor, automation, evidence, imeHarness)
                    markEditorMatched(evidence, imeHarness, sessionIme)
                    evidenceEditor = emailEditor
                    evidence.put("sessionChanged", true)
                        .put("oldFieldId", editor.id)
                        .put("newFieldId", emailEditor.id)
                }
                else -> onMain { scenario.mutate?.invoke(activity, editor); Unit }
            }
            scenario.expectedStatus?.let { status ->
                waitUntil("${scenario.id}: 무효화 상태가 표시되지 않았습니다.", SHORT_TIMEOUT_MS) {
                    findVisibleNodeByTextOrDescription(automation, status) != null
                }
            }
            if (scenario.id == "cursor") {
                onMain { editor.setSelection(editor.text.length) }
                evidence.put("cursorRestoredAfterInvalidation", true)
            }
            if (scenario.id == "text_same_cursor") {
                onMain {
                    val selection = editor.selectionStart to editor.selectionEnd
                    editor.text.replace(0, 1, raw.take(1))
                    assertEquals("same-cursor 원문 복원이 selection을 바꾸면 안 됩니다.", selection, editor.selectionStart to editor.selectionEnd)
                    assertEquals("same-cursor 원문 복원이 실패했습니다.", raw, editor.text.toString())
                    Unit
                }
                evidence.put("sameCursorSourceChanged", true).put("sourceRestoredAfterInvalidation", true)
            }
            val beforeLateResult = onMain { evidenceEditor.text.toString() }
            waitUntil("${scenario.id}: native 실행이 30초 안에 멈추지 않았습니다.", STOP_TIMEOUT_MS) {
                !OnDeviceGenerationControl.isGenerating
            }
            if (scenario.id == "initialization_cancel") {
                // 회귀 가드: 명시 완성이 웜(또는 웜업 중) 자동 추천 lease를 선점했더라도, opt-in이
                // 원래 켜져 있었다면 그 내부 활성 상태 자체는 절대 꺼지면 안 된다(선점은 lease 반납일
                // 뿐 opt-out이 아니다). opt-in을 끄는 방식으로 되돌리면 이 단언이 실패한다.
                val automaticOptIn = AppPrefs.getInstance().internal.automaticOnDeviceSuggestionsOptIn.getValue()
                evidence.put("automaticOptInBeforePreemption", automaticOptIn)
                if (automaticOptIn) {
                    assertTrue(
                        "${scenario.id}: 자동 추천 선점 뒤 opt-in 활성 상태가 꺼졌습니다.",
                        onMain { ime.automaticSuggestionsEnabled }
                    )
                    evidence.put("automaticSuggestionsEnabledAfterPreemption", true)
                }
            }
            assertTrue("${scenario.id}: 취소 뒤 후보가 남았습니다.", findCandidateNode(automation, raw) == null)
            assertTrue("${scenario.id}: 취소 뒤 적용 버튼이 남았습니다.", findVisibleNodeByTextOrDescription(automation, APPEND) == null)
            SystemClock.sleep(LATE_RESULT_GUARD_MS)
            assertEquals("${scenario.id}: 늦은 결과가 editor에 적용되었습니다.", beforeLateResult, onMain { evidenceEditor.text.toString() })
            oldEditorForSession?.let { oldEditor ->
                assertEquals(
                    "${scenario.id}: 늦은 결과가 이전 editor에 적용되었습니다.",
                    oldEditorTextBeforeSession,
                    onMain { oldEditor.text.toString() }
                )
            }
            evidence.put("nativeStopped", true)
                .put("candidateDiscarded", true)
                .put("lateApplyRejected", true)
                .put("editorAfter", beforeLateResult)
        } catch (error: Throwable) {
            bodyFailure = error
            evidence.put("executionFailure", true)
                .put("exceptionClass", error.javaClass.name)
                .put("exceptionMessage", error.message ?: JSONObject.NULL)
            try {
                evidence.put("failureCapture", captureFailureState(automation, "safety-${scenario.id}", activity))
                evidence.put("failureEvidenceFile", persistPublicEvidence("safety", "${scenario.id}_failed", evidence))
            } catch (captureError: Throwable) {
                error.addSuppressed(captureError)
            }
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            try {
                val networkAfter = networkSnapshot(instrumentation.targetContext)
                assertOfflineIfRequired(requiresOffline, networkAfter)
                closeContextWindow(automation)
                waitUntil("${scenario.id}: finally에서 native 실행이 종료하지 않았습니다.", STOP_TIMEOUT_MS) {
                    !OnDeviceGenerationControl.isGenerating
                }
                evidence.put("networkAfter", networkAfter.toJson()).put("offlineProofAfter", requiresOffline && networkAfter.isOfflineProof())
            } catch (error: Throwable) {
                cleanupFailure = error
            } finally {
                try {
                    onMain { activity.finish(); Unit }
                } catch (finishError: Throwable) {
                    cleanupFailure = appendCleanupFailure(cleanupFailure, finishError)
                } finally {
                    try {
                        instrumentation.sendStatus(0, Bundle().apply { putString(SAFETY_EVIDENCE_KEY, evidence.toString()) })
                    } catch (statusError: Throwable) {
                        cleanupFailure = appendCleanupFailure(cleanupFailure, statusError)
                    }
                }
            }
            bodyFailure?.let { primary ->
                cleanupFailure?.let(primary::addSuppressed)
            } ?: cleanupFailure?.let { throw it }
        }
    }

    private fun openContextCompletion(automation: UiAutomation, evidence: JSONObject) {
        evidence.put("actions", JSONArray())
        try {
            val activeIme = requireNotNull(FcitxInputMethodService.activeInstance) {
                "toolbar 접근 중 활성 새글 IME 인스턴스가 없습니다."
            }
            evidence.put(
                "activeImeStrings",
                JSONObject()
                    .put("continueWritingTitle", activeIme.getString(R.string.continue_writing_title))
                    .put("expandToolbar", activeIme.getString(R.string.expand_toolbar))
            )
            val continueWritingTitle = instrumentation.targetContext.getString(R.string.continue_writing_title)
            val visibleContinueWritingButton = ensureVisibleToolbarDescription(
                automation,
                continueWritingTitle,
                "continue_writing",
                evidence
            )
            evidence.getJSONArray("actions").put("continue_writing_open_attempt")
            assertTrue("이어쓰기 toolbar 버튼 클릭이 거부되었습니다.", clickable(visibleContinueWritingButton).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            evidence.getJSONArray("actions").put("continue_writing_opened")
            requireVisibleNodeByTextOrDescription(automation, TITLE, SHORT_TIMEOUT_MS)
        } catch (error: Throwable) {
            evidence.put("executionFailure", true)
                .put("exceptionClass", error.javaClass.name)
                .put("exceptionMessage", error.message ?: JSONObject.NULL)
            try {
                evidence.put("failureCapture", captureFailureState(automation, "toolbar-setup", null))
            } catch (captureError: Throwable) {
                error.addSuppressed(captureError)
            }
            throw error
        }
    }

    private fun ensureVisibleToolbarDescription(
        automation: UiAutomation,
        description: String,
        actionPrefix: String,
        evidence: JSONObject
    ): AccessibilityNodeInfo {
        waitUntil("'$description' toolbar surface가 나타나지 않았습니다.", SHORT_TIMEOUT_MS) {
            findVisibleNodeByDescription(automation, description) != null ||
                findVisibleNodeByTextOrDescription(automation, instrumentation.targetContext.getString(R.string.expand_toolbar)) != null ||
                findVisibleScrollableToolbar(automation) != null
        }
        var target = findVisibleNodeByDescription(automation, description)
        if (target == null) {
            val expandToolbar = findVisibleNodeByTextOrDescription(
                automation,
                instrumentation.targetContext.getString(R.string.expand_toolbar)
            )
            if (expandToolbar != null) {
                assertTrue("'$description' 전 toolbar 펼치기 버튼이 비활성 상태입니다.", expandToolbar.isEnabled)
                evidence.getJSONArray("actions").put("${actionPrefix}_expand_toolbar_attempt")
                assertTrue("'$description' 전 toolbar 펼치기 버튼 클릭이 거부되었습니다.", clickable(expandToolbar).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                evidence.getJSONArray("actions").put("${actionPrefix}_toolbar_expanded")
                waitUntil("'$description' toolbar 펼치기 뒤 surface가 나타나지 않았습니다.", SHORT_TIMEOUT_MS) {
                    findVisibleNodeByDescription(automation, description) != null ||
                        findVisibleScrollableToolbar(automation) != null
                }
                target = findVisibleNodeByDescription(automation, description)
            }
        }
        var scrollAttempts = 0
        // The toolbar keeps its horizontal scroll offset across sessions, so the entry can sit on
        // either side of the current position (e.g. a device last scrolled to the right). Try both
        // directions and tolerate a rejected action: the final visibility check is authoritative.
        val scrollDirections = intArrayOf(
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        )
        for (direction in scrollDirections) {
            if (target != null) break
            var attemptsForDirection = 0
            while (target == null && attemptsForDirection < MAX_TOOLBAR_SCROLL_ATTEMPTS) {
                val toolbar = findVisibleScrollableToolbar(automation)
                    ?: throw AssertionError("'$description' 버튼을 찾을 수 없고 scroll 가능한 HorizontalScrollView도 없습니다.")
                assertTrue("'$description' 전 scroll 가능한 toolbar가 비활성 상태입니다.", toolbar.isEnabled)
                evidence.getJSONArray("actions").put("${actionPrefix}_toolbar_scroll_attempt_${scrollAttempts + 1}")
                val moved = toolbar.performAction(direction)
                scrollAttempts += 1
                attemptsForDirection += 1
                waitUntil("'$description' toolbar scroll 뒤 surface가 나타나지 않았습니다.", SHORT_TIMEOUT_MS) {
                    findVisibleNodeByDescription(automation, description) != null ||
                        findVisibleScrollableToolbar(automation) != null
                }
                target = findVisibleNodeByDescription(automation, description)
                if (!moved) break
            }
        }
        val visibleTarget = requireNotNull(target) {
            "'$description' 버튼이 visible 상태로 나타나지 않았습니다. toolbar scroll 시도: $scrollAttempts"
        }
        assertTrue("'$description' 버튼이 visible 상태가 아닙니다.", visibleTarget.isVisibleToUser)
        assertTrue("'$description' 버튼이 enabled 상태가 아닙니다.", visibleTarget.isEnabled)
        evidence.put("${actionPrefix}ScrollAttempts", scrollAttempts)
        return visibleTarget
    }

    private fun waitForNativeDecode(automation: UiAutomation): NativePhase =
        waitForPanelPhase(automation, DECODING, COMPLETION_TIMEOUT_MS)

    private fun waitForNativePreparing(automation: UiAutomation): NativePhase =
        waitForPanelPhase(automation, PREPARING, COMPLETION_TIMEOUT_MS)

    /**
     * Waits for a native-phase status line inside the panel. The COMPLETE button click scrolls the
     * panel toward its bottom, so the status line is scrolled out of view on tall emulator panels;
     * the support helper scrolls it back into view while polling instead of giving up immediately.
     * The startup phase is bounded by [COMPLETION_TIMEOUT_MS] because the emulator CPU engine warmup
     * can exceed the old [START_TIMEOUT_MS] window.
     */
    private fun waitForPanelPhase(automation: UiAutomation, status: String, timeoutMs: Long): NativePhase {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var leaseObserved = false
        while (SystemClock.elapsedRealtime() < deadline) {
            leaseObserved = leaseObserved || OnDeviceGenerationControl.isGenerating
            if (GemmaImeTestSupport.waitForVisibleTextInPanel(
                    automation,
                    instrumentation.targetContext.packageName,
                    status,
                    POLL_INTERVAL_MS
                )
            ) {
                return NativePhase(leaseObserved, true)
            }
        }
        return NativePhase(leaseObserved, false)
    }

    private fun waitForCompletionOutcome(automation: UiAutomation, raw: String): CompletionOutcome {
        val deadline = SystemClock.elapsedRealtime() + COMPLETION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            GemmaImeTestSupport.waitForVisibleTextInPanel(
                automation,
                instrumentation.targetContext.packageName,
                COMPLETE_READY,
                POLL_INTERVAL_MS
            )
            val candidate = findCandidateNode(automation, raw)
            val status = findVisibleNodeByTextOrDescription(automation, COMPLETE_READY)?.text?.toString()
                ?: findVisibleNodeByTextOrDescription(automation, COMPLETE_READY)?.contentDescription?.toString()
            if (candidate != null && status != null && !OnDeviceGenerationControl.isGenerating) {
                return CompletionOutcome(candidate, status, readLatency(automation))
            }
            val unavailable = findVisibleNodeByTextOrDescription(automation, MODEL_MISSING)
                ?: findVisibleNodeByTextOrDescription(automation, UNSUPPORTED)
                ?: findVisibleNodeByTextOrDescription(automation, EDITOR_CHANGED)
                ?: findVisibleNodeByTextOrDescription(automation, NO_CANDIDATE)
            if (unavailable != null && !OnDeviceGenerationControl.isGenerating) {
                return CompletionOutcome(
                    null,
                    unavailable.text?.toString() ?: unavailable.contentDescription.toString(),
                    findVisibleNodeByTextPrefix(automation, LATENCY_PREFIX)?.let { readLatency(automation) }
                )
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("문맥 완성 결과 또는 명시 오류 상태가 $COMPLETION_TIMEOUT_MS ms 안에 나타나지 않았습니다.")
    }

    private fun readLatency(automation: UiAutomation): JSONObject {
        val node = findVisibleNodeByTextPrefix(automation, LATENCY_PREFIX)
            ?: throw AssertionError("native 완료 지연 상태 줄이 보이지 않습니다.")
        val value = node.text?.toString() ?: node.contentDescription?.toString().orEmpty()
        LATENCY_WITH_FIRST_PATTERN.matchEntire(value)?.let { match ->
            return JSONObject()
                .put("firstTextMs", match.groupValues[1].toLong())
                .put("totalMs", match.groupValues[2].toLong())
        }
        val match = LATENCY_WITHOUT_FIRST_PATTERN.matchEntire(value)
            ?: throw AssertionError("native 완료 지연 상태 형식이 다릅니다: $value")
        return JSONObject()
            .put("firstTextMs", JSONObject.NULL)
            .put("totalMs", match.groupValues[1].toLong())
    }

    private fun findCandidateNode(automation: UiAutomation, raw: String): AccessibilityNodeInfo? =
        findVisibleNodeByDescriptionPrefix(automation, CANDIDATE_DESCRIPTION_PREFIX)?.also { node ->
            assertTrue("후보 contentDescription이 preview 원문과 일치하지 않습니다.", node.contentDescription.toString().startsWith(CANDIDATE_DESCRIPTION_PREFIX + raw))
        }

    private fun configureUiAutomation(): UiAutomation = instrumentation.uiAutomation.apply {
        val info = requireNotNull(serviceInfo) { "UiAutomation accessibility service info를 가져올 수 없습니다." }
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
    }

    private suspend fun prepareImeHarness(): ImeHarness {
        val automation = configureUiAutomation()
        val context = instrumentation.targetContext
        val accumulationStore = GemmaAccumulationStore.get(context)
        val priorAccumulation = accumulationStore.load()
        val manager = requireNotNull(context.getSystemService(InputMethodManager::class.java)) {
            "InputMethodManager를 가져올 수 없습니다."
        }
        val originalImeId = requireNotNull(selectedImeId()) { "기본 입력기 설정을 읽을 수 없습니다." }
        val enabled = manager.enabledInputMethodList
        val target = enabled.singleOrNull {
            it.serviceInfo.packageName == context.packageName &&
                it.serviceInfo.name == FcitxInputMethodService::class.java.name
        }
        val evidence = JSONObject()
            .put("originalImeId", originalImeId)
            .put("enabledImeIds", JSONArray(enabled.map { it.id }))
            .put("targetImeId", target?.id ?: JSONObject.NULL)
            .put("priorEnabled", priorAccumulation.enabled)
            .put("priorManualRequested", priorAccumulation.manualRequested)
            .put("priorOpenSequence", priorAccumulation.openSequence)
        var accumulationDisabledForHarness = false

        try {
            assertTrue("현재 기본 입력기가 활성화된 입력기 목록에 없습니다.", enabled.any { it.id == originalImeId })
            requireNotNull(target) {
                "활성화된 입력기 목록에서 ${context.packageName}/${FcitxInputMethodService::class.java.name}을 찾지 못했습니다."
            }
            val alternate = enabled.firstOrNull { it.id != target.id }
                ?: throw AssertionError("새글 외에 활성화된 입력기가 없어 IMMS 재바인딩을 검증할 수 없습니다.")
            validateImeId(originalImeId)
            validateImeId(target.id)
            validateImeId(alternate.id)
            evidence.put("alternateImeId", alternate.id)
            assertFalse("수동 공개 준비 요청이 남아 있어 재바인딩 전에 비활성화할 수 없습니다.", priorAccumulation.manualRequested)
            if (priorAccumulation.enabled) {
                accumulationDisabledForHarness = true
                GemmaAccumulationScheduler.setEnabled(context, false)
                // Disabling cancels the scheduled work, but a native run already in flight needs a
                // moment to release its lease. The keyboard-hidden material boundary makes that run
                // common, so wait for the release instead of sampling the state once.
                waitUntil("공개 재료 준비 native 실행이 재바인딩 전에 남아 있습니다.", STOP_TIMEOUT_MS) {
                    !OnDeviceGenerationControl.isGenerating
                }
            }
            val preparedAccumulation = accumulationStore.load()
            assertFalse("IME 재바인딩 동안 공개 재료 준비가 활성 상태입니다.", preparedAccumulation.enabled)
            assertFalse("IME 재바인딩 동안 수동 공개 준비 요청이 남아 있습니다.", preparedAccumulation.manualRequested)
            evidence.put("preparedEnabled", preparedAccumulation.enabled)
                .put("preparedManualRequested", preparedAccumulation.manualRequested)
            evidence.put("alternateSetOutput", setSystemIme(automation, alternate.id))
            assertSelectedIme(alternate.id, "다른 입력기 전환")
            waitUntil("다른 입력기로 전환한 뒤 새글 IME가 종료되지 않았습니다.", SHORT_TIMEOUT_MS) {
                FcitxInputMethodService.activeInstance == null
            }
            evidence.put("targetSetOutput", setSystemIme(automation, target.id))
            assertSelectedIme(target.id, "새글 입력기 전환")
            evidence.put("targetActiveInstanceObservedBeforeEditor", FcitxInputMethodService.activeInstance != null)
                .put("rebindSucceeded", true)
            return ImeHarness(
                automation = automation,
                originalImeId = originalImeId,
                accumulationStore = accumulationStore,
                priorAccumulationEnabled = priorAccumulation.enabled,
                evidence = evidence
            )
        } catch (error: Throwable) {
            evidence.put("rebindSucceeded", false)
                .put("failureClass", error.javaClass.name)
                .put("failureMessage", error.message ?: JSONObject.NULL)
            try {
                validateImeId(originalImeId)
                evidence.put("restoreAfterPrepareFailureOutput", setSystemIme(automation, originalImeId))
                assertSelectedIme(originalImeId, "재바인딩 실패 뒤 원래 입력기 복원")
                evidence.put("restoreAfterPrepareFailureSucceeded", true)
            } catch (restoreError: Throwable) {
                evidence.put("restoreAfterPrepareFailureSucceeded", false)
                    .put("restoreFailureClass", restoreError.javaClass.name)
                    .put("restoreFailureMessage", restoreError.message ?: JSONObject.NULL)
                error.addSuppressed(restoreError)
            }
            try {
                restoreAccumulationAfterHarnessFailure(
                    context,
                    accumulationStore,
                    priorAccumulation.enabled,
                    accumulationDisabledForHarness,
                    evidence
                )
            } catch (restoreError: Throwable) {
                evidence.put("restoredEnabled", JSONObject.NULL)
                    .put("accumulationRestoreFailureClass", restoreError.javaClass.name)
                    .put("accumulationRestoreFailureMessage", restoreError.message ?: JSONObject.NULL)
                error.addSuppressed(restoreError)
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString(IME_REBIND_EVIDENCE_KEY, evidence.toString())
            })
            throw ImeRebindFailure("시스템 IME 재바인딩을 완료하지 못했습니다.", evidence, error)
        }
    }

    private suspend fun restoreAccumulationAfterHarnessFailure(
        context: Context,
        store: GemmaAccumulationStore,
        priorEnabled: Boolean,
        disabledForHarness: Boolean,
        evidence: JSONObject
    ) {
        if (priorEnabled && disabledForHarness) {
            GemmaAccumulationScheduler.setEnabled(context, true)
            val restored = store.load()
            assertTrue("재바인딩 실패 뒤 공개 재료 준비를 원래 활성 상태로 복원하지 못했습니다.", restored.enabled)
            evidence.put("restoredEnabled", true).put("backgroundWorkRequeuedOnRestore", true)
        } else {
            evidence.put("restoredEnabled", priorEnabled).put("backgroundWorkRequeuedOnRestore", false)
        }
    }

    private fun selectedImeId(): String? = Settings.Secure.getString(
        instrumentation.targetContext.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD
    )

    private fun setSystemIme(automation: UiAutomation, imeId: String): String =
        automation.executeShellCommand("ime set $imeId").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
        }

    private fun assertSelectedIme(expectedImeId: String, action: String) {
        waitUntil("$action 뒤 Settings.Secure.DEFAULT_INPUT_METHOD가 ${expectedImeId}가 아닙니다.", SHORT_TIMEOUT_MS) {
            selectedImeId() == expectedImeId
        }
        assertEquals("$action 뒤 기본 입력기 설정이 일치하지 않습니다.", expectedImeId, selectedImeId())
    }

    private fun validateImeId(imeId: String) {
        require(IME_ID_PATTERN.matches(imeId)) { "시스템 입력기 ID 형식이 유효하지 않습니다: $imeId" }
    }

    private fun clickVisibleText(automation: UiAutomation, text: String, timeoutMs: Long) {
        val node = requireVisibleNodeByTextOrDescription(automation, text, timeoutMs)
        assertTrue("'$text' 클릭이 거부되었습니다.", clickable(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun clickVisibleDescription(automation: UiAutomation, description: String, timeoutMs: Long) {
        val node = requireVisibleNodeByDescription(automation, description, timeoutMs)
        assertTrue("'$description' 클릭이 거부되었습니다.", clickable(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun requireVisibleNodeByTextOrDescription(automation: UiAutomation, value: String, timeoutMs: Long): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            findVisibleNodeByTextOrDescription(automation, value)?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("'$value' visible accessibility node를 찾지 못했습니다.")
    }

    private fun requireVisibleNodeByDescription(automation: UiAutomation, description: String, timeoutMs: Long): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            findVisibleNodeByDescription(automation, description)?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("'$description' contentDescription node를 찾지 못했습니다.")
    }

    private fun findVisibleNodeByTextOrDescription(automation: UiAutomation, value: String): AccessibilityNodeInfo? =
        imeRoots(automation).firstNotNullOfOrNull { it.findVisible { node -> node.text?.toString() == value || node.contentDescription?.toString() == value } }

    private fun findVisibleNodeByDescription(automation: UiAutomation, value: String): AccessibilityNodeInfo? =
        imeRoots(automation).firstNotNullOfOrNull { it.findVisible { node -> node.contentDescription?.toString() == value } }

    private fun findVisibleNodeByDescriptionPrefix(automation: UiAutomation, prefix: String): AccessibilityNodeInfo? =
        imeRoots(automation).firstNotNullOfOrNull { it.findVisible { node -> node.contentDescription?.toString()?.startsWith(prefix) == true } }

    private fun findVisibleNodeByTextPrefix(automation: UiAutomation, prefix: String): AccessibilityNodeInfo? =
        imeRoots(automation).firstNotNullOfOrNull { it.findVisible { node -> node.text?.toString()?.startsWith(prefix) == true || node.contentDescription?.toString()?.startsWith(prefix) == true } }

    private fun findVisibleScrollableToolbar(automation: UiAutomation): AccessibilityNodeInfo? =
        imeRoots(automation).firstNotNullOfOrNull { root ->
            root.findVisible { node ->
                node.className?.toString() == HORIZONTAL_SCROLL_VIEW_CLASS_NAME && node.isScrollable
            }
        }

    private fun imeRoots(automation: UiAutomation): Sequence<AccessibilityNodeInfo> = automation.windows.asSequence()
        .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        .mapNotNull { it.root }
        .filter { it.packageName?.toString() == instrumentation.targetContext.packageName }

    private fun AccessibilityNodeInfo.findVisible(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (isVisibleToUser && predicate(this)) return this
        for (index in 0 until childCount) {
            getChild(index)?.findVisible(predicate)?.let { return it }
        }
        return null
    }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        throw AssertionError("clickable ancestor가 없습니다.")
    }

    private fun closeContextWindow(automation: UiAutomation) {
        if (findVisibleNodeByTextOrDescription(automation, TITLE) != null) {
            automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    private fun launchActivity(): AiEditorTestActivity = instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, AiEditorTestActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    ) as AiEditorTestActivity

    private fun selectNormalAndClear(activity: AiEditorTestActivity): EditText = onMain {
        requireNotNull(activity.window.decorView.findByContentDescription("Select normal complete-editor mode")).performClick()
        requireNotNull(activity.window.decorView.findByContentDescription("Clear text")).performClick()
        requireNotNull(activity.window.decorView.findByContentDescription("AI E2E normal editor")) as EditText
    }

    private fun showKeyboard(editor: EditText) {
        waitUntil("editor Activity window focus를 확보하지 못했습니다.", SHORT_TIMEOUT_MS) {
            onMain { editor.hasWindowFocus() }
        }
        onMain {
            editor.requestFocus()
            (editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            Unit
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

    private fun markEditorMatched(evidence: JSONObject, imeHarness: ImeHarness, ime: FcitxInputMethodService) {
        assertTrue("editor 연결 뒤 활성 새글 IME 인스턴스가 일치하지 않습니다.", FcitxInputMethodService.activeInstance === ime)
        evidence.put("editorMatched", true).put("targetActiveInstanceObserved", true)
        imeHarness.evidence.put("editorMatched", true).put("targetActiveInstanceObserved", true)
    }

    private fun showConnectedImeWindow(
        editor: EditText,
        automation: UiAutomation,
        evidence: JSONObject,
        imeHarness: ImeHarness
    ) {
        showKeyboard(editor)
        waitUntil("현재 연결된 새글 IME window가 visible 상태로 나타나지 않았습니다.", SHORT_TIMEOUT_MS) {
            imeRoots(automation).any { it.isVisibleToUser }
        }
        evidence.put("imeWindowVisible", true)
        imeHarness.evidence.put("imeWindowVisible", true)
    }

    private fun waitForEditorText(editor: EditText, expected: String) = waitUntil("editor 원문이 기대값과 다릅니다: $expected", SHORT_TIMEOUT_MS) {
        onMain { editor.text.toString() == expected }
    }

    private fun editorTarget(editor: EditText): EditorTarget = onMain {
        EditorTarget(editor.context.packageName, editor.id, editor.inputType, editor.selectionStart, editor.selectionEnd)
    }

    private fun networkSnapshot(context: Context): NetworkSnapshot {
        val manager = requireNotNull(context.getSystemService(ConnectivityManager::class.java)) { "ConnectivityManager를 가져올 수 없습니다." }
        val networks = manager.allNetworks.map { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            NetworkEntry(
                capabilitiesAvailable = capabilities != null,
                internet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                transports = TRANSPORTS.filter { (transport, _) -> capabilities?.hasTransport(transport) == true }.map { it.second }
            )
        }
        return NetworkSnapshot(networks)
    }

    private fun requiresOffline(): Boolean = when (
        InstrumentationRegistry.getArguments().getString(REQUIRES_OFFLINE_ARGUMENT) ?: "true"
    ) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("$REQUIRES_OFFLINE_ARGUMENT 인자는 true 또는 false여야 합니다.")
    }

    private fun assertOfflineIfRequired(required: Boolean, snapshot: NetworkSnapshot) {
        if (required) assertOffline(snapshot)
    }

    private fun assertOffline(snapshot: NetworkSnapshot) {
        snapshot.entries.forEachIndexed { index, entry ->
            assertTrue("네트워크 $index capability를 읽지 못해 오프라인을 증명할 수 없습니다.", entry.capabilitiesAvailable)
            assertFalse("인터넷 capability를 가진 ${entry.transports} 네트워크가 남아 있습니다.", entry.internet == true)
        }
    }

    private fun selectedIndices(size: Int): List<Int> {
        val value = InstrumentationRegistry.getArguments().getString(SAMPLE_INDEX_ARGUMENT) ?: return (0 until size).toList()
        val index = value.toIntOrNull() ?: throw IllegalArgumentException("$SAMPLE_INDEX_ARGUMENT 인자는 0 이상 정수여야 합니다.")
        require(index in 0 until size) { "$SAMPLE_INDEX_ARGUMENT=$index 가 fixture 범위를 벗어났습니다: 0..${size - 1}" }
        return listOf(index)
    }

    private fun assertFixture(cases: List<FixtureCase>, fixtureSet: String) {
        assertEquals("$fixtureSet fixture는 24개여야 합니다.", 24, cases.size)
        assertEquals("$fixtureSet fixture ID가 중복되었습니다.", cases.size, cases.map { it.id }.toSet().size)
        assertEquals("$fixtureSet completion 분모가 23개여야 합니다.", 23, cases.count { it.boundary == COMPLETION })
        assertEquals("$fixtureSet terminal boundary가 1개여야 합니다.", 1, cases.count { it.boundary == TERMINAL_BOUNDARY })
    }

    private fun legacyFixtureSha256(cases: List<FixtureCase>): String = sha256(
        cases.joinToString(prefix = "[", postfix = "]") { input ->
            "{\"id\":${JSONObject.quote(input.id)},\"caseKind\":${JSONObject.quote(input.boundary)},\"prefix\":${JSONObject.quote(input.input)}}"
        }
    )

    private fun manualReview(): JSONObject = JSONObject()
        .put("meaning", "manual_review_required")
        .put("particleEnding", "manual_review_required")
        .put("spacing", "manual_review_required")
        .put("terminalForm", "manual_review_required")

    private fun report(event: String, fixtureSet: String, fixtureSha256: String, payload: JSONObject) {
        val network = networkSnapshot(instrumentation.targetContext)
        val requiredOffline = requiresOffline()
        val evidence = JSONObject()
            .put("event", event)
            .put("scope", "public_synthetic_explicit_context_ime_collection")
            .put("fixtureSet", fixtureSet)
            .put("fixtureSha256", fixtureSha256)
            .put("requiredOffline", requiredOffline)
            .put("offlineProof", requiredOffline && network.isOfflineProof())
            .put("network", network.toJson())
            .put("actualUserInput", false)
            .put("providedToGeneratedSentenceBank", false)
                .put("contextRequestUsesPersonalVault", false)
            .put("networkFallbackUsed", false)
            .put("payload", payload)
        evidence.put("evidenceFile", persistPublicEvidence(fixtureSet, event, evidence))
        instrumentation.sendStatus(0, Bundle().apply {
            putString(EVIDENCE_KEY, evidence.toString())
        })
    }

    private fun persistPublicEvidence(fixtureSet: String, event: String, evidence: JSONObject): String {
        val directory = publicEvidenceDirectory()
        val target = File(directory, "$fixtureSet-$event-${SystemClock.elapsedRealtime()}.json")
        FileOutputStream(target).bufferedWriter(Charsets.UTF_8).use { it.write(evidence.toString()) }
        return target.absolutePath
    }

    private fun captureCandidateScreenshot(
        automation: UiAutomation,
        fixtureSet: String,
        fixtureId: String,
        debugActivity: AiEditorTestActivity
    ): JSONObject {
        val applicationWindowCountBefore = applicationWindowCount(automation)
        val debugActivityFocusedBefore = onMain { debugActivity.hasWindowFocus() }
        val evidence = JSONObject()
            .put("applicationWindowCountBefore", applicationWindowCountBefore)
            .put("debugActivityFocusedBefore", debugActivityFocusedBefore)
        if (applicationWindowCountBefore != 1 || !debugActivityFocusedBefore) {
            return evidence
                .put("skipped", true)
                .put("skipReason", "debug_activity_not_exclusively_foreground")
                .put("path", JSONObject.NULL)
        }
        val screenshot = requireNotNull(automation.takeScreenshot()) {
            "공개 합성 문맥 후보의 UiAutomation screenshot을 가져오지 못했습니다."
        }
        try {
            val applicationWindowCountAfter = applicationWindowCount(automation)
            val debugActivityFocusedAfter = onMain { debugActivity.hasWindowFocus() }
            evidence.put("applicationWindowCountAfter", applicationWindowCountAfter)
                .put("debugActivityFocusedAfter", debugActivityFocusedAfter)
            if (applicationWindowCountAfter != 1 || !debugActivityFocusedAfter) {
                return evidence
                    .put("skipped", true)
                    .put("skipReason", "debug_activity_not_exclusively_foreground_at_screenshot")
                    .put("path", JSONObject.NULL)
            }
            val target = File(publicEvidenceDirectory(), "$fixtureSet-$fixtureId-candidate-${SystemClock.elapsedRealtime()}.png")
            FileOutputStream(target).use { output ->
                assertTrue("공개 합성 문맥 후보 PNG를 저장하지 못했습니다.", screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            return evidence.put("skipped", false).put("path", target.absolutePath)
        } finally {
            screenshot.recycle()
        }
    }

    private fun captureFailureState(
        automation: UiAutomation,
        scope: String,
        debugActivity: AiEditorTestActivity?
    ): JSONObject {
        val initialWindows = automation.windows
        val debugActivityFocused = debugActivity?.let { onMain { it.hasWindowFocus() } } == true
        val applicationWindowCountBefore = initialWindows.count { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val evidence = JSONObject()
            .put("debugActivityPackage", instrumentation.targetContext.packageName)
            .put("debugActivityFocused", debugActivityFocused)
            .put("applicationWindowCountBefore", applicationWindowCountBefore)
            .put("imeNodeMetadata", imeNodeMetadata(initialWindows))
        if (!debugActivityFocused || applicationWindowCountBefore != 1) {
            return evidence.put("captureSkipped", "debug_activity_not_exclusively_foreground")
        }
        val screenshot = requireNotNull(automation.takeScreenshot()) {
            "공개 debug Activity 실패 screenshot을 가져오지 못했습니다."
        }
        try {
            val stillFocused = onMain { debugActivity?.hasWindowFocus() == true }
            val applicationWindowCountAfter = applicationWindowCount(automation)
            evidence.put("debugActivityFocusedAfter", stillFocused)
                .put("applicationWindowCountAfter", applicationWindowCountAfter)
            if (!stillFocused || applicationWindowCountAfter != 1) {
                return evidence.put("captureSkipped", "debug_activity_not_exclusively_foreground_at_screenshot")
            }
            val target = File(publicEvidenceDirectory(), "failure-$scope-${SystemClock.elapsedRealtime()}.png")
            FileOutputStream(target).use { output ->
                assertTrue("공개 debug Activity 실패 PNG를 저장하지 못했습니다.", screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            evidence.put("screenshot", target.absolutePath)
        } finally {
            screenshot.recycle()
        }
        return evidence
    }

    private fun applicationWindowCount(automation: UiAutomation): Int =
        automation.windows.count { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }

    private fun imeNodeMetadata(windows: List<AccessibilityWindowInfo>): JSONArray {
        val metadata = JSONArray()
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }.forEach { window ->
            window.root?.let { root -> appendImeNodeMetadata(root, metadata) }
        }
        return metadata
    }

    private fun appendImeNodeMetadata(node: AccessibilityNodeInfo, output: JSONArray) {
        output.put(
            JSONObject()
                .put("className", node.className?.toString() ?: JSONObject.NULL)
                .put("bounds", boundsOf(node))
                .put("visible", node.isVisibleToUser)
                .put("enabled", node.isEnabled)
                .put("clickable", node.isClickable)
                .put("scrollable", node.isScrollable)
                .put("viewId", node.viewIdResourceName ?: JSONObject.NULL)
                .put("contentDescription", allowedFailureContentDescription(node.contentDescription))
        )
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> appendImeNodeMetadata(child, output) }
        }
    }

    private fun allowedFailureContentDescription(value: CharSequence?): Any {
        val description = value?.toString() ?: return JSONObject.NULL
        return if (description in failureDescriptionAllowList()) description else "redacted"
    }

    private fun failureDescriptionAllowList(): Set<String> = setOf(
        "Continue writing",
        "이어쓰기",
        COMPLETE,
        TITLE,
        instrumentation.targetContext.getString(R.string.expand_toolbar),
        instrumentation.targetContext.getString(R.string.hide_toolbar)
    )

    private fun publicEvidenceDirectory(): File {
        val directory = File(
            requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)) {
                "공개 합성 문맥 증거 저장소를 만들 수 없습니다."
            },
            "gemma-context-ime"
        )
        if (!directory.exists()) assertTrue("공개 합성 문맥 증거 디렉터리를 만들지 못했습니다.", directory.mkdirs())
        return directory
    }

    private fun boundsOf(node: AccessibilityNodeInfo): String = Rect().also(node::getBoundsInScreen).toShortString()

    private fun waitUntil(message: String, timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        assertTrue(message, predicate())
    }

    private fun appendCleanupFailure(current: Throwable?, next: Throwable): Throwable =
        current?.also { it.addSuppressed(next) } ?: next

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

    private fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun bankSnapshot(context: Context, app: FcitxApplication): BankSnapshot {
        val backingFile = File(context.noBackupFilesDir, GENERATED_MATERIALS_FILE_NAME)
        return BankSnapshot(
            count = app.generatedSentenceBank.sentenceCount,
            reloadSensitiveRevision = app.generatedSentenceBank.revision,
            persistedExists = backingFile.isFile,
            persistedBytes = if (backingFile.isFile) backingFile.length() else null,
            persistedSha256 = if (backingFile.isFile) sha256(backingFile) else null
        )
    }

    private data class FixtureCase(val id: String, val boundary: String, val input: String)
    private data class CompletionOutcome(val candidate: AccessibilityNodeInfo?, val status: String, val latency: JSONObject?)
    private data class NativePhase(val leaseObserved: Boolean, val phaseObserved: Boolean)
    private data class EditorTarget(val packageName: String, val fieldId: Int, val inputType: Int, val selectionStart: Int, val selectionEnd: Int)
    private data class BankSnapshot(
        val count: Int,
        val reloadSensitiveRevision: Long,
        val persistedExists: Boolean,
        val persistedBytes: Long?,
        val persistedSha256: String?
    ) {
        fun toJson() = JSONObject()
            .put("count", count)
            .put("reloadSensitiveRevision", reloadSensitiveRevision)
            .put("persistedExists", persistedExists)
            .put("persistedBytes", persistedBytes ?: JSONObject.NULL)
            .put("persistedSha256", persistedSha256 ?: JSONObject.NULL)
    }
    private data class StateSnapshot(val enabled: Boolean, val manualRequested: Boolean, val openSequence: Long) {
        fun toJson() = JSONObject()
            .put("enabled", enabled)
            .put("manualRequested", manualRequested)
            .put("openSequence", openSequence)
    }
    private data class NetworkEntry(val capabilitiesAvailable: Boolean, val internet: Boolean?, val transports: List<String>) {
        fun toJson() = JSONObject().put("capabilitiesAvailable", capabilitiesAvailable).put("internet", internet ?: JSONObject.NULL).put("transports", JSONArray(transports))
    }
    private data class NetworkSnapshot(val entries: List<NetworkEntry>) {
        fun isOfflineProof(): Boolean = entries.all { it.capabilitiesAvailable && it.internet != true }
        fun toJson() = JSONObject().put("networks", JSONArray(entries.map { it.toJson() }))
    }
    private data class SafetyScenario(
        val id: String,
        val mutate: ((AiEditorTestActivity, EditText) -> Unit)?,
        val expectedStatus: String?,
        val phase: String
    ) {
        constructor(id: String, mutateEditor: (EditText) -> Unit, expectedStatus: String, phase: String) : this(id, { _, editor -> mutateEditor(editor) }, expectedStatus, phase)
    }
    private class ImeRebindFailure(message: String, val evidence: JSONObject, cause: Throwable) : AssertionError(message) {
        init {
            initCause(cause)
        }
    }
    private inner class ImeHarness(
        val automation: UiAutomation,
        private val originalImeId: String,
        private val accumulationStore: GemmaAccumulationStore,
        private val priorAccumulationEnabled: Boolean,
        val evidence: JSONObject
    ) {
        private var restored = false

        suspend fun restore() {
            if (restored) return
            var failure: Throwable? = null
            try {
                validateImeId(originalImeId)
                evidence.put("restoreOutput", setSystemIme(automation, originalImeId))
                assertSelectedIme(originalImeId, "원래 입력기 복원")
            } catch (error: Throwable) {
                evidence.put("imeRestoreSucceeded", false)
                    .put("imeRestoreFailureClass", error.javaClass.name)
                    .put("imeRestoreFailureMessage", error.message ?: JSONObject.NULL)
                failure = error
            }
            try {
                if (priorAccumulationEnabled) {
                    GemmaAccumulationScheduler.setEnabled(instrumentation.targetContext, true)
                    val restoredAccumulation = accumulationStore.load()
                    assertTrue("원래 공개 재료 준비 활성 상태를 복원하지 못했습니다.", restoredAccumulation.enabled)
                    evidence.put("restoredEnabled", true).put("backgroundWorkRequeuedOnRestore", true)
                } else {
                    evidence.put("restoredEnabled", false).put("backgroundWorkRequeuedOnRestore", false)
                }
            } catch (error: Throwable) {
                evidence.put("accumulationRestoreFailureClass", error.javaClass.name)
                    .put("accumulationRestoreFailureMessage", error.message ?: JSONObject.NULL)
                failure?.addSuppressed(error) ?: run { failure = error }
            } finally {
                if (failure == null) {
                    evidence.put("imeRestoreSucceeded", true).put("restoreSucceeded", true)
                    restored = true
                } else {
                    evidence.put("restoreSucceeded", false)
                }
                instrumentation.sendStatus(0, Bundle().apply {
                    putString(IME_REBIND_EVIDENCE_KEY, evidence.toString())
                })
            }
            failure?.let { throw it }
        }
    }

    private companion object {
        const val EVIDENCE_KEY = "gemmaContextImeEvidence"
        const val SAFETY_EVIDENCE_KEY = "gemmaContextImeSafetyEvidence"
        const val MODEL_ABSENT_EVIDENCE_KEY = "gemmaContextImeModelAbsentEvidence"
        const val IME_REBIND_EVIDENCE_KEY = "gemmaContextImeRebindEvidence"
        const val SAMPLE_INDEX_ARGUMENT = "sampleIndex"
        const val REQUIRES_OFFLINE_ARGUMENT = "requiresOffline"
        const val HELD_OUT_ASSET = "gemma-context-heldout-20260912.json"
        const val HELD_OUT_SHA256 = "043ec620e8cd8e0bc006fce0dc9a42f9ecf2e1dba007da8ce81acb3fd164c6b5"
        const val GENERATED_MATERIALS_FILE_NAME = "gemma_materials.json"
        const val COMPLETION = "completion"
        const val TERMINAL_BOUNDARY = "terminal_boundary"
        // 기기 로케일로 렌더링된 문구를 조회한다(values/values-ko 분리 이후 en-US 에뮬레이터는 영어를
        // 렌더링하므로 리터럴로는 접근성 노드를 찾지 못한다). 이 companion object의 다른 한국어
        // 리터럴 상수(COMPLETE, CANCEL, APPEND 등)는 이번 패킷 범위 밖이라 그대로 두었다.
        val TITLE: String by lazy {
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.gemma_context_title)
        }
        val COMPLETE: String by lazy {
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.gemma_context_complete)
        }
        val CANCEL: String by lazy {
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.gemma_context_cancel)
        }
        val APPEND: String by lazy {
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.gemma_context_apply)
        }
        val TERMINAL: String by lazy {
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.gemma_context_terminal)
        }
        val COMPLETE_READY: String by lazy {
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.gemma_context_ready)
        }
        val PREPARING: String by lazy {
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.gemma_context_preparing)
        }
        val DECODING: String by lazy {
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.gemma_context_running)
        }
        // 내부 상태 머신 식별자일 뿐 화면에 보이는 문구가 아니라(대응 리소스 없음) 그대로 둔다.
        const val INITIALIZING = "initializing"
        val MODEL_MISSING: String by lazy {
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.gemma_context_model_missing)
        }
        val UNSUPPORTED: String by lazy {
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.gemma_context_unsupported)
        }
        val EDITOR_CHANGED: String by lazy {
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.gemma_context_editor_changed)
        }
        val NO_CANDIDATE: String by lazy {
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.gemma_context_no_candidate)
        }
        val CANCELLED: String by lazy {
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.gemma_context_cancelled)
        }
        // OnDeviceContextCompletionWindow.kt가 실제로 짓는 형태와 맞춘다:
        // "${getString(gemma_context_source_label)}: $text" / "${getString(gemma_context_candidate_label)}: $full"
        val PREVIEW_DESCRIPTION_PREFIX: String by lazy {
            "${InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.gemma_context_source_label)}: "
        }
        val CANDIDATE_DESCRIPTION_PREFIX: String by lazy {
            "${InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.gemma_context_candidate_label)}: "
        }
        // gemma_context_timing(첫 응답 관측)과 gemma_context_timing_no_first(미관측)는 로케일마다
        // 다른 문구를 쓰므로, 한국어 리터럴을 박아 둔 정규식 대신 실제 포맷 리소스에서 리터럴 부분만
        // Regex.escape로 이스케이프하고 %1$d/%2$d 자리를 (\d+) 캡처 그룹으로 치환해 실행 시점에 만든다.
        val LATENCY_PREFIX: String by lazy {
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.gemma_context_timing)
                .substringBefore("%")
        }
        val LATENCY_WITH_FIRST_PATTERN: Regex by lazy { numericFormatRegex(R.string.gemma_context_timing) }
        val LATENCY_WITHOUT_FIRST_PATTERN: Regex by lazy { numericFormatRegex(R.string.gemma_context_timing_no_first) }

        private val NUMERIC_FORMAT_PLACEHOLDER = Regex("%(?:\\d+\\$)?d")

        /**
         * Builds a Regex matching the rendered form of a %-format string resource without going
         * through String.format (which needs real numeric args, not placeholders left in place).
         * Literal segments are escaped with Regex.escape; each %d/%1$d/%2$d placeholder becomes a
         * (\d+) capture group in its original order, so the pattern tracks whichever locale the
         * device is running (see values/ vs values-ko/ gemma_context_strings.xml).
         */
        fun numericFormatRegex(resId: Int): Regex {
            val raw = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)
            val builder = StringBuilder()
            var lastEnd = 0
            for (match in NUMERIC_FORMAT_PLACEHOLDER.findAll(raw)) {
                builder.append(Regex.escape(raw.substring(lastEnd, match.range.first)))
                builder.append("(\\d+)")
                lastEnd = match.range.last + 1
            }
            builder.append(Regex.escape(raw.substring(lastEnd)))
            return Regex(builder.toString())
        }
        const val POLL_INTERVAL_MS = 50L
        const val SHORT_TIMEOUT_MS = 10_000L
        const val START_TIMEOUT_MS = 30_000L
        const val COMPLETION_TIMEOUT_MS = 120_000L
        const val STOP_TIMEOUT_MS = 30_000L
        const val LATE_RESULT_GUARD_MS = 1_000L
        const val MAX_TOOLBAR_SCROLL_ATTEMPTS = 5
        const val HORIZONTAL_SCROLL_VIEW_CLASS_NAME = "android.widget.HorizontalScrollView"
        val IME_ID_PATTERN = Regex("[A-Za-z0-9_.$/]+")
        const val MODEL_ABSENT_INPUT = "내일 일정은 "
        val TRANSPORTS = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "wifi",
            NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
            NetworkCapabilities.TRANSPORT_VPN to "vpn"
        )
        val INDEPENDENT_INPUTS = listOf(
            FixtureCase("h01", COMPLETION, "내일 오전에 "), FixtureCase("h02", COMPLETION, "가능한 시간을 "),
            FixtureCase("h03", COMPLETION, "버스가 늦어서 "), FixtureCase("h04", TERMINAL_BOUNDARY, "역에 도착했어요. "),
            FixtureCase("h05", COMPLETION, "저녁 메뉴는 "), FixtureCase("h06", COMPLETION, "식당 예약을 "),
            FixtureCase("h07", COMPLETION, "빨래를 널고 "), FixtureCase("h08", COMPLETION, "설거지는 제가 "),
            FixtureCase("h09", COMPLETION, "주문한 물건이 "), FixtureCase("h10", COMPLETION, "사이즈가 맞지 "),
            FixtureCase("h11", COMPLETION, "검토가 끝나면 "), FixtureCase("h12", COMPLETION, "수정한 내용을 "),
            FixtureCase("h13", COMPLETION, "이 부분이 "), FixtureCase("h14", COMPLETION, "문제 풀이를 "),
            FixtureCase("h15", COMPLETION, "주말에 산책 "), FixtureCase("h16", COMPLETION, "영화를 보고 "),
            FixtureCase("h17", COMPLETION, "감기 기운이 "), FixtureCase("h18", COMPLETION, "오늘은 일찍 "),
            FixtureCase("h19", COMPLETION, "방문 전에 "), FixtureCase("h20", COMPLETION, "모임 장소가 "),
            FixtureCase("h21", COMPLETION, "도와주신 덕분에 "), FixtureCase("h22", COMPLETION, "걱정해 줘서 "),
            FixtureCase("h23", COMPLETION, "요즘 어떻게 "), FixtureCase("h24", COMPLETION, "잘 지내고 ")
        )
    }
}
