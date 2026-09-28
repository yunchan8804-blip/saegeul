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
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.debug.AiEditorTestActivity
import org.fcitx.fcitx5.android.input.EditorIdentity
import org.fcitx.fcitx5.android.input.EditorSelection
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class HangulInitialAndDiscourseContinuationDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun siotPreeditShowsAndSelectsHangulCandidate() = runBlocking {
        assertInitialConsonantCandidate(initial = 'ㅅ', key = 't', caseId = "siot")
    }

    @Test
    fun giyeokPreeditShowsAndSelectsHangulCandidate() = runBlocking {
        assertInitialConsonantCandidate(initial = 'ㄱ', key = 'r', caseId = "giyeok")
    }

    @Test
    fun ieungPreeditShowsAndSelectsHangulCandidate() = runBlocking {
        assertInitialConsonantCandidate(initial = 'ㅇ', key = 'd', caseId = "ieung")
    }

    @Test
    fun connectiveDangneunSpaceShowsAndSelectsDiscourseContinuation() = runBlocking {
        assertDiscourseContinuation(
            caseId = "dangneun-space",
            physicalKeys = "qkq ajrdjTsmsep ",
            expectedContext = "밥 먹었는데 "
        )
    }

    @Test
    fun terminalWithoutSpaceShowsAndSelectsDiscourseContinuation() = runBlocking {
        assertDiscourseContinuation(
            caseId = "terminal-no-space",
            physicalKeys = "qkq ajrdjTdj.",
            expectedContext = "밥 먹었어."
        )
    }

    @Test
    fun terminalWithSpaceShowsAndSelectsDiscourseContinuation() = runBlocking {
        assertDiscourseContinuation(
            caseId = "terminal-space",
            physicalKeys = "qkq ajrdjTdj. ",
            expectedContext = "밥 먹었어. "
        )
    }

    private suspend fun assertInitialConsonantCandidate(initial: Char, key: Char, caseId: String) {
        var evidence = InitialEvidence(caseId = caseId)
        try {
            withFocusedHangulEditor { editor, ime, automation, uiDiagnostics ->
                val lastKeyStartedAt = sendPhysicalKeys(ime, key.toString())
                val preedit = readLivePreedit(ime)
                evidence = evidence.copy(preeditMatchesInitial = preedit.matches(initial))
                assertTrue("초성 후보 조회 전 실제 Hangul preedit가 요청 초성과 일치해야 한다.", preedit.matches(initial))
                val candidate = waitForInitialCandidate(ime, lastKeyStartedAt)
                evidence = evidence.copy(
                    candidateAvailable = true,
                    candidateAvailableMs = candidate.latencyMs,
                    candidateSource = candidate.source
                )
                val candidateTarget = candidateDisplayTarget(candidate.word)
                uiDiagnostics.setCandidate(candidateTarget.canonicalLabel, candidate.source)
                assertImmediate("초성 후보", candidate.latencyMs)
                val visibleCandidate = waitForVisibleCandidateNode(
                    automation,
                    candidateTarget,
                    candidate.source,
                    lastKeyStartedAt
                )
                val candidateNode = visibleCandidate.node
                evidence = evidence.copy(
                    candidateVisible = candidateNode.isVisibleToUser,
                    candidateVisibleMs = visibleCandidate.latencyMs
                )
                assertImmediate("초성 후보 UI", visibleCandidate.latencyMs)
                assertTrue("초성 후보는 실제 IME 후보 행에 표시되어야 한다.", candidateNode.isVisibleToUser)
                assertTrue("초성 후보 클릭은 성공해야 한다.", candidateNode.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val editorText = waitForSelectedHangulWord(editor, candidate.word.text)
                evidence = evidence.copy(
                    candidateSelected = true,
                    committedWord = true,
                    selectedTextMatchesCandidate = editorText.trim() == candidate.word.text,
                    residualInitialAbsent = initial !in editorText,
                    duplicateInitialAbsent = !editorText.contains("$initial$initial")
                )
                assertEquals("초성 후보 선택은 선택한 완성 단어만 editor에 남겨야 한다.", candidate.word.text, editorText.trim())
                assertTrue("후보 선택 뒤에는 완성 한글 음절이 editor에 있어야 한다.", editorText.any { it in '가'..'힣' })
                assertFalse("후보 선택 뒤에는 호환 자모 초성이 남으면 안 된다.", editorText.any { it in COMPATIBILITY_JAMO })
                assertFalse("후보 선택 뒤에는 입력 초성이 중복되면 안 된다.", editorText.contains("$initial$initial"))
            }
        } catch (error: Throwable) {
            reportInitialEvidence(evidence)
            throw error
        }
        reportInitialEvidence(evidence)
    }

    private suspend fun assertDiscourseContinuation(
        caseId: String,
        physicalKeys: String,
        expectedContext: String
    ) {
        var evidence = DiscourseEvidence(caseId = caseId)
        try {
            withFocusedHangulEditor { editor, ime, automation, uiDiagnostics ->
                assertTrue(
                    "이어쓰기 후보는 오프라인 로컬 검증 중 네트워크 AI 입력을 허용하면 안 된다.",
                    !onMain { ime.allowsNetworkInputFeatures() }
                )
                val lastKeyStartedAt = sendPhysicalKeys(ime, physicalKeys)
                waitForEditorText(editor, expectedContext)
                val candidate = waitForDiscourseCandidate(ime, lastKeyStartedAt)
                val source = requireNotNull(candidate.candidate.metricsCandidate).source
                evidence = evidence.copy(
                    candidateAvailable = true,
                    candidateAvailableMs = candidate.latencyMs,
                    candidateSource = source,
                    appendContractPresent = candidate.candidate.appendSnapshot != null
                )
                val candidateTarget = candidateDisplayTarget(candidate.candidate.word)
                uiDiagnostics.setCandidate(candidateTarget.canonicalLabel, source)
                assertImmediate("이어쓰기 후보", candidate.latencyMs)
                assertEquals("discourse_continuation", source)
                val appendSnapshot = requireNotNull(candidate.candidate.appendSnapshot) {
                    "이어쓰기 후보는 현재 문맥과 연결된 append 계약을 보존해야 한다."
                }
                val expectedInsertion = requireNotNull(appendSnapshot.append.insertionFor(expectedContext)) {
                    "이어쓰기 후보는 실제 입력 문맥에 삽입 가능해야 한다."
                }
                val expectedFullText = expectedContext + expectedInsertion
                val visibleCandidate = waitForVisibleCandidateNode(
                    automation,
                    candidateTarget,
                    source,
                    lastKeyStartedAt
                )
                val candidateNode = visibleCandidate.node
                evidence = evidence.copy(
                    candidateVisible = candidateNode.isVisibleToUser,
                    candidateVisibleMs = visibleCandidate.latencyMs
                )
                assertImmediate("이어쓰기 후보 UI", visibleCandidate.latencyMs)
                assertTrue("이어쓰기 후보는 실제 IME 후보 행에 표시되어야 한다.", candidateNode.isVisibleToUser)
                assertTrue("이어쓰기 후보 클릭은 성공해야 한다.", candidateNode.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitForEditorText(editor, expectedFullText)
                val actual = onMain { editor.text.toString() }
                evidence = evidence.copy(
                    candidateSelected = true,
                    contextPreserved = actual.startsWith(expectedContext),
                    duplicateSpaceAbsent = !actual.contains("  ")
                )
                assertEquals("이어쓰기 후보 선택은 append 계약대로 한 번만 반영해야 한다.", expectedFullText, actual)
                assertTrue("앞 문장은 후보 선택 뒤에도 보존되어야 한다.", actual.startsWith(expectedContext))
                assertFalse("이어쓰기 후보 선택은 공백을 중복하면 안 된다.", actual.contains("  "))
            }
        } catch (error: Throwable) {
            reportDiscourseEvidence(evidence)
            throw error
        }
        reportDiscourseEvidence(evidence)
    }

    private suspend fun <T> withFocusedHangulEditor(
        block: suspend (EditText, FcitxInputMethodService, UiAutomation, CandidateUiDiagnostics) -> T
    ): T {
        val prefs = AppPrefs.getInstance().advanced
        val originalOfflineMode = prefs.offlineMode.getValue()
        val originalBufferedHangulInput = prefs.bufferedHangulInput.getValue()
        var activity: AiEditorTestActivity? = null
        var activeIme: FcitxInputMethodService? = null
        var engineState: EngineState? = null
        var automationState: UiAutomationState? = null
        var editor: EditText? = null
        var primaryFailure: Throwable? = null
        val uiDiagnostics = CandidateUiDiagnostics()
        try {
            prefs.offlineMode.setValue(true)
            prefs.bufferedHangulInput.setValue(false)
            val currentActivity = launchActivity()
            activity = currentActivity
            val selectedEditor = selectNormalAndClear(currentActivity)
            editor = selectedEditor
            requestEditorFocusAndIme(currentActivity, selectedEditor)
            val ime = waitForCurrentEditor(editorTarget(selectedEditor))
            activeIme = ime
            engineState = captureEngineState(ime)
            activateHangul(ime)
            assertTrue("debug editor는 이어쓰기 후보를 위한 텍스트 검사를 허용해야 한다.", onMain { ime.allowsTextInspectionFeatures() })
            val currentAutomationState = configureUiAutomation()
            automationState = currentAutomationState
            return block(selectedEditor, ime, currentAutomationState.automation, uiDiagnostics)
        } catch (error: Throwable) {
            primaryFailure = error
            if (activity != null && activeIme != null && automationState != null && editor != null) {
                try {
                    captureCandidateUiFailure(
                        activity = requireNotNull(activity),
                        editor = requireNotNull(editor),
                        ime = requireNotNull(activeIme),
                        automation = requireNotNull(automationState).automation,
                        diagnostics = uiDiagnostics,
                        originalFailure = error
                    )
                } catch (captureError: Throwable) {
                    error.addSuppressed(captureError)
                }
            }
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            if (engineState != null) {
                try {
                    restoreEngineState(requireNotNull(activeIme), engineState)
                } catch (error: Throwable) {
                    cleanupFailure = error
                }
            }
            if (activity != null) {
                try {
                    onMain { requireNotNull(activity).finish() }
                } catch (error: Throwable) {
                    cleanupFailure = appendCleanupFailure(cleanupFailure, error)
                }
            }
            if (automationState != null) {
                try {
                    restoreUiAutomationState(automationState)
                } catch (error: Throwable) {
                    cleanupFailure = appendCleanupFailure(cleanupFailure, error)
                }
            }
            try {
                prefs.bufferedHangulInput.setValue(originalBufferedHangulInput)
                prefs.offlineMode.setValue(originalOfflineMode)
            } catch (error: Throwable) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, error)
            }
            cleanupFailure?.let { cleanup ->
                primaryFailure?.addSuppressed(cleanup) ?: throw cleanup
            }
        }
    }

    private suspend fun captureEngineState(ime: FcitxInputMethodService): EngineState = postFcitxCall(ime) {
        EngineState(
            enabledIme = enabledIme().map { it.uniqueName },
            currentIme = currentIme().uniqueName
        )
    }

    private suspend fun activateHangul(ime: FcitxInputMethodService) {
        postFcitxCall(ime) {
            check(availableIme().any { it.uniqueName == HANGUL_IME }) {
                "Bundled Hangul IME is unavailable."
            }
            val enabled = enabledIme().map { it.uniqueName }
            setEnabledIme((enabled + HANGUL_IME).distinct().toTypedArray())
            activateIme(HANGUL_IME)
            check(currentIme().uniqueName == HANGUL_IME) {
                "Bundled Hangul IME did not become active."
            }
        }
    }

    private suspend fun restoreEngineState(ime: FcitxInputMethodService, state: EngineState) {
        postFcitxCall(ime) {
            reset()
            setEnabledIme(state.enabledIme.toTypedArray())
            if (state.currentIme in state.enabledIme) activateIme(state.currentIme)
        }
    }

    private suspend fun sendPhysicalKeys(ime: FcitxInputMethodService, keys: String): Long {
        var lastKeyStartedAt = 0L
        keys.forEachIndexed { index, key ->
            if (index == keys.lastIndex) lastKeyStartedAt = SystemClock.elapsedRealtime()
            postFcitxCall(ime) {
                if (key == ' ') {
                    sendKey(KeySym(FcitxKeyMapping.FcitxKey_space), KeyStates.Virtual)
                } else {
                    sendKey(
                        key.toString(),
                        KeyStates.Virtual.states,
                        ScancodeMapping.charToScancode(key),
                        false,
                        -1
                    )
                }
            }
            instrumentation.waitForIdleSync()
        }
        return lastKeyStartedAt
    }

    private suspend fun waitForInitialCandidate(
        ime: FcitxInputMethodService,
        startedAt: Long
    ): InitialCandidate {
        val deadline = SystemClock.elapsedRealtime() + CANDIDATE_READY_TIMEOUT_MS
        var latestSources = emptyList<String>()
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = onMain { ime.getContextualCandidateSnapshot() }
            snapshot.words.firstOrNull {
                it.metricsCandidate?.source in INITIAL_CANDIDATE_SOURCES && isCompletedHangulWord(it.word)
            }?.let { candidate ->
                return InitialCandidate(
                    word = candidate.word,
                    source = requireNotNull(candidate.metricsCandidate).source,
                    latencyMs = SystemClock.elapsedRealtime() - startedAt
                )
            }
            latestSources = snapshot.words.mapNotNull { it.metricsCandidate?.source }.distinct()
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("Hangul engine preedit did not expose a contextual completed-word candidate. sources=$latestSources")
    }

    private fun isCompletedHangulWord(candidate: CandidateWord): Boolean =
        candidate.text.isNotBlank() &&
            candidate.text.any { it in '가'..'힣' } &&
            candidate.text.none { it in COMPATIBILITY_JAMO }

    private suspend fun waitForDiscourseCandidate(
        ime: FcitxInputMethodService,
        startedAt: Long
    ): DiscourseCandidate {
        val deadline = SystemClock.elapsedRealtime() + CANDIDATE_READY_TIMEOUT_MS
        var latestSources = emptyList<String>()
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = onMain { ime.getContextualCandidateSnapshot() }
            (snapshot.words + snapshot.sentences).firstOrNull {
                it.metricsCandidate?.source == DISCOURSE_SOURCE
            }?.let { candidate ->
                return DiscourseCandidate(candidate, SystemClock.elapsedRealtime() - startedAt)
            }
            latestSources = (snapshot.words + snapshot.sentences)
                .mapNotNull { it.metricsCandidate?.source }
                .distinct()
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("Local discourse continuation did not appear within $CANDIDATE_READY_TIMEOUT_MS ms. sources=$latestSources")
    }

    private fun waitForVisibleCandidateNode(
        automation: UiAutomation,
        candidateTarget: CandidateDisplayTarget,
        candidateSource: String,
        startedAt: Long
    ): VisibleCandidate {
        val deadline = SystemClock.elapsedRealtime() + CANDIDATE_READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            automation.windows
                .asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .mapNotNull { it.root }
                .mapNotNull { findVisibleCandidateNode(it, candidateTarget) }
                .firstOrNull()
                ?.let { return VisibleCandidate(it, SystemClock.elapsedRealtime() - startedAt) }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError(
            "IME candidate UI did not show candidateText=${candidateTarget.canonicalLabel} candidateSource=$candidateSource."
        )
    }

    private fun findVisibleCandidateNode(
        node: AccessibilityNodeInfo,
        candidateTarget: CandidateDisplayTarget
    ): AccessibilityNodeInfo? {
        if (node.text?.toString() == candidateTarget.visibleText) {
            findClickableAncestor(node)?.let { candidate ->
                val bounds = Rect()
                candidate.getBoundsInScreen(bounds)
                if (
                    candidate.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0 &&
                    (candidateTarget.badgeText == null || hasExactTextDescendant(candidate, candidateTarget.badgeText))
                ) return candidate
            }
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                findVisibleCandidateNode(child, candidateTarget)?.let { return it }
            }
        }
        return null
    }

    private fun hasExactTextDescendant(node: AccessibilityNodeInfo, expectedText: String): Boolean {
        if (node.text?.toString() == expectedText) return true
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                if (hasExactTextDescendant(child, expectedText)) return true
            }
        }
        return false
    }

    private fun candidateDisplayTarget(candidate: CandidateWord): CandidateDisplayTarget {
        val commentIsVisible = candidate.comment.isNotBlank() &&
            candidate.comment != "추천" &&
            !candidate.comment.contains("추천") &&
            !candidate.comment.contains("🌐")
        val badgeIcon = CandidateItemUi.resolveBadgeIcon(candidate.comment)
        return if (commentIsVisible && badgeIcon.isNotEmpty()) {
            CandidateDisplayTarget(
                visibleText = candidate.text,
                badgeText = badgeIcon,
                canonicalLabel = candidate.textWithComment()
            )
        } else {
            CandidateDisplayTarget(
                visibleText = if (commentIsVisible) candidate.textWithComment() else candidate.text,
                badgeText = null,
                canonicalLabel = candidate.textWithComment()
            )
        }
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun waitForSelectedHangulWord(editor: EditText, candidateText: String): String {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val text = onMain { editor.text.toString() }
            if (text.trim() == candidateText) return text
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return onMain { editor.text.toString() }
    }

    private fun waitForEditorText(editor: EditText, expected: String) {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain { editor.text.toString() } == expected) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        assertEquals(expected, onMain { editor.text.toString() })
    }

    private suspend fun readLivePreedit(ime: FcitxInputMethodService): LivePreedit = postFcitxCall(ime) {
        LivePreedit(
            engine = inputPanelCached.preedit.toString(),
            client = clientPreeditCached.toString()
        )
    }

    private fun configureUiAutomation(): UiAutomationState {
        val automation = instrumentation.uiAutomation
        val info = requireNotNull(automation.serviceInfo) { "UiAutomation accessibility service info is unavailable." }
        val originalFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        return UiAutomationState(automation, originalFlags)
    }

    private fun restoreUiAutomationState(state: UiAutomationState) {
        val info = requireNotNull(state.automation.serviceInfo) {
            "UiAutomation accessibility service info is unavailable during cleanup."
        }
        info.flags = state.originalFlags
        state.automation.serviceInfo = info
    }

    private fun captureCandidateUiFailure(
        activity: AiEditorTestActivity,
        editor: EditText,
        ime: FcitxInputMethodService,
        automation: UiAutomation,
        diagnostics: CandidateUiDiagnostics,
        originalFailure: Throwable
    ) {
        val capture = CandidateUiFailureCapture(
            expectedCandidateText = diagnostics.expectedCandidateText,
            expectedCandidateSource = diagnostics.expectedCandidateSource,
            originalFailureClass = originalFailure.javaClass.name
        )
        try {
            val editorState = onMain {
                EditorVisibilityState(
                    focused = editor.hasFocus(),
                    shown = editor.isShown,
                    activityWindowFocused = activity.hasWindowFocus(),
                    activityFinishing = activity.isFinishing,
                    activityDestroyed = activity.isDestroyed
                )
            }
            capture.editorFocused = editorState.focused
            capture.editorShown = editorState.shown
            capture.activityWindowFocused = editorState.activityWindowFocused
            capture.activityFinishing = editorState.activityFinishing
            capture.activityDestroyed = editorState.activityDestroyed
            check(editorState.focused && editorState.shown && editorState.activityWindowFocused) {
                "Raw candidate UI capture requires the public synthetic editor to be focused and visible."
            }

            val inputViewState = onMain {
                val inputView = ime.javaClass.getDeclaredField("inputView").apply { isAccessible = true }.get(ime) as? View
                InputViewVisibilityState(
                    present = inputView != null,
                    shown = inputView?.isShown == true,
                    visibility = inputView?.visibility,
                    bounds = inputView?.let(::screenBounds)
                )
            }
            capture.inputViewPresent = inputViewState.present
            capture.inputViewShown = inputViewState.shown
            capture.inputViewVisibility = inputViewState.visibility
            capture.inputViewBounds = inputViewState.bounds

            val directory = File(
                requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
                "korean-boundary-preedit"
            )
            check(directory.exists() || directory.mkdirs()) {
                "Candidate UI diagnostic directory could not be created."
            }
            val captureStem = "candidate-ui-${SystemClock.elapsedRealtime()}"
            val screenshot = requireNotNull(automation.takeScreenshot()) {
                "UiAutomation.takeScreenshot returned null for the focused public synthetic editor."
            }
            try {
                val screenshotFile = File(directory, "$captureStem-raw.png")
                FileOutputStream(screenshotFile).use { output ->
                    check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "Candidate UI raw screenshot PNG compression failed."
                    }
                }
                capture.rawScreenshotPath = screenshotFile.absolutePath
            } finally {
                screenshot.recycle()
            }

            val imeWindows = automation.windows.filter {
                it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }
            capture.imeWindowCount = imeWindows.size
            val windows = JSONArray()
            imeWindows.forEach { window ->
                val root = window.root
                val windowJson = JSONObject()
                    .put("layer", window.layer)
                    .put("title", window.title?.toString())
                    .put("rootPresent", root != null)
                if (root != null) {
                    val nodes = JSONArray()
                    appendImeNodeDiagnostics(root, nodes, NodeDiagnosticCounter())
                    windowJson.put("nodes", nodes)
                }
                windows.put(windowJson)
            }
            val diagnosticFile = File(directory, "$captureStem-ime.json")
            FileOutputStream(diagnosticFile).bufferedWriter().use { output ->
                output.write(
                    JSONObject()
                        .put("expectedCandidateText", capture.expectedCandidateText)
                        .put("expectedCandidateSource", capture.expectedCandidateSource)
                        .put("originalFailureClass", capture.originalFailureClass)
                        .put("editorFocused", capture.editorFocused)
                        .put("editorShown", capture.editorShown)
                        .put("activityWindowFocused", capture.activityWindowFocused)
                        .put("activityFinishing", capture.activityFinishing)
                        .put("activityDestroyed", capture.activityDestroyed)
                        .put("inputViewPresent", capture.inputViewPresent)
                        .put("inputViewShown", capture.inputViewShown)
                        .put("inputViewVisibility", capture.inputViewVisibility)
                        .put("inputViewBounds", capture.inputViewBounds)
                        .put("imeWindowCount", capture.imeWindowCount)
                        .put("windows", windows)
                        .toString()
                )
            }
            capture.imeNodePath = diagnosticFile.absolutePath
        } catch (error: Throwable) {
            capture.captureError = "${error.javaClass.name}: ${error.message}"
            throw error
        } finally {
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("candidateUiExpectedText", capture.expectedCandidateText)
                    putString("candidateUiExpectedSource", capture.expectedCandidateSource)
                    putString("candidateUiOriginalFailureClass", capture.originalFailureClass)
                    putBoolean("candidateUiEditorFocused", capture.editorFocused)
                    putBoolean("candidateUiEditorShown", capture.editorShown)
                    putBoolean("candidateUiActivityWindowFocused", capture.activityWindowFocused)
                    putBoolean("candidateUiInputViewPresent", capture.inputViewPresent)
                    putBoolean("candidateUiInputViewShown", capture.inputViewShown)
                    putInt("candidateUiImeWindowCount", capture.imeWindowCount)
                    putString("candidateUiRawScreenshotPath", capture.rawScreenshotPath)
                    putString("candidateUiImeNodePath", capture.imeNodePath)
                    putString("candidateUiCaptureError", capture.captureError)
                }
            )
        }
    }

    private fun appendImeNodeDiagnostics(
        node: AccessibilityNodeInfo,
        destination: JSONArray,
        counter: NodeDiagnosticCounter
    ) {
        if (counter.count >= MAX_IME_DIAGNOSTIC_NODES) return
        counter.count++
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        destination.put(
            JSONObject()
                .put("text", node.text?.toString())
                .put("contentDescription", node.contentDescription?.toString())
                .put("bounds", bounds.toShortString())
                .put("visible", node.isVisibleToUser)
                .put("clickable", node.isClickable)
        )
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                appendImeNodeDiagnostics(child, destination, counter)
            }
        }
    }

    private fun screenBounds(view: View): String {
        val bounds = Rect()
        view.getGlobalVisibleRect(bounds)
        return bounds.toShortString()
    }

    private fun launchActivity(): AiEditorTestActivity = instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, AiEditorTestActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    ) as AiEditorTestActivity

    private fun selectNormalAndClear(activity: AiEditorTestActivity): EditText = onMain {
        requireNotNull(activity.window.decorView.findByContentDescription("Select normal complete-editor mode"))
            .performClick()
        requireNotNull(activity.window.decorView.findByContentDescription("Clear text")).performClick()
        requireNotNull(activity.window.decorView.findByContentDescription("AI E2E normal editor")) as EditText
    }

    private fun requestEditorFocusAndIme(activity: AiEditorTestActivity, editor: EditText) = onMain {
        editor.requestFocus()
        assertTrue("synthetic editor must hold focus before IME candidate checks.", editor.hasFocus())
        val inputMethodManager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun waitForCurrentEditor(target: EditorTarget): FcitxInputMethodService {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = currentEditorState(target)
            if (state.matches) return requireNotNull(state.ime)
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("Saegul IME did not connect to the synthetic editor. expectedTarget=$target")
    }

    private fun currentEditorState(target: EditorTarget): CurrentEditorState = onMain {
        val ime = FcitxInputMethodService.activeInstance
        val matches = ime != null && ime.matchesCurrentEditor(
            EditorIdentity(target.packageName, target.fieldId, target.inputType),
            EditorSelection(target.selectionStart, target.selectionEnd),
            expectedInputSessionEpoch = ime.currentInputSessionEpoch
        )
        CurrentEditorState(ime, matches)
    }

    private fun editorTarget(editor: EditText): EditorTarget = onMain {
        EditorTarget(
            packageName = editor.context.packageName,
            fieldId = editor.id,
            inputType = editor.inputType,
            selectionStart = editor.selectionStart,
            selectionEnd = editor.selectionEnd
        )
    }

    private fun <T : Any> onMain(block: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                result = block()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
        return requireNotNull(result)
    }

    private suspend fun <T> postFcitxCall(
        ime: FcitxInputMethodService,
        block: suspend FcitxAPI.() -> T
    ): T {
        val result = CompletableDeferred<T>()
        val job = ime.postFcitxJob {
            try {
                result.complete(block())
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        return try {
            withTimeout(READY_TIMEOUT_MS) { result.await() }
        } finally {
            if (!result.isCompleted) job.cancel()
        }
    }

    private fun appendCleanupFailure(current: Throwable?, next: Throwable): Throwable {
        if (current == null) return next
        current.addSuppressed(next)
        return current
    }

    private fun assertImmediate(phase: String, elapsedMs: Long) {
        assertTrue("$phase 표시가 ${IMMEDIATE_CANDIDATE_TARGET_MS}ms 안에 완료되어야 한다. elapsedMs=$elapsedMs", elapsedMs <= IMMEDIATE_CANDIDATE_TARGET_MS)
    }

    private fun reportInitialEvidence(evidence: InitialEvidence) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("testCase", evidence.caseId)
                putBoolean("preeditMatchesInitial", evidence.preeditMatchesInitial)
                putBoolean("candidateAvailable", evidence.candidateAvailable)
                putLong("candidateAvailableMs", evidence.candidateAvailableMs)
                putString("candidateSource", evidence.candidateSource)
                putBoolean("candidateVisible", evidence.candidateVisible)
                putLong("candidateVisibleMs", evidence.candidateVisibleMs)
                putBoolean("candidateSelected", evidence.candidateSelected)
                putBoolean("committedWord", evidence.committedWord)
                putBoolean("selectedTextMatchesCandidate", evidence.selectedTextMatchesCandidate)
                putBoolean("residualInitialAbsent", evidence.residualInitialAbsent)
                putBoolean("duplicateInitialAbsent", evidence.duplicateInitialAbsent)
            }
        )
    }

    private fun reportDiscourseEvidence(evidence: DiscourseEvidence) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("testCase", evidence.caseId)
                putBoolean("candidateAvailable", evidence.candidateAvailable)
                putLong("candidateAvailableMs", evidence.candidateAvailableMs)
                putString("candidateSource", evidence.candidateSource)
                putBoolean("appendContractPresent", evidence.appendContractPresent)
                putBoolean("candidateVisible", evidence.candidateVisible)
                putLong("candidateVisibleMs", evidence.candidateVisibleMs)
                putBoolean("candidateSelected", evidence.candidateSelected)
                putBoolean("contextPreserved", evidence.contextPreserved)
                putBoolean("duplicateSpaceAbsent", evidence.duplicateSpaceAbsent)
            }
        )
    }

    private fun View.findByContentDescription(description: String): View? {
        if (contentDescription?.toString() == description) return this
        return (this as? ViewGroup)?.children
            ?.firstNotNullOfOrNull { child -> child.findByContentDescription(description) }
    }

    private val ViewGroup.children: Sequence<View>
        get() = sequence {
            for (index in 0 until childCount) yield(getChildAt(index))
        }

    private data class EngineState(
        val enabledIme: List<String>,
        val currentIme: String
    )

    private data class EditorTarget(
        val packageName: String,
        val fieldId: Int,
        val inputType: Int,
        val selectionStart: Int,
        val selectionEnd: Int
    )

    private data class CurrentEditorState(
        val ime: FcitxInputMethodService?,
        val matches: Boolean
    )

    private data class InitialCandidate(
        val word: CandidateWord,
        val source: String,
        val latencyMs: Long
    )

    private data class DiscourseCandidate(
        val candidate: FcitxInputMethodService.ContextualCandidate,
        val latencyMs: Long
    )

    private data class VisibleCandidate(
        val node: AccessibilityNodeInfo,
        val latencyMs: Long
    )

    private data class CandidateDisplayTarget(
        val visibleText: String,
        val badgeText: String?,
        val canonicalLabel: String
    )

    private data class LivePreedit(
        val engine: String,
        val client: String
    ) {
        fun matches(initial: Char): Boolean = engine == initial.toString() || client == initial.toString()
    }

    private data class UiAutomationState(
        val automation: UiAutomation,
        val originalFlags: Int
    )

    private class CandidateUiDiagnostics {
        var expectedCandidateText: String? = null
        var expectedCandidateSource: String? = null

        fun setCandidate(text: String, source: String) {
            expectedCandidateText = text
            expectedCandidateSource = source
        }
    }

    private data class EditorVisibilityState(
        val focused: Boolean,
        val shown: Boolean,
        val activityWindowFocused: Boolean,
        val activityFinishing: Boolean,
        val activityDestroyed: Boolean
    )

    private data class InputViewVisibilityState(
        val present: Boolean,
        val shown: Boolean,
        val visibility: Int?,
        val bounds: String?
    )

    private data class NodeDiagnosticCounter(var count: Int = 0)

    private data class CandidateUiFailureCapture(
        val expectedCandidateText: String?,
        val expectedCandidateSource: String?,
        val originalFailureClass: String,
        var editorFocused: Boolean = false,
        var editorShown: Boolean = false,
        var activityWindowFocused: Boolean = false,
        var activityFinishing: Boolean = false,
        var activityDestroyed: Boolean = false,
        var inputViewPresent: Boolean = false,
        var inputViewShown: Boolean = false,
        var inputViewVisibility: Int? = null,
        var inputViewBounds: String? = null,
        var imeWindowCount: Int = 0,
        var rawScreenshotPath: String? = null,
        var imeNodePath: String? = null,
        var captureError: String? = null
    )

    private data class InitialEvidence(
        val caseId: String,
        val preeditMatchesInitial: Boolean = false,
        val candidateAvailable: Boolean = false,
        val candidateAvailableMs: Long = -1L,
        val candidateSource: String? = null,
        val candidateVisible: Boolean = false,
        val candidateVisibleMs: Long = -1L,
        val candidateSelected: Boolean = false,
        val committedWord: Boolean = false,
        val selectedTextMatchesCandidate: Boolean = false,
        val residualInitialAbsent: Boolean = false,
        val duplicateInitialAbsent: Boolean = false
    )

    private data class DiscourseEvidence(
        val caseId: String,
        val candidateAvailable: Boolean = false,
        val candidateAvailableMs: Long = -1L,
        val candidateSource: String? = null,
        val appendContractPresent: Boolean = false,
        val candidateVisible: Boolean = false,
        val candidateVisibleMs: Long = -1L,
        val candidateSelected: Boolean = false,
        val contextPreserved: Boolean = false,
        val duplicateSpaceAbsent: Boolean = false
    )

    private companion object {
        const val HANGUL_IME = "hangul"
        const val DISCOURSE_SOURCE = "discourse_continuation"
        const val COMPATIBILITY_JAMO = "ㄱㄲㄳㄴㄵㄶㄷㄸㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅃㅄㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
        val INITIAL_CANDIDATE_SOURCES = setOf("personal_ngram", "base_vocab", "base_lexicon")
        const val IMMEDIATE_CANDIDATE_TARGET_MS = 1_000L
        const val READY_TIMEOUT_MS = 10_000L
        const val CANDIDATE_READY_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 100L
        const val MAX_IME_DIAGNOSTIC_NODES = 160
    }
}
