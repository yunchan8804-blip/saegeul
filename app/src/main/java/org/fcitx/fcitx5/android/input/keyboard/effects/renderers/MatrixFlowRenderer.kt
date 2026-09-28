/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects.renderers

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import org.fcitx.fcitx5.android.input.keyboard.effects.ChromaEffectRenderer
import org.fcitx.fcitx5.android.input.keyboard.effects.ChromaPaints
import org.fcitx.fcitx5.android.input.keyboard.effects.EffectFrame
import kotlin.math.max
import kotlin.random.Random

/**
 * 🟢 Matrix Flow: Vertical cyber code rain droplets and glowing streams.
 */
internal class MatrixFlowRenderer(paints: ChromaPaints) : ChromaEffectRenderer {

    private val fillPaint = paints.fillPaint
    private val strokePaint = paints.strokePaint

    // Matrix Rain column state
    private class MatrixColumn(
        var xRatio: Float,
        var yProgress: Float,
        var speed: Float,
        var length: Float,
        var glitchOffset: Float
    )

    private val matrixColumns = List(32) { index ->
        val rand = Random(index * 997 + 17)
        MatrixColumn(
            xRatio = (index + 0.5f) / 32f,
            yProgress = rand.nextFloat(),
            speed = rand.nextFloat() * 0.45f + 0.35f,
            length = rand.nextFloat() * 0.4f + 0.25f,
            glitchOffset = rand.nextFloat() * 10f
        )
    }

    override fun step(frame: EffectFrame) {
        // Advance Matrix rain columns
        for (col in matrixColumns) {
            col.yProgress = (col.yProgress + frame.deltaSeconds * col.speed * frame.speed * 0.6f) % 1.25f
        }
    }

    override fun draw(canvas: Canvas, frame: EffectFrame) {
        val w = frame.width
        val h = frame.height
        val intensity = frame.intensity
        fillPaint.shader = null
        fillPaint.color = Color.parseColor("#021208")
        fillPaint.alpha = (intensity * 0.55f).toInt()
        canvas.drawRect(0f, 0f, w, h, fillPaint)

        strokePaint.strokeCap = Paint.Cap.ROUND
        val density = frame.density

        for (col in matrixColumns) {
            val cx = col.xRatio * w
            val headY = col.yProgress * h
            val tailY = max(0f, headY - col.length * h)

            val grad = LinearGradient(
                cx, tailY, cx, headY,
                MATRIX_GRADIENT_COLORS,
                MATRIX_GRADIENT_STOPS,
                Shader.TileMode.CLAMP
            )
            strokePaint.shader = grad
            strokePaint.strokeWidth = 3.2f * density
            strokePaint.alpha = intensity
            canvas.drawLine(cx, tailY, cx, headY, strokePaint)

            // White glow drop head
            fillPaint.shader = null
            fillPaint.color = Color.WHITE
            fillPaint.alpha = intensity
            canvas.drawCircle(cx, headY, 2.2f * density, fillPaint)
        }
    }

    companion object {
        val MATRIX_GRADIENT_STOPS: FloatArray = floatArrayOf(0f, 0.6f, 0.9f, 1f)
        val MATRIX_GRADIENT_COLORS: IntArray = intArrayOf(
            Color.TRANSPARENT,
            0xFF00CC55.toInt(),
            0xFF00FF88.toInt(),
            Color.WHITE
        )
    }
}
