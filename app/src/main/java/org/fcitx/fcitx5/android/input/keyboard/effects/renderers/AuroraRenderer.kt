/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects.renderers

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Shader
import org.fcitx.fcitx5.android.input.keyboard.effects.ChromaEffectRenderer
import org.fcitx.fcitx5.android.input.keyboard.effects.ChromaPaints
import org.fcitx.fcitx5.android.input.keyboard.effects.EffectFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 🌌 Aurora Borealis: Organic flowing wave of emerald and royal violet curtains.
 */
internal class AuroraRenderer(paints: ChromaPaints) : ChromaEffectRenderer {

    private val fillPaint = paints.fillPaint

    override fun draw(canvas: Canvas, frame: EffectFrame) {
        val w = frame.width
        val h = frame.height
        val phase = frame.phase
        val waveOffset1 = sin(phase * PI * 2.0).toFloat() * w * 0.28f
        val waveOffset2 = cos(phase * PI * 2.0).toFloat() * h * 0.35f

        val shader = LinearGradient(
            w * 0.15f + waveOffset1, 0f,
            w * 0.85f - waveOffset1, h + waveOffset2,
            frame.palette,
            null,
            Shader.TileMode.MIRROR
        )
        fillPaint.shader = shader
        fillPaint.alpha = frame.intensity
        canvas.drawRect(0f, 0f, w, h, fillPaint)
    }
}
