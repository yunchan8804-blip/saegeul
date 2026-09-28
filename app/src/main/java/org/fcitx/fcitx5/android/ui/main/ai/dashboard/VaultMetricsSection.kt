/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.dashboard

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsStore
import kotlin.math.roundToInt

/** Developer cards with the raw numbers: key storage kind, suggestion metrics, and the growth timeline. */
internal class VaultMetricsSection(private val activity: Activity) {
    private val securitySubtitle: TextView = activity.findViewById(R.id.tv_vault_security_subtitle)
    private val integrityGrid: LinearLayout = activity.findViewById(R.id.vault_integrity_grid)
    private val integrityEmpty: TextView = activity.findViewById(R.id.tv_vault_integrity_empty)
    private val acceptRate: TextView = activity.findViewById(R.id.tv_vault_accept_rate)
    private val acceptRateDescription: TextView = activity.findViewById(R.id.tv_vault_accept_rate_description)
    private val personalHits: TextView = activity.findViewById(R.id.tv_vault_personal_hits)
    private val keystrokesSaved: TextView = activity.findViewById(R.id.tv_vault_keystrokes_saved)
    private val typosFixed: TextView = activity.findViewById(R.id.tv_vault_typos_fixed)
    private val timeline: VaultTimelineView = activity.findViewById(R.id.vault_timeline_view)
    private val timelineSummary: TextView = activity.findViewById(R.id.tv_vault_timeline_summary)
    private val timelineEmpty: TextView = activity.findViewById(R.id.tv_vault_timeline_empty)

    fun renderSecurity(security: DashboardSecurity) {
        securitySubtitle.text = when {
            security.isStrongBoxBacked -> activity.getString(R.string.vault_security_strongbox)
            security.isHardwareBacked -> activity.getString(R.string.vault_security_tee)
            else -> activity.getString(R.string.vault_security_software)
        }
    }

    fun renderMetrics(metrics: PredictionMetricsStore.Summary) {
        integrityGrid.visibility = View.VISIBLE
        acceptRate.text = if (metrics.totalShown > 0) {
            "${(metrics.acceptRate * 100).roundToInt()}%"
        } else {
            activity.getString(R.string.vault_metric_not_recorded)
        }
        acceptRateDescription.text = if (metrics.totalShown > 0) {
            activity.getString(
                R.string.vault_metric_accept_rate_counts,
                metrics.totalShown,
                metrics.totalAccepted
            )
        } else {
            activity.getString(R.string.vault_metric_accept_rate_description)
        }
        personalHits.text = if (metrics.totalAccepted > 0) {
            "${(metrics.personalShare * 100).roundToInt()}%"
        } else {
            activity.getString(R.string.vault_metric_not_recorded)
        }
        keystrokesSaved.text = if (metrics.keystrokesSaved > 0) {
            activity.getString(R.string.vault_metric_keystrokes_saved_value, metrics.keystrokesSaved)
        } else {
            activity.getString(R.string.vault_metric_not_recorded)
        }
        typosFixed.text = "${metrics.typoCorrected}"
        integrityEmpty.visibility = if (metrics.totalShown == 0) {
            View.VISIBLE
        } else {
            View.GONE
        }

        timeline.setSummary(metrics)
        val timelineSentenceTotal = metrics.recent.sumOf { it.learnedSentences }
        val timelineDailyMaximum = metrics.recent.maxOfOrNull { it.learnedSentences } ?: 0
        timelineSummary.text = activity.getString(
            R.string.vault_timeline_summary,
            timelineSentenceTotal,
            timelineDailyMaximum
        )
        timelineEmpty.visibility = if (metrics.recent.none { it.learnedSentences > 0 }) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }
}
