/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationScheduler
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationStore
import org.fcitx.fcitx5.android.debug.gemma.GemmaExperimentActivity
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGenerationEligibility
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGenerationSnapshot
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelFiles
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class GemmaAccumulationCoverageDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun accumulateNewPublicContexts() = runBlocking(Dispatchers.Default) {
        val context = instrumentation.targetContext
        val deadline = SystemClock.elapsedRealtime() + TOTAL_BUDGET_MS
        val app = context.applicationContext as FcitxApplication
        val store = GemmaAccumulationStore.get(context)
        val priorEnabled = store.load().enabled
        var activity: GemmaExperimentActivity? = null
        try {
            val model = GemmaModelFiles.modelFile(context)
            assertTrue("Gemma model is missing", model.isFile)
            assertEquals(GemmaModelFiles.MODEL_BYTES, model.length())
            activity = instrumentation.startActivitySync(Intent(context, GemmaExperimentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }) as GemmaExperimentActivity
            await("keyboard inactive", 10_000) { !OnDeviceGenerationControl.isKeyboardActive }

            app.generatedSentenceBank.load()
            val beforeCount = app.generatedSentenceBank.sentenceCount
            val beforeStarts = app.generatedSentenceBank.recentPublicStarts().toSet()
            val beforeState = store.load()
            assertEligibility("자동 축적 시작", GemmaGenerationEligibility.snapshot(context))
            GemmaAccumulationScheduler.setEnabled(context, true)

            var run = 0
            var newOpenStart: String? = null
            while (run < MAX_RUNS && SystemClock.elapsedRealtime() < deadline) {
                awaitWorkIdle(context, store, deadline)
                app.generatedSentenceBank.load()
                val state = store.load()
                newOpenStart = findNewOpenStart(app, beforeStarts)
                if (app.generatedSentenceBank.sentenceCount > beforeCount &&
                    state.openSequence > beforeState.openSequence && newOpenStart != null) break

                assertEligibility("자동 축적 재요청", GemmaGenerationEligibility.snapshot(context))
                GemmaAccumulationScheduler.requestNow(context)
                awaitWorkIdle(context, store, deadline)
                run++
                val progressedState = store.load()
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("gemmaAccumulationCoverageProgress", JSONObject()
                        .put("contract", "continuous_public_context_expansion")
                        .put("run", run)
                        .put("thermalStatus", GemmaGenerationEligibility.snapshot(context).thermalStatus)
                        .put("stored", app.generatedSentenceBank.sentenceCount)
                        .put("openSequence", progressedState.openSequence)
                        .put("consecutiveUnproductive", progressedState.consecutiveUnproductive)
                        .put("newRecentPublicStart", findNewOpenStart(app, beforeStarts) != null)
                        .put("status", progressedState.status)
                        .put("added", progressedState.added)
                        .put("rejected", progressedState.rejected)
                        .put("duplicates", progressedState.duplicates)
                        .put("attemptsTotal", progressedState.attempts.values.sum())
                        .toString())
                })
            }

            app.generatedSentenceBank.load()
            val afterCount = app.generatedSentenceBank.sentenceCount
            val afterState = store.load()
            newOpenStart = findNewOpenStart(app, beforeStarts)
            assertTrue("자동 축적이 새 공개 문장을 저장하지 않았습니다.", afterCount > beforeCount)
            assertTrue("자동 축적이 공개 문맥 순번을 진행하지 않았습니다.", afterState.openSequence > beforeState.openSequence)
            assertTrue("자동 축적 뒤 새 공개 시작구절이 없습니다.", newOpenStart != null)

            GemmaAccumulationScheduler.setEnabled(context, false)
            await("native generation stopped", STOP_TIMEOUT_MS) { !OnDeviceGenerationControl.isGenerating }
            app.generatedSentenceBank.load()
            val reloadedCount = app.generatedSentenceBank.sentenceCount
            val reloadedState = store.load()
            assertEquals("공개 문맥 저장이 reload 뒤 달라졌습니다.", afterCount, reloadedCount)
            assertEquals("중지 뒤 공개 문맥 순번이 달라졌습니다.", afterState.openSequence, reloadedState.openSequence)
            assertTrue(
                "reload 뒤 새 공개 시작구절의 suffix 조회가 실패했습니다.",
                app.generatedSentenceBank.complete(requireNotNull(newOpenStart), 2).isNotEmpty()
            )
            assertFalse("자동 축적을 끈 뒤 native 생성이 남아 있습니다.", OnDeviceGenerationControl.isGenerating)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("gemmaAccumulationCoverageEvidence", JSONObject()
                    .put("contract", "continuous_public_context_expansion_replaces_fixed_20_auto_completion")
                    .put("runs", run)
                    .put("beforeCount", beforeCount)
                    .put("reloadedCount", reloadedCount)
                    .put("openSequenceBefore", beforeState.openSequence)
                    .put("openSequenceAfter", reloadedState.openSequence)
                    .put("newRecentPublicStart", true)
                    .put("reloaded", true)
                    .put("nativeStopped", true)
                    .toString())
            })
        } finally {
            try {
                GemmaAccumulationScheduler.setEnabled(context, priorEnabled)
            } finally {
                activity?.let { instrumentation.runOnMainSync { it.finish() } }
            }
        }
    }

    private fun findNewOpenStart(app: FcitxApplication, beforeStarts: Set<String>): String? =
        app.generatedSentenceBank.recentPublicStarts().firstOrNull { start ->
            start !in beforeStarts
        }

    private fun assertEligibility(phase: String, snapshot: GemmaGenerationSnapshot) {
        val reason = GemmaGenerationEligibility.evaluate(snapshot)
        assertTrue("$phase 안전 조건이 충족되지 않았습니다: ${reason?.message}", reason == null)
    }

    private suspend fun awaitWorkIdle(context: android.content.Context, store: GemmaAccumulationStore, deadline: Long) {
        val waitDeadline = minOf(deadline, SystemClock.elapsedRealtime() + RUN_TIMEOUT_MS)
        var nextReport = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < waitDeadline) {
            val state = store.load()
            assertTrue("Gemma accumulation reported an error: ${state.error}", state.error == null)
            val manager = WorkManager.getInstance(context)
            val oneTime = manager.getWorkInfosForUniqueWork(ONE_TIME).get(WORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val periodic = manager.getWorkInfosForUniqueWork(PERIODIC).get(WORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (SystemClock.elapsedRealtime() >= nextReport) {
                reportWorkDiagnostic(context, store, oneTime, periodic)
                nextReport += DIAGNOSTIC_INTERVAL_MS
            }
            val oneTimeActive = oneTime.any { !it.state.isFinished }
            val periodicActive = periodic.any { it.state == WorkInfo.State.RUNNING }
            if (!oneTimeActive && !periodicActive && !OnDeviceGenerationControl.isGenerating) return
            delay(POLL_MS)
        }
        throw AssertionError("Accumulation work did not become idle within ${RUN_TIMEOUT_MS}ms")
    }

    private fun reportWorkDiagnostic(
        context: android.content.Context,
        store: GemmaAccumulationStore,
        oneTime: List<WorkInfo>,
        periodic: List<WorkInfo>
    ) {
        fun workJson(info: WorkInfo): JSONObject = JSONObject()
            .put("id", info.id.toString())
            .put("state", info.state.name)
            .put("runAttemptCount", info.runAttemptCount)
            .put("stopReason", if (Build.VERSION.SDK_INT >= 31) info.stopReason else JSONObject.NULL)
        val app = context.applicationContext as FcitxApplication
        instrumentation.sendStatus(0, Bundle().apply {
            putString("gemmaAccumulationWorkDiagnostic", JSONObject()
                .put("oneTime", JSONArray(oneTime.map(::workJson)))
                .put("periodic", JSONArray(periodic.map(::workJson)))
                .put("status", store.state.value.status)
                .put("errorPresent", store.state.value.error != null)
                .put("attemptsTotal", store.state.value.attempts.values.sum())
                .put("openSequence", store.state.value.openSequence)
                .put("consecutiveUnproductive", store.state.value.consecutiveUnproductive)
                .put("keyboardActive", OnDeviceGenerationControl.isKeyboardActive)
                .put("isGenerating", OnDeviceGenerationControl.isGenerating)
                .put("thermalStatus", GemmaGenerationEligibility.snapshot(context).thermalStatus)
                .put("stored", app.generatedSentenceBank.sentenceCount)
                .toString())
        })
    }

    private suspend fun await(description: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            delay(POLL_MS)
        }
        throw AssertionError(description)
    }

    private companion object {
        const val MAX_RUNS = 10
        const val TOTAL_BUDGET_MS = 900_000L
        const val RUN_TIMEOUT_MS = 180_000L
        const val STOP_TIMEOUT_MS = 30_000L
        const val DIAGNOSTIC_INTERVAL_MS = 10_000L
        const val POLL_MS = 250L
        const val WORK_TIMEOUT_SECONDS = 30L
        const val ONE_TIME = "gemma-accumulation-now"
        const val PERIODIC = "gemma-accumulation-periodic"
    }
}
