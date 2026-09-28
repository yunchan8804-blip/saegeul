/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects

import android.graphics.Canvas

/**
 * One ambient lighting effect drawn by [RgbChromaEffectView] beneath the reactive key pulses.
 * [ChromaEffectRegistry] owns the mapping from theme ambient ids to implementations.
 */
internal interface ChromaEffectRenderer {

    /**
     * Advances effect-owned animation state once per Choreographer frame. Effects whose look
     * depends only on [EffectFrame.phase] keep the default, which does nothing.
     */
    fun step(frame: EffectFrame) = Unit

    fun draw(canvas: Canvas, frame: EffectFrame)
}
