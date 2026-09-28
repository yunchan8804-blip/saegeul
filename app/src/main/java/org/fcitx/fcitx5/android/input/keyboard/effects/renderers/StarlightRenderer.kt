/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects.renderers

import android.graphics.Canvas
import android.graphics.Color
import org.fcitx.fcitx5.android.input.keyboard.effects.ChromaEffectRenderer
import org.fcitx.fcitx5.android.input.keyboard.effects.ChromaPaints
import org.fcitx.fcitx5.android.input.keyboard.effects.EffectFrame
import org.fcitx.fcitx5.android.input.keyboard.effects.StarPainter
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * ⭐ Starlight Twinkle: Deep midnight sky with glittering constellation stars.
 */
internal class StarlightRenderer(paints: ChromaPaints) : ChromaEffectRenderer {

    private val fillPaint = paints.fillPaint
    private val stars = StarPainter(fillPaint, crossInnerRatio = 0.2f)

    // Starlight fixed star points
    private class StarPoint(
        val xRatio: Float,
        val yRatio: Float,
        val size: Float,
        val phaseOffset: Float,
        val color: Int,
        val isCross: Boolean
    )

    private val starPoints = List(42) { index ->
        val rand = Random(index * 1337 + 42)
        StarPoint(
            xRatio = rand.nextFloat(),
            yRatio = rand.nextFloat(),
            size = rand.nextFloat() * 3.5f + 1.8f,
            phaseOffset = rand.nextFloat() * (PI * 2).toFloat(),
            color = when (index % 5) {
                0 -> 0xFFFFFFFF.toInt()
                1 -> 0xFF67E8F9.toInt()
                2 -> 0xFFFDE047.toInt()
                3 -> 0xFFF472B6.toInt()
                else -> 0xFFA78BFA.toInt()
            },
            isCross = index % 3 == 0
        )
    }

    override fun draw(canvas: Canvas, frame: EffectFrame) {
        val w = frame.width
        val h = frame.height
        val phase = frame.phase
        val intensity = frame.intensity
        fillPaint.shader = null
        fillPaint.color = Color.parseColor("#040716")
        fillPaint.alpha = (intensity * 0.65f).toInt()
        canvas.drawRect(0f, 0f, w, h, fillPaint)

        val density = frame.density
        for (star in starPoints) {
            val starX = star.xRatio * w
            val starY = star.yRatio * h
            val twinkle = ((sin(phase * PI * 4.0 + star.phaseOffset) + 1.0) / 2.0).toFloat()
            val starAlpha = (twinkle * intensity).toInt().coerceIn(0, 255)
            if (starAlpha <= 0) continue

            fillPaint.color = star.color
            fillPaint.alpha = starAlpha
            val curRadius = star.size * (0.6f + twinkle * 0.65f) * density

            if (star.isCross) {
                stars.drawCrossStar(canvas, starX, starY, curRadius, phase * 180f + star.phaseOffset)
            } else {
                stars.drawStar(canvas, starX, starY, curRadius, phase * 360f + star.phaseOffset)
            }
        }
    }
}
