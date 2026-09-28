/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.debug.AiEditorTestActivity
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationStore
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.EditorIdentity
import org.fcitx.fcitx5.android.input.EditorSelection
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.NumberKeyboard
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.io.File

class NumericSymbolInputLatencyDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun numericAndSymbolKeysAppendWithinOneSecondByActualTouch() = runBlocking {
        val context = instrumentation.targetContext
        val accumulationStore = GemmaAccumulationStore.get(context)
        val automaticPreparationBefore = accumulationStore.load().enabled
        val nativeGeneratingBefore = OnDeviceGenerationControl.isGenerating
        assertFalse("숫자·특수문자 지연 측정은 자동 준비가 꺼진 상태에서만 실행합니다.", automaticPreparationBefore)
        assertFalse("숫자·특수문자 지연 측정 중 native 생성이 실행 중입니다.", nativeGeneratingBefore)

        // ?123 키는 마지막 기호 표면(last_symbol_layout)을 다시 연다. 측정 대상인
        // NumberKeyboard가 반드시 열리도록 측정 전에 고정하고 종료 시 사용자 값으로 복원한다.
        var lastSymbolLayoutPref: String by AppPrefs.getInstance().internal.lastSymbolLayout
        val lastSymbolLayoutBefore = lastSymbolLayoutPref
        lastSymbolLayoutPref = NumberKeyboard.Name

