/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects

import android.graphics.Matrix
import android.graphics.Paint
import org.fcitx.fcitx5.android.input.keyboard.effects.renderers.AuroraRenderer
import org.fcitx.fcitx5.android.input.keyboard.effects.renderers.BreathingRenderer
import org.fcitx.fcitx5.android.input.keyboard.effects.renderers.CyberpunkRenderer
import org.fcitx.fcitx5.android.input.keyboard.effects.renderers.FireEmberRenderer
import org.fcitx.fcitx5.android.input.keyboard.effects.renderers.FrostCrystalRenderer
import org.fcitx.fcitx5.android.input.keyboard.effects.renderers.MatrixFlowRenderer
import org.fcitx.fcitx5.android.input.keyboard.effects.renderers.NeonPulseRenderer
import org.fcitx.fcitx5.android.input.keyboard.effects.renderers.OceanTideRenderer
import org.fcitx.fcitx5.android.input.keyboard.effects.renderers.RainbowWaveRenderer
import org.fcitx.fcitx5.android.input.keyboard.effects.renderers.SakuraBreezeRenderer
import org.fcitx.fcitx5.android.input.keyboard.effects.renderers.StarlightRenderer
import org.fcitx.fcitx5.android.input.keyboard.effects.renderers.SupernovaRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChromaEffectRegistryTest {

    private fun newRegistry() = ChromaEffectRegistry(ChromaPaints(Paint(), Paint(), Matrix()))

    /** Ambient ids as stored in saved themes; renaming one would orphan existing themes. */
    private val storedIds = linkedMapOf(
        "rgb_wave" to RainbowWaveRenderer::class.java,
        "rgb_breathe" to BreathingRenderer::class.java,
        "cyberpunk" to CyberpunkRenderer::class.java,
        "matrix_flow" to MatrixFlowRenderer::class.java,
        "neon_pulse" to NeonPulseRenderer::class.java,
        "aurora" to AuroraRenderer::class.java,
        "starlight" to StarlightRenderer::class.java,
        "ocean_tide" to OceanTideRenderer::class.java,
        "fire_ember" to FireEmberRenderer::class.java,
        "supernova" to SupernovaRenderer::class.java,
        "sakura_breeze" to SakuraBreezeRenderer::class.java,
        "frost_crystal" to FrostCrystalRenderer::class.java
    )

    @Test
    fun everyStoredAmbientIdHasItsOwnRenderer() {
        assertEquals(storedIds.keys.toList(), ChromaEffectRegistry.EFFECT_IDS.toList())
        val registry = newRegistry()
        for ((id, rendererClass) in storedIds) {
            assertEquals(id, rendererClass, registry.rendererFor(id)?.javaClass)
        }
    }

    @Test
    fun offDrawsNoAmbientLayer() {
        assertNull(newRegistry().rendererFor("off"))
    }

    @Test
    fun unknownIdsDrawAsRainbowWave() {
        val registry = newRegistry()
        val rainbowWave = registry.rendererFor("rgb_wave")
        for (id in listOf("", "rainbow", "RGB_WAVE", "future_effect")) {
            assertSame(id, rainbowWave, registry.rendererFor(id))
        }
    }

    @Test
    fun eachRegistryOwnsItsRenderers() {
        val first = newRegistry()
        val second = newRegistry()
        for (id in storedIds.keys) {
            assertNotSame(id, first.rendererFor(id), second.rendererFor(id))
        }
    }
}
