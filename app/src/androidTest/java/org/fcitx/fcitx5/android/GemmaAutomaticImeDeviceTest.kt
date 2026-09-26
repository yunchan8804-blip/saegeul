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
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationScheduler
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationStore
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.ai.AiSuggestionApplyResult
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAutomaticSuggestionWarmupState
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionCoordinator
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionPolicy
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionSession
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
 * Headed proof for the opt-in automatic path. This test has one public development fixture only;
 * it must not be used as held-out utility or Korean-quality evidence.
 */
class GemmaAutomaticImeDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun pauseAccumulationForInstrumentation() {
        // 이전 실기기 세션에서 남은 축적 주기 작업이 이 E2E 도중 깨어나 두 번째 native 엔진을
        // 만드는 것을 막는다. 진행 중인 생성은 그대로 끝나고, 새 생성만 막힌다.
        GemmaAccumulationScheduler.pauseForInstrumentation()
    }

    @After
    fun resumeAccumulationAfterInstrumentation() {
        GemmaAccumulationScheduler.resumeAfterInstrumentation()
    }

    @Test
    fun collectsDisplaysAndAppliesActualAutomaticSentenceCandidateThroughIme() = runBlocking<Unit> {
        val backend = selectedBackend()
        val priorAutomaticOptIn = AppPrefs.getInstance().internal.automaticOnDeviceSuggestionsOptIn.getValue()
        AppPrefs.getInstance().internal.automaticOnDeviceSuggestionsOptIn.setValue(false)
        val harness = prepareImeHarness()
        val evidence = JSONObject()
            .put("scope", "actual_automatic_ime_public_development_fixture")
            .put("backend", backend.argument)
            .put("fixture", PUBLIC_DEVELOPMENT_INPUT)
            .put("actualUserInput", false)
            .put("heldOutFixtureUsed", false)
            .put("networkFallbackUsed", false)
            .put("offlineProof", false)
            .put("networkChangedByTest", false)
            .put("networkBefore", networkSnapshot(instrumentation.targetContext).toJson())
            .put("imeRebind", harness.evidence)
        var activity: AiEditorTestActivity? = null
        var ime: FcitxInputMethodService? = null
        var primaryFailure: Throwable? = null
        try {
            activity = launchActivity()
            val editor = selectNormalAndClear(activity)
            showKeyboard(editor)
            ime = waitForCurrentEditor(editorTarget(editor))
            showConnectedImeWindow(editor, harness.automation, evidence)
            assertTrue("자동 추천 debug 지원이 꺼져 있습니다.", onMain { ime.automaticSuggestionsSupported })
            assertFalse("새 IME 세션은 자동 추천을 기본으로 켜면 안 됩니다.", onMain { ime.automaticSuggestionsEnabled })
            evidence.put("editorConnected", true)
                .put("keyboardActiveBeforeOptIn", OnDeviceGenerationControl.isKeyboardActive)

            val warmupStartedAt = SystemClock.elapsedRealtime()
            var warmupNativeLeaseObserved = false
            waitUntil("키보드 표시 뒤 자동 추천 엔진의 Preparing 상태도, 이미 warm 상태도 관찰하지 못했습니다.", COLD_CANDIDATE_TIMEOUT_MS) {
                warmupNativeLeaseObserved = warmupNativeLeaseObserved || OnDeviceGenerationControl.isGenerating
                onMain { ime.automaticSuggestionWarmupState == OnDeviceAutomaticSuggestionWarmupState.Preparing } ||
                    onMain { ime.automaticSuggestionRuntimeWarm }
            }
            val preparingObserved = onMain { ime.automaticSuggestionWarmupState == OnDeviceAutomaticSuggestionWarmupState.Preparing }
            if (preparingObserved) {
                assertFalse("warm-up 중 자동 추천 opt-in이 켜졌습니다.", onMain { ime.automaticSuggestionsEnabled })
                assertEquals("warm-up 중 공개 입력이 editor에 전달되었습니다.", "", onMain { editor.text.toString() })
                val warmupIndicator = requireVisibleNodeByDescription(
                    harness.automation,
                    instrumentation.targetContext.getString(R.string.gemma_automatic_warmup_content_description),
                    SHORT_TIMEOUT_MS
                )
                assertTrue("warm-up 표시가 visible/enabled가 아닙니다.", warmupIndicator.isVisibleToUser && warmupIndicator.isEnabled)
                evidence.put("warmupNativeLeaseObservedWhilePreparing", warmupNativeLeaseObserved)
                    .put("warmupIndicatorNode", nodeEvidence(warmupIndicator))
                    .put("warmupIndicatorScreenshot", captureScreenshot("warmup-indicator", activity))
                assertTrue(
                    "warm-up 표시 접근성 노드의 탭이 거부되었습니다.",
                    clickable(warmupIndicator).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                )
                val warmupTitle = requireVisibleApplicationNodeByTextOrDescription(
                    harness.automation,
                    instrumentation.targetContext.getString(R.string.gemma_automatic_warmup_title),
                    SHORT_TIMEOUT_MS
                )
                val warmupMessage = requireVisibleApplicationNodeByTextOrDescription(
                    harness.automation,
                    instrumentation.targetContext.getString(R.string.gemma_automatic_warmup_message),
                    SHORT_TIMEOUT_MS
                )
                assertTrue("warm-up 안내 제목이 visible/enabled가 아닙니다.", warmupTitle.isVisibleToUser && warmupTitle.isEnabled)
                assertTrue("warm-up 안내 본문이 visible/enabled가 아닙니다.", warmupMessage.isVisibleToUser && warmupMessage.isEnabled)
                evidence.put("warmupPopupTitleNode", passiveNodeEvidence(warmupTitle))
                    .put("warmupPopupMessageNode", passiveNodeEvidence(warmupMessage))
                    .put("warmupPopupScreenshot", captureScreenshot("warmup-popup", activity))
                val confirmation = requireVisibleApplicationNodeByTextOrDescription(
                    harness.automation,
                    instrumentation.targetContext.getString(android.R.string.ok),
                    SHORT_TIMEOUT_MS
                )
                assertTrue("warm-up 안내 확인 버튼 클릭이 거부되었습니다.", clickable(confirmation).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitUntil("warm-up 안내가 확인 뒤에도 닫히지 않았습니다.", SHORT_TIMEOUT_MS) {
                    findVisibleApplicationNodeByTextOrDescription(
                        harness.automation,
                        instrumentation.targetContext.getString(R.string.gemma_automatic_warmup_title)
                    ) == null
                }
                val autoReturnDeadline = SystemClock.elapsedRealtime() + KEYBOARD_AUTO_RETURN_TIMEOUT_MS
                var keyboardReturnedAutomatically = false
                while (SystemClock.elapsedRealtime() < autoReturnDeadline) {
                    if (imeRoots(harness.automation).any { it.isVisibleToUser }) {
                        keyboardReturnedAutomatically = true
                        break
                    }
                    SystemClock.sleep(POLL_INTERVAL_MS)
                }
                if (!keyboardReturnedAutomatically) showKeyboard(editor)
                evidence.put("keyboardReturnedAutomaticallyAfterWarmupPopup", keyboardReturnedAutomatically)
                waitUntil("warm-up 안내를 닫은 뒤 키보드를 다시 띄우지 못했습니다.", SHORT_TIMEOUT_MS) {
                    imeRoots(harness.automation).any { it.isVisibleToUser }
                }
                waitUntil("warm-up 뒤 엔진이 Idle 상태로 돌아오지 못했습니다.", COLD_CANDIDATE_TIMEOUT_MS) {
                    warmupNativeLeaseObserved = warmupNativeLeaseObserved || OnDeviceGenerationControl.isGenerating
                    onMain { ime.automaticSuggestionWarmupState == OnDeviceAutomaticSuggestionWarmupState.Idle }
                }
                assertTrue("warm-up 중 AUTO_CONTEXT native lease를 관찰하지 못했습니다.", warmupNativeLeaseObserved)
            } else {
                evidence.put("warmupAlreadyWarmAtObservation", true)
                assertTrue(
                    "이미 warm 상태로 관찰됐는데 엔진이 Idle 상태가 아닙니다.",
                    onMain { ime.automaticSuggestionWarmupState == OnDeviceAutomaticSuggestionWarmupState.Idle }
                )
            }
            assertFalse("warm-up 완료 뒤 자동 추천 opt-in이 켜졌습니다.", onMain { ime.automaticSuggestionsEnabled })
            assertEquals("warm-up 완료 전 공개 입력이 editor에 전달되었습니다.", "", onMain { editor.text.toString() })
            evidence.put("warmupPreparingObserved", preparingObserved)
                .put("warmupCompleted", true)
                .put("warmupNativeLeaseObserved", warmupNativeLeaseObserved)
                .put("warmupDurationMs", SystemClock.elapsedRealtime() - warmupStartedAt)
                .put("automaticOptInAfterWarmup", onMain { ime.automaticSuggestionsEnabled })
                .put("editorTextBeforeOptIn", onMain { editor.text.toString() })
                .put("automaticCandidatesBeforeOptIn", onMain { ime.getAutomaticSuggestionCandidates().size })

            val panelStartedAt = SystemClock.elapsedRealtime()
            openContextCompletion(harness.automation, evidence)
            evidence.put("panelOpenMs", SystemClock.elapsedRealtime() - panelStartedAt)

            if (backend == Backend.CPU) {
                val cpuStartedAt = SystemClock.elapsedRealtime()
                // A warm-up failure may already have fallen back to CPU before the panel opened;
                // clicking the already-CPU switch would just flip it back to GPU.
                val alreadyCpu = !onMain { ime.automaticSuggestionsUseGpu }
                if (!alreadyCpu) {
                    GemmaImeTestSupport.clickVisibleTextInPanel(
                        harness.automation,
                        instrumentation.targetContext.packageName,
                        CPU_COMPATIBILITY,
                        SHORT_TIMEOUT_MS
                    )
                    waitUntil("CPU 호환 모드를 실제 panel에서 켜지 못했습니다.", SHORT_TIMEOUT_MS) {
                        !onMain { ime.automaticSuggestionsUseGpu }
                    }
                }
                evidence.put("cpuCompatibilitySelected", true)
                    .put("cpuCompatibilityAlreadySelectedByFallback", alreadyCpu)
                    .put("cpuSelectionMs", SystemClock.elapsedRealtime() - cpuStartedAt)
            } else {
                // GPU delegate가 없는 기기(에뮬레이터 등)에서는 warm-up이 CPU로 자동 폴백한다. 그 경우 panel은 CPU여야 한다.
                val fallbackOccurred = onMain { ime.automaticSuggestionBackendFallbackOccurred }
                val useGpu = onMain { ime.automaticSuggestionsUseGpu }
                // 이전 실행의 폴백이 CPU 선택을 영속화했으면 이번 인스턴스는 처음부터 CPU로 시작한다(의도한 동작).
                val persistedCpu = !AppPrefs.getInstance().internal.automaticOnDeviceSuggestionsUseGpu.getValue()
                if (fallbackOccurred || persistedCpu) {
                    assertFalse("GPU→CPU 폴백(또는 영속된 CPU 선택)인데 panel 설정이 아직 GPU입니다.", useGpu)
                } else {
                    assertTrue("GPU backend 요청인데 panel 설정이 GPU가 아닙니다.", useGpu)
                }
                evidence.put("cpuCompatibilitySelected", false)
                    .put("backendFallbackOccurred", fallbackOccurred)
                    .put("backendPersistedCpu", persistedCpu)
            }

            val optInStartedAt = SystemClock.elapsedRealtime()
            clickVisibleText(harness.automation, AUTOMATIC_ENABLE, SHORT_TIMEOUT_MS)
            waitUntil("자동 문맥 추천 opt-in이 panel 클릭 뒤 켜지지 않았습니다.", SHORT_TIMEOUT_MS) {
                onMain { ime.automaticSuggestionsEnabled }
            }
            evidence.put("automaticOptIn", true)
                .put("automaticOptInMs", SystemClock.elapsedRealtime() - optInStartedAt)
                .put("automaticPanelScreenshot", captureScreenshot("automatic-panel", activity))

            clickVisibleText(
                harness.automation,
                instrumentation.targetContext.getString(R.string.back_to_keyboard),
                SHORT_TIMEOUT_MS
            )
            waitUntil("자동 추천 panel을 닫고 키보드로 돌아오지 못했습니다.", SHORT_TIMEOUT_MS) {
                findVisibleNodeByTextOrDescription(harness.automation, CONTEXT_COMPLETION_TITLE) == null
            }
            evidence.put("panelBackToKeyboard", true)

            val inputStartedAt = SystemClock.elapsedRealtime()
            assertTrue(
                "공개 development 입력을 실제 IME commit 경로로 넣지 못했습니다.",
                onMain { ime.commitToEditor(PUBLIC_DEVELOPMENT_INPUT) }
            )
            waitForEditorText(editor, PUBLIC_DEVELOPMENT_INPUT)
            evidence.put("inputCommitted", true)
                .put("inputCommitMs", SystemClock.elapsedRealtime() - inputStartedAt)

            val observed = awaitAutomaticCandidates(harness.automation, ime)
            evidence.put("nativeLeaseObserved", observed.nativeLeaseObserved)
                .put("automaticStatus", observed.status.toJson())
                .put("candidateModes", candidateObservationJson(observed))
            assertTrue("자동 후보 수집 중 AUTO_CONTEXT native lease를 관측하지 못했습니다.", observed.nativeLeaseObserved)

            val wordNode = requireAutomaticCandidateNode(
                harness.automation,
                observed.word,
                automaticOriginLabel(observed.word.origin),
                COLD_CANDIDATE_TIMEOUT_MS
            )
            val sentenceNode = requireAutomaticCandidateNode(
                harness.automation,
                observed.sentence,
                automaticOriginLabel(observed.sentence.origin),
                COLD_CANDIDATE_TIMEOUT_MS
            )
            val postWarmCandidateVisibleLatencyMs = SystemClock.elapsedRealtime() - inputStartedAt
            evidence.put("wordCandidate", observed.word.toJson())
                .put("wordCandidateNode", nodeEvidence(wordNode))
                .put("sentenceCandidate", observed.sentence.toJson())
                .put("sentenceCandidateNode", nodeEvidence(sentenceNode))
                .put("candidateVisibleLatencyMs", postWarmCandidateVisibleLatencyMs)
                .put("postWarmCandidateVisibleLatencyMs", postWarmCandidateVisibleLatencyMs)
                .put("candidateScreenshot", captureScreenshot("before-candidate", activity))

            val tapStartedAt = SystemClock.elapsedRealtime()
            val sentenceTapBounds = boundsOf(sentenceNode)
            assertTrue(
                "실제 자동 문장 후보 접근성 노드의 탭이 거부되었습니다.",
                clickable(sentenceNode).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            )
            val expectedEditor = PUBLIC_DEVELOPMENT_INPUT + observed.sentence.insertion
            waitForEditorText(editor, expectedEditor)
            evidence.put("sentenceTapBounds", sentenceTapBounds)
                .put("sentenceTapMs", SystemClock.elapsedRealtime() - tapStartedAt)
                .put("editorAfter", expectedEditor)
                .put("afterApplyScreenshot", captureScreenshot("after-apply", activity))
            assertEquals("문장 후보 탭 뒤 editor 원문이 정확히 일치하지 않습니다.", expectedEditor, onMain { editor.text.toString() })

            val secondApply = onMain { ime.commitAutomaticSuggestionCandidate(observed.sentence) }
            evidence.put("secondApplyResult", secondApply.javaClass.simpleName)
            assertTrue(
                "탭으로 소비한 이전 후보의 두 번째 service 적용은 EditorChanged 또는 NotApplied여야 합니다: $secondApply",
                secondApply === AiSuggestionApplyResult.EditorChanged || secondApply === AiSuggestionApplyResult.NotApplied
            )
            assertEquals("두 번째 적용 시도 뒤 editor가 바뀌었습니다.", expectedEditor, onMain { editor.text.toString() })
        } catch (error: Throwable) {
            primaryFailure = error
            evidence.put("executionFailure", true)
                .put("exceptionClass", error.javaClass.name)
                .put("exceptionMessage", error.message ?: JSONObject.NULL)
                .put("failureStatus", ime?.let { onMain { it.automaticSuggestionStatus.toJson() } } ?: JSONObject.NULL)
            try {
                evidence.put("failureImeHierarchy", imeAccessibilityHierarchy(harness.automation))
                evidence.put("failureScreenshot", activity?.let { captureScreenshot("failure", it) } ?: JSONObject.NULL)
                evidence.put("failureEvidenceFile", persistEvidence("failed", evidence))
            } catch (captureError: Throwable) {
                error.addSuppressed(captureError)
            }
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            try {
                ime?.let { activeIme ->
                    if (onMain { activeIme.automaticSuggestionsEnabled }) {
                        openContextCompletion(harness.automation, evidence)
                        clickVisibleText(harness.automation, AUTOMATIC_ENABLE, SHORT_TIMEOUT_MS)
                        waitUntil("자동 추천을 panel에서 끄지 못했습니다.", SHORT_TIMEOUT_MS) {
                            !onMain { activeIme.automaticSuggestionsEnabled }
                        }
                        evidence.put("automaticOptInDisabledViaPanel", true)
                        clickVisibleText(
                            harness.automation,
                            instrumentation.targetContext.getString(R.string.back_to_keyboard),
                            SHORT_TIMEOUT_MS
                        )
                    }
                    waitUntil("자동 추천 opt-out 뒤 native lease가 남아 있습니다.", STOP_TIMEOUT_MS) {
                        !OnDeviceGenerationControl.isGenerating
                    }
                    evidence.put("nativeStoppedAfterOptOut", true)
                }
            } catch (error: Throwable) {
                cleanupFailure = error
                evidence.put("automaticCleanupFailureClass", error.javaClass.name)
                    .put("automaticCleanupFailureMessage", error.message ?: JSONObject.NULL)
                ime?.let { activeIme ->
                    try {
                        onMain { activeIme.setAutomaticSuggestionsEnabled(false) }
                        waitUntil("강제 cleanup 뒤 native lease가 남아 있습니다.", STOP_TIMEOUT_MS) {
                            !OnDeviceGenerationControl.isGenerating
                        }
                        evidence.put("automaticCleanupApiFallback", true)
                    } catch (fallbackError: Throwable) {
                        cleanupFailure = appendFailure(cleanupFailure, fallbackError)
                    }
                }
            } finally {
                try {
                    activity?.let { onMain { it.finish(); Unit } }
                } catch (finishError: Throwable) {
                    cleanupFailure = appendFailure(cleanupFailure, finishError)
                }
                try {
                    AppPrefs.getInstance().internal.automaticOnDeviceSuggestionsOptIn.setValue(priorAutomaticOptIn)
                    evidence.put("restoredAutomaticOptIn", priorAutomaticOptIn)
                } catch (restoreError: Throwable) {
                    cleanupFailure = appendFailure(cleanupFailure, restoreError)
                }
                try {
                    harness.restore()
                } catch (restoreError: Throwable) {
                    cleanupFailure = appendFailure(cleanupFailure, restoreError)
                }
                val networkAfter = networkSnapshot(instrumentation.targetContext)
                evidence.put("networkAfter", networkAfter.toJson())
                    .put("evidenceFile", persistEvidence("finished", evidence))
                instrumentation.sendStatus(0, Bundle().apply { putString(EVIDENCE_KEY, evidence.toString()) })
            }
            primaryFailure?.let { primary -> cleanupFailure?.let(primary::addSuppressed) }
                ?: cleanupFailure?.let { throw it }
        }
    }

    private fun awaitAutomaticCandidates(
        automation: UiAutomation,
        ime: FcitxInputMethodService
    ): CandidateObservation {
        val startedAt = SystemClock.elapsedRealtime()
        var nativeLeaseObserved = false
        var generatingObserved = false
        var lastStatus = onMain { ime.automaticSuggestionStatus }
        while (SystemClock.elapsedRealtime() - startedAt < COLD_CANDIDATE_TIMEOUT_MS) {
            nativeLeaseObserved = nativeLeaseObserved || OnDeviceGenerationControl.isGenerating
            lastStatus = onMain { ime.automaticSuggestionStatus }
            generatingObserved = generatingObserved || lastStatus.state == OnDeviceSuggestionCoordinator.State.GENERATING
            val labels = visibleAutomaticCandidateTexts(automation)
            if (lastStatus.state == OnDeviceSuggestionCoordinator.State.READY && labels.length() >= 2) {
                val candidates = onMain { ime.getAutomaticSuggestionCandidates() }
                val word = candidates.singleOrNull { it.mode == OnDeviceSuggestionPolicy.Mode.WORD }
                val sentence = candidates.singleOrNull { it.mode == OnDeviceSuggestionPolicy.Mode.SENTENCE }
                if (word != null && sentence != null) {
                    return CandidateObservation(word, sentence, lastStatus, nativeLeaseObserved)
                }
                throw AssertionError(
                    "자동 후보 UI는 READY로 두 행을 표시했지만 service identity가 WORD/SENTENCE 한 쌍이 아닙니다: " +
                        "status=${lastStatus.state}, labels=$labels, candidateCount=${candidates.size}"
                )
            }
            // 웜업이 잡은 lease는 생성 증거가 아니다. GENERATING을 실제로 본 뒤의 NO_CANDIDATE만 조기 종료로 본다.
            if (lastStatus.state == OnDeviceSuggestionCoordinator.State.ERROR ||
                (generatingObserved && lastStatus.state == OnDeviceSuggestionCoordinator.State.NO_CANDIDATE &&
                    !OnDeviceGenerationControl.isGenerating)
            ) {
                break
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        val status = onMain { ime.automaticSuggestionStatus }
        throw AssertionError(
            "${COLD_CANDIDATE_TIMEOUT_MS}ms 안에 실제 자동 WORD/SENTENCE 후보가 함께 나타나지 않았습니다: " +
                "state=${status.state}, error=${status.errorCode}, nativeLeaseObserved=$nativeLeaseObserved, " +
                "visibleAutomaticNodes=${visibleAutomaticCandidateTexts(automation)}"
        )
    }

    private fun openContextCompletion(automation: UiAutomation, evidence: JSONObject) {
        val continueWriting = ensureVisibleToolbarDescription(
            automation,
            instrumentation.targetContext.getString(R.string.continue_writing_title),
            evidence
        )
        assertTrue("이어쓰기 toolbar 버튼 클릭이 거부되었습니다.", clickable(continueWriting).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        requireVisibleNodeByTextOrDescription(automation, CONTEXT_COMPLETION_TITLE, SHORT_TIMEOUT_MS)
    }

    private fun ensureVisibleToolbarDescription(
        automation: UiAutomation,
        description: String,
        evidence: JSONObject
    ): AccessibilityNodeInfo {
        val initial = awaitToolbarSurface(automation, description)
        var target = initial.target
        initial.expand?.takeIf { target == null }?.let { expand ->
            assertTrue("toolbar 펼치기 버튼 클릭이 거부되었습니다.", clickable(expand).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitUntil("toolbar 펼치기 뒤 target 또는 UI 변화가 없습니다.", SHORT_TIMEOUT_MS) {
                findVisibleNodeByDescription(automation, description) != null ||
                    toolbarSignature(findVisibleToolbar(automation)) != initial.toolbarSignature
            }
            target = findVisibleNodeByDescription(automation, description)
        }
        var scrolls = 0
        while (target == null && scrolls < MAX_TOOLBAR_SCROLLS) {
            val toolbar = findVisibleToolbar(automation)
                ?: throw AssertionError("AI writing 버튼과 scroll 가능한 toolbar를 찾지 못했습니다.")
            val previousSignature = toolbarSignature(toolbar)
            assertTrue("toolbar scroll이 거부되었습니다.", toolbar.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            scrolls += 1
            waitUntil("toolbar scroll 뒤 target 또는 가시 UI 변화가 없습니다.", SHORT_TIMEOUT_MS) {
                findVisibleNodeByDescription(automation, description) != null ||
                    toolbarSignature(findVisibleToolbar(automation)) != previousSignature
            }
            target = findVisibleNodeByDescription(automation, description)
        }
        evidence.put("toolbarScrollAttempts", scrolls)
        return requireNotNull(target) { "AI writing toolbar 버튼을 찾지 못했습니다." }
    }

    private fun awaitToolbarSurface(automation: UiAutomation, description: String): ToolbarSurface {
        val deadline = SystemClock.elapsedRealtime() + SHORT_TIMEOUT_MS
        val expandDescription = instrumentation.targetContext.getString(R.string.expand_toolbar)
        while (SystemClock.elapsedRealtime() < deadline) {
            val target = findVisibleNodeByDescription(automation, description)
            val expand = findVisibleNodeByTextOrDescription(automation, expandDescription)
            val toolbar = findVisibleToolbar(automation)
            if (target != null || expand != null || toolbar != null) {
                return ToolbarSurface(target, expand, toolbarSignature(toolbar))
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("IME toolbar 접근성 root 또는 AI writing surface가 $SHORT_TIMEOUT_MS ms 안에 나타나지 않았습니다.")
    }

    private fun toolbarSignature(toolbar: AccessibilityNodeInfo?): String {
        if (toolbar == null) return "absent"
        val bounds = boundsOf(toolbar)
        val visibleTexts = mutableListOf<String>()
        toolbar.collectVisibleTexts(visibleTexts)
        return listOf(bounds, visibleTexts.joinToString("\u0001")).joinToString("|")
    }

    private fun AccessibilityNodeInfo.collectVisibleTexts(output: MutableList<String>) {
        if (isVisibleToUser) {
            text?.toString()?.let(output::add)
            contentDescription?.toString()?.let(output::add)
        }
        for (index in 0 until childCount) getChild(index)?.collectVisibleTexts(output)
    }

    private fun requireAutomaticCandidateNode(
        automation: UiAutomation,
        candidate: OnDeviceSuggestionCoordinator.Candidate,
        originLabel: String,
        timeoutMs: Long
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            findAutomaticCandidateNode(automation, candidate.insertion, originLabel)?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("자동 ${candidate.mode} 후보의 실제 접근성 노드를 찾지 못했습니다: origin=$originLabel")
    }

    private fun findAutomaticCandidateNode(
        automation: UiAutomation,
        insertion: String,
        originLabel: String
    ): AccessibilityNodeInfo? = imeRoots(automation).firstNotNullOfOrNull { root ->
        root.findVisible { node ->
            val label = "$insertion $originLabel"
            node.text?.toString() == label || node.contentDescription?.toString() == label
        }
    }

    private fun visibleAutomaticCandidateTexts(automation: UiAutomation): JSONArray = JSONArray().also { output ->
        imeRoots(automation).forEach { root ->
            root.appendVisibleTexts(output)
        }
    }

    private fun AccessibilityNodeInfo.appendVisibleTexts(output: JSONArray) {
        if (isVisibleToUser) {
            val label = contentDescription?.toString()?.takeIf(::isAutomaticDisplayText)
                ?: text?.toString()?.takeIf(::isAutomaticDisplayText)
            label?.let(output::put)
        }
        for (index in 0 until childCount) getChild(index)?.appendVisibleTexts(output)
    }

    private fun isAutomaticDisplayText(value: String): Boolean {
        val context = instrumentation.targetContext
        return value.endsWith(" ${context.getString(R.string.gemma_automatic_generated)}") ||
            value.endsWith(" ${context.getString(R.string.gemma_automatic_continuation)}")
    }

    private fun automaticOriginLabel(origin: OnDeviceSuggestionSession.Origin): String = when (origin) {
        OnDeviceSuggestionSession.Origin.GENERATED -> instrumentation.targetContext.getString(R.string.gemma_automatic_generated)
        OnDeviceSuggestionSession.Origin.CONTINUATION_CACHE -> instrumentation.targetContext.getString(R.string.gemma_automatic_continuation)
    }

    private fun clickVisibleText(automation: UiAutomation, value: String, timeoutMs: Long) {
        val node = requireVisibleNodeByTextOrDescription(automation, value, timeoutMs)
        assertTrue("'$value' UI 클릭이 거부되었습니다.", clickable(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun requireVisibleNodeByTextOrDescription(automation: UiAutomation, value: String, timeoutMs: Long): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            findVisibleNodeByTextOrDescription(automation, value)?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("'$value' visible accessibility node를 찾지 못했습니다.")
    }

    private fun requireVisibleNodeByDescription(automation: UiAutomation, value: String, timeoutMs: Long): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            findVisibleNodeByDescription(automation, value)?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("'$value' visible accessibility content description을 찾지 못했습니다.")
    }

    private fun requireVisibleApplicationNodeByTextOrDescription(
        automation: UiAutomation,
        value: String,
        timeoutMs: Long
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            findVisibleApplicationNodeByTextOrDescription(automation, value)?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("'$value' visible application accessibility node를 찾지 못했습니다.")
    }

    private fun findVisibleNodeByTextOrDescription(automation: UiAutomation, value: String): AccessibilityNodeInfo? =
        imeRoots(automation).firstNotNullOfOrNull { root ->
            root.findVisible { node -> node.text?.toString() == value || node.contentDescription?.toString() == value }
        }

    private fun findVisibleApplicationNodeByTextOrDescription(automation: UiAutomation, value: String): AccessibilityNodeInfo? =
        applicationRoots(automation).firstNotNullOfOrNull { root ->
            root.findVisible { node -> node.text?.toString() == value || node.contentDescription?.toString() == value }
        }

    private fun findVisibleNodeByDescription(automation: UiAutomation, value: String): AccessibilityNodeInfo? =
        imeRoots(automation).firstNotNullOfOrNull { root ->
            root.findVisible { node -> node.contentDescription?.toString() == value }
        }

    private fun findVisibleToolbar(automation: UiAutomation): AccessibilityNodeInfo? = imeRoots(automation).firstNotNullOfOrNull { root ->
        root.findVisible { node -> node.className?.toString() == HORIZONTAL_SCROLL_VIEW && node.isScrollable }
    }

    private fun imeRoots(automation: UiAutomation): Sequence<AccessibilityNodeInfo> = automation.windows.asSequence()
        .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        .mapNotNull { it.root }
        .filter { it.packageName?.toString() == instrumentation.targetContext.packageName }

    private fun applicationRoots(automation: UiAutomation): Sequence<AccessibilityNodeInfo> = automation.windows.asSequence()
        .mapNotNull { it.root }
        .filter { it.packageName?.toString() == instrumentation.targetContext.packageName }

    private fun imeAccessibilityHierarchy(automation: UiAutomation): JSONArray = JSONArray().also { output ->
        imeRoots(automation).forEach { root -> root.appendHierarchy(output) }
    }

    private fun AccessibilityNodeInfo.appendHierarchy(output: JSONArray) {
        output.put(
            JSONObject()
                .put("className", className?.toString() ?: JSONObject.NULL)
                .put("text", text?.toString() ?: JSONObject.NULL)
                .put("contentDescription", contentDescription?.toString() ?: JSONObject.NULL)
                .put("bounds", boundsOf(this))
                .put("visible", isVisibleToUser)
                .put("enabled", isEnabled)
                .put("clickable", isClickable)
                .put("scrollable", isScrollable)
        )
        for (index in 0 until childCount) getChild(index)?.appendHierarchy(output)
    }

    private fun AccessibilityNodeInfo.findVisible(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (isVisibleToUser && predicate(this)) return this
        for (index in 0 until childCount) getChild(index)?.findVisible(predicate)?.let { return it }
        return null
    }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        throw AssertionError("후보 UI의 clickable ancestor가 없습니다.")
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
            // 기기 autofill 서비스가 저장된 계정 등을 채워 넣으면 공개 fixture 검증이 오염된다.
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

    private fun showConnectedImeWindow(editor: EditText, automation: UiAutomation, evidence: JSONObject) {
        showKeyboard(editor)
        waitUntil("현재 연결된 새글 IME window가 visible 상태로 나타나지 않았습니다.", SHORT_TIMEOUT_MS) {
            imeRoots(automation).any { it.isVisibleToUser }
        }
        evidence.put("imeWindowVisible", true)
    }

    private fun waitForCurrentEditor(target: EditorTarget): FcitxInputMethodService {
        val deadline = SystemClock.elapsedRealtime() + SHORT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val ime = FcitxInputMethodService.activeInstance
            if (ime != null && ime.matchesCurrentEditor(target.packageName, target.fieldId, target.inputType, target.selectionStart, target.selectionEnd, ime.currentInputSessionEpoch)) return ime
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("새글 IME가 debug editor에 연결되지 않았습니다.")
    }

    private fun waitForEditorText(editor: EditText, expected: String) = waitUntil("editor 원문이 기대값과 다릅니다.", SHORT_TIMEOUT_MS) {
        onMain { editor.text.toString() == expected }
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
            val target = File(evidenceDirectory(), "$stage-${SystemClock.elapsedRealtime()}.png")
            FileOutputStream(target).use { output ->
                assertTrue("$stage screenshot을 저장하지 못했습니다.", screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            return JSONObject().put("path", target.absolutePath).put("saved", true)
        } finally {
            screenshot.recycle()
        }
    }

    private fun persistEvidence(stage: String, evidence: JSONObject): String {
        val target = File(evidenceDirectory(), "$stage-${SystemClock.elapsedRealtime()}.json")
        FileOutputStream(target).bufferedWriter(Charsets.UTF_8).use { it.write(evidence.toString()) }
        return target.absolutePath
    }

    private fun evidenceDirectory(): File {
        val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "gemma-automatic-ime")
        if (!directory.exists()) assertTrue("자동 IME 증거 디렉터리를 만들지 못했습니다.", directory.mkdirs())
        return directory
    }

    private fun nodeEvidence(node: AccessibilityNodeInfo): JSONObject = JSONObject()
        .put("text", node.text?.toString() ?: JSONObject.NULL)
        .put("bounds", boundsOf(node))
        .put("visible", node.isVisibleToUser)
        .put("enabled", node.isEnabled)
        .put("clickableAncestor", clickable(node).className?.toString() ?: JSONObject.NULL)
        .put("clickableAncestorBounds", boundsOf(clickable(node)))

    private fun passiveNodeEvidence(node: AccessibilityNodeInfo): JSONObject = JSONObject()
        .put("text", node.text?.toString() ?: JSONObject.NULL)
        .put("bounds", boundsOf(node))
        .put("visible", node.isVisibleToUser)
        .put("enabled", node.isEnabled)

    private fun boundsOf(node: AccessibilityNodeInfo): String = Rect().also(node::getBoundsInScreen).toShortString()

    private fun networkSnapshot(context: Context): NetworkSnapshot {
        val manager = requireNotNull(context.getSystemService(ConnectivityManager::class.java)) { "ConnectivityManager가 없습니다." }
        return NetworkSnapshot(manager.allNetworks.map { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            NetworkEntry(
                capabilitiesAvailable = capabilities != null,
                internet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                transports = TRANSPORTS.filter { (transport, _) -> capabilities?.hasTransport(transport) == true }.map { it.second }
            )
        })
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

    private fun selectedBackend(): Backend = when (
        InstrumentationRegistry.getArguments().getString(BACKEND_ARGUMENT) ?: Backend.GPU.argument
    ) {
        Backend.GPU.argument -> Backend.GPU
        Backend.CPU.argument -> Backend.CPU
        else -> throw IllegalArgumentException("$BACKEND_ARGUMENT 인자는 gpu 또는 cpu여야 합니다.")
    }

    private data class EditorTarget(val packageName: String, val fieldId: Int, val inputType: Int, val selectionStart: Int, val selectionEnd: Int)

    private enum class Backend(val argument: String) {
        GPU("gpu"),
        CPU("cpu")
    }

    private data class ToolbarSurface(
        val target: AccessibilityNodeInfo?,
        val expand: AccessibilityNodeInfo?,
        val toolbarSignature: String
    )

    private data class CandidateObservation(
        val word: OnDeviceSuggestionCoordinator.Candidate,
        val sentence: OnDeviceSuggestionCoordinator.Candidate,
        val status: OnDeviceSuggestionCoordinator.Status,
        val nativeLeaseObserved: Boolean
    )

    private fun candidateObservationJson(observation: CandidateObservation): JSONObject = JSONObject()
        .put("word", observation.word.toJson())
        .put("sentence", observation.sentence.toJson())

    private fun OnDeviceSuggestionCoordinator.Candidate.toJson(): JSONObject = JSONObject()
        .put("mode", mode.name)
        .put("origin", origin.name)
        .put("insertion", insertion)

    private fun OnDeviceSuggestionCoordinator.Status.toJson(): JSONObject = JSONObject()
        .put("state", state.name)
        .put("errorCode", errorCode ?: JSONObject.NULL)

    private data class NetworkEntry(val capabilitiesAvailable: Boolean, val internet: Boolean?, val transports: List<String>) {
        fun toJson(): JSONObject = JSONObject().put("capabilitiesAvailable", capabilitiesAvailable).put("internet", internet ?: JSONObject.NULL).put("transports", JSONArray(transports))
    }

    private data class NetworkSnapshot(val entries: List<NetworkEntry>) {
        fun toJson(): JSONObject = JSONObject().put("entries", JSONArray(entries.map(NetworkEntry::toJson)))
    }

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
            evidence.put("restoredPublicPreparationEnabled", priorPublicPreparationEnabled)
                .put("imeRestoreSucceeded", true)
            restored = true
        }
    }

    private companion object {
        const val EVIDENCE_KEY = "gemmaAutomaticImeEvidence"
        const val BACKEND_ARGUMENT = "backend"
        const val PUBLIC_DEVELOPMENT_INPUT = "창문으로 햇빛이 들어와서 "
        // 접근성 노드는 기기 로케일로 렌더링된 문구를 쓴다(values/values-ko 분리 이후 en-US 에뮬레이터는
        // 영어를 렌더링한다). 리터럴 대신 실행 시점 로케일로 해석해야 두 로케일 모두에서 찾을 수 있다.
        val AUTOMATIC_ENABLE: String by lazy {
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.gemma_automatic_enable)
        }
        val CPU_COMPATIBILITY: String by lazy {
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.gemma_automatic_cpu_compatibility)
        }
        val CONTEXT_COMPLETION_TITLE: String by lazy {
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.gemma_context_title)
        }
        const val POLL_INTERVAL_MS = 50L
        const val KEYBOARD_AUTO_RETURN_TIMEOUT_MS = 3_000L
        const val SHORT_TIMEOUT_MS = 10_000L
        const val COLD_CANDIDATE_TIMEOUT_MS = 180_000L
        const val STOP_TIMEOUT_MS = 30_000L
        const val MAX_TOOLBAR_SCROLLS = 5
        const val HORIZONTAL_SCROLL_VIEW = "android.widget.HorizontalScrollView"
        val TRANSPORTS = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "wifi",
            NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
            NetworkCapabilities.TRANSPORT_VPN to "vpn"
        )
    }
}
