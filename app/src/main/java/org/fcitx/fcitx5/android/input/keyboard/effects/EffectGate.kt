/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects

/**
 * Pure decision of whether ambient/reactive lighting and particle animations
 * are allowed to run. Kept free of Android framework calls so the rule itself
 * is unit-testable; [RgbChromaEffectView] and [ParticleTouchOverlayView] read
 * `PowerManager.isPowerSaveMode` and `ValueAnimator.areAnimatorsEnabled()` and
 * pass them in here.
 */
object EffectGate {
    fun shouldAnimate(powerSaveMode: Boolean, animatorsEnabled: Boolean): Boolean =
        !powerSaveMode && animatorsEnabled
}
