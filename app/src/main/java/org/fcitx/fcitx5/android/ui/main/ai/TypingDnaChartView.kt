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
import org.fcitx.fcitx5.android.input.ai.TypingDnaStats
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

    private var stats: TypingDnaStats? = null
    private var compact: Boolean = false
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
        val s = stats
        val topBigramsCount = if (compact) 0 else s?.topBigrams?.take(4)?.size ?: 0
        val baseHeight = if (compact) {
            dp(118)
        } else {
            dp(456) + (if (topBigramsCount > 0) dp(28) + (topBigramsCount * dp(32)) else dp(24))
        }
        setMeasuredDimension(width, resolveSize(baseHeight, heightMeasureSpec))
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

        canvas.drawText("데이터 축적 현황 (온디바이스 학습)", paddingL, currentY, textPaint)
        currentY += dp(20f)

        val maxVal = maxOf(s.totalSentences, s.bigramsCount, s.endingsCount, s.phrasesCount, 15).toFloat()
        drawMetricBar(canvas, "분석 문장", s.totalSentences, maxVal, primaryBlue, paddingL, currentY, availableWidth)
        currentY += dp(36f)
        drawMetricBar(canvas, "단어 연어(Bigram)", s.bigramsCount, maxVal, emeraldGreen, paddingL, currentY, availableWidth)
        currentY += dp(36f)
        drawMetricBar(canvas, "종결 어미", s.endingsCount, maxVal, amberOrange, paddingL, currentY, availableWidth)
        currentY += dp(36f)
        drawMetricBar(canvas, "완성 상용구", s.phrasesCount, maxVal, purpleViolet, paddingL, currentY, availableWidth)
        currentY += dp(46f)

        canvas.drawText("문체 및 톤 밸런스 (Tone Balance)", paddingL, currentY, textPaint)
        currentY += dp(18f)
        drawToneSplitBar(canvas, s.honorificRatio, s.informalRatio, paddingL, currentY, availableWidth)
        currentY += dp(48f)

        canvas.drawText("사용 환경별 페르소나 분포 (Category)", paddingL, currentY, textPaint)
        currentY += dp(18f)
        drawCategorySplitBar(
            canvas,
            s.messengerSentencesRatio,
            s.workSentencesRatio,
            s.generalSentencesRatio,
            paddingL,
            currentY,
            availableWidth
        )
        currentY += dp(50f)

        val topList = s.topBigrams.take(4)
        if (topList.isNotEmpty()) {
            canvas.drawText("자주 이어지는 나만의 단어 연결 (Top Transitions)", paddingL, currentY, textPaint)
            currentY += dp(22f)
            topList.forEach { bg ->
                drawBigramRow(canvas, "${bg.prev} → ${bg.next}", bg.weight, paddingL, currentY, availableWidth)
                currentY += dp(30f)
            }
        } else {
            subTextPaint.textSize = dp(11f)
            subTextPaint.color = getThemedSubTextColor()
            canvas.drawText("키보드로 단어를 입력하면 자주 쓰는 단어 연결이 분석됩니다.", paddingL, currentY, subTextPaint)
            currentY += dp(28f)
        }

        currentY += dp(8f)
        canvas.drawText("온디바이스 프라이버시 게이지", paddingL, currentY, textPaint)
        currentY += dp(12f)
        drawPrivacyGauge(canvas, s.privacyOnDevicePercent, s.cloudBytesExported, paddingL, currentY, availableWidth)
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
            canvas.drawText("타이핑이 쌓이면 존댓말/친근체 비중이 나타납니다.", x, y + barHeight + dp(14f), subTextPaint)
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
        canvas.drawText("● 존댓말/격식체 $hPercent%", x, legendY, subTextPaint)
        subTextPaint.color = purpleViolet
        val rightLegend = "● 친근체/반말 $iPercent%"
        canvas.drawText(rightLegend, x + totalWidth - subTextPaint.measureText(rightLegend), legendY, subTextPaint)
    }

    private fun drawCategorySplitBar(
        canvas: Canvas,
        messengerRatio: Float,
        workRatio: Float,
        generalRatio: Float,
        x: Float,
        y: Float,
        totalWidth: Float
    ) {
        val barHeight = dp(12f)
        val cornerRadius = dp(6f)
        val total = messengerRatio + workRatio + generalRatio

        bgPaint.color = barBackgroundLight
        scratchRect.set(x, y, x + totalWidth, y + barHeight)
        canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, bgPaint)

        if (total < 0.001f) {
            subTextPaint.textSize = dp(11f)
            subTextPaint.color = getThemedSubTextColor()
            canvas.drawText("메신저·업무·일반 환경별 말투가 여기에 나뉩니다.", x, y + barHeight + dp(14f), subTextPaint)
            return
        }

        val mW = totalWidth * (messengerRatio / total) * animationProgress
        val wW = totalWidth * (workRatio / total) * animationProgress

        barPaint.color = tealCyan
        scratchRect.set(x, y, x + mW, y + barHeight)
        canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, barPaint)

        barPaint.color = primaryBlue
        val wLeft = (x + mW + dp(2f)).coerceAtMost(x + totalWidth)
        val wRight = (wLeft + wW).coerceAtMost(x + totalWidth)
        scratchRect.set(wLeft, y, wRight, y + barHeight)
        canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, barPaint)

        barPaint.color = slateGray
        val gLeft = (wRight + dp(2f)).coerceAtMost(x + totalWidth)
        scratchRect.set(gLeft, y, x + totalWidth, y + barHeight)
        canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, barPaint)

        val legendY = y + barHeight + dp(14f)
        val mPercent = (messengerRatio * 100).toInt()
        val wPercent = (workRatio * 100).toInt()
        val gPercent = (generalRatio * 100).toInt()
        subTextPaint.textSize = dp(11f)
        subTextPaint.color = tealCyan
        canvas.drawText("● 메신저 $mPercent%", x, legendY, subTextPaint)
        subTextPaint.color = primaryBlue
        canvas.drawText("● 업무 $wPercent%", x + (totalWidth / 3f), legendY, subTextPaint)
        subTextPaint.color = slateGray
        val rightLegend = "● 일반 $gPercent%"
        canvas.drawText(rightLegend, x + totalWidth - subTextPaint.measureText(rightLegend), legendY, subTextPaint)
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
        canvas.drawText("Zero-Knowledge · 온디바이스 ${privacyPercent}%", textX, y + dp(28f), subTextPaint)
        subTextPaint.color = getThemedSubTextColor()
        canvas.drawText("클라우드 전송 ${cloudBytes}B · PII 스크러빙 적용", textX, y + dp(48f), subTextPaint)
        canvas.drawText("원본 문장은 학습 직후 기기에서 영구 파기됩니다.", textX, y + dp(66f), subTextPaint)
    }

    private fun buildContentDescription(s: TypingDnaStats): String {
        return buildString {
            append("AI 언어 지문 차트. 레벨 ${s.level} ${s.levelTitle}. ")
            append("분석 문장 ${s.totalSentences}개, 단어쌍 ${s.bigramsCount}개, ")
            append("종결어미 ${s.endingsCount}개, 상용구 ${s.phrasesCount}개. ")
            append("온디바이스 개인정보 보호 ${s.privacyOnDevicePercent}퍼센트.")
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
