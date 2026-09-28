/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects

import android.graphics.Matrix
import android.graphics.Paint

/**
 * Drawing tools one [RgbChromaEffectView] shares across its ambient renderers and reactive
 * pulses. They are shared rather than per renderer because paint state set by one layer
 * (for example the round stroke cap of the matrix rain) carries into the layers drawn after it.
 */
internal class ChromaPaints(
    val fillPaint: Paint,
    val strokePaint: Paint,
    val shaderMatrix: Matrix
)
