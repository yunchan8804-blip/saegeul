/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Intent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.ui.main.ai.StyleReportActivity
import org.fcitx.fcitx5.android.ui.main.ai.dashboard.TypingDnaChartView
import org.fcitx.fcitx5.android.ui.main.ai.dashboard.VaultTimelineView
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleReportActivityDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun styleReportInflatesDashboardChartViews() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, StyleReportActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        ) as StyleReportActivity

        try {
            instrumentation.runOnMainSync {
                assertTrue(
                    "누적 차트가 TypingDnaChartView로 inflate되어야 합니다.",
                    requireNotNull(activity.findViewById<View>(R.id.chart_accumulation)) is TypingDnaChartView
                )
                assertTrue(
                    "말투 차트가 TypingDnaChartView로 inflate되어야 합니다.",
                    requireNotNull(activity.findViewById<View>(R.id.chart_tone)) is TypingDnaChartView
                )
                assertTrue(
                    "개인정보 차트가 TypingDnaChartView로 inflate되어야 합니다.",
                    requireNotNull(activity.findViewById<View>(R.id.chart_privacy)) is TypingDnaChartView
                )
                assertTrue(
                    "타임라인이 VaultTimelineView로 inflate되어야 합니다.",
                    requireNotNull(activity.findViewById<View>(R.id.timeline_view)) is VaultTimelineView
                )
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
