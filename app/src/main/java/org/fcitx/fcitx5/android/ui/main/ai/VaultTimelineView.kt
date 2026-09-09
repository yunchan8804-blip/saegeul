/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsStore
import splitties.dimensions.dp
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Pure data mapping from [PredictionMetricsStore.Summary] to drawable bars for
 * [VaultTimelineView]. Kept free of Android view dependencies so it can be unit-tested directly.
 */
object VaultTimelineData {

    data class Bar(
        val day: String,
        val value: Int,
        val heightRatio: Float
    )

    /** Maps recent daily learned-sentence counts into bars normalized to the largest day. */
    fun fromSummary(summary: PredictionMetricsStore.Summary): List<Bar> {
        val recent = summary.recent
        if (recent.isEmpty()) return emptyList()
        val maxValue = recent.maxOf { it.learnedSentences }.coerceAtLeast(1)
        return recent.map { day ->
            val value = day.learnedSentences
            Bar(
                day = day.day,
                value = value,
                heightRatio = value.toFloat() / maxValue
            )
        }
    }

    /** Formats an ISO-8601 (`yyyy-MM-dd`) day key as `M/d`. Falls back to the raw key on parse failure. */
    fun formatLabel(day: String): String = try {
        val date = LocalDate.parse(day)
        "${date.monthValue}/${date.dayOfMonth}"
    } catch (_: DateTimeParseException) {
        day
    }
}

/**
 * Lightweight, 0-dependency canvas view rendering the "내 언어 금고" growth timeline:
 * one bar per recent day using only learned sentences, with first/last day labels.
 */
class VaultTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bars: List<VaultTimelineData.Bar> = emptyList()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scratchRect = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setSummary(summary: PredictionMetricsStore.Summary) {
        bars = VaultTimelineData.fromSummary(summary)
        contentDescription = buildContentDescription()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = dp(CHART_HEIGHT_DP) + dp(LABEL_ROW_HEIGHT_DP) + paddingTop + paddingBottom
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val list = bars

        val paddingL = paddingLeft + dp(2f)
        val paddingR = width - paddingRight - dp(2f)
        val availableWidth = paddingR - paddingL
        if (availableWidth <= 0) return

        val chartTop = paddingTop.toFloat()
        val chartBottom = chartTop + dp(CHART_HEIGHT_DP)
        val chartHeight = chartBottom - chartTop

        val primaryColor = resolveThemeColor(android.R.attr.colorPrimary)
        val onSurfaceColor = resolveThemeColor(android.R.attr.textColorPrimary)

        barPaint.color = primaryColor
        baselinePaint.color = ColorUtils.setAlphaComponent(onSurfaceColor, BASELINE_ALPHA)
        baselinePaint.strokeWidth = dp(1f)
        labelPaint.color = ColorUtils.setAlphaComponent(onSurfaceColor, LABEL_ALPHA)
        labelPaint.textSize = dp(10f)

        canvas.drawLine(paddingL, chartBottom, paddingR, chartBottom, baselinePaint)
        if (list.isEmpty()) return

        val slot = availableWidth / list.size
        val barWidth = (slot * 0.6f).coerceAtLeast(dp(1.5f))
        val gap = (slot - barWidth) / 2f

        list.forEachIndexed { index, bar ->
            val slotStart = paddingL + slot * index
            val left = slotStart + gap
            val right = left + barWidth
            val cornerRadius = barWidth / 2f

            if (bar.heightRatio > 0f) {
                val barTop = chartBottom - chartHeight * bar.heightRatio
                scratchRect.set(left, barTop, right, chartBottom)
                canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, barPaint)
            }
        }

        val labelY = chartBottom + dp(14f)
        val firstLabel = VaultTimelineData.formatLabel(list.first().day)
        canvas.drawText(firstLabel, paddingL, labelY, labelPaint)
        val lastLabel = VaultTimelineData.formatLabel(list.last().day)
        val lastLabelWidth = labelPaint.measureText(lastLabel)
        canvas.drawText(lastLabel, paddingR - lastLabelWidth, labelY, labelPaint)
    }

    private fun buildContentDescription(): String {
        if (bars.isEmpty()) return context.getString(R.string.vault_timeline_accessibility_empty)
        val firstDay = VaultTimelineData.formatLabel(bars.first().day)
        val lastDay = VaultTimelineData.formatLabel(bars.last().day)
        val maxValue = bars.maxOf { it.value }
        return context.getString(
            R.string.vault_timeline_accessibility_populated,
            bars.size,
            firstDay,
            lastDay,
            maxValue
        )
    }

    private fun resolveThemeColor(attr: Int): Int {
        val tv = TypedValue()
        if (!context.theme.resolveAttribute(attr, tv, true)) return Color.GRAY
        return if (tv.resourceId != 0) {
            runCatching { context.getColor(tv.resourceId) }.getOrDefault(tv.data)
        } else {
            tv.data
        }
    }

    companion object {
        private const val CHART_HEIGHT_DP = 96
        private const val LABEL_ROW_HEIGHT_DP = 18
        private const val BASELINE_ALPHA = 96
        private const val LABEL_ALPHA = 160
    }
}
