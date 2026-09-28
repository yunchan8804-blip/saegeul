/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects

/**
 * Inputs a [ChromaEffectRenderer] reads during one step or draw pass. [RgbChromaEffectView]
 * owns a single instance and refreshes it in place every frame, so the animation loop does not
 * allocate a frame object per pass.
 */
internal class EffectFrame {
    /** Canvas width in pixels. */
    var width = 0f

    /** Canvas height in pixels. */
    var height = 0f

    /** Theme intensity mapped to a paint alpha in 25..255. */
    var intensity = 0

    /** Colors of the active ambient mode. */
    var palette: IntArray = IntArray(0)

    /** Theme flow direction id such as `left_to_right`. */
    var direction = ""

    /** Animation loop position in [0, 1). */
    var phase = 0f

    /** Display density used to scale stroke widths and radii. */
    var density = 1f

    /** Seconds elapsed since the previous frame. */
    var deltaSeconds = 0f

    /** Theme speed multiplier, clamped by the view. */
    var speed = 1f
}
