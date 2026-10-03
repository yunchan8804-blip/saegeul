/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationScheduler
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationState
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGenerationEligibility
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGenerationMode
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGenerationSnapshot
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelFiles
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationStore
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.fcitx.fcitx5.android.ui.main.ai.TypingDnaDashboardActivity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.ceil

class GemmaManualBackgroundDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun manualRequestContinuesAfterLeavingVault() = runBlocking(Dispatchers.Default) {
        val context = instrumentation.targetContext
        val app = context.applicationContext as FcitxApplication
        val store = GemmaAccumulationStore.get(context)
        val bank = app.generatedSentenceBank
        val unifiedVaultAction = InstrumentationRegistry.getArguments()
            .getString(UNIFIED_VAULT_ACTION_ARGUMENT) == "true"
        val originalOfflineMode = if (unifiedVaultAction) {
            AppPrefs.getInstance().advanced.offlineMode.getValue()
        } else {
            null
        }
        val startedAt = SystemClock.elapsedRealtime()
        val originalState = store.load()
        val originalNative = OnDeviceGenerationControl.isGenerating
        var dashboard: TypingDnaDashboardActivity? = null
        var stateChangedByTest = false
        var latestState = originalState
        var latestCount = originalState.stored
        var latestEligibility = GemmaGenerationEligibility.snapshot(context)
        var beforeSequence: Long? = null
        var beforeCount: Int? = null
        var failure: Throwable? = null
        var personalResultCompleted = false

        report(
            phase = "preflight",
            state = originalState,
            count = latestCount,
            beforeSequence = null,
            beforeCount = null,
            eligibility = latestEligibility,
            startedAt = startedAt,
            failure = false
        )

