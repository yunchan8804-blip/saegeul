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
import kotlin.math.sin

/**
 * 🌊 Ocean Tide: Deep abyss wave surging with emerald tide and white surf.
 */
internal class OceanTideRenderer(paints: ChromaPaints) : ChromaEffectRenderer {

    private val fillPaint = paints.fillPaint

    override fun draw(canvas: Canvas, frame: EffectFrame) {
        val w = frame.width
        val h = frame.height
        val tideProgress = sin(frame.phase * PI * 2.0).toFloat() * 0.35f + 0.5f
        val shader = LinearGradient(
            0f, h * (1f - tideProgress),
            w, h * tideProgress,
            frame.palette,
            null,
            Shader.TileMode.MIRROR
        )
        fillPaint.shader = shader
        fillPaint.alpha = frame.intensity
        canvas.drawRect(0f, 0f, w, h, fillPaint)
    }
}
