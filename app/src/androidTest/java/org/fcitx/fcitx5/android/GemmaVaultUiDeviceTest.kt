/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationScheduler
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationState
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationStore
import org.fcitx.fcitx5.android.debug.gemma.GemmaExperimentActivity
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelFiles
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.fcitx.fcitx5.android.ui.main.ai.TypingDnaDashboardActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

class GemmaVaultUiDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun modelFreeVaultControlsAutomaticPreparationAndReturnsFromModelManagement() = runBlocking(Dispatchers.Default) {
        val context = instrumentation.targetContext
        val captureName = requireCaptureName()
        val offlineModeForTest = InstrumentationRegistry.getArguments()
            .getString(VAULT_UI_OFFLINE_MODE_ARGUMENT) == "true"
        val originalOfflineMode = if (offlineModeForTest) {
            AppPrefs.getInstance().advanced.offlineMode.getValue()
        } else {
            null
        }
        val store = GemmaAccumulationStore.get(context)
        val model = GemmaModelFiles.modelFile(context)
        val initial = store.load()
        assertFalse("이 검증은 모델 없는 에뮬레이터에서만 실행합니다: ${model.absolutePath}", model.exists())
        assertFalse("이 검증은 자동 준비가 꺼진 상태에서만 실행합니다.", initial.enabled)
        assertFalse("이 검증은 수동 강화 요청이 없는 상태에서만 실행합니다.", initial.manualRequested)
        assertFalse("이 검증을 시작하기 전에 온디바이스 생성이 실행 중입니다.", OnDeviceGenerationControl.isGenerating)
        assertNoUnfinishedWork(context)

