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
import org.fcitx.fcitx5.android.debug.AiEditorTestActivity
import org.fcitx.fcitx5.android.input.EditorIdentity
import org.fcitx.fcitx5.android.input.EditorSelection
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.ai.ContextualReplacement
import org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedMaterialPolicy
import org.fcitx.fcitx5.android.input.ai.prediction.ContextualCandidate
import org.fcitx.fcitx5.android.input.ai.prediction.ContextualReplacementSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualReplacementDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun confirmedReplacementReplacesTheExactCompleteContextOnce() {
        val activity = launchActivity()
        try {
            val editor = selectNormalAndClear(activity)
            val ime = waitForCurrentEditor(editorTarget(editor))
            val original = "회의자료를 공유합니다"
            val corrected = "회의 자료를 공유합니다"
            assertTrue(onMain { ime.commitToEditor(original) })
            waitForEditorText(editor, original)
            val snapshot = onMain { replacementSnapshot(ime, original, corrected) }

            assertTrue(onMain {
                ime.commitContextualSentence(corrected, replacementSnapshot = snapshot)
            })
            waitForEditorText(editor, corrected)
            val selection = onMain { editor.selectionStart to editor.selectionEnd }

            assertFalse(onMain {
                ime.commitContextualSentence(corrected, replacementSnapshot = snapshot)
            })
            assertEditorUnchanged(editor, corrected, selection)
        } finally {
            activity.finish()
        }
    }

    @Test
    fun staleReplacementEpochPreservesEditorText() {
        val activity = launchActivity()
        try {
            val editor = selectNormalAndClear(activity)
            val ime = waitForCurrentEditor(editorTarget(editor))
            val original = "회의자료를 공유합니다"
            assertTrue(onMain { ime.commitToEditor(original) })
            waitForEditorText(editor, original)
            val snapshot = onMain {
                ContextualReplacementSnapshot(
                    replacement = ContextualReplacement(original, "회의 자료를 공유합니다"),
                    inputSessionEpoch = ime.currentInputSessionEpoch - 1,
                    cursor = original.length
                )
            }
            val selection = onMain { editor.selectionStart to editor.selectionEnd }

            assertFalse(onMain {
                ime.commitContextualSentence("회의 자료를 공유합니다", replacementSnapshot = snapshot)
            })
            assertEditorUnchanged(editor, original, selection)
        } finally {
            activity.finish()
        }
    }

    @Test
    fun sameLengthChangedContextPreservesEditorText() {
        val activity = launchActivity()
        try {
            val editor = selectNormalAndClear(activity)
            val ime = waitForCurrentEditor(editorTarget(editor))
            val original = "회의자료"
            val corrected = "회의 자료"
            assertTrue(onMain { ime.commitToEditor(original) })
            waitForEditorText(editor, original)
            val snapshot = onMain { replacementSnapshot(ime, original, corrected) }
            val changed = onMain {
                requireNotNull(activity.window.decorView.findByContentDescription(
                    "Mutate the reviewed source without moving selection"
                )).performClick()
                editor.text.toString()
            }
            assertEquals(original.length, changed.length)
            assertFalse(original == changed)
            val selection = onMain { editor.selectionStart to editor.selectionEnd }

            assertFalse(onMain {
                ime.commitContextualSentence(corrected, replacementSnapshot = snapshot)
            })
            assertEditorUnchanged(editor, changed, selection)
        } finally {
            activity.finish()
        }
    }

    @Test
    fun privatePhoneFieldBlocksReplacement() {
        val activity = launchActivity()
        try {
            val editor = onMain {
                requireNotNull(activity.window.decorView.findByContentDescription("Phone number field test"))
                    .performClick()
                requireNotNull(activity.window.decorView.findByContentDescription("AI E2E phone number input editor")) as EditText
            }
            val ime = waitForCurrentEditor(editorTarget(editor))
            val original = "01012345678"
            val corrected = "010 1234 5678"
            assertTrue(onMain { ime.commitToEditor(original) })
            waitForEditorText(editor, original)
            val snapshot = onMain { replacementSnapshot(ime, original, corrected) }
            val selection = onMain { editor.selectionStart to editor.selectionEnd }

            assertFalse(onMain {
                ime.commitContextualSentence(corrected, replacementSnapshot = snapshot)
            })
            assertEditorUnchanged(editor, original, selection)
        } finally {
            activity.finish()
        }
    }

    @Test
    fun rejectedReplacementCommitPreservesEditorText() {
        val activity = launchActivity()
        try {
            val editor = onMain {
                requireNotNull(activity.window.decorView.findByContentDescription("Select commit-rejection mode"))
                    .performClick()
                requireNotNull(activity.window.decorView.findByContentDescription("AI E2E commit rejection editor")) as EditText
            }
            val ime = waitForCurrentEditor(editorTarget(editor))
            val original = onMain { editor.text.toString() }
            val corrected = "$original 교정"
            val snapshot = onMain { replacementSnapshot(ime, original, corrected) }
            val selection = onMain { editor.selectionStart to editor.selectionEnd }

            assertFalse(onMain {
                ime.commitContextualSentence(corrected, replacementSnapshot = snapshot)
            })
            assertEditorUnchanged(editor, original, selection)
        } finally {
            activity.finish()
        }
    }

    @Test
    fun storedGemmaSpacingCandidateAppearsAndReplacesByTouch() {
        val storedCandidate = findStoredSpacingCandidate()
        assertTrue("저장된 공개 Gemma 띄어쓰기 재료가 없습니다.", storedCandidate != null)
        val candidate = requireNotNull(storedCandidate)
        val activity = launchActivity()
        val diagnostics = ReplacementTouchDiagnostics()
        try {
            val editor = selectNormalAndClear(activity)
            requestEditorFocusAndIme(activity, editor)
            val ime = waitForCurrentEditor(editorTarget(editor))
            assertTrue(onMain { ime.commitToEditor(candidate.original) })
            assertTrue("원문 입력이 반영되지 않았습니다.", waitForEditorTextWithoutContent(editor, candidate.original))
            assertTrue("IME 선택 범위가 원문 끝과 일치하지 않습니다.", waitForSelectionAtEnd(ime, editor, candidate.original.length))

            val snapshot = waitForStoredSpacingSnapshot(ime, candidate)
            diagnostics.currentSource = snapshot.metricsCandidate?.source
            diagnostics.replacementPresent = snapshot.replacementSnapshot != null
            diagnostics.snapshotEpoch = snapshot.replacementSnapshot?.inputSessionEpoch
            diagnostics.snapshotCursor = snapshot.replacementSnapshot?.cursor
            diagnostics.currentEpochBeforeTouch = onMain { ime.currentInputSessionEpoch }
            assertTrue("띄어쓰기 후보 source가 일치하지 않습니다.",
                snapshot.metricsCandidate?.source == "ondevice_generated_spacing"
            )
            assertTrue("띄어쓰기 후보 badge가 일치하지 않습니다.", snapshot.word.comment == "기기 AI 띄어쓰기")
            assertTrue("띄어쓰기 후보 snapshot 원문이 일치하지 않습니다.",
                snapshot.replacementSnapshot?.replacement?.expectedContext == candidate.original
            )
            assertTrue("띄어쓰기 후보 snapshot 대상이 일치하지 않습니다.",
                snapshot.replacementSnapshot?.replacement?.replacement == candidate.target
            )
            assertTrue("띄어쓰기 후보 snapshot epoch이 현재 IME와 일치하지 않습니다.",
                snapshot.replacementSnapshot?.inputSessionEpoch == diagnostics.currentEpochBeforeTouch
            )

            val visible = waitForVerifiedVisibleCandidate(configureUiAutomation(), ime, candidate.target)
            diagnostics.a11yBounds = Rect(visible.a11yBounds)
            diagnostics.liveBounds = Rect(visible.liveBounds)
            diagnostics.a11yLiveBoundsMatch = visible.boundsMatch
            assertTrue("접근성/live 후보 좌표가 일치하지 않습니다.", visible.boundsMatch)
            diagnostics.beforeMatchesOriginal = onMain { editor.text.toString() == candidate.original }
            diagnostics.beforeLength = onMain { editor.text.length }
            diagnostics.beforeCursor = onMain { editor.selectionStart }
            onMain {
                val identity = captureUiIdentitySnapshot(ime, candidate)
                diagnostics.uiIdentitySnapshotPresent = identity.present
                diagnostics.uiIdentitySnapshotCursor = identity.cursor
                diagnostics.uiIdentityCursorMatchesSelection = identity.cursor == editor.selectionStart
                diagnostics.uiIdentityReadError = identity.error
            }
            assertTrue("터치 직전 원문이 변경됐습니다.", diagnostics.beforeMatchesOriginal == true)
            diagnostics.touchInjected = injectTouchTap(visible.node, editor, candidate.original)
            assertTrue("저장된 띄어쓰기 후보 터치 주입에 실패했습니다.", diagnostics.touchInjected == true)
            diagnostics.afterMatchesTarget = waitForEditorTextWithoutContent(editor, candidate.target)
            assertTrue("저장된 띄어쓰기 후보가 정확히 한 번 교체되지 않았습니다.",
                diagnostics.afterMatchesTarget == true)
        } finally {
            captureReplacementTouchDiagnosticsAfter(diagnostics, activity)
            reportReplacementTouchDiagnostics(diagnostics)
            activity.finish()
        }
    }

    private fun findStoredSpacingCandidate(): StoredSpacingCandidate? {
        val bank = FcitxApplication.getInstance().generatedSentenceBank
        bank.load()
        GeneratedMaterialPolicy.PREFIXES.forEach { prefix ->
            bank.complete(prefix, 3).forEach { match ->
                val target = prefix + match.suffix
                val original = target.replace(" ", "")
                if (original != target && bank.suggestSpacing(original) == target) {
                    return StoredSpacingCandidate(original, target)
                }
            }
        }
        return null
    }

    private fun waitForStoredSpacingSnapshot(
        ime: FcitxInputMethodService,
        candidate: StoredSpacingCandidate
    ): ContextualCandidate {
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = onMain { ime.getContextualCandidateSnapshot(sentenceLimit = 2) }
            snapshot.sentences.firstOrNull { contextual ->
                val replacement = contextual.replacementSnapshot?.replacement
                contextual.metricsCandidate?.source == "ondevice_generated_spacing" &&
                    contextual.word.comment == "기기 AI 띄어쓰기" &&
                    contextual.word.text == candidate.target &&
                    replacement != null &&
                    replacement.expectedContext == candidate.original &&
                    replacement.replacement == candidate.target
            }?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("저장된 Gemma 띄어쓰기 후보 snapshot이 나타나지 않았습니다.")
    }

    private fun configureUiAutomation(): UiAutomation = instrumentation.uiAutomation.apply {
        val info = requireNotNull(serviceInfo) { "UiAutomation 접근성 서비스 정보가 없습니다." }
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
    }

    private fun waitForVerifiedVisibleCandidate(
        automation: UiAutomation,
        ime: FcitxInputMethodService,
        target: String
    ): VerifiedCandidate {
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            automation.windows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .mapNotNull { it.root }
                .mapNotNull { root -> findVisibleCandidateNode(root, target) }
                .firstOrNull()
                ?.let { node ->
                    if (!node.refresh()) return@let
                    val a11yBounds = Rect().also(node::getBoundsInScreen)
                    val liveBounds = liveCandidateBounds(ime, target) ?: return@let
                    if (a11yBounds != liveBounds) return@let
                    return VerifiedCandidate(
                        node = node,
                        a11yBounds = a11yBounds,
                        liveBounds = liveBounds,
                        boundsMatch = a11yBounds == liveBounds
                    )
                }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("저장된 Gemma 띄어쓰기 후보의 접근성/live 좌표를 확인하지 못했습니다.")
    }

    private fun liveCandidateBounds(ime: FcitxInputMethodService, target: String): Rect? {
        var bounds: Rect? = null
        instrumentation.runOnMainSync {
            val decor = ime.window.window?.decorView ?: return@runOnMainSync
            val textView = decor.findTextViewWithText(target) ?: return@runOnMainSync
            val clickable = textView.findClickableViewAncestor() ?: return@runOnMainSync
            val candidateBoundsInRoot = Rect()
            val rootLocation = IntArray(2)
            val clickableLocation = IntArray(2)
            if (textView.rootView === decor && textView.isShown && clickable.isShown &&
                clickable.width > 0 && clickable.height > 0 && !clickable.isLayoutRequested &&
                clickable.getGlobalVisibleRect(candidateBoundsInRoot)
            ) {
                decor.getLocationOnScreen(rootLocation)
                clickable.getLocationOnScreen(clickableLocation)
                val candidateBounds = candidateBoundsInRoot.toScreenRect(rootLocation)
                val unclippedBounds = Rect(
                    clickableLocation[0],
                    clickableLocation[1],
                    clickableLocation[0] + clickable.width,
                    clickableLocation[1] + clickable.height
                )
                if (candidateBounds == unclippedBounds) bounds = candidateBounds
            }
        }
        return bounds
    }

    private fun Rect.toScreenRect(rootLocation: IntArray): Rect = Rect(this).apply {
        offset(rootLocation[0], rootLocation[1])
    }

    private fun findVisibleCandidateNode(
        node: AccessibilityNodeInfo,
        target: String
    ): AccessibilityNodeInfo? {
        if (node.text?.toString() == target) {
            findClickableAncestor(node)?.let { candidate ->
                val bounds = Rect().also(candidate::getBoundsInScreen)
                if (candidate.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0) return candidate
            }
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                findVisibleCandidateNode(child, target)?.let { return it }
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
        expectedText: String
    ): Boolean {
        if (onMain { editor.text.toString() } != expectedText) return false
        val bounds = Rect().also(node::getBoundsInScreen)
        val display = instrumentation.targetContext.resources.displayMetrics
        if (!node.isVisibleToUser || bounds.width() <= 0 || bounds.height() <= 0 ||
            bounds.left < 0 || bounds.top < 0 || bounds.right > display.widthPixels || bounds.bottom > display.heightPixels
        ) return false
        val automation = instrumentation.uiAutomation
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            bounds.exactCenterX(),
            bounds.exactCenterY(),
            0
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val downInjected = try {
            automation.injectInputEvent(down, true)
        } finally {
            down.recycle()
        }
        if (!downInjected) return false
        SystemClock.sleep(TOUCH_UP_DELAY_MS)
        val up = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            bounds.exactCenterX(),
            bounds.exactCenterY(),
            0
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        return try {
            automation.injectInputEvent(up, true)
        } finally {
            up.recycle()
        }
    }

    private fun captureUiIdentitySnapshot(
        ime: FcitxInputMethodService,
        candidate: StoredSpacingCandidate
    ): UiIdentitySnapshot {
        return try {
            val inputView = ime.javaClass.getDeclaredField("inputView").apply { isAccessible = true }.get(ime)
                ?: return UiIdentitySnapshot(present = false, cursor = null, error = "input-view-unavailable")
            val component = inputView.javaClass.getDeclaredField("horizontalCandidate").apply { isAccessible = true }
                .get(inputView)
            val snapshots = component.javaClass.getDeclaredField("contextualReplacementSnapshots").apply {
                isAccessible = true
            }.get(component) as? Map<*, *>
                ?: return UiIdentitySnapshot(present = false, cursor = null, error = "snapshot-map-unavailable")
            val snapshot = snapshots.values.filterIsInstance<ContextualReplacementSnapshot>().firstOrNull {
                it.replacement.expectedContext == candidate.original && it.replacement.replacement == candidate.target
            }
            UiIdentitySnapshot(present = snapshot != null, cursor = snapshot?.cursor, error = null)
        } catch (error: ReflectiveOperationException) {
            UiIdentitySnapshot(present = false, cursor = null, error = error.javaClass.simpleName)
        }
    }

    private fun captureReplacementTouchDiagnosticsAfter(
        diagnostics: ReplacementTouchDiagnostics,
        activity: AiEditorTestActivity
    ) = onMain {
        val editor = activity.window.decorView.findSingleEditText()
        val ime = FcitxInputMethodService.activeInstance
        diagnostics.afterLength = editor?.text?.length
        diagnostics.afterCursor = editor?.selectionStart
        diagnostics.currentEpochAfterTouch = ime?.currentInputSessionEpoch
        val contextual = ime?.getContextualCandidateSnapshot(sentenceLimit = 2)?.sentences
            ?.firstOrNull { it.replacementSnapshot != null }
        diagnostics.currentSource = contextual?.metricsCandidate?.source ?: diagnostics.currentSource
        diagnostics.replacementPresent = contextual?.replacementSnapshot != null || diagnostics.replacementPresent == true
    }

    private fun requestEditorFocusAndIme(activity: AiEditorTestActivity, editor: EditText) = onMain {
        editor.requestFocus()
        val inputMethodManager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun waitForEditorTextWithoutContent(editor: EditText, expected: String): Boolean {
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain { editor.text.toString() == expected }) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return onMain { editor.text.toString() == expected }
    }

    private fun waitForSelectionAtEnd(
        ime: FcitxInputMethodService,
        editor: EditText,
        expectedCursor: Int
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain {
                    ime.currentInputSelection.start == expectedCursor &&
                        ime.currentInputSelection.end == expectedCursor &&
                        editor.selectionStart == expectedCursor &&
                        editor.selectionEnd == expectedCursor
                }
            ) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun replacementSnapshot(
        ime: FcitxInputMethodService,
        expectedContext: String,
        replacement: String
    ): ContextualReplacementSnapshot = ContextualReplacementSnapshot(
        replacement = ContextualReplacement(expectedContext, replacement),
        inputSessionEpoch = ime.currentInputSessionEpoch,
        cursor = expectedContext.length
    )

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

    private fun waitForCurrentEditor(target: EditorTarget): FcitxInputMethodService {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        var latest = currentEditorState(target)
        if (latest.matches) return requireNotNull(latest.ime)
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = currentEditorState(target)
            if (latest.matches) return requireNotNull(latest.ime)
            SystemClock.sleep(50L)
        }
        throw AssertionError("새글 IME가 debug editor에 연결되지 않았다. expectedTarget=$target; $latest")
    }

    private fun currentEditorState(target: EditorTarget): CurrentEditorState = onMain {
        val ime = FcitxInputMethodService.activeInstance
        val info = ime?.currentInputEditorInfo
        val selection = ime?.currentInputSelection
        val epoch = ime?.currentInputSessionEpoch
        val matches = ime != null && ime.matchesCurrentEditor(
            EditorIdentity(target.packageName, target.fieldId, target.inputType),
            EditorSelection(target.selectionStart, target.selectionEnd),
            expectedInputSessionEpoch = ime.currentInputSessionEpoch
        )
        CurrentEditorState(
            ime,
            ime != null,
            matches,
            info?.packageName,
            info?.fieldId,
            info?.inputType,
            selection?.start,
            selection?.end,
            epoch
        )
    }

    private fun waitForEditorText(editor: EditText, expected: String) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain { editor.text.toString() } == expected) return
            SystemClock.sleep(50L)
        }
        assertEquals(expected, onMain { editor.text.toString() })
    }

    private fun assertEditorUnchanged(editor: EditText, text: String, selection: Pair<Int, Int>) {
        onMain {
            assertEquals(text, editor.text.toString())
            assertEquals(selection, editor.selectionStart to editor.selectionEnd)
        }
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

    private fun View.findSingleEditText(): EditText? {
        if (this is EditText) return this
        return (this as? ViewGroup)?.children
            ?.firstNotNullOfOrNull { child -> child.findSingleEditText() }
    }

    private val ViewGroup.children: Sequence<View>
        get() = sequence {
            for (index in 0 until childCount) {
                yield(getChildAt(index))
            }
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
        val activeInstancePresent: Boolean,
        val matches: Boolean,
        val packageName: String?,
        val fieldId: Int?,
        val inputType: Int?,
        val selectionStart: Int?,
        val selectionEnd: Int?,
        val epoch: Long?
    ) {
        override fun toString(): String =
            "activeInstance=$activeInstancePresent, matches=$matches, " +
                "currentInputEditorInfo(packageName=$packageName, fieldId=$fieldId, inputType=$inputType), " +
                "currentInputSelection(start=$selectionStart, end=$selectionEnd), epoch=$epoch"
    }

    private data class StoredSpacingCandidate(
        val original: String,
        val target: String
    )

    private data class VerifiedCandidate(
        val node: AccessibilityNodeInfo,
        val a11yBounds: Rect,
        val liveBounds: Rect,
        val boundsMatch: Boolean
    )

    private data class UiIdentitySnapshot(
        val present: Boolean,
        val cursor: Int?,
        val error: String?
    )

    private class ReplacementTouchDiagnostics {
        var beforeMatchesOriginal: Boolean? = null
        var beforeLength: Int? = null
        var beforeCursor: Int? = null
        var afterMatchesTarget: Boolean? = null
        var afterLength: Int? = null
        var afterCursor: Int? = null
        var snapshotEpoch: Long? = null
        var snapshotCursor: Int? = null
        var currentEpochBeforeTouch: Long? = null
        var currentEpochAfterTouch: Long? = null
        var a11yBounds: Rect? = null
        var liveBounds: Rect? = null
        var a11yLiveBoundsMatch: Boolean? = null
        var currentSource: String? = null
        var replacementPresent: Boolean? = null
        var touchInjected: Boolean? = null
        var uiIdentitySnapshotPresent: Boolean? = null
        var uiIdentitySnapshotCursor: Int? = null
        var uiIdentityCursorMatchesSelection: Boolean? = null
        var uiIdentityReadError: String? = null
    }

    private fun reportReplacementTouchDiagnostics(diagnostics: ReplacementTouchDiagnostics) {
        instrumentation.sendStatus(0, Bundle().apply {
            putBoolean("replacementTouchBeforeMatchesOriginal", diagnostics.beforeMatchesOriginal == true)
            putInt("replacementTouchBeforeLength", diagnostics.beforeLength ?: -1)
            putInt("replacementTouchBeforeCursor", diagnostics.beforeCursor ?: -1)
            putBoolean("replacementTouchAfterMatchesTarget", diagnostics.afterMatchesTarget == true)
            putInt("replacementTouchAfterLength", diagnostics.afterLength ?: -1)
            putInt("replacementTouchAfterCursor", diagnostics.afterCursor ?: -1)
            putLong("replacementTouchSnapshotEpoch", diagnostics.snapshotEpoch ?: -1L)
            putInt("replacementTouchSnapshotCursor", diagnostics.snapshotCursor ?: -1)
            putLong("replacementTouchCurrentEpochBefore", diagnostics.currentEpochBeforeTouch ?: -1L)
            putLong("replacementTouchCurrentEpochAfter", diagnostics.currentEpochAfterTouch ?: -1L)
            putString("replacementTouchA11yBounds", diagnostics.a11yBounds?.toShortString())
            putString("replacementTouchLiveBounds", diagnostics.liveBounds?.toShortString())
            putBoolean("replacementTouchA11yLiveBoundsMatch", diagnostics.a11yLiveBoundsMatch == true)
            putString("replacementTouchCurrentSource", diagnostics.currentSource)
            putBoolean("replacementTouchReplacementPresent", diagnostics.replacementPresent == true)
            putBoolean("replacementTouchInjected", diagnostics.touchInjected == true)
            putBoolean("replacementTouchUiIdentitySnapshotPresent", diagnostics.uiIdentitySnapshotPresent == true)
            putInt("replacementTouchUiIdentitySnapshotCursor", diagnostics.uiIdentitySnapshotCursor ?: -1)
            putBoolean("replacementTouchUiIdentityCursorMatchesSelection", diagnostics.uiIdentityCursorMatchesSelection == true)
            putString("replacementTouchUiIdentityReadError", diagnostics.uiIdentityReadError)
        })
    }

    private companion object {
        const val UI_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 50L
        const val TOUCH_UP_DELAY_MS = 40L
    }
}
