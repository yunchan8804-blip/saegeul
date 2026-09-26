/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectGateTest {

    @Test
    fun animatesOnlyWhenNeitherPowerSaveNorAnimatorsDisabled() {
        assertTrue(EffectGate.shouldAnimate(powerSaveMode = false, animatorsEnabled = true))
    }

    @Test
    fun powerSaveModeBlocksAnimation() {
        assertFalse(EffectGate.shouldAnimate(powerSaveMode = true, animatorsEnabled = true))
    }

    @Test
    fun disabledSystemAnimatorsBlocksAnimation() {
        assertFalse(EffectGate.shouldAnimate(powerSaveMode = false, animatorsEnabled = false))
    }

    @Test
    fun bothConditionsBlockAnimation() {
        assertFalse(EffectGate.shouldAnimate(powerSaveMode = true, animatorsEnabled = false))
    }
}
