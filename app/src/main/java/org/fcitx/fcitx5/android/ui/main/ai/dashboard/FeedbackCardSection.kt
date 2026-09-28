/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.dashboard

import android.app.Activity
import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsStore
import org.fcitx.fcitx5.android.ui.main.ai.AcceptanceCardUiState

/** "추천이 도움이 됐나요" card: acceptance rate and how much of it came from the user's own recorded sentences. */
internal class FeedbackCardSection(private val activity: Activity) {
    private val percent: TextView = activity.findViewById(R.id.tv_feedback_percent)
    private val counts: TextView = activity.findViewById(R.id.tv_feedback_counts)
    private val personal: TextView = activity.findViewById(R.id.tv_feedback_personal)

    fun render(metrics: PredictionMetricsStore.Summary) {
        val acceptance = AcceptanceCardUiState.from(metrics)
        if (!acceptance.hasData) {
            percent.visibility = View.GONE
            counts.visibility = View.VISIBLE
            counts.text = activity.getString(R.string.vault_feedback_empty)
            personal.visibility = View.GONE
            return
        }
        percent.visibility = View.VISIBLE
        percent.text = "${acceptance.acceptPercent}%"
        counts.visibility = View.VISIBLE
        counts.text = activity.getString(
            R.string.vault_feedback_counts_line, acceptance.totalShown, acceptance.totalAccepted
        )
        if (acceptance.totalAccepted > 0) {
            personal.visibility = View.VISIBLE
            personal.text = activity.getString(R.string.vault_feedback_personal_line, acceptance.personalPercent)
        } else {
            personal.visibility = View.GONE
        }
    }
}