        var dashboard: TypingDnaDashboardActivity? = null
        var failure: Throwable? = null
        try {
            if (offlineModeForTest) {
                AppPrefs.getInstance().advanced.offlineMode.setValue(true)
                check(AppPrefs.getInstance().advanced.offlineMode.getValue()) {
                    "금고 UI 오프라인 계측 모드를 켜지 못했습니다."
                }
            }
            dashboard = instrumentation.startActivitySync(
                Intent(context, TypingDnaDashboardActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            ) as TypingDnaDashboardActivity
            awaitVaultReady(requireNotNull(dashboard))
            captureVaultScreen(context, captureName, "top", requireNotNull(dashboard))

            awaitAutomaticState(requireNotNull(dashboard), checked = false)
            onMain {
                val root = requireNotNull(dashboard).window.decorView
                val automatic = requireAutomaticControl(root)
                val stop = requireView(root, R.id.gemma_vault_stop)
                assertFalse("금고 자동 준비 스위치가 초기 상태에서 켜져 있습니다.", automatic.isChecked)
                assertFalse("모델 없는 유휴 금고에서 보강 중지 버튼이 표시됩니다.", stop.isShown)
            }
            assertInitialVaultText(requireNotNull(dashboard))
            val initialLayout = assertVaultLayout(requireNotNull(dashboard))
            scrollToControls(requireNotNull(dashboard))
            captureVaultScreen(context, captureName, "controls", requireNotNull(dashboard))

            val personalSync = assertModellessIntegratedStrengthening(
                dashboard = requireNotNull(dashboard),
                store = store
            )
            val personalResult = onMain {
                requireText(requireNotNull(dashboard).window.decorView, R.id.gemma_vault_personal_result)
            }
            scrollIntoView(personalResult)
            onMain {
                assertVisibleText("개인 입력 반영 결과", personalResult)
                assertNoTextClipping(personalResult)
                assertTrue(
                    "개인 입력 반영 결과 본문은 16sp보다 작으면 안 됩니다: ${personalResult.text}",
                    personalResult.textSize + BODY_TEXT_TOLERANCE_PX >= minimumBodyTextPx(personalResult)
                )
            }
            captureVaultScreen(context, captureName, "personal-result", requireNotNull(dashboard))
            awaitVaultReady(requireNotNull(dashboard))

            val automatic = onMain {
                requireAutomaticControl(requireNotNull(dashboard).window.decorView)
            }
            scrollIntoView(automatic)
            tapControl("자동 준비", automatic)
            val enabled = awaitState(store) { it.enabled }
            assertTrue("자동 준비를 켠 뒤 enabled 상태가 저장되지 않았습니다.", enabled.enabled)
            val periodicAfterEnable = awaitPeriodicWork(context)
            assertTrue("자동 준비를 켠 뒤 periodic WorkManager 작업이 없습니다.", periodicAfterEnable.isNotEmpty())

            awaitAutomaticState(requireNotNull(dashboard), checked = true)
            scrollIntoView(automatic)
            onMain { assertTrue("저장된 자동 준비 상태가 UI에 반영되지 않았습니다.", automatic.isChecked) }
            tapControl("자동 준비", automatic)
            val disabled = awaitState(store) { !it.enabled && !it.manualRequested }
            assertFalse("자동 준비를 끈 뒤 enabled 상태가 남아 있습니다.", disabled.enabled)
            assertFalse("자동 준비를 끈 뒤 수동 강화 요청이 남아 있습니다.", disabled.manualRequested)
            awaitNoUnfinishedWork(context)
            assertFalse("모델 없는 자동 준비 조작이 native 추론을 시작했습니다.", OnDeviceGenerationControl.isGenerating)

            assertModelManagementRoundTrip(requireNotNull(dashboard))
            awaitVaultReady(requireNotNull(dashboard))

            val evidence = JSONObject()
                .put("modelPresent", false)
                .put("modelExecution", false)
                .put("offlineMode", offlineModeForTest)
                .put("offlineModeOriginal", originalOfflineMode ?: JSONObject.NULL)
                .put("initial", stateJson(initial))
                .put("layout", initialLayout)
                .put(
                    "toggle",
                    JSONObject()
                        .put("enabledStored", enabled.enabled)
                        .put("periodicWorkCreated", periodicAfterEnable.isNotEmpty())
                        .put("disabledStored", !disabled.enabled)
                        .put("manualRequestedCleared", !disabled.manualRequested)
                        .put("relatedWorkCancelled", noUnfinishedWork(context))
                )
                .put(
                    "integratedStrengthening",
                    personalSync
                )
                .put(
                    "navigation",
                    JSONObject()
                        .put("modellessStrengtheningStayedInVault", true)
                        .put("modelManagementOpened", true)
                        .put("returnedToVault", true)
                )
            instrumentation.sendStatus(
                0,
                Bundle().apply { putString("GemmaVaultUiEvidence", evidence.toString()) }
            )
        } catch (error: Throwable) {
            failure = error
            dashboard?.let { activity ->
                try {
                    reportFailureState(activity)
                } catch (diagnosticError: Throwable) {
                    error.addSuppressed(diagnosticError)
                }
                try {
                    captureVaultScreenRaw(context, captureName, "failed-raw")
                } catch (captureError: Throwable) {
                    error.addSuppressed(captureError)
                }
            }
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            try {
                cleanup(store, context, dashboard, failure)
            } catch (error: Throwable) {
                cleanupFailure = error
            }
            var offlineRestoreFailure: Throwable? = null
            try {
                restoreOfflineMode(offlineModeForTest, originalOfflineMode)
            } catch (error: Throwable) {
                offlineRestoreFailure = error
            }
            if (failure != null) {
                cleanupFailure?.let(failure::addSuppressed)
                offlineRestoreFailure?.let(failure::addSuppressed)
            } else if (cleanupFailure != null) {
                offlineRestoreFailure?.let(cleanupFailure::addSuppressed)
                throw cleanupFailure
            } else {
                offlineRestoreFailure?.let { throw it }
            }
        }
    }

