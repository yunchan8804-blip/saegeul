/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects.renderers

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import org.fcitx.fcitx5.android.input.keyboard.effects.ChromaEffectRenderer
import org.fcitx.fcitx5.android.input.keyboard.effects.ChromaPaints
import org.fcitx.fcitx5.android.input.keyboard.effects.EffectFrame

/**
 * 🌈 Rainbow Wave: 360-degree seamless spectrum flow with multi-direction & ambient bloom support.
 */
internal class RainbowWaveRenderer(paints: ChromaPaints) : ChromaEffectRenderer {

    private val fillPaint = paints.fillPaint
    private val shaderMatrix = paints.shaderMatrix

    override fun draw(canvas: Canvas, frame: EffectFrame) {
        val w = frame.width
        val h = frame.height
        val phase = frame.phase
        val palette = frame.palette
        val shader = when (frame.direction) {
            "right_to_left" -> {
                val shift = (1f - phase) * w
                LinearGradient(shift - w, 0f, shift + w, 0f, palette, null, Shader.TileMode.REPEAT)
            }
            "top_to_bottom" -> {
                val shift = phase * h
                LinearGradient(0f, shift - h, 0f, shift + h, palette, null, Shader.TileMode.REPEAT)
            }
            "bottom_to_top" -> {
                val shift = (1f - phase) * h
                LinearGradient(0f, shift - h, 0f, shift + h, palette, null, Shader.TileMode.REPEAT)
            }
            "diagonal" -> {
                val len = w + h
                val shift = phase * len
                LinearGradient(shift - len, 0f, shift + len, h, palette, null, Shader.TileMode.REPEAT)
            }
            "radial" -> {
                val cx = w / 2f
                val cy = h / 2f
                val sweep = SweepGradient(cx, cy, palette, null)
                shaderMatrix.reset()
                shaderMatrix.postRotate(phase * 360f, cx, cy)
                sweep.setLocalMatrix(shaderMatrix)
                sweep
            }
            else -> { // "left_to_right"
                val shift = phase * w
                LinearGradient(shift - w, 0f, shift + w, 0f, palette, null, Shader.TileMode.REPEAT)
            }
        }

        fillPaint.shader = shader
        fillPaint.alpha = frame.intensity
        canvas.drawRect(0f, 0f, w, h, fillPaint)
    }
}
