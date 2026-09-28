/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import org.fcitx.fcitx5.android.data.theme.Theme
import java.util.concurrent.CopyOnWriteArrayList

/**
 * High-performance, Hardware-accelerated RGB Chroma Backlight & Reactive Mechanical Keyboard Animation Engine.
 * Supports signature ambient lighting and responsive reactive key animations with fluid easing.
 */
class RgbChromaEffectView(
    context: Context,
    var effectDef: Theme.Custom.LightingEffectDef? = null
) : View(context), Choreographer.FrameCallback {

    private val paints = ChromaPaints(
        fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        },
        strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        },
        shaderMatrix = Matrix()
    )
    private val effectRenderers = ChromaEffectRegistry(paints)
    private val reactivePulseRenderer = ReactiveKeyPulseRenderer(paints)
    private val frame = EffectFrame()

    private var phase = 0f
    private var lastFrameTimeMs = 0L
    private var isFrameCallbackPosted = false

    private val powerGate = EffectPowerGate(context) { refreshGateVisibility() }

    // Reactive Touch Events (Mechanical Keyboard RGB Pulse / Ripple / Trace)
    data class ReactiveEvent(
        val x: Float,
        val y: Float,
        val startTimeMs: Long,
        val durationMs: Long,
        val type: String,
        val color: Int
    )

    private val reactiveEvents = CopyOnWriteArrayList<ReactiveEvent>()

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        updateEffect(effectDef)
    }

    fun updateEffect(newDef: Theme.Custom.LightingEffectDef?) {
        effectDef = newDef
        val ambientMode = newDef?.effectiveAmbientMode ?: "off"
        val reactiveMode = newDef?.effectiveReactiveMode ?: "off"

        val hasAmbient = ambientMode != "off"
        val hasReactive = reactiveMode != "off"

        if (!hasAmbient && !hasReactive && reactiveEvents.isEmpty()) {
            stopLoop()
            visibility = GONE
            invalidate()
            return
        }

        if (!powerGate.canAnimate()) {
            stopLoop()
            visibility = GONE
            invalidate()
            return
        }

        visibility = VISIBLE
        startLoop()
        invalidate()
    }

    private fun hasActiveEffect(): Boolean {
        val def = effectDef
        val ambientMode = def?.effectiveAmbientMode ?: "off"
        val reactiveMode = def?.effectiveReactiveMode ?: "off"
        return ambientMode != "off" || reactiveMode != "off" || reactiveEvents.isNotEmpty()
    }

    /**
     * Re-evaluates whether the ambient/reactive loop should be running given the
     * current power-save/animator gate, without changing [effectDef] itself.
     * Called on attach and on power-save mode broadcasts.
     */
    private fun refreshGateVisibility() {
        if (!hasActiveEffect()) return
        if (powerGate.canAnimate()) {
            if (visibility != VISIBLE) visibility = VISIBLE
            startLoop()
        } else {
            stopLoop()
            if (visibility != GONE) visibility = GONE
        }
        invalidate()
    }

    /**
     * Trigger reactive per-key mechanical keyboard RGB animations upon touch/keypress.
     */
    fun spawnReactiveKeyEffect(x: Float, y: Float) {
        val def = effectDef ?: return
        if (!powerGate.canAnimate()) return
        val reactiveMode = def.effectiveReactiveMode
        if (reactiveMode == "off") return

        val now = SystemClock.uptimeMillis()
        val ambientMode = def.effectiveAmbientMode
        val palette = ChromaPalettes.forMode(if (ambientMode != "off") ambientMode else "rainbow", def)
        val colorIndex = ((phase * palette.size).toInt()) % palette.size
        val dynamicColor = palette[colorIndex]

        val duration = when (reactiveMode) {
            "ripple" -> 650L
            "fade" -> 500L
            "firework" -> 500L
            "laser" -> 450L
            else -> 550L
        }

        reactiveEvents.add(
            ReactiveEvent(
                x = x,
                y = y,
                startTimeMs = now,
                durationMs = duration,
                type = reactiveMode,
                color = dynamicColor
            )
        )

        if (visibility != VISIBLE) visibility = VISIBLE
        startLoop()
        invalidate()
    }

    private fun startLoop() {
        if (!powerGate.canAnimate()) return
        if (!isFrameCallbackPosted && isAttachedToWindow && visibility == VISIBLE) {
            lastFrameTimeMs = SystemClock.uptimeMillis()
            isFrameCallbackPosted = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun stopLoop() {
        if (isFrameCallbackPosted) {
            Choreographer.getInstance().removeFrameCallback(this)
            isFrameCallbackPosted = false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        powerGate.attach()
        refreshGateVisibility()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        powerGate.detach()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        val def = effectDef
        if (visibility == VISIBLE && def != null && (def.effectiveAmbientMode != "off" || def.effectiveReactiveMode != "off")) {
            startLoop()
        } else {
            stopLoop()
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        isFrameCallbackPosted = false
        val def = effectDef
        val ambientMode = def?.effectiveAmbientMode ?: "off"
        val reactiveMode = def?.effectiveReactiveMode ?: "off"

        if (def == null || (ambientMode == "off" && reactiveMode == "off" && reactiveEvents.isEmpty()) || visibility != VISIBLE || !isAttachedToWindow || !powerGate.canAnimate()) {
            return
        }

        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameTimeMs > 0L) {
            ((now - lastFrameTimeMs).coerceIn(1L, 100L)) / 1000f
        } else {
            0.016f
        }
        lastFrameTimeMs = now

        val speed = (def.speed).coerceIn(0.2f, 3.5f)
        val phaseDelta = (dt * speed * 0.35f)
        phase = (phase + phaseDelta) % 1.0f

        frame.deltaSeconds = dt
        frame.speed = speed
        effectRenderers.rendererFor(ambientMode)?.step(frame)

        // Clean up expired reactive events
        if (reactiveEvents.isNotEmpty()) {
            val iterator = reactiveEvents.iterator()
            while (iterator.hasNext()) {
                val ev = iterator.next()
                if (now - ev.startTimeMs >= ev.durationMs) {
                    reactiveEvents.remove(ev)
                }
            }
        }

        invalidate()

        // Continue animation loop if active
        if (isAttachedToWindow && visibility == VISIBLE && powerGate.canAnimate() && (ambientMode != "off" || reactiveEvents.isNotEmpty())) {
            isFrameCallbackPosted = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val def = effectDef ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val ambientMode = def.effectiveAmbientMode
        frame.width = w
        frame.height = h
        frame.intensity = (def.intensity.coerceIn(0.1f, 1.0f) * 255).toInt()
        frame.palette = ChromaPalettes.forMode(ambientMode, def)
        frame.direction = def.direction
        frame.phase = phase
        frame.density = resources.displayMetrics.density

        // 1. Render Ambient Base Lighting Layer (if active)
        effectRenderers.rendererFor(ambientMode)?.draw(canvas, frame)

        // 2. Render Reactive Mechanical Keypress Pulses
        reactivePulseRenderer.draw(canvas, frame, reactiveEvents)
    }
}
