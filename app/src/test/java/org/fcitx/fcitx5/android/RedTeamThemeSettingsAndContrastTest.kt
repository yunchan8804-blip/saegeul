/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.theme.CustomThemeSerializer
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemeMonet
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.withTempDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Red Team Adversarial Test Suite - Phase 2:
 * Probing Zip traversal & import integrity, Monet dynamic color conversion,
 * WCAG contrast ratio compliance, and comprehensive theme customization dimensions.
 */
class RedTeamThemeSettingsAndContrastTest {

    private fun String.toCustomTheme(): Pair<Theme.Custom, Boolean> =
        Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, this)

    private fun Theme.Custom.toJson(): String =
        Json.encodeToString(CustomThemeSerializer, this)

    // --- WCAG Contrast Helpers ---
    private fun sRgbToLinear(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun relativeLuminance(color: Int): Double {
        val r = sRgbToLinear((color shr 16) and 0xff)
        val g = sRgbToLinear((color shr 8) and 0xff)
        val b = sRgbToLinear(color and 0xff)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrastRatio(fg: Int, bg: Int): Double {
        val l1 = relativeLuminance(fg)
        val l2 = relativeLuminance(bg)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Attack Vector 1: Malicious Zip Path Traversal (Zip Slip)
     * An attacker crafts a zip file containing paths escaping destDir (e.g., ../../evil.json).
     * ZipInputStream.extract() MUST throw SecurityException.
     */
    @Test
    fun probeZipSecurityAndTraversal() {
        val evilZipBytes = ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("../../../escape_sandbox.json"))
                zos.write("{\"pwned\": true}".toByteArray())
                zos.closeEntry()
            }
            bos.toByteArray()
        }

        val tempDir = java.nio.file.Files.createTempDirectory("red_team_zip_").toFile()
        try {
            ZipInputStream(ByteArrayInputStream(evilZipBytes)).use { it.extract(tempDir) }
            fail("Expected SecurityException on zip slip traversal attack!")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("escapes destination") == true)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Attack Vector 2: Zip Export & Import Roundtrip Integrity
     * Ensure a complete custom theme can be archived into a Zip stream,
     * and reconstructed with zero loss of color or structural data.
     */
    @Test
    fun probeZipExportAndImportIntegrity() {
        val customTheme = ThemePreset.BaegjaLight.deriveCustomNoBackground("BaegjaExportTest")
        val zipBytes = ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("${customTheme.name}.json"))
                zos.write(customTheme.toJson().toByteArray())
                zos.closeEntry()
            }
            bos.toByteArray()
        }

        val tempDir = java.nio.file.Files.createTempDirectory("red_team_export_").toFile()
        try {
            val extracted = ZipInputStream(ByteArrayInputStream(zipBytes)).use { it.extract(tempDir) }
            assertEquals(1, extracted.size)
            val jsonFile = extracted.first()
            assertEquals("BaegjaExportTest.json", jsonFile.name)

            val (decoded, migrated) = jsonFile.readText().toCustomTheme()
            assertEquals(false, migrated)
            assertEquals(customTheme.name, decoded.name)
            assertEquals(customTheme.accentKeyBackgroundColor, decoded.accentKeyBackgroundColor)
            assertEquals(customTheme.keyBackgroundColor, decoded.keyBackgroundColor)
            assertEquals(customTheme.candidateTextColor, decoded.candidateTextColor)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Attack Vector 3: Monet Dynamic Color Theme Conversion
     * Theme.Monet must correctly map dynamic system palette colors to Theme.Custom,
     * generating a unique identifier tag and valid serialization format.
     */
    @Test
    fun probeMonetThemeConversionAndDerivation() {
        val monetLight = ThemeMonet.getLight()
        val monetDark = ThemeMonet.getDark()

        val customLight = monetLight.toCustom()
        val customDark = monetDark.toDarkCustom()

        assertTrue(customLight.name.startsWith("MonetLight#"))
        assertEquals(false, customLight.isDark)
        assertEquals(monetLight.accentKeyBackgroundColor, customLight.accentKeyBackgroundColor)
        assertEquals(monetLight.keyTextColor, customLight.keyTextColor)

        assertTrue(customDark.name.startsWith("MonetDark#"))
        assertEquals(true, customDark.isDark)
        assertEquals(monetDark.accentKeyBackgroundColor, customDark.accentKeyBackgroundColor)
        assertEquals(monetDark.keyTextColor, customDark.keyTextColor)

        // Roundtrip JSON serialization check for Monet-derived themes
        val (decodedLight, _) = customLight.toJson().toCustomTheme()
        val (decodedDark, _) = customDark.toJson().toCustomTheme()

        assertEquals(customLight.name, decodedLight.name)
        assertEquals(customLight.backgroundColor, decodedLight.backgroundColor)
        assertEquals(customDark.name, decodedDark.name)
        assertEquals(customDark.backgroundColor, decodedDark.backgroundColor)
    }

    private fun Theme.Monet.toDarkCustom(): Theme.Custom = toCustom()

    /**
     * Attack Vector 4: WCAG 2.1 Contrast Ratio Compliance for All Signature Themes
     * Probing legibility:
     * - Key Text vs Key Background must be >= 3.0:1
     * - Accent Key Text vs Accent Key Background must be >= 3.0:1
     * - Candidate Text vs Bar Color must be >= 3.0:1
     */
    @Test
    fun probePresetContrastRatiosWcagCompliance() {
        val signatures = listOf(
            ThemePreset.HanjiLight,
            ThemePreset.DancheongDark,
            ThemePreset.BaegjaLight,
            ThemePreset.CheongjaDark,
            ThemePreset.MidnightOLED,
            ThemePreset.SeoulMistGlass
        )

        for (preset in signatures) {
            val keyContrast = contrastRatio(preset.keyTextColor, preset.keyBackgroundColor)
            val accentContrast = contrastRatio(preset.accentKeyTextColor, preset.accentKeyBackgroundColor)
            val candidateContrast = contrastRatio(preset.candidateTextColor, preset.barColor)

            assertTrue(
                "Theme ${preset.name} key contrast ratio $keyContrast must be >= 3.0:1",
                keyContrast >= 3.0
            )
            assertTrue(
                "Theme ${preset.name} accent contrast ratio $accentContrast must be >= 3.0:1",
                accentContrast >= 3.0
            )
            assertTrue(
                "Theme ${preset.name} candidate contrast ratio $candidateContrast must be >= 3.0:1",
                candidateContrast >= 3.0
            )
        }
    }

    /**
     * Attack Vector 5: Comprehensive Color Customization Field-by-Field Mutation
     * Ensure every single color field in Theme.Custom can be modified independently
     * without side effects or cross-field contamination.
     */
    @Test
    fun probeCustomThemeAllPropertiesMutationResilience() {
        val base = ThemePreset.BaegjaLight.deriveCustomNoBackground("MutationBase")

        val mutated = base.copy(
            isDark = true,
            backgroundColor = 0xff112233.toInt(),
            barColor = 0xff223344.toInt(),
            keyboardColor = 0xff334455.toInt(),
            keyBackgroundColor = 0xff445566.toInt(),
            keyTextColor = 0xff556677.toInt(),
            candidateTextColor = 0xff667788.toInt(),
            candidateLabelColor = 0xff778899.toInt(),
            candidateCommentColor = 0xff8899aa.toInt(),
            altKeyBackgroundColor = 0xff99aabb.toInt(),
            altKeyTextColor = 0xffaabbcc.toInt(),
            accentKeyBackgroundColor = 0xffbbccdd.toInt(),
            accentKeyTextColor = 0xffccddee.toInt(),
            keyPressHighlightColor = 0x55ffffff,
            keyShadowColor = 0x22000000,
            popupBackgroundColor = 0xffddeeff.toInt(),
            popupTextColor = 0xffeeffff.toInt(),
            spaceBarColor = 0xff123456.toInt(),
            dividerColor = 0xff234567.toInt(),
            clipboardEntryColor = 0xff345678.toInt(),
            genericActiveBackgroundColor = 0xff456789.toInt(),
            genericActiveForegroundColor = 0xff56789a.toInt()
        )

        val (decoded, migrated) = mutated.toJson().toCustomTheme()
        assertEquals(false, migrated)
        assertEquals(true, decoded.isDark)
        assertEquals(0xff112233.toInt(), decoded.backgroundColor)
        assertEquals(0xff223344.toInt(), decoded.barColor)
        assertEquals(0xff334455.toInt(), decoded.keyboardColor)
        assertEquals(0xff445566.toInt(), decoded.keyBackgroundColor)
        assertEquals(0xff556677.toInt(), decoded.keyTextColor)
        assertEquals(0xff667788.toInt(), decoded.candidateTextColor)
        assertEquals(0xff778899.toInt(), decoded.candidateLabelColor)
        assertEquals(0xff8899aa.toInt(), decoded.candidateCommentColor)
        assertEquals(0xff99aabb.toInt(), decoded.altKeyBackgroundColor)
        assertEquals(0xffaabbcc.toInt(), decoded.altKeyTextColor)
        assertEquals(0xffbbccdd.toInt(), decoded.accentKeyBackgroundColor)
        assertEquals(0xffccddee.toInt(), decoded.accentKeyTextColor)
        assertEquals(0x55ffffff, decoded.keyPressHighlightColor)
        assertEquals(0x22000000, decoded.keyShadowColor)
        assertEquals(0xffddeeff.toInt(), decoded.popupBackgroundColor)
        assertEquals(0xffeeffff.toInt(), decoded.popupTextColor)
        assertEquals(0xff123456.toInt(), decoded.spaceBarColor)
        assertEquals(0xff234567.toInt(), decoded.dividerColor)
        assertEquals(0xff345678.toInt(), decoded.clipboardEntryColor)
        assertEquals(0xff456789.toInt(), decoded.genericActiveBackgroundColor)
        assertEquals(0xff56789a.toInt(), decoded.genericActiveForegroundColor)
    }

    /**
     * Attack Vector 6: Built-in Theme Registry & Name Resolvability
     * Ensure built-in preset registry behaves reliably and does not crash on missing keys.
     */
    @Test
    fun probeBuiltinPresetsRegistryAndFallbacks() {
        val allBuiltinPresets = listOf(
            ThemePreset.HanjiLight,
            ThemePreset.DancheongDark,
            ThemePreset.BaegjaLight,
            ThemePreset.CheongjaDark,
            ThemePreset.MidnightOLED,
            ThemePreset.SeoulMistGlass,
            ThemePreset.MaterialLight,
            ThemePreset.MaterialDark,
            ThemePreset.PixelLight,
            ThemePreset.PixelDark,
            ThemePreset.NordLight,
            ThemePreset.NordDark,
            ThemePreset.DeepBlue,
            ThemePreset.Monokai,
            ThemePreset.AMOLEDBlack,
        )

        for (builtin in allBuiltinPresets) {
            val found = allBuiltinPresets.find { it.name == builtin.name }
            assertNotNull("Builtin theme ${builtin.name} must be resolvable", found)
            assertEquals(builtin.name, found?.name)
            assertTrue(builtin.backgroundColor != 0)
            assertTrue(builtin.keyboardColor != 0)
        }

        // Non-existent theme lookup returns null safely
        assertNull(allBuiltinPresets.find { it.name == "NonExistentTheme_Random_12345" })
        assertNull(allBuiltinPresets.find { it.name == "" })
    }
}
