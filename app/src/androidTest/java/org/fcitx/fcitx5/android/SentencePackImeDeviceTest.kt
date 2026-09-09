/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Rect
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

class SentencePackImeDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun builtinSentencePackAppearsInProductionImeAndAppendsOnceOffline() {
        assertSentencePackCandidateAppendsOnceOffline(
            prefix = EXACT_PREFIX,
            expectedSuffix = EXACT_SUFFIX
        )
    }

    @Test
    fun builtinSentencePackBacksOffToLastWordAndAppendsOnceOffline() {
        assertSentencePackCandidateAppendsOnceOffline(
            prefix = LAST_WORD_PREFIX,
            expectedSuffix = LAST_WORD_SUFFIX
        )
    }

    @Test
    fun builtinSentencePackCompletesBeforeSpaceAndAppendsOnceOffline() {
        assertSentencePackCandidateAppendsOnceOffline(
            prefix = NO_TRAILING_SPACE_PREFIX,
            expectedSuffix = NO_TRAILING_SPACE_SUFFIX
        )
    }

    private fun assertSentencePackCandidateAppendsOnceOffline(
        prefix: String,
        expectedSuffix: String
    ) {
        val offlineMode = AppPrefs.getInstance().advanced.offlineMode.getValue()
        var activity: AiEditorTestActivity? = null
        try {
            AppPrefs.getInstance().advanced.offlineMode.setValue(true)
            val currentActivity = launchActivity()
            activity = currentActivity
            waitForBuiltinSentencePack()
            val editor = selectNormalAndClear(currentActivity)
            requestEditorFocusAndIme(currentActivity, editor)
            val ime = waitForCurrentEditor(editorTarget(editor))
            assertTrue("오프라인 문장팩 테스트 중에는 네트워크 AI 입력이 차단되어야 한다.", onMain { !ime.allowsAiInputFeatures() })
            assertTrue("일반 debug editor에서는 문장팩을 위한 텍스트 검사가 허용되어야 한다.", onMain { ime.allowsTextInspectionFeatures() })

            assertTrue(onMain { ime.commitToEditor(prefix) })
            waitForEditorText(editor, prefix)

            val sentenceCandidate = waitForSentencePackCandidate(ime, expectedSuffix)
            assertEquals(expectedSuffix, sentenceCandidate.word.text)
            val automation = configureUiAutomation()
            val candidateNode = waitForVisibleCandidateNode(automation, expectedSuffix)
            assertTrue("문장팩 후보는 실제 IME 후보 행에 보여야 한다.", candidateNode.isVisibleToUser)
            assertTrue("문장팩 후보 클릭은 성공해야 한다.", candidateNode.performAction(AccessibilityNodeInfo.ACTION_CLICK))

            val separator = if (prefix.lastOrNull()?.isWhitespace() == true) "" else " "
            val expectedEditorText = "$prefix$separator$expectedSuffix "
            waitForEditorText(editor, expectedEditorText)
            assertEquals(expectedEditorText, onMain { editor.text.toString() })
        } finally {
            try {
                activity?.let { currentActivity -> onMain { currentActivity.finish() } }
            } finally {
                AppPrefs.getInstance().advanced.offlineMode.setValue(offlineMode)
            }
        }
    }

    private fun waitForBuiltinSentencePack() {
        val repository = FcitxApplication.getInstance().sentencePacks
        repository.prepare()
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        var latest = repository.status.value
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = repository.status.value
            if (!latest.isLoading && latest.builtinCount == BUILTIN_SENTENCE_COUNT && latest.error == null) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("기본 문장팩이 준비되지 않았다. status=$latest")
    }

    private fun waitForSentencePackCandidate(
        ime: FcitxInputMethodService,
        expectedSuffix: String
    ): FcitxInputMethodService.ContextualCandidate {
        val deadline = SystemClock.elapsedRealtime() + CANDIDATE_TIMEOUT_MS
        var latestSources = emptyList<String>()
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = onMain { ime.getContextualCandidateSnapshot() }
            snapshot.sentences.firstOrNull {
                it.metricsCandidate?.source == SENTENCE_PACK_SOURCE && it.word.text == expectedSuffix
            }?.let { return it }
            latestSources = snapshot.sentences.mapNotNull { it.metricsCandidate?.source }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("production IME 문장 후보에 sentence_pack이 나타나지 않았다. sources=$latestSources")
    }

    private fun configureUiAutomation(): UiAutomation = instrumentation.uiAutomation.apply {
        val info = requireNotNull(serviceInfo) { "UiAutomation accessibility service info is unavailable." }
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
    }

    private fun waitForVisibleCandidateNode(
        automation: UiAutomation,
        candidateText: String
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + CANDIDATE_TIMEOUT_MS
        var latestImeWindowCount = 0
        var latestImeRootCount = 0
        var latestImeRootVisible = false
        var latestExpectedNode = ExpectedNodeDiagnostics.Absent
        while (SystemClock.elapsedRealtime() < deadline) {
            val imeWindows = automation.windows
                .asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .toList()
            val imeRoots = imeWindows
                .asSequence()
                .mapNotNull { it.root }
                .toList()
            val candidate = imeRoots
                .asSequence()
                .mapNotNull { findVisibleCandidateNode(it, candidateText) }
                .firstOrNull()
            if (candidate != null) return candidate
            latestImeWindowCount = imeWindows.size
            latestImeRootCount = imeRoots.size
            latestImeRootVisible = imeRoots.any { it.isVisibleToUser }
            latestExpectedNode = imeRoots.asSequence()
                .mapNotNull { expectedNodeDiagnostics(it, candidateText) }
                .firstOrNull() ?: ExpectedNodeDiagnostics.Absent
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        instrumentation.sendStatus(
            0,
            android.os.Bundle().apply {
                putInt("imeWindowCount", latestImeWindowCount)
                putInt("imeRootCount", latestImeRootCount)
                putBoolean("imeRootVisible", latestImeRootVisible)
                putString("expectedCandidate", candidateText)
                putBoolean("expectedTextNodeFound", latestExpectedNode.found)
                putBoolean("expectedTextNodeVisible", latestExpectedNode.visible)
                putBoolean("expectedTextNodeClickable", latestExpectedNode.clickable)
                putString("expectedTextNodeBounds", latestExpectedNode.bounds)
            }
        )
        throw AssertionError("IME 후보 행에 '$candidateText' 텍스트를 가진 클릭 가능한 후보가 나타나지 않았다. imeWindowCount=$latestImeWindowCount")
    }

    private fun findVisibleCandidateNode(
        node: AccessibilityNodeInfo,
        candidateText: String
    ): AccessibilityNodeInfo? {
        if (node.text?.toString() == candidateText) {
            findClickableAncestor(node)?.let { candidate ->
                val bounds = Rect()
                candidate.getBoundsInScreen(bounds)
                if (candidate.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0) return candidate
            }
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                findVisibleCandidateNode(child, candidateText)?.let { return it }
            }
        }
        return null
    }

    private fun expectedNodeDiagnostics(
        node: AccessibilityNodeInfo,
        candidateText: String
    ): ExpectedNodeDiagnostics? {
        if (node.text?.toString() == candidateText) {
            val clickableNode = findClickableAncestor(node)
            val bounds = Rect()
            (clickableNode ?: node).getBoundsInScreen(bounds)
            return ExpectedNodeDiagnostics(
                found = true,
                visible = node.isVisibleToUser,
                clickable = clickableNode != null,
                bounds = bounds.toShortString()
            )
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                expectedNodeDiagnostics(child, candidateText)?.let { return it }
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
        assertTrue("synthetic editor는 IME 후보 표시 전에 focus를 가져야 한다.", editor.hasFocus())
        val inputMethodManager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun waitForCurrentEditor(target: EditorTarget): FcitxInputMethodService {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        var latest = currentEditorState(target)
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = currentEditorState(target)
            if (latest.matches) return requireNotNull(latest.ime)
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("새글 IME가 debug editor에 연결되지 않았다. expectedTarget=$target; $latest")
    }

    private fun currentEditorState(target: EditorTarget): CurrentEditorState = onMain {
        val ime = FcitxInputMethodService.activeInstance
        val info = ime?.currentInputEditorInfo
        val selection = ime?.currentInputSelection
        val matches = ime != null && ime.matchesCurrentEditor(
            packageName = target.packageName,
            fieldId = target.fieldId,
            inputType = target.inputType,
            selectionStart = target.selectionStart,
            selectionEnd = target.selectionEnd,
            expectedInputSessionEpoch = ime.currentInputSessionEpoch
        )
        CurrentEditorState(
            ime = ime,
            matches = matches,
            packageName = info?.packageName,
            fieldId = info?.fieldId,
            inputType = info?.inputType,
            selectionStart = selection?.start,
            selectionEnd = selection?.end
        )
    }

    private fun waitForEditorText(editor: EditText, expected: String) {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
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

    private fun View.findByContentDescription(description: String): View? {
        if (contentDescription?.toString() == description) return this
        return (this as? ViewGroup)?.children
            ?.firstNotNullOfOrNull { child -> child.findByContentDescription(description) }
    }

    private val ViewGroup.children: Sequence<View>
        get() = sequence {
            for (index in 0 until childCount) yield(getChildAt(index))
        }

    private data class EditorTarget(
        val packageName: String,
        val fieldId: Int,
        val inputType: Int,
        val selectionStart: Int,
        val selectionEnd: Int
    )

    private data class CurrentEditorState(
        val ime: FcitxInputMethodService?,
        val matches: Boolean,
        val packageName: String?,
        val fieldId: Int?,
        val inputType: Int?,
        val selectionStart: Int?,
        val selectionEnd: Int?
    )

    private data class ExpectedNodeDiagnostics(
        val found: Boolean,
        val visible: Boolean,
        val clickable: Boolean,
        val bounds: String
    ) {
        companion object {
            val Absent = ExpectedNodeDiagnostics(
                found = false,
                visible = false,
                clickable = false,
                bounds = ""
            )
        }
    }

    private companion object {
        const val EXACT_PREFIX = "회의 시작 전에 "
        const val EXACT_SUFFIX = "자료를 공유하겠습니다."
        const val LAST_WORD_PREFIX = "내일 회의 "
        const val LAST_WORD_SUFFIX = "끝나고 다시 연락드릴게요."
        const val NO_TRAILING_SPACE_PREFIX = "목요일 회의는"
        const val NO_TRAILING_SPACE_SUFFIX = "정해진 시간에 진행해도 될까요?"
        const val SENTENCE_PACK_SOURCE = "sentence_pack"
        const val BUILTIN_SENTENCE_COUNT = 216
        const val READY_TIMEOUT_MS = 10_000L
        const val CANDIDATE_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
