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
        val heightRatio: Float,
        val accepted: Int,
        val acceptedRatio: Float
    )

    /** Maps the recent daily window into bars, normalizing bar height and dot height to 1.0. */
    fun fromSummary(summary: PredictionMetricsStore.Summary): List<Bar> {
        val recent = summary.recent
        if (recent.isEmpty()) return emptyList()
        val maxValue = recent.maxOf { it.learnedWords + it.learnedSentences }.coerceAtLeast(1)
        val maxAccepted = recent.maxOf { it.accepted }.coerceAtLeast(1)
        return recent.map { day ->
            val value = day.learnedWords + day.learnedSentences
            Bar(
                day = day.day,
                value = value,
                heightRatio = value.toFloat() / maxValue,
                accepted = day.accepted,
                acceptedRatio = day.accepted.toFloat() / maxAccepted
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
 * one bar per recent day (learned words + sentences), with a dot marking that day's
 * accepted-candidate volume, and first/last day labels.
 */
class VaultTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bars: List<VaultTimelineData.Bar> = emptyList()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
        if (list.isEmpty()) return

        val paddingL = paddingLeft + dp(2f)
        val paddingR = width - paddingRight - dp(2f)
        val availableWidth = paddingR - paddingL
        if (availableWidth <= 0) return

        val chartTop = paddingTop.toFloat()
        val chartBottom = chartTop + dp(CHART_HEIGHT_DP)
        val chartHeight = chartBottom - chartTop

        val primaryColor = resolveThemeColor(android.R.attr.colorPrimary)
        val onSurfaceColor = resolveThemeColor(android.R.attr.textColorPrimary)

        trackPaint.color = ColorUtils.setAlphaComponent(primaryColor, TRACK_ALPHA)
        barPaint.color = primaryColor
        dotPaint.color = onSurfaceColor
        labelPaint.color = ColorUtils.setAlphaComponent(onSurfaceColor, LABEL_ALPHA)
        labelPaint.textSize = dp(10f)

        val slot = availableWidth / list.size
        val barWidth = (slot * 0.6f).coerceAtLeast(dp(1.5f))
        val gap = (slot - barWidth) / 2f

        list.forEachIndexed { index, bar ->
            val slotStart = paddingL + slot * index
            val left = slotStart + gap
            val right = left + barWidth
            val cornerRadius = barWidth / 2f

            scratchRect.set(left, chartTop, right, chartBottom)
            canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, trackPaint)

            if (bar.heightRatio > 0f) {
                val barTop = chartBottom - chartHeight * bar.heightRatio
                scratchRect.set(left, barTop, right, chartBottom)
                canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, barPaint)
            }

            if (bar.accepted > 0) {
                val dotY = (chartBottom - chartHeight * bar.acceptedRatio - dp(3f))
                    .coerceIn(chartTop, chartBottom)
                canvas.drawCircle((left + right) / 2f, dotY, dp(2.5f), dotPaint)
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
        val total = bars.sumOf { it.value }
        return "최근 ${bars.size}일 학습 성장 타임라인. 이 기간 총 학습량 ${total}."
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
        private const val TRACK_ALPHA = 40
        private const val LABEL_ALPHA = 160
    }
}