    private suspend fun cleanup(
        store: GemmaAccumulationStore,
        context: android.content.Context,
        dashboard: TypingDnaDashboardActivity?,
        originalFailure: Throwable?
    ) {
        var cleanupFailure: Throwable? = null
        try {
            GemmaAccumulationScheduler.setEnabled(context, false)
            val state = store.load()
            check(!state.enabled && !state.manualRequested) {
                "Gemma 금고 UI 검증 정리가 자동 준비 상태를 끄지 못했습니다."
            }
        } catch (error: Throwable) {
            cleanupFailure = error
        }
        try {
            dashboard?.let { activity ->
                instrumentation.runOnMainSync { activity.finish() }
            }
        } catch (error: Throwable) {
            cleanupFailure?.addSuppressed(error) ?: run { cleanupFailure = error }
        }
        cleanupFailure?.let { error ->
            if (originalFailure != null) originalFailure.addSuppressed(error) else throw error
        }
    }

    private fun restoreOfflineMode(offlineModeForTest: Boolean, originalOfflineMode: Boolean?) {
        if (!offlineModeForTest) return
        val original = requireNotNull(originalOfflineMode) {
            "오프라인 계측 모드의 기존 값을 보관하지 못했습니다."
        }
        AppPrefs.getInstance().advanced.offlineMode.setValue(original)
        val restored = AppPrefs.getInstance().advanced.offlineMode.getValue()
        check(restored == original) {
            "금고 UI 오프라인 계측 모드의 기존 값을 복원하지 못했습니다. expected=$original actual=$restored"
        }
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString(
                    "GemmaVaultUiOfflineModeEvidence",
                    JSONObject()
                        .put("offlineMode", true)
                        .put("original", original)
                        .put("restored", restored)
                        .toString()
                )
            }
        )
    }

    private suspend fun assertModellessIntegratedStrengthening(
        dashboard: TypingDnaDashboardActivity,
        store: GemmaAccumulationStore
    ): JSONObject {
        val monitor = instrumentation.addMonitor(GemmaExperimentActivity::class.java.name, null, false)
        try {
            awaitEnabledControl(dashboard, R.id.gemma_vault_generate)
            val generate = onMain {
                val root = dashboard.window.decorView
                val control = requireView(root, R.id.gemma_vault_generate)
                assertEquals(
                    "통합 보강 버튼의 문구가 다릅니다.",
                    "언어 금고 보강",
                    (control as? TextView)?.text?.toString()
                )
                assertEquals(
                    "기존 하단 개인 입력 반영 버튼은 통합 보강 뒤 숨겨져야 합니다.",
                    View.GONE,
                    requireView(root, R.id.btn_sync_now).visibility
                )
                assertEquals(
                    "통합 보강 전 개인 입력 반영 결과가 표시됩니다.",
                    View.GONE,
                    requireText(root, R.id.gemma_vault_personal_result).visibility
                )
                control
            }
            scrollIntoView(generate)
            tapControl("언어 금고 보강", generate)
            waitUntil("모델 없는 통합 보강이 개인 상태 완료와 활성화 상태에 도달하지 않았습니다.") {
                onMain {
                    val root = dashboard.window.decorView
                    val personalResult = requireText(root, R.id.gemma_vault_personal_result)
                    val control = requireView(root, R.id.gemma_vault_generate)
                    dashboard.hasWindowFocus() &&
                        dashboard.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        root.isShown &&
                        root.findViewById<View>(R.id.gemma_vault_card)?.isShown == true &&
                        personalResult.isShown &&
                        PERSONAL_SYNC_NORMAL_RESULT.matches(personalResult.text.toString()) &&
                        control.isEnabled && control.isClickable &&
                        (control as? TextView)?.text?.toString() == "언어 금고 보강" &&
                        requireView(root, R.id.btn_sync_now).visibility == View.GONE
                }
            }
            val completed = awaitState(store) { !it.enabled && !it.manualRequested }
            assertFalse("모델 없는 통합 보강이 native 추론을 시작했습니다.", OnDeviceGenerationControl.isGenerating)
            assertEquals("모델 없는 통합 보강이 모델 관리 화면으로 이동했습니다.", 0, monitor.hits)
            return JSONObject()
                .put("label", "언어 금고 보강")
                .put("personalStatusCompleted", true)
                .put("generateReenabled", true)
                .put("stayedInVault", true)
                .put("modelManagementLaunches", monitor.hits)
                .put("automaticEnabled", completed.enabled)
                .put("manualRequested", completed.manualRequested)
                .put("nativeGenerating", OnDeviceGenerationControl.isGenerating)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun assertModelManagementRoundTrip(dashboard: TypingDnaDashboardActivity) {
        val monitor = instrumentation.addMonitor(GemmaExperimentActivity::class.java.name, null, false)
        try {
            awaitEnabledControl(dashboard, R.id.gemma_vault_model)
            val model = onMain {
                requireView(dashboard.window.decorView, R.id.gemma_vault_model)
            }
            scrollIntoView(model)
            tapControl("모델 관리", model)
            val management = requireNotNull(monitor.waitForActivityWithTimeout(NAVIGATION_TIMEOUT_MS)) {
                "모델 관리 버튼이 GemmaExperimentActivity로 이동하지 않았습니다."
            } as GemmaExperimentActivity
            returnToVault(management, dashboard)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun returnToVault(
        management: GemmaExperimentActivity,
        dashboard: TypingDnaDashboardActivity
    ) {
        onMain {
            assertTrue("모델 관리 화면이 표시되지 않았습니다.", management.window.decorView.isShown)
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitUntil("모델 관리 뒤 통합 금고 화면으로 돌아오지 않았습니다.") {
            onMain {
                dashboard.hasWindowFocus() &&
                    dashboard.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    dashboard.window.decorView.isShown &&
                    dashboard.findViewById<View>(R.id.gemma_vault_card)?.isShown == true
            }
        }
        assertFalse("모델 관리에서 돌아온 뒤 native 추론이 시작되었습니다.", OnDeviceGenerationControl.isGenerating)
    }

    private suspend fun awaitState(
        store: GemmaAccumulationStore,
        predicate: (GemmaAccumulationState) -> Boolean
    ): GemmaAccumulationState {
        val deadline = SystemClock.elapsedRealtime() + STATE_TIMEOUT_MS
        var state = store.load()
        while (SystemClock.elapsedRealtime() < deadline) {
            state = store.load()
            if (predicate(state)) return state
            delay(POLL_INTERVAL_MS)
        }
        assertTrue("Gemma 자동 준비 상태가 기대값에 도달하지 않았습니다: $state", predicate(state))
        return state
    }

    private fun awaitVaultReady(dashboard: TypingDnaDashboardActivity) {
        waitUntil("통합 금고 UI가 준비되지 않았습니다.") {
            onMain {
                val root = dashboard.window.decorView
                val automatic = root.findViewById<View>(R.id.gemma_vault_auto) as? CompoundButton
                val status = root.findViewById<View>(R.id.gemma_vault_status) as? TextView
                val count = root.findViewById<View>(R.id.gemma_vault_count) as? TextView
                val generate = root.findViewById<View>(R.id.gemma_vault_generate)
                val model = root.findViewById<View>(R.id.gemma_vault_model)
                dashboard.hasWindowFocus() &&
                    dashboard.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    root.isShown && !root.isLayoutRequested &&
                    root.findViewById<View>(R.id.gemma_vault_card)?.isShown == true &&
                    automatic != null && automatic.isEnabled && automatic.isClickable &&
                    status?.text?.isNotBlank() == true &&
                    count?.text?.isNotBlank() == true &&
                    generate?.isEnabled == true && generate?.isClickable == true &&
                    model?.isEnabled == true && model?.isClickable == true &&
                    root.findViewById<View>(R.id.gemma_vault_stop) != null
            }
        }
    }

    private fun awaitAutomaticState(dashboard: TypingDnaDashboardActivity, checked: Boolean) {
        waitUntil("자동 준비 스위치가 기대 상태에 도달하지 않았습니다.") {
            onMain {
                val automatic = requireAutomaticControl(dashboard.window.decorView)
                automatic.isEnabled && automatic.isClickable && automatic.isChecked == checked
            }
        }
    }

    private fun awaitEnabledControl(dashboard: TypingDnaDashboardActivity, id: Int) {
        waitUntil("${dashboard.resources.getResourceEntryName(id)} 제어가 활성화되지 않았습니다.") {
            onMain {
                requireView(dashboard.window.decorView, id).let { it.isEnabled && it.isClickable }
            }
        }
    }

    private fun assertInitialVaultText(dashboard: TypingDnaDashboardActivity) {
        val views = onMain {
            val root = dashboard.window.decorView
            listOf(
                "자동 준비" to requireAutomaticControl(root),
                "준비 상태" to requireText(root, R.id.gemma_vault_status),
                "준비 문장 수" to requireText(root, R.id.gemma_vault_count)
            )
        }
        views.forEach { (label, text) ->
            scrollIntoView(text)
            onMain { assertVisibleText(label, text) }
        }
        onMain {
            val count = requireText(dashboard.window.decorView, R.id.gemma_vault_count)
            assertTrue("준비 문장 수는 숫자 형식으로 보여야 합니다: ${count.text}", count.text.any { it.isDigit() })
        }
    }

    private fun assertVaultLayout(dashboard: TypingDnaDashboardActivity): JSONObject {
        val root = onMain { dashboard.window.decorView }
        val card = onMain { requireView(root, R.id.gemma_vault_card) }
        scrollIntoView(card)
        val controls = onMain {
            listOf(
                "자동 준비" to requireAutomaticControl(root),
                "강화" to requireView(root, R.id.gemma_vault_generate),
                "모델 관리" to requireView(root, R.id.gemma_vault_model)
            )
        }
        val geometry = JSONArray()
        controls.forEach { (label, control) ->
            scrollIntoView(control)
            onMain {
                assertControlTarget(label, control)
                geometry.put(viewJson(label, control))
            }
        }
        val visibleTextViews = onMain {
            card.descendantTextViews()
                .filter { it.visibility == View.VISIBLE && !it.hasGoneAncestor() }
                .toList()
        }
        visibleTextViews.forEach { textView ->
            scrollIntoView(textView)
            onMain {
                assertVisibleText("금고 텍스트", textView)
                assertNoTextClipping(textView)
                if (textView !is Button && textView !is CompoundButton) {
                    assertTrue(
                        "금고 본문 텍스트는 16sp보다 작으면 안 됩니다: ${textView.text}",
                        textView.textSize + BODY_TEXT_TOLERANCE_PX >= minimumBodyTextPx(textView)
                    )
                }
            }
        }
        return JSONObject()
            .put("controls", geometry)
            .put("textViews", visibleTextViews.size)
            .put("minimumTouchTargetDp", MIN_TOUCH_TARGET_DP)
    }

    private fun scrollToControls(dashboard: TypingDnaDashboardActivity) {
        val model = onMain {
            requireView(dashboard.window.decorView, R.id.gemma_vault_model)
        }
        scrollIntoView(model)
    }

    private fun assertVisibleText(label: String, text: TextView) {
        assertTrue("$label 텍스트가 비어 있습니다.", text.text.isNotBlank())
        assertTrue("$label 텍스트가 표시되지 않습니다.", text.isShown)
        val visible = Rect()
        assertTrue("$label 텍스트를 화면에 스크롤할 수 없습니다.", text.getGlobalVisibleRect(visible))
        val screen = Rect()
        text.rootView.getGlobalVisibleRect(screen)
        assertTrue(
            "$label 텍스트가 화면 범위를 벗어납니다.",
            visible.left >= screen.left && visible.top >= screen.top &&
                visible.right <= screen.right && visible.bottom <= screen.bottom
        )
    }

    private fun assertNoTextClipping(text: TextView) {
        val layout = requireNotNull(text.layout) { "${text.text} TextView 레이아웃이 없습니다." }
        for (line in 0 until layout.lineCount) {
            assertEquals("${text.text} TextView의 $line 번째 줄이 잘렸습니다.", 0, layout.getEllipsisCount(line))
        }
        val availableWidth = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
        val availableHeight = text.height - text.compoundPaddingTop - text.compoundPaddingBottom
        assertTrue("${text.text} TextView 레이아웃 폭이 콘텐츠 영역을 넘습니다.", layout.width <= availableWidth)
        assertTrue("${text.text} TextView 레이아웃 높이가 콘텐츠 영역을 넘습니다.", layout.height <= availableHeight)
    }

    private fun assertControlTarget(label: String, control: View) {
        assertTrue("$label 제어가 표시되지 않습니다.", control.isShown)
        assertTrue("$label 제어가 클릭 가능하지 않습니다.", control.isClickable)
        val visible = Rect()
        assertTrue("$label 제어를 화면에 스크롤할 수 없습니다.", control.getGlobalVisibleRect(visible))
        val rootBounds = Rect()
        control.rootView.getGlobalVisibleRect(rootBounds)
        val location = IntArray(2)
        control.getLocationOnScreen(location)
        val nestedScroll = control.findNestedScrollAncestor()
        val nestedScrollDescription = nestedScroll?.let { scroll ->
            val scrollBounds = Rect()
            scroll.getGlobalVisibleRect(scrollBounds)
            "scrollY=${scroll.scrollY},height=${scroll.height},globalRect=$scrollBounds"
        } ?: "none"
        val geometry = "height=${control.height},width=${control.width},globalRect=$visible," +
            "rootRect=$rootBounds,screenY=${location[1]},nestedScroll=$nestedScrollDescription"
        val minimumSize = ceil(MIN_TOUCH_TARGET_DP * control.resources.displayMetrics.density).toInt()
        assertTrue("$label 제어 높이가 ${MIN_TOUCH_TARGET_DP}dp보다 작습니다: $geometry", control.height >= minimumSize)
        assertTrue("$label 제어의 보이는 높이가 ${MIN_TOUCH_TARGET_DP}dp보다 작습니다: $geometry", visible.height() >= minimumSize)
        assertTrue("$label 제어 폭이 ${MIN_TOUCH_TARGET_DP}dp보다 작습니다: $geometry", control.width >= minimumSize)
        assertTrue("$label 제어의 보이는 폭이 ${MIN_TOUCH_TARGET_DP}dp보다 작습니다: $geometry", visible.width() >= minimumSize)
        assertTrue("$label 제어가 화면 경계 밖에 있습니다: $geometry", Rect.intersects(rootBounds, visible))
    }

    private fun scrollIntoView(target: View) {
        onMain {
            target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
        }
        instrumentation.waitForIdleSync()
    }

    private fun tapControl(label: String, control: View) {
        val stableBounds = awaitStableControlBounds(label, control)
        val downAt = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downAt,
            downAt,
            MotionEvent.ACTION_DOWN,
            stableBounds.centerX().toFloat(),
            stableBounds.centerY().toFloat(),
            0
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(
            downAt,
            downAt + TOUCH_DURATION_MS,
            MotionEvent.ACTION_UP,
            stableBounds.centerX().toFloat(),
            stableBounds.centerY().toFloat(),
            0
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            instrumentation.sendPointerSync(down)
            instrumentation.sendPointerSync(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun awaitStableControlBounds(label: String, control: View): Rect {
        var previous: ControlLocation? = null
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = onMain {
                if (
                    !control.rootView.hasWindowFocus() ||
                    !control.isShown ||
                    !control.isEnabled ||
                    !control.isClickable
                ) {
                    null
                } else {
                    val globalRect = Rect()
                    if (!control.getGlobalVisibleRect(globalRect)) {
                        null
                    } else {
                        val location = IntArray(2)
                        control.getLocationOnScreen(location)
                        ControlLocation(globalRect, location[0], location[1])
                    }
                }
            }
            if (current != null && current == previous) {
                onMain {
                    assertControlTarget(label, control)
                }
                return Rect(current.globalRect)
            }
            previous = current
            SystemClock.sleep(CONTROL_STABILITY_POLL_MS)
        }
        throw AssertionError("$label 제어의 focus·표시·위치가 ${UI_TIMEOUT_MS}ms 안에 안정되지 않았습니다.")
    }

    private fun awaitDashboardFocusReady(dashboard: TypingDnaDashboardActivity) {
        waitUntil("금고 창이 focus를 얻고 RESUMED 상태가 되지 않았습니다.") {
            onMain {
                dashboard.hasWindowFocus() &&
                    dashboard.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    dashboard.window.decorView.isShown
            }
        }
    }

    private fun requireAutomaticControl(root: View): CompoundButton =
        requireView(root, R.id.gemma_vault_auto) as? CompoundButton
            ?: throw AssertionError("gemma_vault_auto는 체크 가능한 자동 준비 제어여야 합니다.")

    private fun requireText(root: View, id: Int): TextView =
        requireView(root, id) as? TextView
            ?: throw AssertionError("${root.resources.getResourceEntryName(id)}는 TextView여야 합니다.")

    private fun requireView(root: View, id: Int): View = requireNotNull(root.findViewById(id)) {
        "${root.resources.getResourceEntryName(id)} 뷰를 찾지 못했습니다."
    }

    private fun View.descendantTextViews(): Sequence<TextView> = sequence {
        if (this@descendantTextViews is TextView) yield(this@descendantTextViews)
        val group = this@descendantTextViews as? ViewGroup ?: return@sequence
        for (index in 0 until group.childCount) {
            yieldAll(group.getChildAt(index).descendantTextViews())
        }
    }

    private fun View.hasGoneAncestor(): Boolean {
        var current: View? = this
        while (current != null) {
            if (current.visibility == View.GONE) return true
            current = current.parent as? View
        }
        return false
    }

    private fun View.findNestedScrollAncestor(): NestedScrollView? {
        var current = parent as? View
        while (current != null) {
            if (current is NestedScrollView) return current
            current = current.parent as? View
        }
        return null
    }

    private fun viewJson(label: String, view: View): JSONObject {
        val bounds = Rect()
        view.getGlobalVisibleRect(bounds)
        return JSONObject()
            .put("label", label)
            .put("widthPx", view.width)
            .put("heightPx", view.height)
            .put("visibleWidthPx", bounds.width())
            .put("visibleHeightPx", bounds.height())
    }

    private fun requireCaptureName(): String {
        val name = InstrumentationRegistry.getArguments().getString("captureName") ?: "basic"
        require(CAPTURE_NAME.matches(name)) {
            "captureName은 영문, 숫자, 밑줄, 하이픈만 사용할 수 있습니다."
        }
        return name
    }

    private fun captureVaultScreen(
        context: android.content.Context,
        captureName: String,
        stage: String,
        dashboard: TypingDnaDashboardActivity
    ) {
        awaitDashboardFocusReady(dashboard)
        instrumentation.uiAutomation.waitForIdle(UI_AUTOMATION_IDLE_MS, UI_AUTOMATION_IDLE_TIMEOUT_MS)
        saveVaultScreenshot(context, captureName, stage)
    }

    private fun captureVaultScreenRaw(
        context: android.content.Context,
        captureName: String,
        stage: String
    ) {
        saveVaultScreenshot(context, captureName, stage)
    }

    private fun saveVaultScreenshot(
        context: android.content.Context,
        captureName: String,
        stage: String
    ) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "금고 UI $stage 스크린샷을 가져오지 못했습니다."
        }
        val baseDirectory = requireNotNull(context.getExternalFilesDir(null)) {
            "금고 UI 스크린샷 외부 파일 디렉터리를 만들 수 없습니다."
        }
        val directory = File(baseDirectory, "gemma-vault-ui")
        check(directory.isDirectory || directory.mkdirs()) {
            "금고 UI 스크린샷 디렉터리를 만들 수 없습니다: ${directory.absolutePath}"
        }
        val screenshot = File(directory, "$captureName-$stage.png")
        try {
            screenshot.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "금고 UI $stage PNG를 저장하지 못했습니다."
                }
            }
        } finally {
            bitmap.recycle()
        }
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("gemmaVaultUiScreenshotStage", stage)
                putString("gemmaVaultUiScreenshotPath", screenshot.absolutePath)
            }
        )
    }

    private fun reportFailureState(dashboard: TypingDnaDashboardActivity) {
        val state = onMain {
            val root = dashboard.window.decorView
            val status = root.findViewById<TextView>(R.id.gemma_vault_status)
            val count = root.findViewById<TextView>(R.id.gemma_vault_count)
            JSONObject()
                .put("event", "gemma_vault_ui_failure_state")
                .put("lifecycle", dashboard.lifecycle.currentState.name)
                .put("hasWindowFocus", dashboard.hasWindowFocus())
                .put("isFinishing", dashboard.isFinishing)
                .put("isDestroyed", dashboard.isDestroyed)
                .put("rootShown", root.isShown)
                .put("rootAttached", root.isAttachedToWindow)
                .put("status", status?.text?.toString() ?: JSONObject.NULL)
                .put("personalCount", count?.text?.toString() ?: JSONObject.NULL)
        }
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString("GemmaVaultUiFailureState", state.toString()) }
        )
    }

    private fun minimumBodyTextPx(text: TextView): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        MIN_BODY_TEXT_SP,
        text.resources.displayMetrics
    )

    private data class ControlLocation(
        val globalRect: Rect,
        val screenX: Int,
        val screenY: Int
    )

    private fun stateJson(state: GemmaAccumulationState): JSONObject = JSONObject()
        .put("enabled", state.enabled)
        .put("manualRequested", state.manualRequested)
        .put("status", state.status)

    private fun awaitPeriodicWork(context: android.content.Context): List<WorkInfo> {
        val deadline = SystemClock.elapsedRealtime() + WORK_TIMEOUT_MS
        var unfinished = emptyList<WorkInfo>()
        while (SystemClock.elapsedRealtime() < deadline) {
            unfinished = periodicWork(context).filter { !it.state.isFinished }
            if (unfinished.isNotEmpty()) return unfinished
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return unfinished
    }

    private fun awaitNoUnfinishedWork(context: android.content.Context) {
        waitUntil("자동 준비 WorkManager 작업이 취소되지 않았습니다.") { noUnfinishedWork(context) }
    }

    private fun assertNoUnfinishedWork(context: android.content.Context) {
        assertTrue("자동 준비 WorkManager 작업이 이미 실행 중입니다.", noUnfinishedWork(context))
    }

    private fun noUnfinishedWork(context: android.content.Context): Boolean =
        relatedWork(context).none { !it.state.isFinished }

    private fun periodicWork(context: android.content.Context): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(PERIODIC_WORK_NAME)
            .get(WORK_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun relatedWork(context: android.content.Context): List<WorkInfo> {
        val manager = WorkManager.getInstance(context)
        return manager.getWorkInfosForUniqueWork(PERIODIC_WORK_NAME)
            .get(WORK_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS) +
            manager.getWorkInfosForUniqueWork(ONE_TIME_WORK_NAME)
                .get(WORK_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        assertTrue(message, predicate())
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private companion object {
        const val MIN_TOUCH_TARGET_DP = 48
        const val MIN_BODY_TEXT_SP = 16f
        const val BODY_TEXT_TOLERANCE_PX = 0.5f
        const val POLL_INTERVAL_MS = 100L
        const val UI_TIMEOUT_MS = 15_000L
        const val STATE_TIMEOUT_MS = 30_000L
        const val WORK_TIMEOUT_MS = 30_000L
        const val NAVIGATION_TIMEOUT_MS = 10_000L
        const val WORK_QUERY_TIMEOUT_SECONDS = 10L
        const val TOUCH_DURATION_MS = 40L
        const val CONTROL_STABILITY_POLL_MS = 100L
        const val UI_AUTOMATION_IDLE_MS = 100L
        const val UI_AUTOMATION_IDLE_TIMEOUT_MS = 5_000L
        const val PERIODIC_WORK_NAME = "gemma-accumulation-periodic"
        const val ONE_TIME_WORK_NAME = "gemma-accumulation-now"
        const val VAULT_UI_OFFLINE_MODE_ARGUMENT = "vaultUiOfflineMode"
        val CAPTURE_NAME = Regex("[a-zA-Z0-9_-]+")
        val PERSONAL_SYNC_NORMAL_RESULT = Regex(
            "^(개인 입력을 반영했습니다|새로 반영할 개인 입력이 없습니다)\\. 분석 문장 \\d+개$"
        )
    }
}
