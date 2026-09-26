/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.provider.Settings
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.TypingDnaStats
import org.fcitx.fcitx5.android.input.ai.persona.PersonaRegistry
import splitties.dimensions.dp
import kotlin.math.min

/**
 * Ultra-lightweight, 0-dependency native canvas chart view for Typing DNA.
 * Visualizes accumulation bars, tone & category distributions, top bigram
 * transitions, and the on-device privacy gauge with 120Hz-friendly animation.
 */
class TypingDnaChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * One drawable block of the non-compact chart. [setVisibleSections] lets a caller (e.g. the "내
     * 말투 리포트" screen) show only the section(s) it needs from this same view instead of
     * reimplementing the drawing; the developer-info dashboard keeps the default (every section).
     */
    enum class Section { ACCUMULATION, TONE_BALANCE, CATEGORY_DISTRIBUTION, TOP_TRANSITIONS, PRIVACY_GAUGE }

    private var stats: TypingDnaStats? = null
    private var compact: Boolean = false
    private var visibleSections: Set<Section> = Section.entries.toSet()
    private var animationProgress: Float = 1.0f
    private var progressAnimator: ValueAnimator? = null

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gaugePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val scratchRect = RectF()
    private val gaugeRect = RectF()

    private val primaryBlue = Color.parseColor("#3B82F6")
    private val emeraldGreen = Color.parseColor("#10B981")
    private val amberOrange = Color.parseColor("#F59E0B")
    private val purpleViolet = Color.parseColor("#8B5CF6")
    private val tealCyan = Color.parseColor("#06B6D4")
    private val slateGray = Color.parseColor("#64748B")
    private val barBackgroundLight = Color.parseColor("#1A888888")

    // One color per PersonaRegistry.all slot (8 personas), reused by index so a given persona
    // keeps the same color across renders regardless of which categories are active.
    private val categoryPalette = intArrayOf(
        tealCyan,
        primaryBlue,
        amberOrange,
        Color.parseColor("#EC4899"),
        purpleViolet,
        emeraldGreen,
        Color.parseColor("#F97316"),
        slateGray
    )

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        textPaint.isFakeBoldText = true
    }

    fun setCompact(value: Boolean) {
        if (compact == value) return
        compact = value
        requestLayout()
        invalidate()
    }

    /** Which non-compact sections to draw; ignored in compact mode. Defaults to every section. */
    fun setVisibleSections(sections: Set<Section>) {
        if (visibleSections == sections) return
        visibleSections = sections
        requestLayout()
        invalidate()
    }

    fun setStats(newStats: TypingDnaStats, animate: Boolean = true) {
        this.stats = newStats
        contentDescription = buildContentDescription(newStats)
        requestLayout()
        val canAnimate = animate && shouldAnimate()
        progressAnimator?.cancel()
        if (canAnimate) {
            progressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 720L
                interpolator = DecelerateInterpolator(1.8f)
                addUpdateListener {
                    animationProgress = it.animatedValue as Float
                    postInvalidateOnAnimation()
                }
                start()
            }
        } else {
            animationProgress = 1.0f
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        progressAnimator?.cancel()
        progressAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val baseHeight = if (compact) dp(118) else dp(16) + visibleSections.sumOf { sectionHeight(it, stats) }
        setMeasuredDimension(width, resolveSize(baseHeight, heightMeasureSpec))
    }

    /** Approximate drawn height of one non-compact [section], mirroring [onDraw]'s own increments. */
    private fun sectionHeight(section: Section, s: TypingDnaStats?): Int = when (section) {
        Section.ACCUMULATION -> dp(20) + dp(36) * 4 + dp(10)
        Section.TONE_BALANCE -> dp(18) + dp(48)
        Section.CATEGORY_DISTRIBUTION -> {
            val activeCategoryCount = s?.categoryCounts?.values?.count { it > 0 } ?: 0
            val categoryLegendRows = if (activeCategoryCount <= 0) 1 else (activeCategoryCount + 2) / 3
            dp(18) + dp(50) + (categoryLegendRows - 1).coerceAtLeast(0) * dp(16)
        }
        Section.TOP_TRANSITIONS -> {
            val topBigramsCount = s?.topBigrams?.take(4)?.size ?: 0
            (if (topBigramsCount > 0) dp(22) + topBigramsCount * dp(30) else dp(28)) + dp(8)
        }
        Section.PRIVACY_GAUGE -> dp(12) + dp(96) + dp(8)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = stats ?: return

        val paddingL = paddingLeft + dp(16f)
        val paddingR = width - paddingRight - dp(16f)
        val availableWidth = paddingR - paddingL
        if (availableWidth <= 0) return

        textPaint.textSize = dp(13f)
        textPaint.color = getThemedTextColor()

        var currentY = paddingTop + dp(16f)

        if (compact) {
            canvas.drawText("학습 축적 미니 그래프", paddingL, currentY, textPaint)
            currentY += dp(18f)
            val maxVal = maxOf(s.totalSentences, s.bigramsCount, s.endingsCount, s.phrasesCount, 8).toFloat()
            drawMetricBar(canvas, "문장", s.totalSentences, maxVal, primaryBlue, paddingL, currentY, availableWidth, compact = true)
            currentY += dp(22f)
            drawMetricBar(canvas, "단어쌍", s.bigramsCount, maxVal, emeraldGreen, paddingL, currentY, availableWidth, compact = true)
            currentY += dp(22f)
            drawMetricBar(canvas, "어미", s.endingsCount, maxVal, amberOrange, paddingL, currentY, availableWidth, compact = true)
            currentY += dp(22f)
            drawMetricBar(canvas, "상용구", s.phrasesCount, maxVal, purpleViolet, paddingL, currentY, availableWidth, compact = true)
            return
        }

        if (Section.ACCUMULATION in visibleSections) {
            canvas.drawText(context.getString(R.string.typing_dna_chart_accumulation_title), paddingL, currentY, textPaint)
            currentY += dp(20f)

            val maxVal = maxOf(s.totalSentences, s.bigramsCount, s.endingsCount, s.phrasesCount, 15).toFloat()
            drawMetricBar(canvas, context.getString(R.string.typing_dna_chart_label_sentences), s.totalSentences, maxVal, primaryBlue, paddingL, currentY, availableWidth)
            currentY += dp(36f)
            drawMetricBar(canvas, context.getString(R.string.typing_dna_chart_label_word_pairs), s.bigramsCount, maxVal, emeraldGreen, paddingL, currentY, availableWidth)
            currentY += dp(36f)
            drawMetricBar(canvas, context.getString(R.string.typing_dna_chart_label_endings), s.endingsCount, maxVal, amberOrange, paddingL, currentY, availableWidth)
            currentY += dp(36f)
            drawMetricBar(canvas, context.getString(R.string.typing_dna_chart_label_phrases), s.phrasesCount, maxVal, purpleViolet, paddingL, currentY, availableWidth)
            currentY += dp(10f)
        }

        if (Section.TONE_BALANCE in visibleSections) {
            canvas.drawText(context.getString(R.string.typing_dna_chart_tone_title), paddingL, currentY, textPaint)
            currentY += dp(18f)
            drawToneSplitBar(canvas, s.honorificRatio, s.informalRatio, paddingL, currentY, availableWidth)
            currentY += dp(48f)
        }

        if (Section.CATEGORY_DISTRIBUTION in visibleSections) {
            canvas.drawText(context.getString(R.string.typing_dna_chart_category_title), paddingL, currentY, textPaint)
            currentY += dp(18f)
            val categoryLegendRows = drawCategorySplitBar(
                canvas,
                s.categoryCounts,
                paddingL,
                currentY,
                availableWidth
            )
            currentY += dp(50f) + (categoryLegendRows - 1).coerceAtLeast(0) * dp(16f)
        }

        if (Section.TOP_TRANSITIONS in visibleSections) {
            val topList = s.topBigrams.take(4)
            if (topList.isNotEmpty()) {
                canvas.drawText(context.getString(R.string.typing_dna_chart_transitions_title), paddingL, currentY, textPaint)
                currentY += dp(22f)
                topList.forEach { bg ->
                    drawBigramRow(canvas, "${bg.prev} → ${bg.next}", bg.weight, paddingL, currentY, availableWidth)
                    currentY += dp(30f)
                }
            } else {
                subTextPaint.textSize = dp(11f)
                subTextPaint.color = getThemedSubTextColor()
                canvas.drawText(context.getString(R.string.typing_dna_chart_transitions_empty), paddingL, currentY, subTextPaint)
                currentY += dp(28f)
            }
            currentY += dp(8f)
        }

        if (Section.PRIVACY_GAUGE in visibleSections) {
            canvas.drawText(context.getString(R.string.typing_dna_chart_privacy_title), paddingL, currentY, textPaint)
            currentY += dp(12f)
            drawPrivacyGauge(canvas, s.privacyOnDevicePercent, s.cloudBytesExported, paddingL, currentY, availableWidth)
        }
    }

    private fun drawMetricBar(
        canvas: Canvas,
        label: String,
        value: Int,
        max: Float,
        barColor: Int,
        x: Float,
        y: Float,
        totalWidth: Float,
        compact: Boolean = false
    ) {
        val labelWidth = if (compact) dp(52f) else dp(110f)
        val valueSlot = if (compact) dp(36f) else dp(44f)
        val barStartX = x + labelWidth
        val barWidth = (totalWidth - labelWidth - valueSlot).coerceAtLeast(dp(24f))
        val barHeight = if (compact) dp(8f) else dp(10f)
        val cornerRadius = barHeight / 2f

        subTextPaint.textSize = if (compact) dp(11f) else dp(12f)
        subTextPaint.color = getThemedSubTextColor()
        canvas.drawText(label, x, y + dp(8f), subTextPaint)

        bgPaint.color = barBackgroundLight
        scratchRect.set(barStartX, y, barStartX + barWidth, y + barHeight)
        canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, bgPaint)

        if (value > 0 && max > 0f) {
            val ratio = (value / max).coerceIn(0.04f, 1.0f) * animationProgress
            barPaint.color = barColor
            scratchRect.set(barStartX, y, barStartX + barWidth * ratio, y + barHeight)
            canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, barPaint)
        }

        val animatedValue = (value * animationProgress).toInt()
        val valStr = if (compact) "$animatedValue" else "${animatedValue}개"
        subTextPaint.isFakeBoldText = true
        canvas.drawText(valStr, barStartX + barWidth + dp(8f), y + dp(8.5f), subTextPaint)
        subTextPaint.isFakeBoldText = false
    }

    private fun drawToneSplitBar(
        canvas: Canvas,
        honorificRatio: Float,
        informalRatio: Float,
        x: Float,
        y: Float,
        totalWidth: Float
    ) {
        val barHeight = dp(12f)
        val cornerRadius = dp(6f)
        val total = honorificRatio + informalRatio

        bgPaint.color = barBackgroundLight
        scratchRect.set(x, y, x + totalWidth, y + barHeight)
        canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, bgPaint)

        if (total < 0.001f) {
            subTextPaint.textSize = dp(11f)
            subTextPaint.color = getThemedSubTextColor()
            canvas.drawText(context.getString(R.string.typing_dna_chart_tone_empty), x, y + barHeight + dp(14f), subTextPaint)
            return
        }

        val hRatio = (honorificRatio / total * animationProgress).coerceIn(0f, 1f)
        val splitWidth = totalWidth * hRatio

        if (splitWidth > 0f) {
            barPaint.color = emeraldGreen
            scratchRect.set(x, y, x + splitWidth, y + barHeight)
            canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, barPaint)
        }
        if (splitWidth < totalWidth) {
            barPaint.color = purpleViolet
            val rightLeft = (x + splitWidth + dp(2f)).coerceAtMost(x + totalWidth)
            scratchRect.set(rightLeft, y, x + totalWidth, y + barHeight)
            canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, barPaint)
        }

        val legendY = y + barHeight + dp(14f)
        val hPercent = (honorificRatio * 100).toInt()
        val iPercent = (informalRatio * 100).toInt()
        subTextPaint.textSize = dp(11f)
        subTextPaint.color = emeraldGreen
        canvas.drawText(context.getString(R.string.typing_dna_chart_tone_honorific_legend, hPercent), x, legendY, subTextPaint)
        subTextPaint.color = purpleViolet
        val rightLegend = context.getString(R.string.typing_dna_chart_tone_informal_legend, iPercent)
        canvas.drawText(rightLegend, x + totalWidth - subTextPaint.measureText(rightLegend), legendY, subTextPaint)
    }

    /**
     * Draws an N-segment split bar (one segment per active [PersonaRegistry] persona, in
     * registry order, colored from [categoryPalette] by registry index so a persona's color is
     * stable across renders) plus a wrapped legend below it. Returns the number of legend rows
     * drawn so the caller can reserve enough vertical space.
     */
    private fun drawCategorySplitBar(
        canvas: Canvas,
        categoryCounts: Map<String, Int>,
        x: Float,
        y: Float,
        totalWidth: Float
    ): Int {
        val barHeight = dp(12f)
        val cornerRadius = dp(6f)

        bgPaint.color = barBackgroundLight
        scratchRect.set(x, y, x + totalWidth, y + barHeight)
        canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, bgPaint)

        val entries = PersonaRegistry.all.mapIndexedNotNull { index, persona ->
            val count = categoryCounts[persona.id] ?: 0
            if (count > 0) Triple(persona, count, categoryPalette[index % categoryPalette.size]) else null
        }
        val total = entries.sumOf { it.second }

        if (entries.isEmpty() || total <= 0) {
            subTextPaint.textSize = dp(11f)
            subTextPaint.color = getThemedSubTextColor()
            canvas.drawText(
                context.getString(R.string.vault_category_chart_empty_hint),
                x, y + barHeight + dp(14f), subTextPaint
            )
            return 1
        }

        var segmentStart = x
        entries.forEach { (_, count, color) ->
            val segmentWidth = totalWidth * (count.toFloat() / total) * animationProgress
            barPaint.color = color
            val segmentEnd = (segmentStart + segmentWidth).coerceAtMost(x + totalWidth)
            scratchRect.set(segmentStart, y, segmentEnd, y + barHeight)
            canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, barPaint)
            segmentStart = (segmentEnd + dp(2f)).coerceAtMost(x + totalWidth)
        }

        subTextPaint.textSize = dp(11f)
        val columns = 3
        val columnWidth = totalWidth / columns
        entries.forEachIndexed { i, (persona, count, color) ->
            val row = i / columns
            val col = i % columns
            val percent = (count * 100f / total).toInt()
            val legendX = x + col * columnWidth
            val legendY = y + barHeight + dp(14f) + row * dp(16f)
            subTextPaint.color = color
            canvas.drawText("● ${context.getString(persona.labelRes)} $percent%", legendX, legendY, subTextPaint)
        }

        return (entries.size + columns - 1) / columns
    }

    private fun drawBigramRow(
        canvas: Canvas,
        pairText: String,
        weight: Float,
        x: Float,
        y: Float,
        totalWidth: Float
    ) {
        val pairWidth = dp(140f)
        val barStartX = x + pairWidth
        val barWidth = (totalWidth - pairWidth).coerceAtLeast(dp(20f))
        val barHeight = dp(8f)
        val cornerRadius = dp(4f)

        subTextPaint.textSize = dp(12f)
        subTextPaint.color = getThemedSubTextColor()
        val truncated = android.text.TextUtils.ellipsize(
            pairText,
            android.text.TextPaint(subTextPaint),
            pairWidth - dp(8f),
            android.text.TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(truncated, x, y + dp(7f), subTextPaint)

        bgPaint.color = barBackgroundLight
        scratchRect.set(barStartX, y, barStartX + barWidth, y + barHeight)
        canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, bgPaint)

        barPaint.color = primaryBlue
        scratchRect.set(
            barStartX,
            y,
            barStartX + barWidth * weight.coerceIn(0.1f, 1.0f) * animationProgress,
            y + barHeight
        )
        canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, barPaint)
    }

    private fun drawPrivacyGauge(
        canvas: Canvas,
        privacyPercent: Int,
        cloudBytes: Int,
        x: Float,
        y: Float,
        totalWidth: Float
    ) {
        val size = min(dp(96f), totalWidth * 0.32f)
        val stroke = dp(10f)
        val cx = x + size / 2f
        val cy = y + size / 2f
        gaugeRect.set(cx - size / 2f + stroke, cy - size / 2f + stroke, cx + size / 2f - stroke, cy + size / 2f - stroke)

        gaugePaint.strokeWidth = stroke
        gaugePaint.color = barBackgroundLight
        canvas.drawArc(gaugeRect, 135f, 270f, false, gaugePaint)

        gaugePaint.color = emeraldGreen
        canvas.drawArc(gaugeRect, 135f, 270f * (privacyPercent / 100f) * animationProgress, false, gaugePaint)

        textPaint.textSize = dp(16f)
        textPaint.color = getThemedTextColor()
        val pct = "${(privacyPercent * animationProgress).toInt()}%"
        val pctWidth = textPaint.measureText(pct)
        canvas.drawText(pct, cx - pctWidth / 2f, cy + dp(6f), textPaint)

        val textX = x + size + dp(16f)
        subTextPaint.textSize = dp(12f)
        subTextPaint.color = emeraldGreen
        canvas.drawText(context.getString(R.string.typing_dna_chart_privacy_zero_knowledge, privacyPercent), textX, y + dp(28f), subTextPaint)
        subTextPaint.color = getThemedSubTextColor()
        canvas.drawText(context.getString(R.string.typing_dna_chart_privacy_cloud, cloudBytes), textX, y + dp(48f), subTextPaint)
        canvas.drawText(context.getString(R.string.typing_dna_chart_privacy_discard), textX, y + dp(66f), subTextPaint)
    }

    private fun buildContentDescription(s: TypingDnaStats): String {
        return buildString {
            append(context.getString(R.string.typing_dna_chart_accessibility_header))
            if (Section.ACCUMULATION in visibleSections) {
                append(" ")
                append(
                    context.getString(
                        R.string.typing_dna_chart_accessibility_accumulation,
                        s.totalSentences, s.bigramsCount, s.endingsCount, s.phrasesCount
                    )
                )
            }
            if (Section.PRIVACY_GAUGE in visibleSections) {
                append(" ")
                append(context.getString(R.string.typing_dna_chart_accessibility_privacy, s.privacyOnDevicePercent))
            }
        }
    }

    private fun shouldAnimate(): Boolean {
        return try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) != 0f
        } catch (_: Exception) {
            true
        }
    }

    private fun getThemedTextColor(): Int {
        val nightMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val fallback = if (nightMode) Color.parseColor("#F1F5F9") else Color.parseColor("#0F172A")
        return resolveThemeColor(android.R.attr.textColorPrimary, fallback)
    }

    private fun getThemedSubTextColor(): Int {
        val nightMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (nightMode) Color.parseColor("#94A3B8") else Color.parseColor("#475569")
    }

    private fun resolveThemeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        if (!context.theme.resolveAttribute(attr, tv, true)) return fallback
        return when {
            tv.resourceId != 0 -> runCatching { context.getColor(tv.resourceId) }.getOrDefault(fallback)
            tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT -> tv.data
            else -> fallback
        }
    }
}
