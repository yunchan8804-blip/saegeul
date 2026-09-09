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
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.debug.AiEditorTestActivity
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class SentencePackMatrixDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun observesOfflineSentencePackMatrixInProductionIme() {
        val priorOfflineMode = AppPrefs.getInstance().advanced.offlineMode.getValue()
        var activity: AiEditorTestActivity? = null
        val interactionFailures = mutableListOf<String>()
        var capturedCandidateCount = 0
        try {
            AppPrefs.getInstance().advanced.offlineMode.setValue(true)
            reportRepositoryStatus(waitForSentencePackRepository())

            val currentActivity = launchActivity()
            activity = currentActivity
            val editor = selectNormalAndClear(currentActivity)
            requestEditorFocusAndIme(currentActivity, editor)
            val ime = waitForCurrentEditor(editorTarget(editor))
            assertTrue("오프라인 matrix 관측 중 네트워크 AI 입력은 차단되어야 한다.", onMain { !ime.allowsAiInputFeatures() })
            assertTrue("일반 synthetic editor에서는 문장팩의 텍스트 검사가 허용되어야 한다.", onMain { ime.allowsTextInspectionFeatures() })
            val automation = configureUiAutomation()

            selectedMatrixCases().forEach { (caseNumber, prefix) ->
                clearEditor(currentActivity, editor)
                waitForEditorText(editor, "")
                waitForServiceSelection(ime, editor, expectedText = "")

                val startedAt = SystemClock.elapsedRealtime()
                assertTrue("synthetic prefix를 실제 editor에 입력하지 못했다.", onMain { ime.commitToEditor(prefix) })
                waitForEditorText(editor, prefix)
                waitForServiceSelection(ime, editor, expectedText = prefix)

                val lookupCount = FcitxApplication.getInstance().sentencePacks.complete(prefix, 2).size
                val observation = waitForStableSentenceSnapshot(ime, startedAt)
                val sentencePackCandidates = observation.snapshot.sentences
                    .filter { it.metricsCandidate?.source == SENTENCE_PACK_SOURCE }
                    .map { candidate ->
                        SentencePackCandidateResult(
                            suffix = candidate.word.text,
                            expectedInsertion = candidate.appendSnapshot?.append?.insertionFor(prefix)
                        )
                    }
                val result = MatrixResult(
                    caseNumber = caseNumber,
                    prefix = prefix,
                    elapsedMs = observation.elapsedMs,
                    lookupCount = lookupCount,
                    sentenceCount = observation.snapshot.sentences.size,
                    sourceCounts = sourceCounts(observation.snapshot),
                    stable = observation.stable,
                    sentencePackCandidates = sentencePackCandidates
                )

                sentencePackCandidates.firstOrNull()?.let { sentencePackCandidate ->
                    val suffix = sentencePackCandidate.suffix
                    val insertion = sentencePackCandidate.expectedInsertion
                    val initialVisible = waitForVisibleCandidateNode(automation, suffix)
                    result.initialUiSuffixPresent = initialVisible != null
                    val visible = initialVisible ?: revealCandidateByScrolling(
                        automation = automation,
                        snapshot = observation.snapshot,
                        suffix = suffix,
                        result = result
                    )
                    result.uiSuffixPresent = visible != null
                    result.uiBasicBadgePresent = visible?.let(::containsBasicSentenceBadge) ?: false
                    visible?.let { node ->
                        val bounds = Rect().also(node::getBoundsInScreen)
                        result.uiBounds = bounds.toShortString()
                        if (capturedCandidateCount < MAX_AUDIT_CAPTURES) {
                            capturedCandidateCount++
                            result.screenshotPath = captureCandidateCrop(node, capturedCandidateCount, automation)
                                .also { capture -> result.screenshotError = capture.error }
                                .path
                        }
                        if (insertion == null) {
                            interactionFailures += "case=$caseNumber: sentence_pack append insertionFor(prefix)가 null이다."
                        } else if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            interactionFailures += "case=$caseNumber: 표시된 sentence_pack 후보 클릭이 실패했다."
                        } else {
                            val expectedEditorText = prefix + insertion
                            try {
                                waitForEditorText(editor, expectedEditorText)
                                assertEquals(expectedEditorText, onMain { editor.text.toString() })
                                result.clickInsertionMatched = true
                            } catch (error: AssertionError) {
                                interactionFailures += "case=$caseNumber: 클릭 뒤 editor 입력이 append 계약과 다르다."
                                result.clickInsertionMatched = false
                            }
                        }
                    }
                    if (visible == null) {
                        interactionFailures += "case=$caseNumber: snapshot의 sentence_pack 후보가 IME 후보 행에 보이지 않는다."
                    }
                }
                reportMatrixResult(result)
            }
        } finally {
            try {
                activity?.let { currentActivity -> onMain { currentActivity.finish() } }
            } finally {
                AppPrefs.getInstance().advanced.offlineMode.setValue(priorOfflineMode)
            }
        }
        assertTrue(interactionFailures.joinToString(separator = "\n"), interactionFailures.isEmpty())
    }

    private fun waitForSentencePackRepository(): org.fcitx.fcitx5.android.input.ai.sentencepack.SentencePackStatus {
        val repository = FcitxApplication.getInstance().sentencePacks
        repository.prepare()
        val deadline = SystemClock.elapsedRealtime() + REPOSITORY_READY_TIMEOUT_MS
        var latest = repository.status.value
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = repository.status.value
            if (!latest.isLoading && latest.builtinCount == BUILTIN_SENTENCE_COUNT && latest.error == null) return latest
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("문장팩 repository가 준비되지 않았다. status=$latest")
    }

    private fun reportRepositoryStatus(status: org.fcitx.fcitx5.android.input.ai.sentencepack.SentencePackStatus) {
        reportJson(
            JSONObject()
                .put("kind", "sentence_pack_repository")
                .put("builtinCount", status.builtinCount)
                .put("installedCount", status.installedCount)
                .put("isLoading", status.isLoading)
                .put("hasError", status.error != null)
                .toString()
        )
    }

    private fun waitForStableSentenceSnapshot(
        ime: FcitxInputMethodService,
        startedAt: Long
    ): SnapshotObservation {
        val deadline = startedAt + SNAPSHOT_TIMEOUT_MS
        var previousSignature: String? = null
        var stablePolls = 0
        var latest = onMain { ime.getContextualCandidateSnapshot(sentenceLimit = 2) }
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = onMain { ime.getContextualCandidateSnapshot(sentenceLimit = 2) }
            val signature = snapshotSignature(latest)
            stablePolls = if (signature == previousSignature) stablePolls + 1 else 1
            previousSignature = signature
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed >= MINIMUM_OBSERVATION_MS && latest.sentences.isNotEmpty() && stablePolls >= REQUIRED_STABLE_POLLS) {
                return SnapshotObservation(latest, elapsed, stable = true)
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return SnapshotObservation(
            snapshot = latest,
            elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            stable = false
        )
    }

    private fun snapshotSignature(snapshot: FcitxInputMethodService.ContextualCandidateSnapshot): String =
        snapshot.sentences.joinToString(separator = "|") { candidate ->
            "${candidate.metricsCandidate?.source ?: "unattributed"}:${candidate.word.text.hashCode()}"
        }

    private fun sourceCounts(snapshot: FcitxInputMethodService.ContextualCandidateSnapshot): Map<String, Int> =
        snapshot.sentences.groupingBy { it.metricsCandidate?.source ?: "unattributed" }.eachCount()

    private fun configureUiAutomation(): UiAutomation = instrumentation.uiAutomation.apply {
        val info = requireNotNull(serviceInfo) { "UiAutomation accessibility service info is unavailable." }
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
    }

    private fun waitForVisibleCandidateNode(
        automation: UiAutomation,
        suffix: String
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + UI_CANDIDATE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val candidate = automation.windows
                .asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .mapNotNull { it.root }
                .mapNotNull { findVisibleCandidateNode(it, suffix) }
                .firstOrNull()
            if (candidate != null) return candidate
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    private fun revealCandidateByScrolling(
        automation: UiAutomation,
        snapshot: FcitxInputMethodService.ContextualCandidateSnapshot,
        suffix: String,
        result: MatrixResult
    ): AccessibilityNodeInfo? {
        val scrollableAncestor = snapshot.sentences
            .asSequence()
            .mapNotNull { candidate -> waitForVisibleCandidateNode(automation, candidate.word.text) }
            .mapNotNull(::findScrollableAncestor)
            .firstOrNull() ?: return null
        for (ignored in 1..MAX_SCROLL_ATTEMPTS) {
            if (!scrollableAncestor.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
            result.scrollAttempts++
            waitForVisibleCandidateNode(automation, suffix)?.let { return it }
        }
        return null
    }

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

    private fun containsBasicSentenceBadge(node: AccessibilityNodeInfo): Boolean {
        if (node.text?.toString() == BASIC_SENTENCE_BADGE_ICON) return true
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                if (containsBasicSentenceBadge(child)) return true
            }
        }
        return false
    }

    private fun findScrollableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isScrollable) return current
            current = current.parent
        }
        return null
    }

    private fun captureCandidateCrop(
        node: AccessibilityNodeInfo,
        captureNumber: Int,
        automation: UiAutomation
    ): CaptureResult {
        val bounds = Rect().also(node::getBoundsInScreen)
        val screenshot = automation.takeScreenshot() ?: return CaptureResult(error = "UiAutomation.takeScreenshot returned null")
        try {
            val cropBounds = Rect(
                bounds.left.coerceIn(0, screenshot.width),
                bounds.top.coerceIn(0, screenshot.height),
                bounds.right.coerceIn(0, screenshot.width),
                bounds.bottom.coerceIn(0, screenshot.height)
            )
            if (cropBounds.width() <= 0 || cropBounds.height() <= 0) {
                return CaptureResult(error = "candidate bounds are outside screenshot: ${bounds.toShortString()}")
            }
            val crop = Bitmap.createBitmap(
                screenshot,
                cropBounds.left,
                cropBounds.top,
                cropBounds.width(),
                cropBounds.height()
            )
            try {
                val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "sentence-pack-audit")
                if (!directory.exists() && !directory.mkdirs()) {
                    return CaptureResult(error = "candidate crop directory could not be created")
                }
                val target = File(directory, "case-${captureNumber.toString().padStart(2, '0')}.png")
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

    private fun reportMatrixResult(result: MatrixResult) {
        val json = JSONObject()
            .put("kind", "sentence_pack_matrix")
            .put("case", result.caseNumber)
            .put("prefix", result.prefix)
            .put("elapsedMs", result.elapsedMs)
            .put("elapsedMsMeaning", "synthetic commit 이후 후보 관측 대기 시간")
            .put("lookupCount", result.lookupCount)
            .put("snapshotStable", result.stable)
            .put("sentenceCandidateCount", result.sentenceCount)
            .put("sentenceCandidateSourceCounts", JSONObject(result.sourceCounts))
            .put("sentencePackCandidates", JSONArray().apply {
                result.sentencePackCandidates.forEach { candidate ->
                    put(
                        JSONObject()
                            .put("suffix", candidate.suffix)
                            .put("expectedInsertion", candidate.expectedInsertion)
                    )
                }
            })
            .put("initialUiSuffixPresent", result.initialUiSuffixPresent)
            .put("scrollAttempts", result.scrollAttempts)
            .put("uiSuffixPresent", result.uiSuffixPresent)
            .put("uiBasicBadgePresent", result.uiBasicBadgePresent)
            .put("uiBounds", result.uiBounds)
            .put("clickInsertionMatched", result.clickInsertionMatched)
            .put("screenshotPath", result.screenshotPath)
            .put("screenshotError", result.screenshotError)
        reportJson(json.toString())
    }

    private fun reportJson(json: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("sentencePackMatrixJson", json) })
    }

    private fun selectedMatrixCases(): List<Pair<Int, String>> {
        val requested = InstrumentationRegistry.getArguments().getString(MATRIX_CASE_ARGUMENT) ?: return MATRIX_PREFIXES.mapIndexed { index, prefix ->
            index + 1 to prefix
        }
        val caseNumber = requested.toIntOrNull()
            ?: throw AssertionError("matrixCase는 1부터 ${MATRIX_PREFIXES.size} 사이의 정수여야 한다.")
        val prefix = MATRIX_PREFIXES.getOrNull(caseNumber - 1)
            ?: throw AssertionError("matrixCase는 1부터 ${MATRIX_PREFIXES.size} 사이여야 한다.")
        return listOf(caseNumber to prefix)
    }

    private fun clearEditor(activity: AiEditorTestActivity, editor: EditText) = onMain {
        requireNotNull(activity.window.decorView.findByContentDescription("Clear text")).performClick()
        editor.requestFocus()
        val inputMethodManager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun launchActivity(): AiEditorTestActivity = instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, AiEditorTestActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    ) as AiEditorTestActivity

    private fun selectNormalAndClear(activity: AiEditorTestActivity): EditText = onMain {
        requireNotNull(activity.window.decorView.findByContentDescription("Select normal complete-editor mode")).performClick()
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
        throw AssertionError("새글 IME가 synthetic editor에 연결되지 않았다.")
    }

    private fun waitForServiceSelection(
        ime: FcitxInputMethodService,
        editor: EditText,
        expectedText: String
    ) {
        val deadline = SystemClock.elapsedRealtime() + IME_READY_TIMEOUT_MS
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
        throw AssertionError("synthetic editor의 cursor가 service context에 반영되지 않았다. expected=${expectedText.length}")
    }

    private fun waitForEditorText(editor: EditText, expected: String) {
        val deadline = SystemClock.elapsedRealtime() + IME_READY_TIMEOUT_MS
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

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun <T : Any> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        return requireNotNull(result)
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

    private data class SnapshotObservation(
        val snapshot: FcitxInputMethodService.ContextualCandidateSnapshot,
        val elapsedMs: Long,
        val stable: Boolean
    )

    private data class CaptureResult(
        val path: String? = null,
        val error: String? = null
    )

    private data class MatrixResult(
        val caseNumber: Int,
        val prefix: String,
        val elapsedMs: Long,
        val lookupCount: Int,
        val sentenceCount: Int,
        val sourceCounts: Map<String, Int>,
        val stable: Boolean,
        val sentencePackCandidates: List<SentencePackCandidateResult>,
        var initialUiSuffixPresent: Boolean? = null,
        var scrollAttempts: Int = 0,
        var uiSuffixPresent: Boolean? = null,
        var uiBasicBadgePresent: Boolean? = null,
        var uiBounds: String? = null,
        var clickInsertionMatched: Boolean? = null,
        var screenshotPath: String? = null,
        var screenshotError: String? = null
    )

    private data class SentencePackCandidateResult(
        val suffix: String,
        val expectedInsertion: String?
    )

    private data class EditorTarget(
        val packageName: String,
        val fieldId: Int,
        val inputType: Int,
        val selectionStart: Int,
        val selectionEnd: Int
    )

    private companion object {
        val MATRIX_PREFIXES = listOf(
            "오늘 회의 ",
            "내일 회의",
            "목요일 회의는",
            "회의 자료를 ",
            "나는 지금 ",
            "친구야 오늘 ",
            "오늘 저녁 ",
            "점심 뭐",
            "저녁 먹",
            "내일 시간 ",
            "주말에 뭐",
            "약속을 ",
            "확인 부탁 ",
            "자료를 검토",
            "회의가 끝나",
            "지금은 ",
            "고마워",
            "안녕하세요",
            "지금 뭐해",
            "퇴근하고 ",
            "내가 뭘",
            "카페에서 ",
            "이전 문장입니다. 오늘 회의 ",
            "오늘 회의 끝났어요. ",
            "저는 지금 ",
            "혹시 내일 시간 "
        )
        const val SENTENCE_PACK_SOURCE = "sentence_pack"
        const val BASIC_SENTENCE_BADGE_ICON = "📖"
        const val BUILTIN_SENTENCE_COUNT = 216
        const val REPOSITORY_READY_TIMEOUT_MS = 10_000L
        const val IME_READY_TIMEOUT_MS = 5_000L
        const val SNAPSHOT_TIMEOUT_MS = 2_000L
        const val MINIMUM_OBSERVATION_MS = 500L
        const val UI_CANDIDATE_TIMEOUT_MS = 1_000L
        const val POLL_INTERVAL_MS = 100L
        const val REQUIRED_STABLE_POLLS = 2
        const val MAX_AUDIT_CAPTURES = 3
        const val MAX_SCROLL_ATTEMPTS = 2
        const val MATRIX_CASE_ARGUMENT = "matrixCase"
    }
}
