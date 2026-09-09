/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.ai.TypingDnaDashboardActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MainActivityResponsivenessDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun mainHomeCardLoadsAndNavigatesWithoutFreezingMainQueue() {
        val probe = MainQueueProbe()
        val totalStartedAt = SystemClock.elapsedRealtime()
        var mainActivity: MainActivity? = null
        var dashboardActivity: TypingDnaDashboardActivity? = null
        val dashboardMonitor = instrumentation.addMonitor(TypingDnaDashboardActivity::class.java.name, null, false)
        probe.start()
        try {
            val mainStartedAt = SystemClock.elapsedRealtime()
            val launchedMain = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            ) as MainActivity
            mainActivity = launchedMain

            waitForHomeState(launchedMain, "home_first_ui") { it.firstUiReady }
            val firstUiElapsedMs = SystemClock.elapsedRealtime() - mainStartedAt
            val firstUiProbe = waitForProbeSample(probe)
            report("home_first_ui", firstUiElapsedMs, firstUiProbe)
            assertResponsive("home_first_ui", firstUiProbe)

            waitForHomeState(launchedMain, "home_data_ready") { it.dataReady }
            val dataReadyElapsedMs = SystemClock.elapsedRealtime() - mainStartedAt
            val dataReadyProbe = waitForProbeSample(probe)
            report("home_data_ready", dataReadyElapsedMs, dataReadyProbe)
            assertResponsive("home_data_ready", dataReadyProbe)

            probe.reset()
            val dashboardStartedAt = SystemClock.elapsedRealtime()
            clickDashboard(launchedMain)
            val launchedDashboard = dashboardMonitor.waitForActivityWithTimeout(READY_TIMEOUT_MS) as? TypingDnaDashboardActivity
                ?: throw AssertionError("dashboard activity was not started within $READY_TIMEOUT_MS ms")
            dashboardActivity = launchedDashboard
            waitForDashboardVisible(launchedDashboard)
            val dashboardElapsedMs = SystemClock.elapsedRealtime() - dashboardStartedAt
            val dashboardProbe = waitForProbeSample(probe)
            report("dashboard_first_ui", dashboardElapsedMs, dashboardProbe)
            assertResponsive("dashboard_first_ui", dashboardProbe)

            probe.reset()
            val backStartedAt = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync { launchedDashboard.onBackPressedDispatcher.onBackPressed() }
            waitForHomeState(launchedMain, "dashboard_back") { it.firstUiReady }
            val backElapsedMs = SystemClock.elapsedRealtime() - backStartedAt
            val backProbe = waitForProbeSample(probe)
            report("dashboard_back", backElapsedMs, backProbe)
            assertResponsive("dashboard_back", backProbe)
        } catch (error: Throwable) {
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("failureClass", error.javaClass.simpleName)
                    putLong("timestampMs", SystemClock.elapsedRealtime())
                }
            )
            throw error
        } finally {
            val finalProbe = probe.snapshot()
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putLong("totalElapsedMs", SystemClock.elapsedRealtime() - totalStartedAt)
                    putLong("mainMaxQueueDelayMs", finalProbe.maxQueueDelayMs)
                    putInt("mainQueueSamples", finalProbe.samples)
                }
            )
            probe.stop()
            instrumentation.removeMonitor(dashboardMonitor)
            dashboardActivity?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
            mainActivity?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
        }
    }

    private fun waitForHomeState(
        activity: MainActivity,
        stage: String,
        condition: (HomeState) -> Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        var lastFlags = HomeViewFlags()
        var lastState: HomeState? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val reading = readHomeState(activity)
            lastFlags = reading.flags
            val state = reading.state
            lastState = state
            if (state != null && condition(state)) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        reportHomeState(stage, lastFlags, lastState)
        throw AssertionError("$stage home card did not reach its required visible state within $READY_TIMEOUT_MS ms")
    }

    private fun readHomeState(activity: MainActivity): HomeStateReading {
        var reading = HomeStateReading(HomeViewFlags(), null)
        instrumentation.runOnMainSync {
            val title = activity.findViewById<TextView>(R.id.tv_main_card_title)
            val card = title?.let(::findCardAncestor)
            val detail = activity.findViewById<View>(R.id.btn_dna_card_more)
            val progress = activity.findViewById<LinearProgressIndicator>(R.id.progress_main_card)
            val metricSentences = activity.findViewById<TextView>(R.id.tv_metric_sentences)
            val metricBigrams = activity.findViewById<TextView>(R.id.tv_metric_bigrams)
            val metricEndings = activity.findViewById<TextView>(R.id.tv_metric_endings)
            val metricPhrases = activity.findViewById<TextView>(R.id.tv_metric_phrases)
            val flags = HomeViewFlags(
                titleFound = title != null,
                cardFound = card != null,
                detailFound = detail != null,
                progressFound = progress != null,
                metricSentencesFound = metricSentences != null,
                metricBigramsFound = metricBigrams != null,
                metricEndingsFound = metricEndings != null,
                metricPhrasesFound = metricPhrases != null
            )
            val metrics = listOfNotNull(metricSentences, metricBigrams, metricEndings, metricPhrases)
            val state = if (card != null && detail != null && progress != null && metrics.size == 4) {
                HomeState(
                    cardVisibleAndClickable = card.isShown && card.isEnabled && card.isClickable,
                    detailActionVisibleAndClickable = detail.isShown && detail.isEnabled && detail.isClickable,
                    loading = progress.isIndeterminate,
                    snapshotNumbersVisible = metrics.all { metric ->
                        metric.isShown && metric.text.any { character -> character.isDigit() }
                    }
                )
            } else {
                null
            }
            reading = HomeStateReading(flags, state)
        }
        return reading
    }

    private fun findCardAncestor(view: View): MaterialCardView? {
        var current: View? = view
        while (current != null) {
            if (current is MaterialCardView) return current
            current = current.parent as? View
        }
        return null
    }

    private fun reportHomeState(stage: String, flags: HomeViewFlags, state: HomeState?) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("homeStage", stage)
                putBoolean("homeTitleFound", flags.titleFound)
                putBoolean("homeCardFound", flags.cardFound)
                putBoolean("homeDetailFound", flags.detailFound)
                putBoolean("homeProgressFound", flags.progressFound)
                putBoolean("homeMetricSentencesFound", flags.metricSentencesFound)
                putBoolean("homeMetricBigramsFound", flags.metricBigramsFound)
                putBoolean("homeMetricEndingsFound", flags.metricEndingsFound)
                putBoolean("homeMetricPhrasesFound", flags.metricPhrasesFound)
                putBoolean("homeCardVisibleAndClickable", state?.cardVisibleAndClickable ?: false)
                putBoolean("homeDetailVisibleAndClickable", state?.detailActionVisibleAndClickable ?: false)
                putBoolean("homeLoading", state?.loading ?: false)
                putBoolean("homeSnapshotNumbersVisible", state?.snapshotNumbersVisible ?: false)
            }
        )
    }

    private fun clickDashboard(activity: MainActivity) = onMain {
        val detail = requireNotNull(activity.findViewById<View>(R.id.btn_dna_card_more))
        assertTrue("detail graph action must be visible and clickable", detail.isShown && detail.isEnabled && detail.isClickable)
        assertTrue("detail graph action did not accept the click", detail.performClick())
    }

    private fun waitForDashboardVisible(activity: TypingDnaDashboardActivity) {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val visible = onMain {
                activity.findViewById<View>(R.id.dashboard_root)?.isShown == true
            }
            if (visible) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("dashboard root was not visible within $READY_TIMEOUT_MS ms")
    }

    private fun waitForProbeSample(probe: MainQueueProbe): ProbeSnapshot {
        val deadline = SystemClock.elapsedRealtime() + PROBE_SAMPLE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = probe.snapshot()
            if (snapshot.samples > 0) return snapshot
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("main queue probe did not collect a sample within $PROBE_SAMPLE_TIMEOUT_MS ms")
    }

    private fun assertResponsive(stage: String, snapshot: ProbeSnapshot) {
        assertTrue("$stage probe must collect samples", snapshot.samples > 0)
        assertTrue(
            "$stage main queue delay must stay below $MAX_MAIN_QUEUE_DELAY_MS ms, actual=${snapshot.maxQueueDelayMs} ms",
            snapshot.maxQueueDelayMs < MAX_MAIN_QUEUE_DELAY_MS
        )
    }

    private fun report(stage: String, elapsedMs: Long, snapshot: ProbeSnapshot) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("stage", stage)
                putLong("elapsedMs", elapsedMs)
                putLong("mainMaxQueueDelayMs", snapshot.maxQueueDelayMs)
                putInt("mainQueueSamples", snapshot.samples)
                putLong("timestampMs", SystemClock.elapsedRealtime())
            }
        )
    }

    private fun <T : Any> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        return requireNotNull(result)
    }

    private data class HomeState(
        val cardVisibleAndClickable: Boolean,
        val detailActionVisibleAndClickable: Boolean,
        val loading: Boolean,
        val snapshotNumbersVisible: Boolean
    ) {
        val firstUiReady: Boolean
            get() = cardVisibleAndClickable && detailActionVisibleAndClickable
        val dataReady: Boolean
            get() = firstUiReady && !loading && snapshotNumbersVisible
    }

    private data class HomeStateReading(
        val flags: HomeViewFlags,
        val state: HomeState?
    )

    private data class HomeViewFlags(
        val titleFound: Boolean = false,
        val cardFound: Boolean = false,
        val detailFound: Boolean = false,
        val progressFound: Boolean = false,
        val metricSentencesFound: Boolean = false,
        val metricBigramsFound: Boolean = false,
        val metricEndingsFound: Boolean = false,
        val metricPhrasesFound: Boolean = false
    )

    private data class ProbeSnapshot(val maxQueueDelayMs: Long, val samples: Int)

    private class MainQueueProbe {
        private val handler = Handler(Looper.getMainLooper())
        private val token = Any()
        private val running = AtomicBoolean(false)
        private val maxQueueDelayMs = AtomicLong(0L)
        private val samples = AtomicInteger(0)
        private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "main-activity-main-queue-probe").apply { isDaemon = true }
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
                                    updateMax((SystemClock.uptimeMillis() - enqueuedAt).coerceAtLeast(0L))
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

        fun snapshot() = ProbeSnapshot(maxQueueDelayMs.get(), samples.get())

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
        const val READY_TIMEOUT_MS = 45_000L
        const val POLL_INTERVAL_MS = 100L
        const val PROBE_INTERVAL_MS = 100L
        const val PROBE_SAMPLE_TIMEOUT_MS = 2_000L
        const val MAX_MAIN_QUEUE_DELAY_MS = 1_500L
    }
}
