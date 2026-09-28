/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects

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

/**
 * Canonical table from the ambient lighting ids stored in themes
 * ([org.fcitx.fcitx5.android.data.theme.Theme.Custom.LightingEffectDef.effectiveAmbientMode])
 * to their renderers. The ids are part of the theme storage format.
 *
 * Each instance builds its own renderers, so the animation state of stateful effects belongs to
 * a single [RgbChromaEffectView].
 */
internal class ChromaEffectRegistry(paints: ChromaPaints) {

    private val renderers: Map<String, ChromaEffectRenderer> =
        FACTORIES.mapValues { (_, create) -> create(paints) }

    /**
     * Renderer for [ambientMode], or null when ambient lighting is [OFF]. Ids this build does not
     * know draw as [FALLBACK_ID].
     */
    fun rendererFor(ambientMode: String): ChromaEffectRenderer? =
        if (ambientMode == OFF) null else renderers[ambientMode] ?: renderers.getValue(FALLBACK_ID)

    companion object {
        const val OFF = "off"
        const val FALLBACK_ID = "rgb_wave"

        private val FACTORIES: Map<String, (ChromaPaints) -> ChromaEffectRenderer> = linkedMapOf(
            "rgb_wave" to ::RainbowWaveRenderer,
            "rgb_breathe" to ::BreathingRenderer,
            "cyberpunk" to ::CyberpunkRenderer,
            "matrix_flow" to ::MatrixFlowRenderer,
            "neon_pulse" to ::NeonPulseRenderer,
            "aurora" to ::AuroraRenderer,
            "starlight" to ::StarlightRenderer,
            "ocean_tide" to ::OceanTideRenderer,
            "fire_ember" to ::FireEmberRenderer,
            "supernova" to ::SupernovaRenderer,
            "sakura_breeze" to ::SakuraBreezeRenderer,
            "frost_crystal" to ::FrostCrystalRenderer
        )

        /** Every ambient id with its own renderer, in theme editor order. */
        val EFFECT_IDS: Set<String> get() = FACTORIES.keys
    }
}
