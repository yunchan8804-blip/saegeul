/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.theme.CustomThemeSerializer
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Red Team Adversarial Test Suite - Phase 3:
 * Probing Per-Key customization overrides, 9-Patch Sliced Keycaps,
 * RGB Chroma lighting modes, and touch particle sparkle physics configurations.
 */
class RedTeamPerKeyAndEffectsTest {

    private fun String.toCustomTheme(): Pair<Theme.Custom, Boolean> =
        Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, this)

    private fun Theme.Custom.toJson(): String =
        Json.encodeToString(CustomThemeSerializer, this)

    @Test
    fun testV3ThemeSerializationWithEffectsAndPerKeyOverrides() {
        val base = ThemePreset.MidnightOLED.deriveCustomNoBackground("Midnight Neon Chroma")

        val keyOverridesMap = mapOf(
            "button_space" to Theme.Custom.KeyCustomStyle(
                keyBackgroundColor = 0xFF00FF66.toInt(),
                keyTextColor = 0xFF000000.toInt(),
                keyBorderColor = 0xFF00FF66.toInt(),
                cornerRadius = 12f
            ),
            "button_return" to Theme.Custom.KeyCustomStyle(
                keyBackgroundColor = 0xFFFF007F.toInt(),
                keyTextColor = 0xFFFFFFFF.toInt(),
                keyGlowColor = 0xFFFF007F.toInt(),
                keyGlowRadius = 6f
            ),
            "a" to Theme.Custom.KeyCustomStyle(
                keyBackgroundColor = 0xFF1E293B.toInt(),
                keyTextColor = 0xFF38BDF8.toInt()
            )
        )

        val v3Theme = base.copy(
            keyOverrides = keyOverridesMap,
            globalKeyStyle = Theme.Custom.KeyCustomStyle(
                keyBackgroundColor = 0xFF121212.toInt(),
                cornerRadius = 8f
            ),
            lightingEffect = Theme.Custom.LightingEffectDef(
                mode = "rgb_wave",
                speed = 1.5f,
                intensity = 0.9f,
                direction = "left_to_right"
            ),
            particleEffect = Theme.Custom.ParticleEffectDef(
                type = "star_sparkle",
                particleCount = 12,
                lifetimeMs = 500L,
                speed = 1.2f
            ),
            keyGlowEffect = Theme.Custom.KeyGlowDef(
                enabled = true,
                glowColor = 0xFF00F0FF.toInt(),
                glowRadius = 5f
            )
        )

        val json = v3Theme.toJson()
        assertTrue("JSON must contain version 3.0", json.contains("\"version\":\"3.0\""))
        assertTrue("JSON must contain keyOverrides", json.contains("button_space"))
        assertTrue("JSON must contain lightingEffect", json.contains("rgb_wave"))
        assertTrue("JSON must contain particleEffect", json.contains("star_sparkle"))
        assertTrue("JSON must contain keyGlowEffect", json.contains("glowRadius"))

        val (decoded, migrated) = json.toCustomTheme()
        assertFalse("Current v3 theme must not need migration", migrated)
        assertEquals("Midnight Neon Chroma", decoded.name)
        assertNotNull(decoded.keyOverrides)
        assertEquals(3, decoded.keyOverrides!!.size)
        assertEquals(0xFF00FF66.toInt(), decoded.keyOverrides!!["button_space"]?.keyBackgroundColor)
        assertEquals(12f, decoded.keyOverrides!!["button_space"]?.cornerRadius)
        assertEquals("rgb_wave", decoded.lightingEffect?.mode)
        assertEquals(1.5f, decoded.lightingEffect?.speed ?: 0f, 0.001f)
        assertEquals("star_sparkle", decoded.particleEffect?.type)
        assertEquals(12, decoded.particleEffect?.particleCount)
        assertTrue(decoded.keyGlowEffect?.enabled == true)
        assertEquals(0xFF00F0FF.toInt(), decoded.keyGlowEffect?.glowColor)
    }

    @Test
    fun testV1AndV2ThemeAutoMigrationToV3() {
        val legacyJson = """
            {
                "version": "1.0",
                "name": "Legacy Theme",
                "isDark": true,
                "backgroundColor": -16777216,
                "barColor": -16777216,
                "keyboardColor": -16777216,
                "keyBackgroundColor": -13421773,
                "keyTextColor": -1,
                "altKeyBackgroundColor": -14540254,
                "altKeyTextColor": -7829368,
                "accentKeyBackgroundColor": -16711936,
                "accentKeyTextColor": -16777216,
                "keyPressHighlightColor": 855638016,
                "keyShadowColor": 0,
                "spaceBarColor": -13421773,
                "dividerColor": -12303292,
                "clipboardEntryColor": -13421773
            }
        """.trimIndent()

        val (migratedTheme, migrated) = legacyJson.toCustomTheme()
        assertTrue("Legacy v1.0 theme must be marked as migrated", migrated)
        assertEquals("Legacy Theme", migratedTheme.name)
        // Check safe defaults for v3 fields
        assertNull("keyOverrides must default to null", migratedTheme.keyOverrides)
        assertNull("globalKeyStyle must default to null", migratedTheme.globalKeyStyle)
        assertNull("lightingEffect must default to null", migratedTheme.lightingEffect)
        assertNull("particleEffect must default to null", migratedTheme.particleEffect)
        assertNull("keyGlowEffect must default to null", migratedTheme.keyGlowEffect)
    }

    @Test
    fun testSlicedImageDefSerialization() {
        val base = ThemePreset.HanjiLight.deriveCustomNoBackground("Sliced Hanji")
        val sliced = Theme.Custom.SlicedImageDef(
            imagePath = "/data/user/0/theme_assets/sliced_key.png",
            leftSlice = 16,
            topSlice = 16,
            rightSlice = 16,
            bottomSlice = 16,
            repeatMode = "stretch"
        )
        val customKey = Theme.Custom.KeyCustomStyle(
            slicedImage = sliced,
            cornerRadius = 10f
        )
        val themeWithSlice = base.copy(
            keyOverrides = mapOf("button_space" to customKey)
        )

        val json = themeWithSlice.toJson()
        val (decoded, _) = json.toCustomTheme()
        val spaceStyle = decoded.keyOverrides?.get("button_space")
        assertNotNull(spaceStyle)
        assertNotNull(spaceStyle!!.slicedImage)
        assertEquals("/data/user/0/theme_assets/sliced_key.png", spaceStyle.slicedImage!!.imagePath)
        assertEquals(16, spaceStyle.slicedImage!!.leftSlice)
        assertEquals(16, spaceStyle.slicedImage!!.topSlice)
        assertEquals("stretch", spaceStyle.slicedImage!!.repeatMode)
    }

    @Test
    fun testAllRgbChromaAndParticleModesValid() {
        val supportedChromaModes = listOf(
            "off",
            "rgb_wave",
            "rgb_breathe",
            "neon_pulse",
            "cyberpunk",
            "matrix_flow",
            "aurora",
            "starlight",
            "ocean_tide",
            "fire_ember",
            "supernova",
            "sakura_breeze",
            "frost_crystal",
            "reactive_ripple",
            "reactive_fade",
            "reactive_firework",
            "reactive_laser"
        )
        val supportedParticleModes = listOf("off", "star_sparkle", "glowing_dust", "neon_burst", "cosmic_ripple")

        for (mode in supportedChromaModes) {
            val def = Theme.Custom.LightingEffectDef(mode = mode, speed = 1.0f, intensity = 0.8f)
            assertEquals(mode, def.mode)
            assertTrue(def.speed > 0f)
            assertTrue(def.intensity in 0f..1f)
        }

        for (type in supportedParticleModes) {
            val def = Theme.Custom.ParticleEffectDef(type = type, particleCount = 8, lifetimeMs = 450L)
            assertEquals(type, def.type)
            assertTrue(def.particleCount > 0)
            assertTrue(def.lifetimeMs > 0L)
        }
    }

    @Test
    fun testLightingEffectModesSerializationAndRoundtrip() {
        val base = ThemePreset.MidnightOLED.deriveCustomNoBackground("Multi-Mode Chroma")
        val modes = listOf(
            "off", "rgb_wave", "rgb_breathe", "neon_pulse", "cyberpunk", "matrix_flow",
            "aurora", "starlight", "ocean_tide", "fire_ember", "supernova",
            "sakura_breeze", "frost_crystal", "reactive_ripple", "reactive_fade",
            "reactive_firework", "reactive_laser"
        )

        for (mode in modes) {
            val theme = base.copy(
                lightingEffect = Theme.Custom.LightingEffectDef(
                    mode = mode,
                    speed = 1.25f,
                    intensity = 0.9f,
                    direction = "left_to_right"
                )
            )
            val json = theme.toJson()
            val (decoded, _) = json.toCustomTheme()
            assertNotNull(decoded.lightingEffect)
            assertEquals(mode, decoded.lightingEffect?.mode)
            assertEquals(1.25f, decoded.lightingEffect?.speed ?: 0f, 0.001f)
            assertEquals(0.9f, decoded.lightingEffect?.intensity ?: 0f, 0.001f)
        }
    }

    @Test
    fun testLightingEffectDefWithCustomColors() {
        val customColors = listOf(0xFFFF0055.toInt(), 0xFF00FF66.toInt(), 0xFF00CCFF.toInt())
        val base = ThemePreset.MidnightOLED.deriveCustomNoBackground("Custom Colors Theme")
        val theme = base.copy(
            lightingEffect = Theme.Custom.LightingEffectDef(
                mode = "rgb_wave",
                speed = 2.0f,
                intensity = 1.0f,
                customColors = customColors
            )
        )
        val json = theme.toJson()
        val (decoded, _) = json.toCustomTheme()
        assertNotNull(decoded.lightingEffect)
        assertEquals(3, decoded.lightingEffect?.customColors?.size)
        assertEquals(customColors, decoded.lightingEffect?.customColors)
    }
}
