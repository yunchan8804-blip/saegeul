/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import kotlin.math.sin
import kotlin.random.Random

/**
 * Confetti overlay for level-up celebrations. Plays once, then removes
 * itself from its parent. Does not intercept touches.
 */
class CelebrationOverlayView(context: Context) : View(context) {

    private class Particle(
        var x: Float,
        var y: Float,
        val drift: Float,
        var vy: Float,
        val size: Float,
        val color: Int,
        val spin: Float,
        var rotation: Float
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var particles: List<Particle> = emptyList()
    private var animator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (particles.isNotEmpty()) return
        val random = Random(20260912)
        particles = List(PARTICLE_COUNT) {
            Particle(
                x = random.nextFloat() * w,
                y = -random.nextFloat() * h * 0.4f,
                drift = (random.nextFloat() - 0.5f) * 2.2f,
                vy = 6f + random.nextFloat() * 10f,
                size = 8f + random.nextFloat() * 12f,
                color = COLORS[random.nextInt(COLORS.size)],
                spin = (random.nextFloat() - 0.5f) * 24f,
                rotation = random.nextFloat() * 360f
            )
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val progress = it.animatedValue as Float
                updateParticles(h, progress)
                invalidate()
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) = Unit
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    detach()
                }

                override fun onAnimationCancel(animation: android.animation.Animator) = Unit
                override fun onAnimationRepeat(animation: android.animation.Animator) = Unit
            })
            start()
        }
    }

    private fun updateParticles(screenHeight: Int, progress: Float) {
        particles.forEach { particle ->
            particle.y += particle.vy
            particle.x += particle.drift + sin(progress * 6f + particle.spin) * 2f
            particle.rotation += particle.spin
            if (particle.y > screenHeight + particle.size) {
                particle.y = -particle.size
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        particles.forEach { particle ->
            canvas.save()
            canvas.translate(particle.x, particle.y)
            canvas.rotate(particle.rotation)
            paint.color = particle.color
            canvas.drawRect(
                -particle.size / 2f,
                -particle.size / 4f,
                particle.size / 2f,
                particle.size / 4f,
                paint
            )
            canvas.restore()
        }
    }

    private fun detach() {
        (parent as? ViewGroup)?.removeView(this)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val PARTICLE_COUNT = 130
        const val DURATION_MS = 3000L
        val COLORS = intArrayOf(
            0xff2e8b6a.toInt(),
            0xffd1607e.toInt(),
            0xff46708a.toInt(),
            0xffe8b23a.toInt(),
            0xff7a5fb5.toInt(),
            0xff3aa6a0.toInt()
        )
    }
}
