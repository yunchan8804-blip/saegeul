/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsStore
import org.fcitx.fcitx5.android.ui.main.ai.dashboard.TypingDnaChartView
import org.fcitx.fcitx5.android.ui.main.ai.dashboard.VaultTimelineView

/**
 * Binds `res/layout/view_style_report_sections.xml` (accumulated records, last 30 days, where I
 * write, style traits). Shared by the vault home, where it is shown by default, and
 * [StyleReportActivity]. Every value comes from one [StyleReportUiState.from] call.
 */
class StyleReportSections(root: View) {

    data class Data(
        val state: StyleReportUiState,
        val stats: org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaStats,
        val metrics: PredictionMetricsStore.Summary
    )

    private val context: Context = root.context
    private val chartAccumulation: TypingDnaChartView = root.findViewById(R.id.chart_accumulation)
    private val chartTone: TypingDnaChartView = root.findViewById(R.id.chart_tone)
    private val chartPrivacy: TypingDnaChartView = root.findViewById(R.id.chart_privacy)
    private val timelineView: VaultTimelineView = root.findViewById(R.id.timeline_view)
    private val tvRecentSummary: TextView = root.findViewById(R.id.tv_recent_summary)
    private val tvRecentEmpty: TextView = root.findViewById(R.id.tv_recent_empty)
    private val categoryRowsContainer: LinearLayout = root.findViewById(R.id.category_rows_container)
    private val tvCategoryEmpty: TextView = root.findViewById(R.id.tv_category_empty)
    private val tvTransitionsTitle: TextView = root.findViewById(R.id.tv_transitions_title)
    private val chipGroupTransitions: ChipGroup = root.findViewById(R.id.chip_group_transitions)
    private val tvEndingsTitle: TextView = root.findViewById(R.id.tv_endings_title)
    private val chipGroupEndings: ChipGroup = root.findViewById(R.id.chip_group_endings)

    init {
        chartAccumulation.setVisibleSections(setOf(TypingDnaChartView.Section.ACCUMULATION))
        chartTone.setVisibleSections(
            setOf(TypingDnaChartView.Section.TONE_BALANCE, TypingDnaChartView.Section.TOP_TRANSITIONS)
        )
        chartPrivacy.setVisibleSections(setOf(TypingDnaChartView.Section.PRIVACY_GAUGE))
    }

    fun render(data: Data, animate: Boolean) {
        val state = data.state
        // Drawn even before anything is learned, so the cards show empty bars instead of a blank box.
        chartAccumulation.setStats(data.stats, animate = animate)
        chartTone.setStats(data.stats, animate = animate)
        chartPrivacy.setStats(data.stats, animate = animate)
        timelineView.setSummary(data.metrics)
        renderRecent(state)
        renderCategoryRows(state.categoryRows)
        // Word transitions are drawn by the chart above, so no duplicate chip list.
        renderChips(tvTransitionsTitle, chipGroupTransitions, emptyList())
        renderChips(tvEndingsTitle, chipGroupEndings, state.topEndingChips)
    }

    private fun renderRecent(state: StyleReportUiState) {
        val hasAny = state.recentTotalSentences > 0
        tvRecentSummary.visibility = if (hasAny) View.VISIBLE else View.GONE
        tvRecentEmpty.visibility = if (hasAny) View.GONE else View.VISIBLE
        if (hasAny) {
            tvRecentSummary.text = context.getString(
                R.string.vault_timeline_summary, state.recentTotalSentences, state.recentDailyMaximum
            )
        }
    }

    private fun renderCategoryRows(rows: List<StyleReportUiState.CategoryRow>) {
        categoryRowsContainer.removeAllViews()
        if (rows.isEmpty()) {
            categoryRowsContainer.visibility = View.GONE
            tvCategoryEmpty.visibility = View.VISIBLE
            return
        }
        categoryRowsContainer.visibility = View.VISIBLE
        tvCategoryEmpty.visibility = View.GONE
        val inflater = LayoutInflater.from(context)
        rows.forEach { row ->
            val rowView = inflater.inflate(R.layout.view_dashboard_category_row, categoryRowsContainer, false)
            rowView.findViewById<TextView>(R.id.tv_category_row_label).text = context.getString(row.labelRes)
            rowView.findViewById<View>(R.id.tv_category_row_empty).visibility = View.GONE
            val fillView = rowView.findViewById<View>(R.id.view_category_row_fill)
            val params = fillView.layoutParams as LinearLayout.LayoutParams
            params.weight = (row.percent / 100f).coerceIn(0f, 1f)
            fillView.layoutParams = params
            rowView.findViewById<TextView>(R.id.tv_category_row_value).text = "${row.percent}%"
            categoryRowsContainer.addView(rowView)
        }
    }

    private fun renderChips(title: TextView, group: ChipGroup, chips: List<String>) {
        group.removeAllViews()
        chips.forEach { text ->
            group.addView(Chip(context).apply {
                this.text = text
                isClickable = false
                isCheckable = false
                isFocusable = false
            })
        }
        val hasAny = chips.isNotEmpty()
        title.visibility = if (hasAny) View.VISIBLE else View.GONE
        group.visibility = if (hasAny) View.VISIBLE else View.GONE
    }

    companion object {
        /** Blocking read of every store the sections need; call off the main thread. */
        fun load(): Data {
            val app = FcitxApplication.getInstance()
            val stats = app.typingDnaRepository.getStats()
            val metrics = app.predictionMetricsStore.summary()
            val state = StyleReportUiState.from(
                stats, app.personalSentenceVault.categoryCounts(), metrics
            )
            return Data(state, stats, metrics)
        }
    }
}
