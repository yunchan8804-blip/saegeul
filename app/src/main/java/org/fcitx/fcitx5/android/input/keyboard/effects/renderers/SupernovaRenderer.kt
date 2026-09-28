/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects.renderers

import android.graphics.Canvas
import android.graphics.SweepGradient
import org.fcitx.fcitx5.android.input.keyboard.effects.ChromaEffectRenderer
import org.fcitx.fcitx5.android.input.keyboard.effects.ChromaPaints
import org.fcitx.fcitx5.android.input.keyboard.effects.EffectFrame

/**
 * 🪐 Cosmic Supernova: Rotating spiral galaxy nebula.
 */
internal class SupernovaRenderer(paints: ChromaPaints) : ChromaEffectRenderer {

    private val fillPaint = paints.fillPaint
    private val shaderMatrix = paints.shaderMatrix

    override fun draw(canvas: Canvas, frame: EffectFrame) {
        val w = frame.width
        val h = frame.height
        val cx = w / 2f
        val cy = h / 2f
        val sweep = SweepGradient(cx, cy, frame.palette, null)
        shaderMatrix.reset()
        shaderMatrix.postRotate(frame.phase * 360f, cx, cy)
        sweep.setLocalMatrix(shaderMatrix)

        fillPaint.shader = sweep
        fillPaint.alpha = frame.intensity
        canvas.drawRect(0f, 0f, w, h, fillPaint)
    }
}
