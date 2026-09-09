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
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.core.EditorPrivacyPolicy
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.debug.AiEditorTestActivity
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.ai.AiPrediction
import org.fcitx.fcitx5.android.input.ai.ContextualPredictionInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class ImmediateSentenceLatencyDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun sentencePackCandidatesAppearWithinOneSecondAndAppendExactlyOffline() {
        val priorOfflineMode = AppPrefs.getInstance().advanced.offlineMode.getValue()
        val restoreOfflineMode = InstrumentationRegistry.getArguments()
            .getString(RESTORE_OFFLINE_MODE_ARGUMENT)
            ?.toBooleanStrict()
            ?: priorOfflineMode
        var activity: AiEditorTestActivity? = null
        val results = mutableListOf<LatencyResult>()
        val tapMode = InstrumentationRegistry.getArguments().getString(TAP_MODE_ARGUMENT)
            .takeIf { it == TOUCH_TAP_MODE } ?: ACCESSIBILITY_TAP_MODE
        val candidateSource = when (val requestedSource =
            InstrumentationRegistry.getArguments().getString(CANDIDATE_SOURCE_ARGUMENT)
        ) {
            null -> SENTENCE_PACK_SOURCE
            SENTENCE_PACK_SOURCE, GENERATED_SOURCE -> requestedSource
            else -> error("candidateSource는 sentence_pack 또는 ondevice_generated만 허용합니다: $requestedSource")
        }
        val requestedLatencyCase = InstrumentationRegistry.getArguments().getString(LATENCY_CASE_ARGUMENT)
        val latencyCase = requestedLatencyCase?.toIntOrNull()
        val prefixes = if (candidateSource == GENERATED_SOURCE) GENERATED_PREFIXES else LATENCY_PREFIXES
        require(requestedLatencyCase == null || latencyCase in prefixes.indices.map { it + 1 }) {
            "latencyCase는 1..${prefixes.size}이어야 합니다."
        }
        try {
            AppPrefs.getInstance().advanced.offlineMode.setValue(true)
            if (candidateSource == SENTENCE_PACK_SOURCE) {
                waitForBuiltinSentencePack()
            } else {
                val generatedBank = FcitxApplication.getInstance().generatedSentenceBank
                generatedBank.load()
                val generatedCount = generatedBank.sentenceCount
                assertTrue("저장된 on-device generated 재료가 없습니다.", generatedCount > 0)
            }
            val currentActivity = launchActivity()
            activity = currentActivity
            val automation = configureUiAutomation()

            val caseIndices = latencyCase?.let { listOf(it - 1) } ?: prefixes.indices
            caseIndices.forEach { index ->
                val prefix = prefixes[index]
                val editor = selectNormalAndClear(currentActivity)
                requestEditorFocusAndIme(currentActivity, editor)
                val ime = waitForCurrentEditor(editorTarget(editor))
                val readyDeadline = SystemClock.elapsedRealtime() + IME_READY_TIMEOUT_MS
                waitForEditorText(editor, "", readyDeadline)
                waitForServiceSelection(ime, editor, "", readyDeadline)
                assertTrue("오프라인 문장팩 검증 중 네트워크 AI 입력은 차단되어야 한다.", onMain {
                    !ime.allowsAiInputFeatures()
                })
                assertTrue("일반 debug editor에서는 문장팩 텍스트 검사가 허용되어야 한다.", onMain {
                    ime.allowsTextInspectionFeatures()
                })
                val result = measureCase(
                    caseNumber = index + 1,
                    prefix = prefix,
                    editor = editor,
                    ime = ime,
                    automation = automation,
                    tapMode = tapMode,
                    candidateSource = candidateSource
                )
                results += result
                reportResult(result)
            }

            val failures = results.flatMap { result ->
                buildList {
                    val visibleCandidateMs = result.visibleCandidateMs
                    result.error?.let { add("case=${result.caseNumber}: $it") }
                    if (visibleCandidateMs == null) add("case=${result.caseNumber}: visible $candidateSource 후보 없음")
                    if (visibleCandidateMs != null && visibleCandidateMs > VISIBLE_LATENCY_LIMIT_MS) {
                        add("case=${result.caseNumber}: visible $candidateSource latency=${visibleCandidateMs}ms")
                    }
                    if (result.clickInsertionMatched != true) add("case=${result.caseNumber}: exact append 실패")
                }
            }
            assertTrue(failures.joinToString(separator = "\n"), failures.isEmpty())
        } finally {
            try {
                activity?.let { currentActivity -> onMain { currentActivity.finish() } }
            } finally {
                AppPrefs.getInstance().advanced.offlineMode.setValue(restoreOfflineMode)
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putBoolean(
                            "restoredOfflineMode",
                            AppPrefs.getInstance().advanced.offlineMode.getValue()
                        )
                    }
                )
            }
        }
    }

    private fun measureCase(
        caseNumber: Int,
        prefix: String,
        editor: EditText,
        ime: FcitxInputMethodService,
        automation: UiAutomation,
        tapMode: String,
        candidateSource: String
    ): LatencyResult {
        val expected = if (candidateSource == SENTENCE_PACK_SOURCE) {
            FcitxApplication.getInstance().sentencePacks.complete(prefix, CANDIDATE_LIMIT)
        } else {
            FcitxApplication.getInstance().generatedSentenceBank.complete(prefix, CANDIDATE_LIMIT)
        }
            .firstOrNull()

        val beforeText = onMain { editor.text.toString() }
        val startedAt = SystemClock.elapsedRealtime()
        val committed = onMain { ime.commitToEditor(prefix) }
        val commitReturnMs = SystemClock.elapsedRealtime() - startedAt
        val result = LatencyResult(
            caseNumber = caseNumber,
            commitReturnMs = commitReturnMs,
            stage = "editor_text",
            tapMode = tapMode
        )
        if (!committed) {
            result.error = "IME commitToEditor 실패"
            return result
        }
        if (expected == null) {
            result.error = "$candidateSource lookup 결과가 비어 있음"
            return result
        }

        val committedText = beforeText + prefix
        try {
            waitForEditorText(editor, committedText, startedAt + CASE_DEADLINE_MS)
            result.editorTextReadyMs = SystemClock.elapsedRealtime() - startedAt
            result.stage = "service_selection"
            waitForServiceSelection(ime, editor, committedText, startedAt + CASE_DEADLINE_MS)
            result.serviceSelectionReadyMs = SystemClock.elapsedRealtime() - startedAt
            result.stage = "snapshot"
            val candidate = waitForSnapshotCandidate(
                ime, prefix, expected.suffix, candidateSource, startedAt, result
            )
            result.stage = "visible"
            val firstVisible = waitForVisibleCandidateNode(
                automation = automation,
                ime = ime,
                suffix = expected.suffix,
                deadline = startedAt + CASE_DEADLINE_MS,
                startedAt = startedAt,
                result = result
            )
            result.visibleCandidateMs = SystemClock.elapsedRealtime() - startedAt
            result.candidateSource = candidate.metricsCandidate?.source
            if (result.candidateSource != candidateSource) {
                result.error = "snapshot 후보 source가 $candidateSource 아님"
                return result
            }
            val expectedInsertion = candidate.appendSnapshot?.append?.insertionFor(prefix)
            if (expectedInsertion == null) {
                result.error = "appendSnapshot insertionFor(prefix)가 null"
                return result
            }

            val currentText = onMain { editor.text.toString() }
            if (currentText != committedText) {
                result.error = "클릭 직전 입력이 변경됨"
                return result
            }
            result.captureAttempted = true
            result.capturePhase = "after_latency_before_click"
            result.captureLatencyExcluded = true
            captureCandidateCrop(
                firstVisible.live.textBounds,
                automation,
                "case-$caseNumber-visible-$candidateSource-text.png"
            ).also { capture ->
                result.capturePath = capture.path
                result.captureError = capture.error
            }
            result.stage = "click"
            val currentVisible = findVerifiedCandidate(automation, ime, expected.suffix, result)
            if (currentVisible == null) {
                result.error = "클릭 직전 동일 suffix의 refreshed IME 노드가 없음"
                return result
            }
            recordCandidateGeometry(currentVisible, result)
            if (!currentVisible.boundsMatch) {
                result.error = "클릭 직전 접근성/live 후보 좌표가 일치하지 않음"
                return result
            }
            reportResult(result, "immediateSentencePreTouchJson")
            val clicked = if (tapMode == TOUCH_TAP_MODE) {
                injectTouchTap(currentVisible.a11y.node, editor, committedText, result, automation)
            } else {
                currentVisible.a11y.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (!clicked) {
                if (result.error == null) result.error = "$candidateSource 후보 클릭 실패"
                return result
            }
            val expectedText = committedText + expectedInsertion
            result.stage = "append"
            waitForEditorText(editor, expectedText, startedAt + CASE_DEADLINE_MS)
            assertEquals(expectedText, onMain { editor.text.toString() })
            result.clickInsertionMatched = true
        } catch (error: Throwable) {
            result.error = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(MAX_ERROR_MESSAGE_CHARS)}"
        }
        return result
    }

    private fun waitForBuiltinSentencePack() {
        val repository = FcitxApplication.getInstance().sentencePacks
        repository.prepare()
        val deadline = SystemClock.elapsedRealtime() + REPOSITORY_READY_TIMEOUT_MS
        var latest = repository.status.value
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = repository.status.value
            if (!latest.isLoading && latest.builtinCount == BUILTIN_SENTENCE_COUNT && latest.error == null) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("기본 문장팩이 준비되지 않았습니다. builtinCount=${latest.builtinCount}, loading=${latest.isLoading}")
    }

    private fun waitForSnapshotCandidate(
        ime: FcitxInputMethodService,
        prefix: String,
        suffix: String,
        candidateSource: String,
        startedAt: Long,
        result: LatencyResult
    ): FcitxInputMethodService.ContextualCandidate {
        val deadline = startedAt + CASE_DEADLINE_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            result.snapshotPollCount += 1
            val snapshot = onMain { ime.getContextualCandidateSnapshot(sentenceLimit = CANDIDATE_LIMIT) }
            result.snapshotHadSentences = snapshot.sentences.isNotEmpty()
            result.snapshotSourceCounts = snapshot.sentences
                .groupingBy { it.metricsCandidate?.source ?: "unattributed" }
                .eachCount()
            snapshot.sentences.firstOrNull {
                it.metricsCandidate?.source == candidateSource && it.word.text == suffix
            }?.let { candidate ->
                result.snapshotCandidateMs = SystemClock.elapsedRealtime() - startedAt
                recordSnapshotDiagnostics(ime, prefix, result)
                return candidate
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        recordSnapshotDiagnostics(ime, prefix, result)
        recordTimeoutCacheDiagnostics(ime, prefix, result)
        throw AssertionError("deadline 안에 $candidateSource snapshot 후보가 나타나지 않았습니다.")
    }

    private fun recordSnapshotDiagnostics(
        ime: FcitxInputMethodService,
        prefix: String,
        result: LatencyResult
    ) {
        onMain {
            val editorInfo = ime.currentInputEditorInfo
            val capabilityFlags = ime.capabilityFlags
            val beforeCursor = ime.currentInputConnection?.getTextBeforeCursor(128, 0)?.toString().orEmpty()
            result.snapshotTextInspectionAllowed = ime.allowsTextInspectionFeatures()
            result.snapshotTextBeforeCursorMatchesPrefix = beforeCursor == prefix
            result.snapshotSelectionNotEmpty = ime.currentInputSelection.isNotEmpty()
            result.snapshotSentencePackCompletionCount = FcitxApplication.getInstance().sentencePacks
                .complete(beforeCursor, CANDIDATE_LIMIT)
                .size
            result.snapshotConversationalTextField = EditorPrivacyPolicy.isConversationalTextField(
                editorInfo,
                capabilityFlags
            )
            result.snapshotEmailField = EditorPrivacyPolicy.isEmailAddressField(editorInfo, capabilityFlags)
            result.snapshotPhoneField = EditorPrivacyPolicy.isPhoneField(editorInfo, capabilityFlags)
            result.snapshotNumericField = EditorPrivacyPolicy.isNumericField(editorInfo, capabilityFlags)
            result.snapshotUrlField = EditorPrivacyPolicy.isUrlField(editorInfo, capabilityFlags)
        }
    }

    private fun recordTimeoutCacheDiagnostics(
        ime: FcitxInputMethodService,
        prefix: String,
        result: LatencyResult
    ) {
        if (result.snapshotTextInspectionAllowed != true ||
            result.snapshotTextBeforeCursorMatchesPrefix != true
        ) {
            result.timeoutCacheDiagnosticError = "normal editor prefix gate failed"
            return
        }
        try {
            val beforeCursor = onMain {
                ime.currentInputConnection?.getTextBeforeCursor(128, 0)?.toString().orEmpty()
            }
            onMain {
                val bufferedPrefix = ime.bufferedHangulPrefix
                val composingText = readDeclaredField(ime, "composingText").toString()
                val resolved = ContextualPredictionInput.resolve(
                    beforeCursor,
                    bufferedPrefix + composingText.trim()
                )
                val rawFullContext = ContextualPredictionInput.rawFullContext(
                    resolved.stroke,
                    resolved.context
                )
                result.timeoutBufferedPrefixBlank = bufferedPrefix.isBlank()
                result.timeoutComposingTextBlank = composingText.isBlank()
                result.timeoutRawFullContextMatchesPrefix = rawFullContext == prefix
                result.timeoutRawContextPackLookupCount = FcitxApplication.getInstance().sentencePacks
                    .complete(rawFullContext, CANDIDATE_LIMIT)
                    .size

                val cache = readDeclaredField(ime, "contextualResultCache")
                result.timeoutCacheExists = cache != null
                if (cache != null) {
                    val cacheKey = requireNotNull(readDeclaredField(cache, "key")) {
                        "contextualResultCache key was null"
                    }
                    val cachePredictions = readDeclaredField(cache, "predictions") as List<*>
                    result.timeoutCachePredictionSourceCounts = cachePredictions
                        .map { it as AiPrediction }
                        .groupingBy { it.source }
                        .eachCount()
                    val cacheStroke = readDeclaredField(cacheKey, "stroke") as String
                    val cacheContext = readDeclaredField(cacheKey, "context") as String
                    result.timeoutCacheKeyRawFullContextMatchesPrefix =
                        ContextualPredictionInput.rawFullContext(cacheStroke, cacheContext) == prefix
                }
            }
        } catch (error: NoSuchFieldException) {
            result.timeoutCacheDiagnosticError = "${error.javaClass.simpleName}: ${error.message}"
        }
    }

    private fun readDeclaredField(instance: Any, fieldName: String): Any? =
        instance.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.get(instance)

    private fun waitForVisibleCandidateNode(
        automation: UiAutomation,
        ime: FcitxInputMethodService,
        suffix: String,
        deadline: Long,
        startedAt: Long,
        result: LatencyResult
    ): VerifiedCandidate {
        while (SystemClock.elapsedRealtime() < deadline) {
            findRefreshedA11yCandidate(automation, suffix) { refreshed ->
                result.a11yNodeRefreshSucceeded = refreshed
            }?.let { a11y ->
                result.accessibilityCandidateMs = result.accessibilityCandidateMs
                    ?: SystemClock.elapsedRealtime() - startedAt
                result.a11yBounds = Rect(a11y.bounds)
                findLiveCandidate(ime, suffix, result)?.let { live ->
                    val candidate = VerifiedCandidate(
                        a11y = a11y,
                        live = live,
                        boundsMatch = a11y.bounds == live.clickableBounds &&
                            live.clickableVisibleUnclipped
                    )
                    recordCandidateGeometry(candidate, result)
                    if (candidate.boundsMatch) return candidate
                }
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("deadline 안에 접근성/live 좌표가 일치하는 sentence_pack 후보가 IME 후보 행에 보이지 않았습니다.")
    }

    private fun findVerifiedCandidate(
        automation: UiAutomation,
        ime: FcitxInputMethodService,
        suffix: String,
        result: LatencyResult
    ): VerifiedCandidate? {
        val a11y = findRefreshedA11yCandidate(automation, suffix) ?: return null
        val live = findLiveCandidate(ime, suffix, result) ?: return null
        return VerifiedCandidate(
            a11y = a11y,
            live = live,
            boundsMatch = a11y.bounds == live.clickableBounds && live.clickableVisibleUnclipped
        )
    }

    private fun findRefreshedA11yCandidate(
        automation: UiAutomation,
        suffix: String,
        onRefresh: ((Boolean) -> Unit)? = null
    ): A11yCandidateGeometry? {
        val node = findVisibleCandidateNodeFromCurrentIme(automation, suffix) ?: return null
        val refreshed = node.refresh()
        onRefresh?.invoke(refreshed)
        if (!refreshed) return null
        val bounds = Rect().also(node::getBoundsInScreen)
        if (!node.isVisibleToUser || bounds.width() <= 0 || bounds.height() <= 0) return null
        return A11yCandidateGeometry(node, bounds)
    }

    private fun recordCandidateGeometry(candidate: VerifiedCandidate, result: LatencyResult) {
        result.a11yNodeRefreshSucceeded = true
        result.a11yBounds = Rect(candidate.a11y.bounds)
        result.liveTextBounds = Rect(candidate.live.textBounds)
        result.liveClickableBounds = Rect(candidate.live.clickableBounds)
        result.liveClickableLocation = candidate.live.clickableLocation.copyOf()
        result.liveClickableVisibleUnclipped = candidate.live.clickableVisibleUnclipped
        result.imeBounds = candidate.live.imeBounds?.let(::Rect)
        result.liveRootViewIsDecor = candidate.live.rootViewIsDecor
        result.a11yLiveBoundsMatch = candidate.boundsMatch
    }

    private fun findVisibleCandidateNodeFromCurrentIme(
        automation: UiAutomation,
        suffix: String
    ): AccessibilityNodeInfo? = automation.windows
        .asSequence()
        .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        .mapNotNull { it.root }
        .mapNotNull { root -> findVisibleCandidateNode(root, suffix) }
        .firstOrNull()

    private fun findVisibleCandidateNode(
        node: AccessibilityNodeInfo,
        suffix: String
    ): AccessibilityNodeInfo? {
        if (node.text?.toString() == suffix) {
            findClickableAncestor(node)?.let { candidate ->
                val bounds = Rect().also(candidate::getBoundsInScreen)
                if (candidate.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0) return candidate
            }
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                findVisibleCandidateNode(child, suffix)?.let { return it }
            }
        }
        return null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun findLiveCandidate(
        ime: FcitxInputMethodService,
        suffix: String,
        result: LatencyResult
    ): LiveCandidateGeometry? = onMainNullable {
        val decor = ime.window.window?.decorView ?: return@onMainNullable null
        val textView = decor.findTextViewWithText(suffix) ?: return@onMainNullable null
        val clickable = textView.findClickableViewAncestor() ?: return@onMainNullable null
        val root = textView.rootView
        if (root !== decor) {
            result.liveRootViewIsDecor = false
            result.liveGeometryError = "IME decorView is not the candidate TextView rootView"
            return@onMainNullable null
        }
        result.liveRootViewIsDecor = true
        val textBoundsInRoot = Rect()
        val clickableBoundsInRoot = Rect()
        val imeBoundsInRoot = Rect()
        val rootLocation = IntArray(2)
        val location = IntArray(2)
        if (!textView.isShown || textView.alpha <= 0f || textView.isLayoutRequested ||
            !textView.getGlobalVisibleRect(textBoundsInRoot) || !clickable.isShown ||
            clickable.alpha <= 0f || clickable.width <= 0 || clickable.height <= 0 ||
            clickable.isLayoutRequested || !clickable.getGlobalVisibleRect(clickableBoundsInRoot)
        ) return@onMainNullable null
        root.getLocationOnScreen(rootLocation)
        clickable.getLocationOnScreen(location)
        val textBounds = textBoundsInRoot.toScreenRect(rootLocation)
        val clickableBounds = clickableBoundsInRoot.toScreenRect(rootLocation)
        val unclippedClickableBounds = Rect(
            location[0],
            location[1],
            location[0] + clickable.width,
            location[1] + clickable.height
        )
        LiveCandidateGeometry(
            textBounds = textBounds,
            clickableBounds = clickableBounds,
            clickableLocation = location,
            clickableVisibleUnclipped = clickableBounds == unclippedClickableBounds,
            imeBounds = imeBoundsInRoot.takeIf { decor.getGlobalVisibleRect(it) }
                ?.toScreenRect(rootLocation),
            rootViewIsDecor = true
        )
    }

    private fun Rect.toScreenRect(rootLocation: IntArray): Rect = Rect(this).apply {
        offset(rootLocation[0], rootLocation[1])
    }

    private fun View.findTextViewWithText(expectedText: String): TextView? {
        if (this is TextView && text?.toString() == expectedText) return this
        return (this as? ViewGroup)?.children
            ?.firstNotNullOfOrNull { child -> child.findTextViewWithText(expectedText) }
    }

    private fun View.findClickableViewAncestor(): View? {
        var current: View? = this
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent as? View
        }
        return null
    }

    private fun injectTouchTap(
        node: AccessibilityNodeInfo,
        editor: EditText,
        expectedPrefix: String,
        result: LatencyResult,
        automation: UiAutomation
    ): Boolean {
        if (onMain { editor.text.toString() } != expectedPrefix) {
            result.error = "터치 직전 입력이 변경됨"
            return false
        }
        val bounds = Rect().also(node::getBoundsInScreen)
        val screen = instrumentation.targetContext.resources.displayMetrics
        if (
            !node.isVisibleToUser || bounds.width() <= 0 || bounds.height() <= 0 ||
            bounds.left < 0 || bounds.top < 0 || bounds.right > screen.widthPixels ||
            bounds.bottom > screen.heightPixels
        ) {
            result.error = "터치 대상 후보 bounds가 화면 안의 양수 영역이 아님"
            return false
        }
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            bounds.exactCenterX(),
            bounds.exactCenterY(),
            0
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            result.touchDownInjected = automation.injectInputEvent(down, true)
        } finally {
            down.recycle()
        }
        if (result.touchDownInjected != true) {
            result.error = "터치 DOWN 주입 실패"
            return false
        }
        SystemClock.sleep(TOUCH_UP_DELAY_MS)
        val up = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            bounds.exactCenterX(),
            bounds.exactCenterY(),
            0
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            result.touchUpInjected = automation.injectInputEvent(up, true)
        } finally {
            up.recycle()
        }
        if (result.touchUpInjected != true) result.error = "터치 UP 주입 실패"
        return result.touchUpInjected == true
    }

    private fun captureCandidateCrop(
        bounds: Rect,
        automation: UiAutomation,
        fileName: String
    ): CaptureResult = captureScreenCrop(bounds, automation, fileName)

    private fun captureScreenCrop(bounds: Rect, automation: UiAutomation, fileName: String): CaptureResult {
        val screenshot = automation.takeScreenshot() ?: return CaptureResult(error = "UiAutomation.takeScreenshot returned null")
        try {
            val cropBounds = Rect(
                bounds.left.coerceIn(0, screenshot.width),
                bounds.top.coerceIn(0, screenshot.height),
                bounds.right.coerceIn(0, screenshot.width),
                bounds.bottom.coerceIn(0, screenshot.height)
            )
            if (cropBounds.width() <= 0 || cropBounds.height() <= 0) {
                return CaptureResult(error = "candidate bounds are outside screenshot")
            }
            val crop = Bitmap.createBitmap(
                screenshot,
                cropBounds.left,
                cropBounds.top,
                cropBounds.width(),
                cropBounds.height()
            )
            try {
                val directory = File(
                    requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
                    "sentence-pack-latency"
                )
                if (!directory.exists() && !directory.mkdirs()) {
                    return CaptureResult(error = "candidate crop directory could not be created")
                }
                val target = File(directory, fileName)
                FileOutputStream(target).use { output ->
                    if (!crop.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        return CaptureResult(error = "candidate crop PNG compression failed")
                    }
                }
                return CaptureResult(path = target.absolutePath)
            } finally {
                crop.recycle()
            }
        } catch (error: Exception) {
            return CaptureResult(error = "candidate crop failed: ${error.javaClass.simpleName}")
        } finally {
            screenshot.recycle()
        }
    }

    private fun reportResult(
        result: LatencyResult,
        statusKey: String = "immediateSentenceLatencyJson"
    ) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString(
                    statusKey,
                    JSONObject()
                        .put("kind", "immediate_sentence_latency")
                        .put("case", result.caseNumber)
                        .put("evidenceScope", "${result.candidateSource} local 1 second; this is not an AI latency claim")
                        .put("deadlineMs", CASE_DEADLINE_MS)
                        .put("stage", result.stage)
                        .put("tapMode", result.tapMode)
                        .put("commitReturnMs", result.commitReturnMs)
                        .put("editorTextReadyMs", result.editorTextReadyMs)
                        .put("serviceSelectionReadyMs", result.serviceSelectionReadyMs)
                        .put("snapshotCandidateMs", result.snapshotCandidateMs)
                        .put("accessibilityCandidateMs", result.accessibilityCandidateMs)
                        .put("visibleCandidateMs", result.visibleCandidateMs)
                        .put("snapshotPollCount", result.snapshotPollCount)
                        .put("snapshotHadSentences", result.snapshotHadSentences)
                        .put("snapshotSourceCounts", JSONObject(result.snapshotSourceCounts))
                        .put("snapshotTextInspectionAllowed", result.snapshotTextInspectionAllowed)
                        .put("snapshotTextBeforeCursorMatchesPrefix", result.snapshotTextBeforeCursorMatchesPrefix)
                        .put("snapshotSelectionNotEmpty", result.snapshotSelectionNotEmpty)
                        .put("snapshotSentencePackCompletionCount", result.snapshotSentencePackCompletionCount)
                        .put("snapshotConversationalTextField", result.snapshotConversationalTextField)
                        .put("snapshotEmailField", result.snapshotEmailField)
                        .put("snapshotPhoneField", result.snapshotPhoneField)
                        .put("snapshotNumericField", result.snapshotNumericField)
                        .put("snapshotUrlField", result.snapshotUrlField)
                        .put("timeoutBufferedPrefixBlank", result.timeoutBufferedPrefixBlank)
                        .put("timeoutComposingTextBlank", result.timeoutComposingTextBlank)
                        .put("timeoutRawFullContextMatchesPrefix", result.timeoutRawFullContextMatchesPrefix)
                        .put("timeoutRawContextPackLookupCount", result.timeoutRawContextPackLookupCount)
                        .put("timeoutCacheExists", result.timeoutCacheExists)
                        .put("timeoutCachePredictionSourceCounts", JSONObject(result.timeoutCachePredictionSourceCounts))
                        .put("timeoutCacheKeyRawFullContextMatchesPrefix", result.timeoutCacheKeyRawFullContextMatchesPrefix)
                        .put("timeoutCacheDiagnosticError", result.timeoutCacheDiagnosticError)
                        .put("a11yNodeRefreshSucceeded", result.a11yNodeRefreshSucceeded)
                        .put("a11yBounds", result.a11yBounds?.toJson())
                        .put("liveTextBounds", result.liveTextBounds?.toJson())
                        .put("liveClickableBounds", result.liveClickableBounds?.toJson())
                        .put("liveClickableLocation", result.liveClickableLocation?.toJson())
                        .put("liveClickableVisibleUnclipped", result.liveClickableVisibleUnclipped)
                        .put("imeBounds", result.imeBounds?.toJson())
                        .put("liveRootViewIsDecor", result.liveRootViewIsDecor)
                        .put("liveGeometryError", result.liveGeometryError)
                        .put("a11yLiveBoundsMatch", result.a11yLiveBoundsMatch)
                        .put("candidateSource", result.candidateSource)
                        .put("clickInsertionMatched", result.clickInsertionMatched)
                        .put("touchDownInjected", result.touchDownInjected)
                        .put("touchUpInjected", result.touchUpInjected)
                        .put("capturePhase", result.capturePhase)
                        .put("captureLatencyExcluded", result.captureLatencyExcluded)
                        .put("capturePath", result.capturePath)
                        .put("captureError", result.captureError)
                        .put("error", result.error)
                        .toString()
                )
            }
        )
    }

    private fun configureUiAutomation(): UiAutomation = instrumentation.uiAutomation.apply {
        val info = requireNotNull(serviceInfo) { "UiAutomation accessibility service info is unavailable." }
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
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
        val inputMethodManager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun waitForCurrentEditor(target: EditorTarget): FcitxInputMethodService {
        val deadline = SystemClock.elapsedRealtime() + IME_READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val (ime, matches) = onMain {
                val activeIme = FcitxInputMethodService.activeInstance
                activeIme to (activeIme != null && activeIme.matchesCurrentEditor(
                    packageName = target.packageName,
                    fieldId = target.fieldId,
                    inputType = target.inputType,
                    selectionStart = target.selectionStart,
                    selectionEnd = target.selectionEnd,
                    expectedInputSessionEpoch = activeIme.currentInputSessionEpoch
                ))
            }
            if (matches) return requireNotNull(ime)
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("새글 IME가 debug editor에 연결되지 않았습니다.")
    }

    private fun waitForServiceSelection(
        ime: FcitxInputMethodService,
        editor: EditText,
        expectedText: String,
        deadline: Long
    ) {
        while (SystemClock.elapsedRealtime() < deadline) {
            val matches = onMain {
                ime.currentInputSelection.start == expectedText.length &&
                    ime.currentInputSelection.end == expectedText.length &&
                    editor.selectionStart == expectedText.length &&
                    editor.selectionEnd == expectedText.length
            }
            if (matches) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("IME 선택 범위가 입력에 반영되지 않았습니다.")
    }

    private fun waitForEditorText(editor: EditText, expected: String, deadline: Long) {
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain { editor.text.toString() } == expected) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        assertEquals(expected, onMain { editor.text.toString() })
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
        instrumentation.runOnMainSync { result = block() }
        return requireNotNull(result)
    }

    private fun <T> onMainNullable(block: () -> T?): T? {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        return result
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

    private fun Rect.toJson(): JSONObject = JSONObject()
        .put("left", left)
        .put("top", top)
        .put("right", right)
        .put("bottom", bottom)

    private fun IntArray.toJson(): JSONObject = JSONObject()
        .put("x", getOrNull(0))
        .put("y", getOrNull(1))

    private data class LatencyResult(
        val caseNumber: Int,
        val commitReturnMs: Long? = null,
        var stage: String = "editor_text",
        var tapMode: String = ACCESSIBILITY_TAP_MODE,
        var editorTextReadyMs: Long? = null,
        var serviceSelectionReadyMs: Long? = null,
        var snapshotCandidateMs: Long? = null,
        var accessibilityCandidateMs: Long? = null,
        var visibleCandidateMs: Long? = null,
        var snapshotPollCount: Int = 0,
        var snapshotHadSentences: Boolean = false,
        var snapshotSourceCounts: Map<String, Int> = emptyMap(),
        var snapshotTextInspectionAllowed: Boolean? = null,
        var snapshotTextBeforeCursorMatchesPrefix: Boolean? = null,
        var snapshotSelectionNotEmpty: Boolean? = null,
        var snapshotSentencePackCompletionCount: Int? = null,
        var snapshotConversationalTextField: Boolean? = null,
        var snapshotEmailField: Boolean? = null,
        var snapshotPhoneField: Boolean? = null,
        var snapshotNumericField: Boolean? = null,
        var snapshotUrlField: Boolean? = null,
        var timeoutBufferedPrefixBlank: Boolean? = null,
        var timeoutComposingTextBlank: Boolean? = null,
        var timeoutRawFullContextMatchesPrefix: Boolean? = null,
        var timeoutRawContextPackLookupCount: Int? = null,
        var timeoutCacheExists: Boolean? = null,
        var timeoutCachePredictionSourceCounts: Map<String, Int> = emptyMap(),
        var timeoutCacheKeyRawFullContextMatchesPrefix: Boolean? = null,
        var timeoutCacheDiagnosticError: String? = null,
        var a11yNodeRefreshSucceeded: Boolean? = null,
        var a11yBounds: Rect? = null,
        var liveTextBounds: Rect? = null,
        var liveClickableBounds: Rect? = null,
        var liveClickableLocation: IntArray? = null,
        var liveClickableVisibleUnclipped: Boolean? = null,
        var imeBounds: Rect? = null,
        var liveRootViewIsDecor: Boolean? = null,
        var liveGeometryError: String? = null,
        var a11yLiveBoundsMatch: Boolean? = null,
        var candidateSource: String? = null,
        var clickInsertionMatched: Boolean? = null,
        var touchDownInjected: Boolean? = null,
        var touchUpInjected: Boolean? = null,
        var captureAttempted: Boolean = false,
        var capturePhase: String? = null,
        var captureLatencyExcluded: Boolean = false,
        var capturePath: String? = null,
        var captureError: String? = null,
        var error: String? = null
    )

    private data class CaptureResult(val path: String? = null, val error: String? = null)

    private data class A11yCandidateGeometry(
        val node: AccessibilityNodeInfo,
        val bounds: Rect
    )

    private data class LiveCandidateGeometry(
        val textBounds: Rect,
        val clickableBounds: Rect,
        val clickableLocation: IntArray,
        val clickableVisibleUnclipped: Boolean,
        val imeBounds: Rect?,
        val rootViewIsDecor: Boolean
    )

    private data class VerifiedCandidate(
        val a11y: A11yCandidateGeometry,
        val live: LiveCandidateGeometry,
        val boundsMatch: Boolean
    )

    private data class EditorTarget(
        val packageName: String,
        val fieldId: Int,
        val inputType: Int,
        val selectionStart: Int,
        val selectionEnd: Int
    )

    private companion object {
        val LATENCY_PREFIXES = listOf("회의 자료를 ", "오늘 저녁 ", "회의가 끝나", "약속을 ")
        val GENERATED_PREFIXES = listOf("회의 자료를 ", "오늘 저녁 ", "약속을 ")
        const val SENTENCE_PACK_SOURCE = "sentence_pack"
        const val GENERATED_SOURCE = "ondevice_generated"
        const val BUILTIN_SENTENCE_COUNT = 216
        const val CANDIDATE_LIMIT = 2
        const val REPOSITORY_READY_TIMEOUT_MS = 10_000L
        const val IME_READY_TIMEOUT_MS = 10_000L
        const val CASE_DEADLINE_MS = 3_000L
        const val VISIBLE_LATENCY_LIMIT_MS = 1_000L
        const val POLL_INTERVAL_MS = 25L
        const val MAX_ERROR_MESSAGE_CHARS = 400
        const val TAP_MODE_ARGUMENT = "tapMode"
        const val LATENCY_CASE_ARGUMENT = "latencyCase"
        const val CANDIDATE_SOURCE_ARGUMENT = "candidateSource"
        const val RESTORE_OFFLINE_MODE_ARGUMENT = "restoreOfflineMode"
        const val ACCESSIBILITY_TAP_MODE = "accessibility"
        const val TOUCH_TAP_MODE = "touch"
        const val TOUCH_UP_DELAY_MS = 40L
    }
}
