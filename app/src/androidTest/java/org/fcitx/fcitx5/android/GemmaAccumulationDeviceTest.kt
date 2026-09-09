/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.debug.gemma.GemmaAccumulationScheduler
import org.fcitx.fcitx5.android.debug.gemma.GemmaAccumulationState
import org.fcitx.fcitx5.android.debug.gemma.GemmaAccumulationStore
import org.fcitx.fcitx5.android.debug.gemma.GemmaExperimentActivity
import org.fcitx.fcitx5.android.debug.gemma.GemmaModelFiles
import org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedMaterialPolicy
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class GemmaAccumulationDeviceTest {

    @Test
    fun accumulateInBackgroundAndPausePreservingMaterials() = runBlocking(Dispatchers.Default) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as FcitxApplication
        val store = GemmaAccumulationStore.get(context)
        val bank = app.generatedSentenceBank
        val startedAt = SystemClock.elapsedRealtime()
        var priorEnabled: Boolean? = null
        var launchedActivity: GemmaExperimentActivity? = null

        try {
            val model = GemmaModelFiles.modelFile(context)
            assertTrue("Gemma model is missing: ${model.absolutePath}", model.isFile)
            assertEquals("Gemma model size mismatch", GemmaModelFiles.MODEL_BYTES, model.length())

            launchedActivity = launchGemmaActivity()
            waitUntil("IME is hidden", IME_HIDDEN_TIMEOUT_MS) {
                !OnDeviceGenerationControl.isKeyboardActive
            }

            val beforeState = store.load()
            priorEnabled = beforeState.enabled
            bank.load()
            val beforeCount = bank.sentenceCount
            val beforeCounts = GeneratedMaterialPolicy.PREFIXES.associateWith(bank::exactPrefixCount)
            val before = stateJson(beforeState, beforeCount)

            GemmaAccumulationScheduler.setEnabled(context, true)
            openHomeAndWaitForBackground(requireNotNull(launchedActivity))
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "gemmaAccumulationHomeJson",
                        JSONObject()
                            .put("elapsedMs", SystemClock.elapsedRealtime() - startedAt)
                            .put("enabled", store.state.value.enabled)
                            .put("keyboardActive", OnDeviceGenerationControl.isKeyboardActive)
                            .put("work", workJson(workSnapshot(context)))
                            .toString()
                    )
                }
            )

            val after = waitForAccumulation(
                context = context,
                store = store,
                bank = bank,
                beforeCount = beforeCount,
                beforeCounts = beforeCounts,
                startedAt = startedAt
            )
            val afterState = after.state
            val afterCount = after.bankCount

            GemmaAccumulationScheduler.setEnabled(context, false)
            waitUntil("Gemma native generation stops", STOP_TIMEOUT_MS) {
                !OnDeviceGenerationControl.isGenerating
            }
            val pausedState = store.load()
            assertFalse("Accumulation must be disabled after pause", pausedState.enabled)
            val pausedCount = bank.sentenceCount
            val pausedAttempts = pausedState.attempts.toMap()

            bank.load()
            assertEquals("Pause must preserve generated materials after reload", pausedCount, bank.sentenceCount)
            delay(STABLE_WAIT_MS)
            bank.load()
            val stableCount = bank.sentenceCount
            val stableState = store.load()
            assertEquals("Disabled accumulation must not add materials", pausedCount, stableCount)
            assertEquals("Disabled accumulation must not add attempts", pausedAttempts, stableState.attempts)

            GemmaAccumulationScheduler.requestNow(context)
            waitUntil("Disabled accumulation has no incomplete work", STOP_TIMEOUT_MS) {
                workSnapshot(context).incompleteCount == 0
            }
            bank.load()
            assertEquals("Disabled request must not add materials", stableCount, bank.sentenceCount)
            assertEquals("Disabled request must not add attempts", pausedAttempts, store.load().attempts)

            val evidence = JSONObject()
                .put("before", before)
                .put("after", stateJson(afterState, afterCount))
                .put("stateAdded", afterState.added)
                .put("increasedPrefixCount", after.increasedPrefixCount)
                .put("covered", afterState.covered)
                .put("rejected", afterState.rejected)
                .put("duplicates", afterState.duplicates)
                .put("pausedNoNative", !OnDeviceGenerationControl.isGenerating)
                .put("reloaded", bank.sentenceCount == pausedCount)
                .put("stable", bank.sentenceCount == stableCount && store.state.value.attempts == pausedAttempts)
            reportEvidence(evidence)

            launchedActivity?.let { activity ->
                instrumentation.runOnMainSync { activity.finish() }
            }
            val screenshotActivity = launchGemmaActivity()
            launchedActivity = screenshotActivity
            waitUntil("Gemma 화면 유휴 상태", GEMMA_SCREEN_READY_TIMEOUT_MS) {
                isGemmaScreenReady(screenshotActivity)
            }
            captureGemmaScreen()
        } finally {
            try {
                priorEnabled?.let { GemmaAccumulationScheduler.setEnabled(context, it) }
            } finally {
                launchedActivity?.let { activity ->
                    instrumentation.runOnMainSync { activity.finish() }
                }
            }
        }
    }

    private suspend fun waitForAccumulation(
        context: android.content.Context,
        store: GemmaAccumulationStore,
        bank: org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedSentenceBank,
        beforeCount: Int,
        beforeCounts: Map<String, Int>,
        startedAt: Long
    ): AccumulationResult {
        val deadline = SystemClock.elapsedRealtime() + ACCUMULATION_TIMEOUT_MS
        var nextProgressAt = SystemClock.elapsedRealtime()
        var lastState = store.state.value
        var lastCount = beforeCount
        while (SystemClock.elapsedRealtime() < deadline) {
            bank.load()
            lastCount = bank.sentenceCount
            lastState = store.load()
            if (lastState.error != null) {
                reportFailure(lastState, lastCount, "stateError")
                fail("Gemma accumulation reported an error: ${lastState.error}")
            }
            val increasedPrefixCount = GeneratedMaterialPolicy.PREFIXES.count { prefix ->
                bank.exactPrefixCount(prefix) > requireNotNull(beforeCounts[prefix])
            }
            if (lastCount > beforeCount && increasedPrefixCount > 0) {
                return AccumulationResult(lastState, lastCount, increasedPrefixCount)
            }
            if (SystemClock.elapsedRealtime() >= nextProgressAt) {
                InstrumentationRegistry.getInstrumentation().sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "gemmaAccumulationProgress",
                            JSONObject()
                                .put("elapsedMs", SystemClock.elapsedRealtime() - startedAt)
                                .put("bankCount", lastCount)
                                .put("covered", lastState.covered)
                                .put("added", lastState.added)
                                .put("rejected", lastState.rejected)
                                .put("duplicates", lastState.duplicates)
                                .put("status", lastState.status)
                                .put("work", workJson(workSnapshot(context)))
                                .toString()
                        )
                    }
                )
                nextProgressAt += PROGRESS_INTERVAL_MS
            }
            delay(POLL_INTERVAL_MS)
        }
        reportFailure(lastState, lastCount, "timeout")
        throw AssertionError(
            "Gemma accumulation did not create material for a new prefix within " +
                "$ACCUMULATION_TIMEOUT_MS ms: count=$lastCount, covered=${lastState.covered}, " +
                "status=${lastState.status}"
        )
    }

    private suspend fun waitUntil(description: String, timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            delay(POLL_INTERVAL_MS)
        }
        assertTrue("Timed out waiting for $description after $timeoutMs ms", predicate())
    }

    private fun launchGemmaActivity(): GemmaExperimentActivity =
        InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(
                InstrumentationRegistry.getInstrumentation().targetContext,
                GemmaExperimentActivity::class.java
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        ) as GemmaExperimentActivity

    private suspend fun openHomeAndWaitForBackground(activity: GemmaExperimentActivity) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.targetContext.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        waitUntil("Gemma 화면 background 전환", HOME_TRANSITION_TIMEOUT_MS) {
            var backgrounded = false
            instrumentation.runOnMainSync {
                backgrounded = !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
            backgrounded
        }
    }

    private fun isGemmaScreenReady(activity: GemmaExperimentActivity): Boolean {
        var ready = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val decor = activity.window.decorView
            ready = decor.isShown && !decor.isLayoutRequested && !OnDeviceGenerationControl.isGenerating
        }
        return ready
    }

    private fun workSnapshot(context: android.content.Context): WorkSnapshot {
        val manager = WorkManager.getInstance(context)
        val oneTime = manager.getWorkInfosForUniqueWork(ONE_TIME_WORK_NAME)
            .get(WORK_MANAGER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val periodic = manager.getWorkInfosForUniqueWork(PERIODIC_WORK_NAME)
            .get(WORK_MANAGER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return WorkSnapshot(oneTime, periodic)
    }

    private fun workJson(snapshot: WorkSnapshot): JSONObject = JSONObject()
        .put("oneTime", JSONObject().put("active", snapshot.oneTimeActive).put("terminal", snapshot.oneTimeTerminal))
        .put("periodic", JSONObject().put("active", snapshot.periodicActive).put("terminal", snapshot.periodicTerminal))

    private fun stateJson(state: GemmaAccumulationState, count: Int): JSONObject = JSONObject()
        .put("enabled", state.enabled)
        .put("stored", count)
        .put("covered", state.covered)
        .put("added", state.added)
        .put("rejected", state.rejected)
        .put("duplicates", state.duplicates)
        .put("attemptTotal", state.attempts.values.sum())
        .put("error", state.error ?: JSONObject.NULL)

    private fun reportFailure(state: GemmaAccumulationState, count: Int, reason: String) {
        val report = JSONObject()
            .put("failure", reason)
            .put("state", stateJson(state, count))
        reportEvidence(report)
        report.put("work", workJson(workSnapshot(InstrumentationRegistry.getInstrumentation().targetContext)))
        reportEvidence(report)
    }

    private fun reportEvidence(evidence: JSONObject) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("gemmaAccumulationEvidence", evidence.toString()) }
        )
    }

    private fun captureGemmaScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
                ?: throw IllegalStateException("UiAutomation.takeScreenshot returned null")
            val directory = requireNotNull(
                instrumentation.targetContext.getExternalFilesDir("gemma-accumulation-device-test")
            ) { "Gemma screenshot directory is unavailable." }
            val screenshot = File(directory, "gemma-accumulation-${SystemClock.elapsedRealtime()}.png")
            try {
                screenshot.outputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "Gemma screenshot could not be encoded."
                    }
                }
            } finally {
                bitmap.recycle()
            }
            instrumentation.sendStatus(
                0,
                Bundle().apply { putString("gemmaAccumulationScreenshotPath", screenshot.absolutePath) }
            )
        } catch (error: Exception) {
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "gemmaAccumulationScreenshotError",
                        "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                    )
                }
            )
        }
    }

    private data class AccumulationResult(
        val state: GemmaAccumulationState,
        val bankCount: Int,
        val increasedPrefixCount: Int
    )

    private class WorkSnapshot(
        oneTime: List<WorkInfo>,
        periodic: List<WorkInfo>
    ) {
        val oneTimeActive = oneTime.count { !it.state.isFinished }
        val oneTimeTerminal = oneTime.count { it.state.isFinished }
        val periodicActive = periodic.count { !it.state.isFinished }
        val periodicTerminal = periodic.count { it.state.isFinished }
        val incompleteCount = oneTimeActive + periodicActive
    }

    private companion object {
        const val ONE_TIME_WORK_NAME = "gemma-accumulation-now"
        const val PERIODIC_WORK_NAME = "gemma-accumulation-periodic"
        const val IME_HIDDEN_TIMEOUT_MS = 10_000L
        const val HOME_TRANSITION_TIMEOUT_MS = 10_000L
        const val GEMMA_SCREEN_READY_TIMEOUT_MS = 5_000L
        const val STOP_TIMEOUT_MS = 30_000L
        const val ACCUMULATION_TIMEOUT_MS = 180_000L
        const val STABLE_WAIT_MS = 2_000L
        const val POLL_INTERVAL_MS = 250L
        const val PROGRESS_INTERVAL_MS = 10_000L
        const val WORK_MANAGER_TIMEOUT_SECONDS = 30L
    }
}
