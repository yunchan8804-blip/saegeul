/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Star outlines shared by the starlight ambient effect and [ParticleTouchOverlayView]. One
 * [Path] is reset and reused for every star, and every star is filled with [paint].
 *
 * @param crossInnerRatio inner radius of the four-point star relative to its outer radius.
 */
internal class StarPainter(private val paint: Paint, crossInnerRatio: Float) {

    private class Shape(val points: Int, val innerRatio: Float)

    private val crossShape = Shape(points = 4, innerRatio = crossInnerRatio)
    private val path = Path()

    fun drawStar(canvas: Canvas, cx: Float, cy: Float, radius: Float, rotation: Float) {
        tracePath(FIVE_POINT_SHAPE, cx, cy, radius, rotation)
        canvas.drawPath(path, paint)
    }

    fun drawCrossStar(canvas: Canvas, cx: Float, cy: Float, radius: Float, rotation: Float) {
        tracePath(crossShape, cx, cy, radius, rotation)
        canvas.drawPath(path, paint)
    }

    private fun tracePath(shape: Shape, cx: Float, cy: Float, radius: Float, rotation: Float) {
        path.reset()
        val innerRadius = radius * shape.innerRatio
        val points = shape.points
        val step = PI / points
        val rotRad = Math.toRadians(rotation.toDouble())

        for (i in 0 until (points * 2)) {
            val r = if (i % 2 == 0) radius else innerRadius
            val a = i * step - PI / 2.0 + rotRad
            val x = (cx + cos(a) * r).toFloat()
            val y = (cy + sin(a) * r).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    private companion object {
        val FIVE_POINT_SHAPE = Shape(points = 5, innerRatio = 0.42f)
    }
}