        val results = mutableListOf<KeyLatencyResult>()
        var layoutTransition: LayoutTransitionResult? = null
        var activity: AiEditorTestActivity? = null
        var editorForEvidence: EditText? = null
        var imeForEvidence: FcitxInputMethodService? = null
        var failure: Throwable? = null
        try {
            activity = launchActivity()
            val editor = selectNormalAndClear(requireNotNull(activity))
            editorForEvidence = editor
            requestEditorFocusAndIme(requireNotNull(activity), editor)
            val session = waitForCurrentEditor(editorTarget(editor))
            imeForEvidence = session.ime
            waitForInitialKeyboardLayout(session.ime)
            layoutTransition = switchToNumberKeyboard(session, editor)
            var expectedText = ""
            SYNTHETIC_KEYS.forEach { label ->
                val result = KeyLatencyResult(label = label)
                results += result
                try {
                    assertActiveEditorSession(session, editor)
                    val stableKey = awaitStableVisibleKey(session.ime, label, IME_READY_TIMEOUT_MS)
                    result.editorLength = readEditorLength(editor)
                    reportTouchTarget(stableKey, result.editorLength)
                    val startedAt = SystemClock.elapsedRealtime()
                    result.startedAt = startedAt
                    result.touch = injectTouch(stableKey)
                    assertTrue("숫자·특수문자 키 터치 주입에 실패했습니다: $label", result.touch.injected)
                    expectedText += label
                    val gate = waitForExactEditorText(
                        session = session,
                        editor = editor,
                        expected = expectedText,
                        startedAt = startedAt,
                        deadlineAt = startedAt + KEY_APPLY_TIMEOUT_MS
                    )
                    result.editorAppliedMs = gate.elapsedMs
                    result.editorLength = gate.length
                    result.appendedExactly = gate.matches
                    assertActiveEditorSession(session, editor)
                } catch (error: Throwable) {
                    result.failureType = error.javaClass.simpleName
                    if (result.startedAt >= 0L) {
                        result.editorAppliedMs = SystemClock.elapsedRealtime() - result.startedAt
                    }
                    try {
                        reportKeyEvidence(result)
                    } catch (reportError: Throwable) {
                        error.addSuppressed(reportError)
                    }
                    throw error
                }
                reportKeyEvidence(result)
                assertTrue("키 입력이 ${KEY_APPLY_TIMEOUT_MS}ms 안에 정확히 반영되지 않았습니다: $label", result.appendedExactly)
                assertTrue("키 입력 지연이 ${KEY_APPLY_TIMEOUT_MS}ms를 넘었습니다: $label", result.editorAppliedMs <= KEY_APPLY_TIMEOUT_MS)
            }
        } catch (error: Throwable) {
            failure = error
            try {
                reportFailureDiagnostics(imeForEvidence, editorForEvidence)
            } catch (diagnosticError: Throwable) {
                error.addSuppressed(diagnosticError)
            }
            try {
                activity?.let { captureFailureScreenshot(context) }
            } catch (captureError: Throwable) {
                error.addSuppressed(captureError)
            }
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            try {
                val automaticPreparationAfter = accumulationStore.load().enabled
                val nativeGeneratingAfter = OnDeviceGenerationControl.isGenerating
                reportFinalEvidence(
                    results = results,
                    layoutTransition = layoutTransition,
                    automaticPreparationBefore = automaticPreparationBefore,
                    automaticPreparationAfter = automaticPreparationAfter,
                    nativeGeneratingBefore = nativeGeneratingBefore,
                    nativeGeneratingAfter = nativeGeneratingAfter,
                    lastSymbolLayoutBefore = lastSymbolLayoutBefore,
                    lastSymbolLayoutAfter = lastSymbolLayoutPref,
                    failure = failure
                )
                assertFalse("숫자·특수문자 지연 측정이 자동 준비 상태를 변경했습니다.", automaticPreparationAfter)
                assertFalse("숫자·특수문자 지연 측정이 native 생성을 시작했습니다.", nativeGeneratingAfter)
            } catch (error: Throwable) {
                cleanupFailure = error
            }
            try {
                lastSymbolLayoutPref = lastSymbolLayoutBefore
            } catch (restoreError: Throwable) {
                if (cleanupFailure == null) cleanupFailure = restoreError else cleanupFailure.addSuppressed(restoreError)
            }
            try {
                activity?.let(::finishActivity)
            } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure.addSuppressed(error)
            }
            if (failure != null) {
                cleanupFailure?.let(failure::addSuppressed)
            } else {
                cleanupFailure?.let { throw it }
            }
        }
    }

    private fun switchToNumberKeyboard(
        session: EditorSession,
        editor: EditText
    ): LayoutTransitionResult {
        assertActiveEditorSession(session, editor)
        if (hasNumberKeyboardReadyKeys(session.ime)) {
            val stableAbc = awaitStableVisibleKey(session.ime, "ABC", IME_READY_TIMEOUT_MS)
            reportTouchTarget(stableAbc, readEditorLength(editor))
            val abcTouch = injectTouch(stableAbc)
            assertTrue("NumberKeyboard의 ABC 전환 키 터치 주입에 실패했습니다.", abcTouch.injected)
            waitForVisibleKey(session.ime, "?123", KEY_APPLY_TIMEOUT_MS)
            assertActiveEditorSession(session, editor)
        }
        val stableNumberSwitch = awaitStableVisibleKey(session.ime, "?123", IME_READY_TIMEOUT_MS)
        assertActiveEditorSession(session, editor)
        reportTouchTarget(stableNumberSwitch, readEditorLength(editor))
        val startedAt = SystemClock.elapsedRealtime()
        val switchTouch = injectTouch(stableNumberSwitch)
        assertTrue("?123 NumberKeyboard 전환 키 터치 주입에 실패했습니다.", switchTouch.injected)
        val ready = waitForNumberKeyboardReady(session.ime, KEY_APPLY_TIMEOUT_MS)
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val transition = LayoutTransitionResult(
            switchTouchInjected = switchTouch.injected,
            numberReady = ready,
            layoutTransitionMs = elapsedMs
        )
        reportLayoutEvidence(transition)
        assertTrue("?123 전환 뒤 NumberKeyboard의 1 및 !?# 키를 확인하지 못했습니다.", transition.numberReady)
        assertTrue("NumberKeyboard 전환 지연이 ${KEY_APPLY_TIMEOUT_MS}ms를 넘었습니다.",
            transition.layoutTransitionMs <= KEY_APPLY_TIMEOUT_MS)
        assertActiveEditorSession(session, editor)
        return transition
    }

    private fun hasNumberKeyboardReadyKeys(ime: FcitxInputMethodService): Boolean =
        findVisibleKey(ime, "1") != null && findVisibleKey(ime, "!?#") != null

    private fun waitForInitialKeyboardLayout(ime: FcitxInputMethodService) {
        val deadline = SystemClock.elapsedRealtime() + IME_READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (findVisibleKey(ime, "?123") != null || hasNumberKeyboardReadyKeys(ime)) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("IME 입력 뷰의 초기 ?123 또는 NumberKeyboard 준비를 ${IME_READY_TIMEOUT_MS}ms 안에 확인하지 못했습니다.")
    }

    private fun waitForNumberKeyboardReady(ime: FcitxInputMethodService, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (hasNumberKeyboardReadyKeys(ime)) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return hasNumberKeyboardReadyKeys(ime)
    }

    private fun waitForVisibleKey(ime: FcitxInputMethodService, label: String, timeoutMs: Long): KeyTarget {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            findVisibleKey(ime, label)?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return requireNotNull(findVisibleKey(ime, label)) { "IME 키를 찾지 못했습니다: $label" }
    }

    private fun awaitStableVisibleKey(
        ime: FcitxInputMethodService,
        label: String,
        timeoutMs: Long
    ): KeyTarget {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var previous: KeyTarget? = null
        var matchingSamples = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = findVisibleKey(ime, label)
            if (current != null && current == previous) {
                matchingSamples++
            } else {
                previous = current
                matchingSamples = if (current == null) 0 else 1
            }
            if (matchingSamples >= STABLE_KEY_SAMPLE_COUNT) return requireNotNull(current)
            SystemClock.sleep(STABLE_KEY_SAMPLE_INTERVAL_MS)
        }
        throw AssertionError("IME 키 $label 의 표시·부착·레이아웃과 bounds가 ${timeoutMs}ms 안에 ${STABLE_KEY_SAMPLE_COUNT}회 안정되지 않았습니다.")
    }

    private fun findVisibleKey(ime: FcitxInputMethodService, label: String): KeyTarget? = onMain {
        val inputView = ime.javaClass.getDeclaredField("inputView").apply { isAccessible = true }.get(ime) as? View
            ?: return@onMain null
        for (text in inputView.findTextViewsWithText(label)) {
            val keyView = text.findKeyViewAncestor() ?: continue
            if (
                !text.isShown || !text.isAttachedToWindow || text.isLayoutRequested ||
                !keyView.isShown || !keyView.isAttachedToWindow ||
                keyView.width <= 0 || keyView.height <= 0 || keyView.isLayoutRequested ||
                inputView.isLayoutRequested
            ) continue
            val visibleInInputRoot = Rect()
            if (!keyView.getGlobalVisibleRect(visibleInInputRoot) || visibleInInputRoot.width() <= 0 || visibleInInputRoot.height() <= 0) {
                continue
            }
            val rootLocation = IntArray(2)
            keyView.rootView.getLocationOnScreen(rootLocation)
            val visibleOnScreen = Rect(visibleInInputRoot).apply {
                offset(rootLocation[0], rootLocation[1])
            }
            val keyLocation = IntArray(2)
            keyView.getLocationOnScreen(keyLocation)
            val keyBoundsOnScreen = Rect(
                keyLocation[0],
                keyLocation[1],
                keyLocation[0] + keyView.width,
                keyLocation[1] + keyView.height
            )
            val inputViewLocation = IntArray(2)
            inputView.getLocationOnScreen(inputViewLocation)
            val inputBoundsOnScreen = Rect(
                inputViewLocation[0],
                inputViewLocation[1],
                inputViewLocation[0] + inputView.width,
                inputViewLocation[1] + inputView.height
            )
            val touchBounds = Rect(keyBoundsOnScreen)
            if (!touchBounds.intersect(inputBoundsOnScreen) || !touchBounds.intersect(visibleOnScreen)) continue
            return@onMain KeyTarget(label = label, bounds = touchBounds)
        }
        null
    }

    private fun injectTouch(target: KeyTarget): TouchResult {
        val screen = instrumentation.targetContext.resources.displayMetrics
        if (target.bounds.left < 0 || target.bounds.top < 0 || target.bounds.right > screen.widthPixels ||
            target.bounds.bottom > screen.heightPixels
        ) return TouchResult(injected = false)
        val automation = instrumentation.uiAutomation
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            target.bounds.exactCenterX(),
            target.bounds.exactCenterY(),
            0
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val downInjected = try {
            automation.injectInputEvent(down, true)
        } finally {
            down.recycle()
        }
        if (!downInjected) return TouchResult(injected = false)
        val up = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            target.bounds.exactCenterX(),
            target.bounds.exactCenterY(),
            0
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val upInjected = try {
            automation.injectInputEvent(up, true)
        } finally {
            up.recycle()
        }
        return TouchResult(injected = downInjected && upInjected)
    }

    private fun reportFailureDiagnostics(
        ime: FcitxInputMethodService?,
        editor: EditText?
    ) {
        val diagnostic = onMain {
            val inputView = ime?.let(::imeInputView)
            JSONObject()
                .put("event", "numeric_symbol_failure_diagnostic")
                .put("target", "synthetic_numeric_symbol_sequence")
                .put("editor", editor?.let(::viewDiagnostic) ?: JSONObject.NULL)
                .put("imeInput", inputView?.let(::viewDiagnostic) ?: JSONObject.NULL)
                .put("keys", JSONArray().apply {
                    DIAGNOSTIC_KEY_LABELS.forEach { label ->
                        put(keyDiagnostic(inputView, label))
                    }
                })
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("numericSymbolLatencyFailureDiagnostic", diagnostic.toString())
        })
    }

    private fun keyDiagnostic(inputView: View?, label: String): JSONObject {
        val diagnostic = JSONObject().put("label", label)
        if (inputView == null) return diagnostic.put("found", false).put("inputViewAvailable", false)
        val text = inputView.findTextViewWithText(label)
            ?: return diagnostic.put("found", false).put("inputViewAvailable", true)
        val keyView = text.findKeyViewAncestor()
            ?: return diagnostic.put("found", false).put("textShown", text.isShown).put("keyViewAvailable", false)
        val keyVisible = Rect()
        val keyVisiblePresent = keyView.getGlobalVisibleRect(keyVisible)
        val keyLocation = IntArray(2)
        keyView.getLocationOnScreen(keyLocation)
        val keyScreenBounds = Rect(
            keyLocation[0],
            keyLocation[1],
            keyLocation[0] + keyView.width,
            keyLocation[1] + keyView.height
        )
        val inputLocation = IntArray(2)
        inputView.getLocationOnScreen(inputLocation)
        val inputScreenBounds = Rect(
            inputLocation[0],
            inputLocation[1],
            inputLocation[0] + inputView.width,
            inputLocation[1] + inputView.height
        )
        val rootLocation = IntArray(2)
        keyView.rootView.getLocationOnScreen(rootLocation)
        val existingTestVisibleBounds = Rect(keyVisible).apply { offset(rootLocation[0], rootLocation[1]) }
        val existingTestTouchBounds = Rect(keyScreenBounds).also { bounds ->
            if (!bounds.intersect(inputScreenBounds) || !bounds.intersect(existingTestVisibleBounds)) bounds.setEmpty()
        }
        return diagnostic
            .put("found", true)
            .put("textShown", text.isShown)
            .put("keyShown", keyView.isShown)
            .put("keyWindowFocus", keyView.hasWindowFocus())
            .put("inputWindowFocus", inputView.hasWindowFocus())
            .put("keyVisiblePresent", keyVisiblePresent)
            .put("keyGlobalVisibleRect", rectJson(keyVisible))
            .put("keyScreenBounds", rectJson(keyScreenBounds))
            .put("keyRootBounds", viewDiagnostic(keyView.rootView))
            .put("inputScreenBounds", rectJson(inputScreenBounds))
            .put("existingTestVisibleBounds", rectJson(existingTestVisibleBounds))
            .put("existingTestTouchBounds", rectJson(existingTestTouchBounds))
    }

    private fun viewDiagnostic(view: View): JSONObject {
        val visible = Rect()
        val visiblePresent = view.getGlobalVisibleRect(visible)
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val root = view.rootView
        val rootVisible = Rect()
        root.getGlobalVisibleRect(rootVisible)
        return JSONObject()
            .put("shown", view.isShown)
            .put("windowFocus", view.hasWindowFocus())
            .put("width", view.width)
            .put("height", view.height)
            .put("screenX", location[0])
            .put("screenY", location[1])
            .put("globalVisiblePresent", visiblePresent)
            .put("globalVisibleRect", rectJson(visible))
            .put("rootGlobalRect", rectJson(rootVisible))
    }

    private fun rectJson(rect: Rect): JSONObject = JSONObject()
        .put("left", rect.left)
        .put("top", rect.top)
        .put("right", rect.right)
        .put("bottom", rect.bottom)

    private fun imeInputView(ime: FcitxInputMethodService): View? =
        ime.javaClass.getDeclaredField("inputView").apply { isAccessible = true }.get(ime) as? View

    private fun captureFailureScreenshot(context: Context) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "숫자·특수문자 실패 시 synthetic editor/IME 스크린샷을 가져오지 못했습니다."
        }
        val directory = requireNotNull(context.getExternalFilesDir("numeric-symbol-input-latency")) {
            "숫자·특수문자 실패 스크린샷 디렉터리를 만들 수 없습니다."
        }
        val screenshot = File(directory, "numeric-symbol-failure-${SystemClock.elapsedRealtime()}.png")
        try {
            screenshot.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "숫자·특수문자 실패 스크린샷 PNG 저장에 실패했습니다."
                }
            }
        } finally {
            bitmap.recycle()
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("numericSymbolLatencyFailureScreenshotPath", screenshot.absolutePath)
        })
    }

    private fun waitForExactEditorText(
        session: EditorSession,
        editor: EditText,
        expected: String,
        startedAt: Long,
        deadlineAt: Long
    ): EditorGate {
        while (SystemClock.elapsedRealtime() < deadlineAt) {
            assertActiveEditorSession(session, editor)
            val snapshot = onMain { EditorSnapshot(editor.text.toString(), editor.text.length) }
            if (snapshot.text == expected) {
                return EditorGate(matches = true, elapsedMs = SystemClock.elapsedRealtime() - startedAt, length = snapshot.length)
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        assertActiveEditorSession(session, editor)
        val snapshot = onMain { EditorSnapshot(editor.text.toString(), editor.text.length) }
        return EditorGate(
            matches = snapshot.text == expected,
            elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            length = snapshot.length
        )
    }

    private fun waitForCurrentEditor(target: EditorTarget): EditorSession {
        val deadline = SystemClock.elapsedRealtime() + IME_READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val ime = FcitxInputMethodService.activeInstance
            if (ime != null && onMain {
                    ime.matchesCurrentEditor(
                        EditorIdentity(target.packageName, target.fieldId, target.inputType),
                        EditorSelection(target.selectionStart, target.selectionEnd),
                        expectedInputSessionEpoch = ime.currentInputSessionEpoch
                    )
                }
            ) {
                return EditorSession(ime, target.packageName, target.fieldId, target.inputType, ime.currentInputSessionEpoch)
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("새글 IME가 normal debug editor에 ${IME_READY_TIMEOUT_MS}ms 안에 연결되지 않았습니다.")
    }

    private fun assertActiveEditorSession(session: EditorSession, editor: EditText) {
        val matches = onMain {
            val activeIme = FcitxInputMethodService.activeInstance
            activeIme === session.ime && activeIme.currentInputSessionEpoch == session.epoch &&
                activeIme.matchesCurrentEditor(
                    EditorIdentity(session.packageName, session.fieldId, session.inputType),
                    EditorSelection(editor.selectionStart, editor.selectionEnd),
                    expectedInputSessionEpoch = session.epoch
                )
        }
        assertTrue("측정 대상 editor/package/session이 변경되었습니다. 다른 앱 입력을 계속하지 않습니다.", matches)
    }

    private fun reportLayoutEvidence(result: LayoutTransitionResult) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString("numericSymbolLatencyEvidence", JSONObject()
                .put("event", "layout_transition_measured_before_assertion")
                .put("target", "synthetic_numeric_symbol_sequence")
                .put("switchTouchInjected", result.switchTouchInjected)
                .put("numberReady", result.numberReady)
                .put("layoutTransitionMs", result.layoutTransitionMs)
                .toString())
        })
    }

    private fun reportTouchTarget(target: KeyTarget, editorLength: Int) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString("numericSymbolLatencyTouchTarget", JSONObject()
                .put("event", "touch_target_stable_before_injection")
                .put("target", "synthetic_numeric_symbol_sequence")
                .put("keyLabel", target.label)
                .put("targetBounds", rectJson(target.bounds))
                .put("touchX", target.bounds.exactCenterX())
                .put("touchY", target.bounds.exactCenterY())
                .put("editorLength", editorLength)
                .toString())
        })
    }

    private fun reportKeyEvidence(result: KeyLatencyResult) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString("numericSymbolLatencyEvidence", JSONObject()
                .put("event", "key_measured_before_threshold_assertion")
                .put("target", "synthetic_numeric_symbol_sequence")
                .put("keyLabel", result.label)
                .put("touchInjected", result.touch.injected)
                .put("editorAppliedMs", result.editorAppliedMs)
                .put("editorLength", result.editorLength)
                .put("appendedExactly", result.appendedExactly)
                .put("failureType", result.failureType)
                .toString())
        })
    }

    private fun reportFinalEvidence(
        results: List<KeyLatencyResult>,
        layoutTransition: LayoutTransitionResult?,
        automaticPreparationBefore: Boolean,
        automaticPreparationAfter: Boolean,
        nativeGeneratingBefore: Boolean,
        nativeGeneratingAfter: Boolean,
        lastSymbolLayoutBefore: String,
        lastSymbolLayoutAfter: String,
        failure: Throwable?
    ) {
        val perKey = JSONArray().apply {
            results.forEach { result ->
                put(JSONObject()
                    .put("keyLabel", result.label)
                    .put("touchInjected", result.touch.injected)
                    .put("editorAppliedMs", result.editorAppliedMs)
                    .put("editorLength", result.editorLength)
                    .put("appendedExactly", result.appendedExactly)
                    .put("failureType", result.failureType))
            }
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("numericSymbolLatencyFinalJson", JSONObject()
                .put("scope", "actual touchscreen numeric and symbol key latency")
                .put("target", "synthetic_numeric_symbol_sequence")
                .put("actualUserTextLogged", false)
                .put("keyCount", results.size)
                .put("perKey", perKey)
                .put("layoutTransitionMs", layoutTransition?.layoutTransitionMs)
                .put("layoutNumberReady", layoutTransition?.numberReady)
                .put("automaticPreparationBefore", automaticPreparationBefore)
                .put("automaticPreparationAfter", automaticPreparationAfter)
                .put("nativeGeneratingBefore", nativeGeneratingBefore)
                .put("nativeGeneratingAfter", nativeGeneratingAfter)
                .put("lastSymbolLayoutBefore", lastSymbolLayoutBefore)
                .put("lastSymbolLayoutAfter", lastSymbolLayoutAfter)
                .put("failureType", failure?.javaClass?.simpleName)
                .toString())
        })
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
        val manager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun finishActivity(activity: AiEditorTestActivity) = onMain { activity.finish(); Unit }

    private fun editorTarget(editor: EditText): EditorTarget = onMain {
        EditorTarget(editor.context.packageName, editor.id, editor.inputType, editor.selectionStart, editor.selectionEnd)
    }

    private fun readEditorLength(editor: EditText): Int = onMain { editor.text.length }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(Callable { block() })
        assertTrue("main thread 작업을 예약하지 못했습니다.", Handler(Looper.getMainLooper()).post(task))
        try {
            return task.get(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            task.cancel(false)
            throw AssertionError("main thread 작업이 ${MAIN_THREAD_TIMEOUT_MS}ms 안에 끝나지 않았습니다.", error)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private fun View.findByContentDescription(description: String): View? {
        if (contentDescription?.toString() == description) return this
        return (this as? ViewGroup)?.children?.firstNotNullOfOrNull { it.findByContentDescription(description) }
    }

    private fun View.findTextViewsWithText(label: String): Sequence<TextView> = sequence {
        if (this@findTextViewsWithText is TextView && text?.toString() == label) {
            yield(this@findTextViewsWithText)
        }
        (this@findTextViewsWithText as? ViewGroup)?.children?.forEach { child ->
            yieldAll(child.findTextViewsWithText(label))
        }
    }

    private fun View.findTextViewWithText(label: String): TextView? =
        findTextViewsWithText(label).firstOrNull()

    private fun View.findKeyViewAncestor(): KeyView? {
        var current: View? = this
        while (current != null) {
            if (current is KeyView) return current
            current = current.parent as? View
        }
        return null
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

    private data class EditorSession(
        val ime: FcitxInputMethodService,
        val packageName: String,
        val fieldId: Int,
        val inputType: Int,
        val epoch: Long
    )

    private data class KeyTarget(val label: String, val bounds: Rect)

    private data class TouchResult(val injected: Boolean)

    private data class EditorSnapshot(val text: String, val length: Int)

    private data class EditorGate(val matches: Boolean, val elapsedMs: Long, val length: Int)

    private data class LayoutTransitionResult(
        val switchTouchInjected: Boolean,
        val numberReady: Boolean,
        val layoutTransitionMs: Long
    )

    private data class KeyLatencyResult(
        val label: String,
        var touch: TouchResult = TouchResult(false),
        var startedAt: Long = -1L,
        var editorAppliedMs: Long = -1L,
        var editorLength: Int = -1,
        var appendedExactly: Boolean = false,
        var failureType: String? = null
    )

    private companion object {
        const val IME_READY_TIMEOUT_MS = 10_000L
        const val KEY_APPLY_TIMEOUT_MS = 1_000L
        const val MAIN_THREAD_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 20L
        const val STABLE_KEY_SAMPLE_INTERVAL_MS = 100L
        const val STABLE_KEY_SAMPLE_COUNT = 3
        val DIAGNOSTIC_KEY_LABELS = listOf("?123", "ABC", "1", "!?#")
        val SYNTHETIC_KEYS = listOf("1", "2", "+", "3", "4", "-", "5", "6", "*", "7", "8", "/", "9", "0", "=", ".")
    }
}
