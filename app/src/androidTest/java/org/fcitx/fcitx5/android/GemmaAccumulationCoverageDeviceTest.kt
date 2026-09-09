/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.debug.gemma.GemmaAccumulationScheduler
import org.fcitx.fcitx5.android.debug.gemma.GemmaAccumulationStore
import org.fcitx.fcitx5.android.debug.gemma.GemmaExperimentActivity
import org.fcitx.fcitx5.android.debug.gemma.GemmaModelFiles
import org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedMaterialPolicy
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class GemmaAccumulationCoverageDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun accumulateCoverageAcrossPublicPrefixes() = runBlocking(Dispatchers.Default) {
        val context = instrumentation.targetContext
        val overallDeadline = SystemClock.elapsedRealtime() + TOTAL_BUDGET_MS
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
            awaitThermalClear(context, app, store, overallDeadline)
            GemmaAccumulationScheduler.setEnabled(context, true)

            var run = 0
            while (run < MAX_RUNS && SystemClock.elapsedRealtime() < overallDeadline) {
                awaitWorkIdle(context, store, overallDeadline)
                val beforeRequest = GeneratedMaterialPolicy.PREFIXES.associateWith(app.generatedSentenceBank::exactPrefixCount)
                if (beforeRequest.values.all { it >= 1 }) break
                awaitThermalClear(context, app, store, overallDeadline)
                GemmaAccumulationScheduler.requestNow(context)
                awaitWorkIdle(context, store, overallDeadline)
                run++
                val counts = GeneratedMaterialPolicy.PREFIXES.associateWith(app.generatedSentenceBank::exactPrefixCount)
                val state = store.load()
                val thermal = thermalStatus(context)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("gemmaAccumulationCoverageProgress", JSONObject()
                        .put("run", run)
                        .put("thermalStatus", thermal)
                        .put("counts", JSONObject(counts as Map<*, *>))
                        .put("status", state.status)
                        .put("added", state.added)
                        .put("rejected", state.rejected)
                        .put("duplicates", state.duplicates)
                        .put("attemptsTotal", state.attempts.values.sum())
                        .toString())
                })
                if (counts.values.all { it >= 1 }) break
            }
            val covered = GeneratedMaterialPolicy.PREFIXES.associateWith(app.generatedSentenceBank::exactPrefixCount)
            assertTrue("Not all public prefixes covered after $run runs: $covered", covered.values.all { it >= 1 })
            GemmaAccumulationScheduler.setEnabled(context, false)
            await("native generation stopped", 30_000) { !OnDeviceGenerationControl.isGenerating }
            app.generatedSentenceBank.load()
            val reloaded = GeneratedMaterialPolicy.PREFIXES.associateWith(app.generatedSentenceBank::exactPrefixCount)
            assertTrue("Reloaded coverage changed: $reloaded", reloaded.values.all { it >= 1 })
            val evidence = JSONObject()
                .put("runs", run)
                .put("prefixCounts", JSONObject(reloaded as Map<*, *>))
                .put("reloaded", true)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("gemmaAccumulationCoverageEvidence", evidence.toString())
            })
        } finally {
            try {
                GemmaAccumulationScheduler.setEnabled(context, priorEnabled)
            } finally {
                activity?.let { instrumentation.runOnMainSync { it.finish() } }
            }
        }
    }

    private suspend fun awaitThermalClear(
        context: android.content.Context,
        app: FcitxApplication,
        store: GemmaAccumulationStore,
        deadline: Long
    ) {
        var nextReport = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < deadline) {
            val thermal = thermalStatus(context)
            if (thermal < PowerManager.THERMAL_STATUS_MODERATE) return
            if (SystemClock.elapsedRealtime() >= nextReport) {
                val counts = GeneratedMaterialPolicy.PREFIXES.associateWith(app.generatedSentenceBank::exactPrefixCount)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("gemmaAccumulationThermalWait", JSONObject()
                        .put("thermalStatus", thermal)
                        .put("elapsedMs", TOTAL_BUDGET_MS - (deadline - SystemClock.elapsedRealtime()))
                        .put("counts", JSONObject(counts as Map<*, *>))
                        .put("status", store.state.value.status)
                        .toString())
                })
                nextReport += THERMAL_REPORT_INTERVAL_MS
            }
            delay(THERMAL_POLL_MS)
        }
        throw AssertionError("Thermal status did not clear before the 900 second coverage deadline")
    }

    private fun thermalStatus(context: android.content.Context): Int =
        if (Build.VERSION.SDK_INT >= 29) context.getSystemService(PowerManager::class.java).currentThermalStatus else 0

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
            val active = oneTimeActive || periodicActive
            if (!active && !OnDeviceGenerationControl.isGenerating) return
            delay(POLL_MS)
        }
        val manager = WorkManager.getInstance(context)
        reportWorkDiagnostic(
            context,
            store,
            manager.getWorkInfosForUniqueWork(ONE_TIME).get(WORK_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            manager.getWorkInfosForUniqueWork(PERIODIC).get(WORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        )
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
            .put("nextScheduleTimeMillis", info.nextScheduleTimeMillis)
        val app = context.applicationContext as FcitxApplication
        val counts = GeneratedMaterialPolicy.PREFIXES.associateWith(app.generatedSentenceBank::exactPrefixCount)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("gemmaAccumulationWorkDiagnostic", JSONObject()
                .put("oneTime", JSONArray(oneTime.map(::workJson)))
                .put("periodic", JSONArray(periodic.map(::workJson)))
                .put("status", store.state.value.status)
                .put("errorPresent", store.state.value.error != null)
                .put("attemptsTotal", store.state.value.attempts.values.sum())
                .put("keyboardActive", OnDeviceGenerationControl.isKeyboardActive)
                .put("isGenerating", OnDeviceGenerationControl.isGenerating)
                .put("thermalStatus", thermalStatus(context))
                .put("prefixCounts", JSONObject(counts as Map<*, *>))
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
        const val THERMAL_POLL_MS = 1_000L
        const val THERMAL_REPORT_INTERVAL_MS = 10_000L
        const val DIAGNOSTIC_INTERVAL_MS = 10_000L
        const val POLL_MS = 250L
        const val WORK_TIMEOUT_SECONDS = 30L
        const val ONE_TIME = "gemma-accumulation-now"
        const val PERIODIC = "gemma-accumulation-periodic"
    }
}
