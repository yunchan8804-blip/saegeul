/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.ui.main.ai.TypingDnaDashboardActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class TypingDnaDashboardResponsivenessDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun dashboardLoadAndOptionalAnalysisDoNotFreezeMainQueue() {
        val runAnalysis = InstrumentationRegistry.getArguments().getString("runAnalysis") == "true"
        val probe = MainQueueProbe()
        val startupStartedAt = SystemClock.elapsedRealtime()
        var activity: TypingDnaDashboardActivity? = null
        probe.start()
        try {
            activity = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, TypingDnaDashboardActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            ) as TypingDnaDashboardActivity

            waitForStartupReady(activity)
            val startupElapsedMs = SystemClock.elapsedRealtime() - startupStartedAt
            val startupProbe = probe.snapshot()
            report("startup", startupElapsedMs, startupProbe)
            assertResponsive("startup", startupProbe)
            captureScreen("startup")

            if (runAnalysis) {
                probe.reset()
                val analysisStartedAt = SystemClock.elapsedRealtime()
                clickSyncAndAssertBusy(activity)
                waitForAnalysisReady(activity)
                val analysisElapsedMs = SystemClock.elapsedRealtime() - analysisStartedAt
                val analysisProbe = probe.snapshot()
                report("analysis", analysisElapsedMs, analysisProbe)
                assertResponsive("analysis", analysisProbe)
                captureScreen("analysis")
            }
        } finally {
            val elapsedMs = SystemClock.elapsedRealtime() - startupStartedAt
            val finalProbe = probe.snapshot()
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putLong("totalElapsedMs", elapsedMs)
                    putLong("mainMaxQueueDelayMs", finalProbe.maxQueueDelayMs)
                    putInt("samples", finalProbe.samples)
                    putBoolean("runAnalysis", runAnalysis)
                }
            )
            probe.stop()
            activity?.let { launched -> instrumentation.runOnMainSync { launched.finish() } }
        }
    }

    private fun waitForStartupReady(activity: TypingDnaDashboardActivity) {
        waitForReady(
            activity = activity,
            stage = "startup",
            busyId = R.id.dashboard_loading,
            busyMessageId = R.id.dashboard_loading_message,
            errorId = R.id.dashboard_loading_error
        )
    }

    private fun waitForAnalysisReady(activity: TypingDnaDashboardActivity) {
        waitForReady(
            activity = activity,
            stage = "analysis",
            busyId = R.id.dashboard_sync_busy,
            busyMessageId = R.id.dashboard_sync_busy_message,
            errorId = R.id.dashboard_sync_error
        )
    }

    private fun waitForReady(
        activity: TypingDnaDashboardActivity,
        stage: String,
        busyId: Int,
        busyMessageId: Int,
        errorId: Int
    ) {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = readDashboardState(activity, busyId, busyMessageId, errorId)
            if (state.errorVisible) {
                throw AssertionError("$stage dashboard loading error is visible")
            }
            if (state.loadingVisibility == View.GONE && state.contentVisibility == View.VISIBLE) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        val state = readDashboardState(activity, busyId, busyMessageId, errorId)
        throw AssertionError(
            "$stage dashboard did not become ready within $READY_TIMEOUT_MS ms: " +
                "loading=${state.loadingVisibility}, content=${state.contentVisibility}, error=${state.errorVisible}"
        )
    }

    private fun clickSyncAndAssertBusy(activity: TypingDnaDashboardActivity) = onMain {
        val syncBusy = requireNotNull(activity.findViewById<View>(R.id.dashboard_sync_busy))
        requireNotNull(activity.findViewById<View>(R.id.dashboard_sync_busy_message))
        val syncError = requireNotNull(activity.findViewById<View>(R.id.dashboard_sync_error))
        val sync = requireNotNull(activity.findViewById<View>(R.id.btn_sync_now))
        val clear = requireNotNull(activity.findViewById<View>(R.id.btn_clear_dna))

        assertFalse("analysis must start without a visible sync error", syncError.isShown)
        assertTrue("sync action must be enabled before analysis", sync.isEnabled)
        sync.performClick()
        assertEquals("analysis must show busy state immediately", View.VISIBLE, syncBusy.visibility)
        assertFalse("analysis must disable sync while busy", sync.isEnabled)
        assertFalse("analysis must disable clear while busy", clear.isEnabled)
    }

    private fun readDashboardState(
        activity: TypingDnaDashboardActivity,
        busyId: Int,
        busyMessageId: Int,
        errorId: Int
    ): DashboardState = onMain {
        val busy = requireNotNull(activity.findViewById<View>(busyId))
        requireNotNull(activity.findViewById<View>(busyMessageId))
        val error = requireNotNull(activity.findViewById<View>(errorId))
        val content = requireNotNull(activity.findViewById<View>(R.id.dashboard_content))
        DashboardState(
            loadingVisibility = busy.visibility,
            contentVisibility = content.visibility,
            errorVisible = error.isShown
        )
    }

    private fun assertResponsive(stage: String, probe: ProbeSnapshot) {
        assertTrue("$stage probe must collect samples", probe.samples > 0)
        assertTrue(
            "$stage main queue delay must stay below $MAX_MAIN_QUEUE_DELAY_MS ms, actual=${probe.maxQueueDelayMs} ms",
            probe.maxQueueDelayMs < MAX_MAIN_QUEUE_DELAY_MS
        )
    }

    private fun report(stage: String, elapsedMs: Long, probe: ProbeSnapshot) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("dashboardStage", stage)
                if (stage == "startup") {
                    putLong("startupElapsedMs", elapsedMs)
                    putLong("mainMaxQueueDelayMs", probe.maxQueueDelayMs)
                    putInt("samples", probe.samples)
                } else {
                    putLong("analysisElapsedMs", elapsedMs)
                    putLong("analysisMainMaxQueueDelayMs", probe.maxQueueDelayMs)
                    putInt("analysisSamples", probe.samples)
                }
            }
        )
    }

    private fun captureScreen(stage: String) {
        val automation: UiAutomation = instrumentation.uiAutomation
        val bitmap = requireNotNull(automation.takeScreenshot()) {
            "Dashboard $stage screenshot is unavailable."
        }
        val directory = requireNotNull(
            instrumentation.targetContext.getExternalFilesDir("typing-dna-dashboard-responsiveness")
        ) {
            "Dashboard screenshot directory is unavailable."
        }
        val screenshot = File(
            directory,
            "typing-dna-dashboard-$stage-${SystemClock.elapsedRealtime()}-${screenshotIndex.incrementAndGet()}.png"
        )
        try {
            screenshot.outputStream().use { output ->
                require(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) {
                    "Dashboard $stage screenshot could not be encoded."
                }
            }
        } finally {
            bitmap.recycle()
        }
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("dashboardScreenshotStage", stage)
                putString("dashboardScreenshotPath", screenshot.absolutePath)
            }
        )
    }

    private fun <T : Any> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        return requireNotNull(result)
    }

    private data class DashboardState(
        val loadingVisibility: Int,
        val contentVisibility: Int,
        val errorVisible: Boolean
    )

    private data class ProbeSnapshot(val maxQueueDelayMs: Long, val samples: Int)

    private class MainQueueProbe {
        private val handler = Handler(Looper.getMainLooper())
        private val token = Any()
        private val running = AtomicBoolean(false)
        private val maxQueueDelayMs = AtomicLong(0L)
        private val samples = AtomicInteger(0)
        private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "typing-dna-main-queue-probe").apply { isDaemon = true }
        }
        private var future: ScheduledFuture<*>? = null

        fun start() {
            check(running.compareAndSet(false, true)) { "Main queue probe is already running" }
            future = executor.scheduleAtFixedRate(
                {
                    if (running.get()) {
                        val enqueuedAt = SystemClock.uptimeMillis()
                        handler.postAtTime(
                            {
                                if (running.get()) {
                                    val delayMs = (SystemClock.uptimeMillis() - enqueuedAt).coerceAtLeast(0L)
                                    updateMax(delayMs)
                                    samples.incrementAndGet()
                                }
                            },
                            token,
                            enqueuedAt
                        )
                    }
                },
                0L,
                PROBE_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        }

        fun reset() {
            maxQueueDelayMs.set(0L)
            samples.set(0)
        }

        fun snapshot(): ProbeSnapshot = ProbeSnapshot(maxQueueDelayMs.get(), samples.get())

        fun stop() {
            if (!running.compareAndSet(true, false)) return
            future?.cancel(true)
            executor.shutdownNow()
            handler.removeCallbacksAndMessages(token)
        }

        private fun updateMax(delayMs: Long) {
            while (true) {
                val current = maxQueueDelayMs.get()
                if (delayMs <= current || maxQueueDelayMs.compareAndSet(current, delayMs)) return
            }
        }
    }

    private companion object {
        const val READY_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 100L
        const val PROBE_INTERVAL_MS = 100L
        const val MAX_MAIN_QUEUE_DELAY_MS = 1_500L
        val screenshotIndex = AtomicInteger(0)
    }
}
