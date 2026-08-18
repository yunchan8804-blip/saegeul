/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.graphics.Rect
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.theme.CustomThemeSerializer
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RedTeamThemeTest {

    private fun Theme.Custom.toJson() = Json.encodeToString(CustomThemeSerializer, this)
    private fun String.toCustomTheme() =
        Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, this)

    /**
     * Attack Vector 1: Presets uniqueness and color token integrity
     */
    @Test
    fun probePresetsUniquenessAndNonEmptyNames() {
        val presets = listOf(
            ThemePreset.HanjiLight,
            ThemePreset.DancheongDark,
            ThemePreset.BaegjaLight,
            ThemePreset.CheongjaDark,
            ThemePreset.MidnightOLED,
            ThemePreset.SeoulMistGlass,
            ThemePreset.PixelDark,
            ThemePreset.PixelLight,
            ThemePreset.MaterialDark,
            ThemePreset.MaterialLight,
            ThemePreset.NordDark,
            ThemePreset.NordLight,
            ThemePreset.DeepBlue,
            ThemePreset.Monokai,
            ThemePreset.AMOLEDBlack
        )

        val names = mutableSetOf<String>()
        for (preset in presets) {
            assertTrue("Preset name must not be blank", preset.name.isNotBlank())
            assertTrue("Preset name ${preset.name} must be unique", names.add(preset.name))
            assertNotNull(preset.backgroundColor)
            assertNotNull(preset.keyBackgroundColor)
            assertNotNull(preset.accentKeyBackgroundColor)
            assertNotNull(preset.keyTextColor)
            assertNotNull(preset.barColor)
        }
    }

    /**
     * Attack Vector 2: Special character & Unicode theme names fuzzing
     */
    @Test
    fun probeSpecialCharacterAndUnicodeThemeNames() {
        val weirdNames = listOf(
            "훈민정음 2026 🌾🏮🍶",
            "Theme-with-symbols-!@#$%^&*()_+~`",
            "   leading and trailing spaces   ",
            "Theme with \"escaped quotes\" and \\slashes/",
            "가나다라마바사아자차카타파하".repeat(10) // 140 chars
        )

        for (name in weirdNames) {
            val custom = ThemePreset.HanjiLight.deriveCustomNoBackground(name)
            val json = custom.toJson()
            val (decoded, migrated) = json.toCustomTheme()
            assertEquals("Name preservation failed for '$name'", name, decoded.name)
            assertEquals("Migration should not trigger for current version", false, migrated)
            assertEquals("Full object equality failed for '$name'", custom, decoded)
        }
    }

    /**
     * Attack Vector 3: Extreme boundary color values (Alpha 0, Pure White, Pitch Black, Negative Ints)
     */
    @Test
    fun probeBoundaryColors() {
        val boundaryTheme = Theme.Custom(
            name = "ExtremeBoundaryTheme",
            backgroundImage = null,
            backgroundColor = 0x00000000, // fully transparent
            barColor = -1, // 0xffffffff pure white
            keyboardColor = -16777216, // 0xff000000 pure black
            keyBackgroundColor = 0x7f000000, // 50% transparent black
            keyTextColor = -1,
            candidateTextColor = -1,
            candidateLabelColor = 0x33ffffff,
            candidateCommentColor = 0x11ffffff,
            altKeyBackgroundColor = 0x00ffffff,
            altKeyTextColor = -1,
            accentKeyBackgroundColor = 0x00e699, // Neon Jade
            accentKeyTextColor = -16777216,
            keyPressHighlightColor = 0x3300e699,
            keyShadowColor = 0,
            popupBackgroundColor = -16777216,
            popupTextColor = -1,
            spaceBarColor = 0x22ffffff,
            dividerColor = 0x1affffff,
            clipboardEntryColor = -16777216,
            genericActiveBackgroundColor = 0x00e699,
            genericActiveForegroundColor = -16777216,
            isDark = true
        )

        val (decoded, _) = boundaryTheme.toJson().toCustomTheme()
        assertEquals(boundaryTheme, decoded)
    }

    /**
     * Attack Vector 4: Non-existent image file paths & extreme crop parameters
     */
    @Test
    fun probeMissingImageFilesAndCropParameters() {
        val nonExistentBg = Theme.Custom.CustomBackground(
            croppedFilePath = "/system/corrupted/does_not_exist_crop.png",
            srcFilePath = "/data/local/tmp/does_not_exist_src.png",
            brightness = 0, // 0% brightness
            cropRect = Rect(-100, -200, 5000, 8000), // extreme bounds
            cropRotation = 270
        )

        val customWithBg = Theme.Custom(
            name = "GhostImageTheme",
            backgroundImage = nonExistentBg,
            backgroundColor = 0xff101918.toInt(),
            barColor = 0xff0b1211.toInt(),
            keyboardColor = 0xff101918.toInt(),
            keyBackgroundColor = 0xff253330.toInt(),
            keyTextColor = 0xfff4ead8.toInt(),
            candidateTextColor = 0xfff4ead8.toInt(),
            candidateLabelColor = 0xffc9bda9.toInt(),
            candidateCommentColor = 0xffa69b89.toInt(),
            altKeyBackgroundColor = 0xff192623.toInt(),
            altKeyTextColor = 0xffc9bda9.toInt(),
            accentKeyBackgroundColor = 0xffc84a3f.toInt(),
            accentKeyTextColor = -1,
            keyPressHighlightColor = 0x33ffffff,
            keyShadowColor = 0,
            popupBackgroundColor = 0xff253330.toInt(),
            popupTextColor = 0xfff4ead8.toInt(),
            spaceBarColor = 0xff33443f.toInt(),
            dividerColor = 0x26ffffff,
            clipboardEntryColor = 0xff253330.toInt(),
            genericActiveBackgroundColor = 0xff24776d.toInt(),
            genericActiveForegroundColor = -1,
            isDark = true
        )

        val (decoded, migrated) = customWithBg.toJson().toCustomTheme()
        assertEquals(false, migrated)
        assertEquals("GhostImageTheme", decoded.name)
        assertEquals(customWithBg.backgroundColor, decoded.backgroundColor)
        assertEquals(customWithBg.accentKeyBackgroundColor, decoded.accentKeyBackgroundColor)
        assertEquals(0, decoded.backgroundImage?.brightness)
        assertEquals(270, decoded.backgroundImage?.cropRotation)
        assertEquals(nonExistentBg.croppedFilePath, decoded.backgroundImage?.croppedFilePath)
    }

    /**
     * Attack Vector 5: Brightness 100% and Null Crop Rect
     */
    @Test
    fun probeBrightness100AndNullCropRect() {
        val bg = Theme.Custom.CustomBackground(
            croppedFilePath = "crop.png",
            srcFilePath = "src.png",
            brightness = 100,
            cropRect = null,
            cropRotation = 0
        )
        val custom = ThemePreset.BaegjaLight.deriveCustomBackground("BaegjaCustom", "crop.png", "src.png", 100, null, 0)
        val (decoded, _) = custom.toJson().toCustomTheme()
        assertEquals(100, decoded.backgroundImage?.brightness)
        assertNull(decoded.backgroundImage?.cropRect)
        assertEquals(custom, decoded)
    }

    /**
     * Attack Vector 6: Corrupted / Malformed JSON payloads must fail safely
     */
    @Test
    fun probeMalformedJsonThrowsSerializationException() {
        val badJsons = listOf(
            "", // empty
            "{", // unclosed
            "{\"name\": 12345}", // invalid type for string
            "{\"invalid_json_payload\": true}" // missing required colors
        )

        for (bad in badJsons) {
            try {
                bad.toCustomTheme()
                fail("Expected SerializationException or IllegalArgumentException for: $bad")
            } catch (e: SerializationException) {
                // Expected behavior
                assertNotNull(e.message)
            } catch (e: IllegalArgumentException) {
                // Expected behavior
                assertNotNull(e.message)
            }
        }
    }

    /**
     * Attack Vector 7: Legacy v1.0 and v2.0 JSON migration stress test
     */
    @Test
    fun probeLegacyVersionMigrationResilience() {
        // v1.0 payload without popup and genericActive colors
        val v1Json = """
            {
                "name": "LegacyV1",
                "backgroundImage": null,
                "backgroundColor": -1,
                "barColor": -1,
                "keyboardColor": -1,
                "keyBackgroundColor": -1,
                "keyTextColor": -16777216,
                "altKeyBackgroundColor": -1,
                "altKeyTextColor": -16777216,
                "accentKeyBackgroundColor": -16776961,
                "accentKeyTextColor": -1,
                "keyPressHighlightColor": 0,
                "keyShadowColor": 0,
                "spaceBarColor": -1,
                "dividerColor": 0,
                "clipboardEntryColor": -1,
                "isDark": false,
                "version": "1.0"
            }
        """.trimIndent()

        val (decodedV1, migratedV1) = v1Json.toCustomTheme()
        assertTrue("v1.0 must be migrated", migratedV1)
        assertEquals("LegacyV1", decodedV1.name)
        assertNotNull(decodedV1.popupBackgroundColor)
        assertNotNull(decodedV1.genericActiveBackgroundColor)

        // v2.0 payload without 2.1 specific fields
        val v2Json = """
            {
                "name": "LegacyV2",
                "backgroundImage": null,
                "backgroundColor": -1,
                "barColor": -1,
                "keyboardColor": -1,
                "keyBackgroundColor": -1,
                "keyTextColor": -16777216,
                "altKeyBackgroundColor": -1,
                "altKeyTextColor": -16777216,
                "accentKeyBackgroundColor": -16776961,
                "accentKeyTextColor": -1,
                "keyPressHighlightColor": 0,
                "keyShadowColor": 0,
                "popupBackgroundColor": -1,
                "popupTextColor": -16777216,
                "spaceBarColor": -1,
                "dividerColor": 0,
                "clipboardEntryColor": -1,
                "genericActiveBackgroundColor": -16776961,
                "genericActiveForegroundColor": -1,
                "isDark": false,
                "version": "2.0"
            }
        """.trimIndent()

        val (decodedV2, migratedV2) = v2Json.toCustomTheme()
        assertTrue("v2.0 must be migrated to 2.1", migratedV2)
        assertEquals("LegacyV2", decodedV2.name)
    }

    /**
     * Attack Vector 8: 6 Korean Signature & Modern Presets roundtrip derivation
     */
    @Test
    fun probeAllKoreanSignatureThemesDerivation() {
        val signatures = listOf(
            ThemePreset.HanjiLight,
            ThemePreset.DancheongDark,
            ThemePreset.BaegjaLight,
            ThemePreset.CheongjaDark,
            ThemePreset.MidnightOLED,
            ThemePreset.SeoulMistGlass
        )

        for (preset in signatures) {
            val custom = preset.deriveCustomNoBackground("custom-${preset.name}")
            val (decoded, migrated) = custom.toJson().toCustomTheme()
            assertEquals("Migration should not happen for fresh derivation", false, migrated)
            assertEquals("Key background color mismatch for ${preset.name}", preset.keyBackgroundColor, decoded.keyBackgroundColor)
            assertEquals("Accent key color mismatch for ${preset.name}", preset.accentKeyBackgroundColor, decoded.accentKeyBackgroundColor)
            assertEquals("Surface color mismatch for ${preset.name}", preset.backgroundColor, decoded.backgroundColor)
            assertEquals("Text color mismatch for ${preset.name}", preset.keyTextColor, decoded.keyTextColor)
        }
    }
}
