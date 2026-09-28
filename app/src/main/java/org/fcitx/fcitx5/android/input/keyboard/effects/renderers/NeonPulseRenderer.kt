/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects.renderers

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import org.fcitx.fcitx5.android.input.keyboard.effects.ChromaEffectRenderer
import org.fcitx.fcitx5.android.input.keyboard.effects.ChromaPaints
import org.fcitx.fcitx5.android.input.keyboard.effects.EffectFrame
import kotlin.math.min

/**
 * 💫 Neon Pulse: High-luminance horizontal scanning laser line.
 */
internal class NeonPulseRenderer(paints: ChromaPaints) : ChromaEffectRenderer {

    private val fillPaint = paints.fillPaint

    override fun draw(canvas: Canvas, frame: EffectFrame) {
        val w = frame.width
        val h = frame.height
        val phase = frame.phase
        val palette = frame.palette
        val pulseX = if (frame.direction == "right_to_left") (1f - phase) * w else phase * w
        val beamWidth = w * 0.38f
        val color1 = palette[0]
        val color2 = palette[min(1, palette.size - 1)]

        val shader = LinearGradient(
            pulseX - beamWidth, 0f, pulseX + beamWidth, 0f,
            intArrayOf(Color.TRANSPARENT, color1, color2, Color.WHITE, color2, color1, Color.TRANSPARENT),
            NEON_PULSE_STOPS,
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = shader
        fillPaint.alpha = frame.intensity
        canvas.drawRect(0f, 0f, w, h, fillPaint)
    }

    companion object {
        val NEON_PULSE_STOPS: FloatArray = floatArrayOf(0f, 0.22f, 0.44f, 0.5f, 0.56f, 0.78f, 1f)
    }
}