        try {
            if (unifiedVaultAction) {
                AppPrefs.getInstance().advanced.offlineMode.setValue(true)
                check(AppPrefs.getInstance().advanced.offlineMode.getValue()) {
                    "통합 금고 수동 강화의 오프라인 모드를 켜지 못했습니다."
                }
                reportUnifiedVaultAction(
                    phase = "offline_mode_enabled",
                    manualRequested = false,
                    personalResultCompleted = false,
                    originalOfflineMode = originalOfflineMode,
                    restoredOfflineMode = null
                )
            }
            assertFalse("기존 수동 강화 요청이 있어 방해하지 않습니다.", originalState.manualRequested)
            assertFalse("기존 Gemma native 실행이 있어 방해하지 않습니다.", originalNative)

            val model = GemmaModelFiles.modelFile(context)
            assertTrue("Gemma 모델 파일이 없습니다: ${model.absolutePath}", model.isFile)
            assertEquals("Gemma 모델 크기가 기준과 다릅니다.", GemmaModelFiles.MODEL_BYTES, model.length())

            dashboard = instrumentation.startActivitySync(
                Intent(context, TypingDnaDashboardActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            ) as TypingDnaDashboardActivity
            awaitCondition("키보드가 비활성 상태가 되지 않았습니다.", KEYBOARD_IDLE_TIMEOUT_MS) {
                !OnDeviceGenerationControl.isKeyboardActive
            }

            latestEligibility = GemmaGenerationEligibility.snapshot(context)
            // 수동 강화는 CRITICAL 이상 열 상태에서 설계상 차단된다. 기기가 뜨거우면 행복 경로를
            // 검증할 수 없으므로 실패가 아니라 건너뛴다(모델 부재 preflight와 같은 규칙).
            assumeTrue(
                "수동 강화는 CRITICAL 이상 열 상태에서 실행하지 않습니다: ${latestEligibility.thermalStatus}",
                latestEligibility.thermalStatus?.let {
                    it < PowerManager.THERMAL_STATUS_CRITICAL
                } ?: true
            )
            assertTrue(
                "수동 강화 안전 조건이 충족되지 않았습니다: " +
                    GemmaGenerationEligibility.evaluate(latestEligibility, GemmaGenerationMode.MANUAL)?.name,
                GemmaGenerationEligibility.evaluate(latestEligibility, GemmaGenerationMode.MANUAL) == null
            )

            if (originalState.enabled) {
                stateChangedByTest = true
                GemmaAccumulationScheduler.setEnabled(context, false)
                awaitCondition("기존 자동 준비 native 실행이 멈추지 않았습니다.", STOP_TIMEOUT_MS) {
                    !OnDeviceGenerationControl.isGenerating
                }
                latestState = store.load()
                assertFalse("기존 자동 준비를 멈춘 뒤 enabled가 남아 있습니다.", latestState.enabled)
                assertFalse("기존 자동 준비를 멈춘 뒤 manualRequested가 남아 있습니다.", latestState.manualRequested)
            }

            latestState = store.load()
            assertFalse("수동 강화 시작 전에 자동 준비가 켜져 있습니다.", latestState.enabled)
            assertFalse("수동 강화 시작 전에 manualRequested가 켜져 있습니다.", latestState.manualRequested)
            bank.load()
            latestCount = bank.sentenceCount
            assertEquals("수동 강화 시작 전 은행 수와 저장 상태가 다릅니다.", latestState.stored, latestCount)
            beforeSequence = latestState.openSequence
            beforeCount = latestCount

            stateChangedByTest = true
            if (unifiedVaultAction) {
                latestState = requestManualThroughUnifiedVaultAction(requireNotNull(dashboard), store)
                personalResultCompleted = true
            } else {
                GemmaAccumulationScheduler.requestManual(context)
                latestState = store.load()
            }
            assertFalse("수동 강화 요청이 자동 준비를 켰습니다.", latestState.enabled)
            assertTrue("수동 강화 요청이 저장되지 않았습니다.", latestState.manualRequested)
            if (unifiedVaultAction) {
                reportUnifiedVaultAction(
                    phase = "manual_requested_before_home",
                    manualRequested = latestState.manualRequested,
                    personalResultCompleted = personalResultCompleted,
                    originalOfflineMode = originalOfflineMode,
                    restoredOfflineMode = null
                )
            }

            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            awaitCondition("금고 화면이 background 상태가 되지 않았습니다.", HOME_TRANSITION_TIMEOUT_MS) {
                var backgrounded = false
                instrumentation.runOnMainSync {
                    backgrounded = !requireNotNull(dashboard).lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                }
                backgrounded
            }

            val completed = awaitFiveStoredContexts(
                store = store,
                beforeSequence = requireNotNull(beforeSequence),
                beforeCount = requireNotNull(beforeCount),
                startedAt = startedAt,
                initialEligibility = latestEligibility,
                onObservation = { state, count, eligibility ->
                    latestState = state
                    latestCount = count
                    latestEligibility = eligibility
                }
            )
            latestState = completed.state
            latestCount = completed.count
            latestEligibility = completed.eligibility

            GemmaAccumulationScheduler.setEnabled(context, false)
            awaitCondition("수동 강화 native 실행이 30초 안에 멈추지 않았습니다.", STOP_TIMEOUT_MS) {
                !OnDeviceGenerationControl.isGenerating
            }
            val stoppedState = store.load()
            val stoppedCount = stoppedState.stored
            assertFalse("중지 뒤 자동 준비가 켜져 있습니다.", stoppedState.enabled)
            assertFalse("중지 뒤 수동 강화 요청이 남아 있습니다.", stoppedState.manualRequested)
            assertTrue("중지 직전 추가 저장이 줄었습니다.", stoppedCount >= latestCount)
            assertTrue("중지 직전 공개 문맥 순번이 줄었습니다.", stoppedState.openSequence >= latestState.openSequence)
            bank.load()
            val reloadedCount = bank.sentenceCount
            val reloadedState = store.load()
            assertEquals("완전 중지 뒤 저장 문장 수가 reload에서 달라졌습니다.", stoppedCount, reloadedCount)
            assertEquals("완전 중지 뒤 공개 문맥 순번이 reload에서 달라졌습니다.", stoppedState.openSequence, reloadedState.openSequence)
            assertTrue("수동 강화가 최소 $MINIMUM_SEQUENCE_DELTA 회 진행하지 않았습니다.", stoppedState.openSequence - requireNotNull(beforeSequence) >= MINIMUM_SEQUENCE_DELTA)
            assertTrue("수동 강화가 새 문장을 저장하지 않았습니다.", reloadedCount > requireNotNull(beforeCount))

            report(
                phase = "completed",
                state = reloadedState,
                count = reloadedCount,
                beforeSequence = beforeSequence,
                beforeCount = beforeCount,
                eligibility = latestEligibility,
                startedAt = startedAt,
                failure = false
            )
        } catch (error: Throwable) {
            failure = error
            if (unifiedVaultAction && dashboard != null) {
                try {
                    captureVisibleVaultFailure(context, dashboard, startedAt)
                } catch (captureError: Throwable) {
                    error.addSuppressed(captureError)
                }
            }
            report(
                phase = "failed",
                state = latestState,
                count = latestCount,
                beforeSequence = beforeSequence,
                beforeCount = beforeCount,
                eligibility = latestEligibility,
                startedAt = startedAt,
                failure = true
            )
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            try {
                cleanup(
                    context = context,
                    dashboard = dashboard,
                    stateChangedByTest = stateChangedByTest,
                    restoreAutomatic = originalState.enabled && stateChangedByTest,
                    originalFailure = failure
                )
            } catch (error: Throwable) {
                cleanupFailure = error
            }
            var offlineRestoreFailure: Throwable? = null
            try {
                restoreOfflineModeForUnifiedVaultAction(unifiedVaultAction, originalOfflineMode)
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

    private suspend fun requestManualThroughUnifiedVaultAction(
        dashboard: TypingDnaDashboardActivity,
        store: GemmaAccumulationStore
    ): GemmaAccumulationState {
        scrollUnifiedVaultActionIntoView(dashboard)
        val target = awaitStableUnifiedVaultAction(dashboard)
        val downAt = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downAt,
            downAt,
            MotionEvent.ACTION_DOWN,
            target.visibleBounds.centerX().toFloat(),
            target.visibleBounds.centerY().toFloat(),
            0
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(
            downAt,
            downAt + TOUCH_DURATION_MS,
            MotionEvent.ACTION_UP,
            target.visibleBounds.centerX().toFloat(),
            target.visibleBounds.centerY().toFloat(),
            0
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            instrumentation.sendPointerSync(down)
            instrumentation.sendPointerSync(up)
        } finally {
            down.recycle()
            up.recycle()
        }
        val state = awaitManualRequested(store)
        awaitCondition("통합 금고 보강 뒤 개인 입력 반영 정상 결과가 표시되지 않았습니다.", UNIFIED_ACTION_TIMEOUT_MS) {
            onMain {
                val result = dashboard.window.decorView
                    .findViewById<TextView>(R.id.gemma_vault_personal_result)
                result?.isShown == true &&
                    PERSONAL_SYNC_NORMAL_RESULT.matches(result.text.toString())
            }
        }
        return state
    }

    private suspend fun scrollUnifiedVaultActionIntoView(dashboard: TypingDnaDashboardActivity) {
        awaitCondition("통합 금고 보강 제어의 레이아웃이 준비되지 않았습니다.", UNIFIED_ACTION_TIMEOUT_MS) {
            onMain {
                dashboard.window.decorView.findViewById<View>(R.id.gemma_vault_generate)?.let { control ->
                    control.width > 0 && control.height > 0
                } == true
            }
        }
        onMain {
            val control = requireNotNull(
                dashboard.window.decorView.findViewById<View>(R.id.gemma_vault_generate)
            ) { "통합 금고 보강 제어를 찾지 못했습니다." }
            control.requestRectangleOnScreen(Rect(0, 0, control.width, control.height), true)
        }
        instrumentation.waitForIdleSync()
    }

    private suspend fun awaitStableUnifiedVaultAction(
        dashboard: TypingDnaDashboardActivity
    ): UnifiedVaultActionTarget {
        val deadline = SystemClock.elapsedRealtime() + UNIFIED_ACTION_TIMEOUT_MS
        var previous: UnifiedVaultActionTarget? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = onMain {
                val root = dashboard.window.decorView
                val control = root.findViewById<View>(R.id.gemma_vault_generate)
                if (
                    !dashboard.hasWindowFocus() ||
                    !dashboard.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                    !root.isShown ||
                    control == null ||
                    !control.isShown ||
                    !control.isEnabled ||
                    !control.isClickable
                ) {
                    null
                } else {
                    val visibleInRoot = Rect()
                    if (!control.getGlobalVisibleRect(visibleInRoot) ||
                        visibleInRoot.width() <= 0 || visibleInRoot.height() <= 0
                    ) {
                        null
                    } else {
                        val rootLocation = IntArray(2)
                        control.rootView.getLocationOnScreen(rootLocation)
                        val visibleOnScreen = Rect(visibleInRoot).apply {
                            offset(rootLocation[0], rootLocation[1])
                        }
                        val minimumTargetSize = ceil(
                            MIN_TOUCH_TARGET_DP * control.resources.displayMetrics.density
                        ).toInt()
                        if (
                            visibleOnScreen.width() < minimumTargetSize ||
                            visibleOnScreen.height() < minimumTargetSize
                        ) {
                            return@onMain null
                        }
                        val location = IntArray(2)
                        control.getLocationOnScreen(location)
                        UnifiedVaultActionTarget(visibleOnScreen, location[0], location[1])
                    }
                }
            }
            if (current != null && current == previous) return current
            previous = current
            delay(POLL_INTERVAL_MS)
        }
        throw AssertionError("통합 금고 보강 제어가 ${UNIFIED_ACTION_TIMEOUT_MS}ms 안에 focus·활성·위치 안정 상태가 되지 않았습니다.")
    }

    private suspend fun awaitManualRequested(store: GemmaAccumulationStore): GemmaAccumulationState {
        val deadline = SystemClock.elapsedRealtime() + UNIFIED_ACTION_TIMEOUT_MS
        var state = store.load()
        while (SystemClock.elapsedRealtime() < deadline) {
            state = store.load()
            if (state.manualRequested) return state
            delay(POLL_INTERVAL_MS)
        }
        assertTrue("수동 강화 요청이 ${UNIFIED_ACTION_TIMEOUT_MS}ms 안에 저장되지 않았습니다.", state.manualRequested)
        return state
    }

    private suspend fun awaitFiveStoredContexts(
        store: GemmaAccumulationStore,
        beforeSequence: Long,
        beforeCount: Int,
        startedAt: Long,
        initialEligibility: GemmaGenerationSnapshot,
        onObservation: (GemmaAccumulationState, Int, GemmaGenerationSnapshot) -> Unit
    ): Completion {
        val deadline = SystemClock.elapsedRealtime() + MANUAL_RUN_TIMEOUT_MS
        var nextDiagnosticAt = SystemClock.elapsedRealtime()
        var state = store.load()
        var count = beforeCount
        var eligibility = initialEligibility
        while (SystemClock.elapsedRealtime() < deadline) {
            state = store.load()
            eligibility = GemmaGenerationEligibility.snapshot(instrumentation.targetContext)
            count = state.stored
            onObservation(state, count, eligibility)
            val sequenceDelta = state.openSequence - beforeSequence
            if (sequenceDelta >= MINIMUM_SEQUENCE_DELTA && count > beforeCount) {
                return Completion(state, count, eligibility)
            }
            if (state.error != null) {
                throw AssertionError("수동 강화가 오류 상태가 되었습니다.")
            }
            if (!state.manualRequested && !OnDeviceGenerationControl.isGenerating) {
                throw AssertionError(
                    "수동 강화가 정상 종료되었지만 최소 $MINIMUM_SEQUENCE_DELTA 회에 도달하지 못했습니다: " +
                        "sequenceDelta=$sequenceDelta, countDelta=${count - beforeCount}, " +
                        "status=${state.status}, unproductive=${state.consecutiveUnproductive}, " +
                        "thermal=${eligibility.thermalStatus}, battery=${eligibility.batteryPercent}, " +
                        "errorPresent=${state.error != null}"
                )
            }
            if (SystemClock.elapsedRealtime() >= nextDiagnosticAt) {
                report(
                    phase = "progress",
                    state = state,
                    count = count,
                    beforeSequence = beforeSequence,
                    beforeCount = beforeCount,
                    eligibility = eligibility,
                    startedAt = startedAt,
                    failure = false
                )
                nextDiagnosticAt += DIAGNOSTIC_INTERVAL_MS
            }
            delay(POLL_INTERVAL_MS)
        }
        throw AssertionError("수동 강화가 ${MANUAL_RUN_TIMEOUT_MS}ms 안에 최소 $MINIMUM_SEQUENCE_DELTA 회에 도달하지 못했습니다.")
    }

    private suspend fun cleanup(
        context: android.content.Context,
        dashboard: TypingDnaDashboardActivity?,
        stateChangedByTest: Boolean,
        restoreAutomatic: Boolean,
        originalFailure: Throwable?
    ) {
        var cleanupFailure: Throwable? = null
        try {
            if (stateChangedByTest) {
                GemmaAccumulationScheduler.setEnabled(context, false)
                awaitCondition("정리 중 Gemma native 실행이 멈추지 않았습니다.", STOP_TIMEOUT_MS) {
                    !OnDeviceGenerationControl.isGenerating
                }
                if (restoreAutomatic) {
                    GemmaAccumulationScheduler.setEnabled(context, true)
                }
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

    private fun restoreOfflineModeForUnifiedVaultAction(
        unifiedVaultAction: Boolean,
        originalOfflineMode: Boolean?
    ) {
        if (!unifiedVaultAction) return
        val original = requireNotNull(originalOfflineMode) {
            "통합 금고 수동 강화의 기존 오프라인 모드 값을 보관하지 못했습니다."
        }
        AppPrefs.getInstance().advanced.offlineMode.setValue(original)
        val restored = AppPrefs.getInstance().advanced.offlineMode.getValue()
        check(restored == original) {
            "통합 금고 수동 강화의 오프라인 모드를 복원하지 못했습니다. expected=$original actual=$restored"
        }
        reportUnifiedVaultAction(
            phase = "offline_mode_restored",
            manualRequested = null,
            personalResultCompleted = false,
            originalOfflineMode = original,
            restoredOfflineMode = restored
        )
    }

    private fun reportUnifiedVaultAction(
        phase: String,
        manualRequested: Boolean?,
        personalResultCompleted: Boolean,
        originalOfflineMode: Boolean?,
        restoredOfflineMode: Boolean?
    ) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString(
                    "GemmaManualBackgroundUnifiedVaultEvidence",
                    JSONObject()
                        .put("phase", phase)
                        .put("unifiedVaultAction", true)
                        .put("offlineMode", true)
                        .put("scope", "offline local vault UI; advertising end-to-end is out of scope")
                        .put("manualRequested", manualRequested ?: JSONObject.NULL)
                        .put("personalResultCompleted", personalResultCompleted)
                        .put("originalOfflineMode", originalOfflineMode ?: JSONObject.NULL)
                        .put("restoredOfflineMode", restoredOfflineMode ?: JSONObject.NULL)
                        .toString()
                )
            }
        )
    }

    private fun captureVisibleVaultFailure(
        context: android.content.Context,
        dashboard: TypingDnaDashboardActivity,
        startedAt: Long
    ) {
        val vaultVisible = onMain {
            dashboard.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                dashboard.window.decorView.isShown
        }
        if (!vaultVisible) return
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "통합 금고 수동 강화 실패 화면을 가져오지 못했습니다."
        }
        val directory = requireNotNull(context.getExternalFilesDir("gemma-manual-vault")) {
            "통합 금고 수동 강화 실패 화면 디렉터리를 만들 수 없습니다."
        }
        check(directory.isDirectory || directory.mkdirs()) {
            "통합 금고 수동 강화 실패 화면 디렉터리를 만들 수 없습니다: ${directory.absolutePath}"
        }
        val screenshot = File(directory, "unified-vault-failed-raw-$startedAt.png")
        try {
            screenshot.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "통합 금고 수동 강화 실패 화면 PNG를 저장하지 못했습니다."
                }
            }
        } finally {
            bitmap.recycle()
        }
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString("GemmaManualBackgroundFailureScreenshot", screenshot.absolutePath) }
        )
    }

    private suspend fun awaitCondition(
        message: String,
        timeoutMs: Long,
        predicate: () -> Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            delay(POLL_INTERVAL_MS)
        }
        assertTrue(message, predicate())
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun report(
        phase: String,
        state: GemmaAccumulationState,
        count: Int,
        beforeSequence: Long?,
        beforeCount: Int?,
        eligibility: GemmaGenerationSnapshot,
        startedAt: Long,
        failure: Boolean
    ) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString(
                    "GemmaManualBackgroundEvidence",
                    JSONObject()
                        .put("phase", phase)
                        .put("failure", failure)
                        .put("elapsedMs", SystemClock.elapsedRealtime() - startedAt)
                        .put("status", state.status)
                        .put("count", count)
                        .put("sequence", state.openSequence)
                        .put("sequenceDelta", beforeSequence?.let { state.openSequence - it } ?: JSONObject.NULL)
                        .put("countDelta", beforeCount?.let { count - it } ?: JSONObject.NULL)
                        .put("manualRequested", state.manualRequested)
                        .put("automaticEnabled", state.enabled)
                        .put("nativeGenerating", OnDeviceGenerationControl.isGenerating)
                        .put("consecutiveUnproductive", state.consecutiveUnproductive)
                        .put("errorPresent", state.error != null)
                        .put("thermalStatus", eligibility.thermalStatus ?: JSONObject.NULL)
                        .put("batteryPercent", eligibility.batteryPercent ?: JSONObject.NULL)
                        .toString()
                )
            }
        )
    }

    private data class Completion(
        val state: GemmaAccumulationState,
        val count: Int,
        val eligibility: GemmaGenerationSnapshot
    )

    private data class UnifiedVaultActionTarget(
        val visibleBounds: Rect,
        val screenX: Int,
        val screenY: Int
    )

    private companion object {
        const val MINIMUM_SEQUENCE_DELTA = 5L
        const val KEYBOARD_IDLE_TIMEOUT_MS = 10_000L
        const val HOME_TRANSITION_TIMEOUT_MS = 10_000L
        const val STOP_TIMEOUT_MS = 30_000L
        const val MANUAL_RUN_TIMEOUT_MS = 600_000L
        const val DIAGNOSTIC_INTERVAL_MS = 10_000L
        const val POLL_INTERVAL_MS = 250L
        const val UNIFIED_ACTION_TIMEOUT_MS = 30_000L
        const val TOUCH_DURATION_MS = 40L
        const val MIN_TOUCH_TARGET_DP = 48
        const val UNIFIED_VAULT_ACTION_ARGUMENT = "unifiedVaultAction"
        val PERSONAL_SYNC_NORMAL_RESULT = Regex(
            "^(개인 입력을 반영했습니다|새로 반영할 개인 입력이 없습니다)\\. 분석 문장 \\d+개$"
        )
    }
}
