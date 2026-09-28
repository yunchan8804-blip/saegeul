/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects.renderers

import android.graphics.Canvas
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.input.keyboard.effects.ChromaEffectRenderer
import org.fcitx.fcitx5.android.input.keyboard.effects.ChromaPaints
import org.fcitx.fcitx5.android.input.keyboard.effects.EffectFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/**
 * 💓 Breathing: Luxury S-curve breath flow with 100% smooth color interpolation and dual radial ambiance.
 */
internal class BreathingRenderer(paints: ChromaPaints) : ChromaEffectRenderer {

    private val fillPaint = paints.fillPaint

    override fun draw(canvas: Canvas, frame: EffectFrame) {
        val w = frame.width
        val h = frame.height
        val phase = frame.phase
        val palette = frame.palette
        // Smooth Cosine S-Curve breath envelope: [0.18 .. 1.0]
        val breathEnvelope = ((1.0 - cos(phase * PI * 2.0)) / 2.0).toFloat()
        val currentAlpha = ((0.18f + breathEnvelope * 0.82f) * frame.intensity).toInt().coerceIn(10, 255)

        // Seamless interpolated color transition between palette points
        val floatIndex = phase * (palette.size - 1)
        val idx1 = floatIndex.toInt() % palette.size
        val idx2 = (idx1 + 1) % palette.size
        val fraction = (floatIndex - idx1.toFloat()).coerceIn(0f, 1f)
        val blendedColor = ColorUtils.blendARGB(palette[idx1], palette[idx2], fraction)

        // Center primary radiant glow
        val cx = w / 2f
        val cy = h / 2f
        val radius = max(w, h) * 0.8f

        val shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                ColorUtils.setAlphaComponent(blendedColor, currentAlpha),
                ColorUtils.setAlphaComponent(blendedColor, (currentAlpha * 0.6f).toInt()),
                ColorUtils.setAlphaComponent(blendedColor, 0)
            ),
            BREATHING_STOPS,
            Shader.TileMode.CLAMP
        )

        fillPaint.shader = shader
        fillPaint.alpha = 255
        canvas.drawRect(0f, 0f, w, h, fillPaint)
    }

    companion object {
        val BREATHING_STOPS: FloatArray = floatArrayOf(0f, 0.5f, 1f)
    }
}
