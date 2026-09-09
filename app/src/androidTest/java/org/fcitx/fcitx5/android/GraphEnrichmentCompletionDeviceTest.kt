/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentPhase
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentStatus
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentStatusStore
import org.fcitx.fcitx5.android.ui.main.ai.TypingDnaDashboardActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class GraphEnrichmentCompletionDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun dashboardSyncStartsNewGraphEnrichmentAndReachesTerminalState() {
        val automation = instrumentation.uiAutomation
        val statusStore = GraphEnrichmentStatusStore(instrumentation.targetContext)
        var activity: TypingDnaDashboardActivity? = null
        var terminalCaptured = false
        try {
            activity = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, TypingDnaDashboardActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            ) as TypingDnaDashboardActivity
            waitForDashboardContent(activity)
            captureScreen("initial", automation)

            val previous = statusStore.snapshot()
            clickSyncNow(activity)
            val started = waitForNewRunStart(statusStore, previous.startedMs, automation)
            val terminal = waitForTerminalStatus(statusStore, started.startedMs, automation)
            assertTrue(
                "Successful enrichment must apply after its own start.",
                terminal.lastAppliedMs >= started.startedMs
            )
            assertTrue(
                "Successful enrichment must finish after its own start.",
                terminal.finishedMs >= started.startedMs
            )
            waitForTerminalDashboardRender(activity, terminal.phase)
            captureScreen("terminal", automation)
            terminalCaptured = true
            reportStatus(terminal)
        } finally {
            if (!terminalCaptured && activity != null) {
                captureScreen("terminal", automation)
            }
            activity?.let { launched -> instrumentation.runOnMainSync { launched.finish() } }
        }
    }

    private fun waitForDashboardContent(activity: TypingDnaDashboardActivity) {
        val deadline = SystemClock.elapsedRealtime() + DASHBOARD_READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val ready = onMain {
                val loading = requireNotNull(activity.findViewById<View>(R.id.dashboard_loading))
                val content = requireNotNull(activity.findViewById<View>(R.id.dashboard_content))
                val error = requireNotNull(activity.findViewById<View>(R.id.dashboard_loading_error))
                loading.visibility == View.GONE && content.visibility == View.VISIBLE && !error.isShown
            }
            if (ready) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("Dashboard content did not become visible within $DASHBOARD_READY_TIMEOUT_MS ms.")
    }

    private fun clickSyncNow(activity: TypingDnaDashboardActivity) = onMain {
        val sync = requireNotNull(activity.findViewById<View>(R.id.btn_sync_now))
        assertTrue("Dashboard sync action must be enabled before starting enrichment.", sync.isEnabled)
        assertTrue("Dashboard sync action did not accept the click.", sync.performClick())
    }

    private fun waitForNewRunStart(
        statusStore: GraphEnrichmentStatusStore,
        previousStartedMs: Long,
        automation: UiAutomation
    ): GraphEnrichmentStatus {
        val deadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
        var confirmationDismissed = false
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!confirmationDismissed) {
                confirmationDismissed = dismissConfirmationDialogIfPresent(automation)
            }
            val status = statusStore.snapshot()
            if (status.startedMs > previousStartedMs) {
                when (status.phase) {
                    GraphEnrichmentPhase.RUNNING,
                    GraphEnrichmentPhase.SUCCEEDED,
                    GraphEnrichmentPhase.PARTIAL -> return status
                    GraphEnrichmentPhase.FAILED,
                    GraphEnrichmentPhase.NO_DATA,
                    GraphEnrichmentPhase.INTERRUPTED -> terminalFailure(status)
                    GraphEnrichmentPhase.NEVER -> Unit
                }
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("Dashboard sync did not create a new graph enrichment start within $START_TIMEOUT_MS ms.")
    }

    private fun waitForTerminalStatus(
        statusStore: GraphEnrichmentStatusStore,
        startedMs: Long,
        automation: UiAutomation
    ): GraphEnrichmentStatus {
        val deadline = SystemClock.elapsedRealtime() + TERMINAL_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            dismissConfirmationDialogIfPresent(automation)
            val status = statusStore.snapshot()
            if (status.startedMs == startedMs) {
                when (status.phase) {
                    GraphEnrichmentPhase.SUCCEEDED,
                    GraphEnrichmentPhase.PARTIAL -> return status
                    GraphEnrichmentPhase.FAILED,
                    GraphEnrichmentPhase.NO_DATA,
                    GraphEnrichmentPhase.INTERRUPTED -> terminalFailure(status)
                    GraphEnrichmentPhase.NEVER,
                    GraphEnrichmentPhase.RUNNING -> Unit
                }
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("Graph enrichment did not reach a successful terminal state within $TERMINAL_TIMEOUT_MS ms.")
    }

    private fun waitForTerminalDashboardRender(
        activity: TypingDnaDashboardActivity,
        phase: GraphEnrichmentPhase
    ) {
        val expectedText = onMain {
            activity.getString(
                when (phase) {
                    GraphEnrichmentPhase.SUCCEEDED -> R.string.enrichment_status_succeeded
                    GraphEnrichmentPhase.PARTIAL -> R.string.enrichment_status_partial
                    else -> throw AssertionError("Terminal phase $phase cannot render as successful.")
                }
            )
        }
        val deadline = SystemClock.elapsedRealtime() + DASHBOARD_TERMINAL_RENDER_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val rendered = onMain {
                val progress = requireNotNull(activity.findViewById<View>(R.id.progress_enrichment))
                val level = requireNotNull(activity.findViewById<android.widget.TextView>(R.id.tv_dash_sync_level))
                progress.visibility == View.GONE && level.text.toString() == expectedText
            }
            if (rendered) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("Dashboard did not render the $phase terminal state within $DASHBOARD_TERMINAL_RENDER_TIMEOUT_MS ms.")
    }

    private fun terminalFailure(status: GraphEnrichmentStatus): Nothing {
        reportStatus(status)
        throw AssertionError(
            "Graph enrichment ended with ${status.phase.name}/${status.failure.name}: " +
                "started=${status.startedMs}, finished=${status.finishedMs}, " +
                "lastApplied=${status.lastAppliedMs}, nodes=${status.nodes}, " +
                "edges=${status.edges}, topics=${status.topics}"
        )
    }

    private fun dismissConfirmationDialogIfPresent(automation: UiAutomation): Boolean {
        val expectedText = instrumentation.targetContext.getString(android.R.string.ok)
        val targetPackage = instrumentation.targetContext.packageName
        val confirmation = automation.windows
            .asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .mapNotNull { findClickableTextNode(it, expectedText, targetPackage) }
            .firstOrNull()
            ?: return false
        assertTrue("Only the confirmation action may dismiss the enrichment modal.", confirmation.isClickable)
        assertTrue("Confirmation action did not accept the click.", confirmation.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        return true
    }

    private fun findClickableTextNode(
        node: AccessibilityNodeInfo,
        expectedText: String,
        targetPackage: String
    ): AccessibilityNodeInfo? {
        if (
            node.text?.toString() == expectedText &&
            node.packageName?.toString() == targetPackage &&
            node.isClickable &&
            node.isVisibleToUser
        ) {
            return node
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                findClickableTextNode(child, expectedText, targetPackage)?.let { return it }
            }
        }
        return null
    }

    private fun reportStatus(status: GraphEnrichmentStatus) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("phase", status.phase.name)
                putString("failure", status.failure.name)
                putLong("lastAppliedMs", status.lastAppliedMs)
                putLong("startedMs", status.startedMs)
                putLong("finishedMs", status.finishedMs)
                putInt("nodes", status.nodes)
                putInt("edges", status.edges)
                putInt("topics", status.topics)
            }
        )
    }

    private fun captureScreen(stage: String, automation: UiAutomation) {
        val bitmap = requireNotNull(automation.takeScreenshot()) {
            "Graph enrichment $stage screenshot is unavailable."
        }
        val directory = requireNotNull(
            instrumentation.targetContext.getExternalFilesDir("graph-enrichment-completion")
        ) {
            "Graph enrichment screenshot directory is unavailable."
        }
        val screenshot = File(
            directory,
            "graph-enrichment-$stage-${SystemClock.elapsedRealtime()}-${screenshotIndex.incrementAndGet()}.png"
        )
        try {
            screenshot.outputStream().use { output ->
                require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Graph enrichment $stage screenshot could not be encoded."
                }
            }
        } finally {
            bitmap.recycle()
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("${stage}ScreenshotPath", screenshot.absolutePath) })
    }

    private fun <T : Any> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        return requireNotNull(result)
    }

    private companion object {
        const val DASHBOARD_READY_TIMEOUT_MS = 30_000L
        const val START_TIMEOUT_MS = 30_000L
        const val TERMINAL_TIMEOUT_MS = 180_000L
        const val DASHBOARD_TERMINAL_RENDER_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 500L
        val screenshotIndex = AtomicInteger(0)
    }
}
