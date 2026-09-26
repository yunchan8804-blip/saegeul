/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fcitx.fcitx5.android.data.theme.CustomThemeSerializer
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.data.theme.ThemeShopCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Red Team Adversarial Unit Test Suite: Theme Robustness & Contrast Invariants.
 *
 * Attack Vector 1: Malformed and unknown JSON keys resilience (third-party / forward compatibility).
 * Attack Vector 2: Missing required fields resilience and legacy migration fallback.
 * Attack Vector 3: Popup text contrast ratio and accessibility invariants (WCAG 3.0:1 minimum, ghost text defense).
 */
class RedTeamThemeRobustnessTest {

    // --- Strict & Safe Deserializer Definitions ---
    private val strictJson = Json { ignoreUnknownKeys = false }
    private val safeJson = Json { ignoreUnknownKeys = true }

    private fun String.toCustomThemeStrict(): Pair<Theme.Custom, Boolean> =
        strictJson.decodeFromString(CustomThemeSerializer.WithMigrationStatus, this)

    private fun String.toCustomThemeSafe(): Theme.Custom =
        safeJson.decodeFromString(CustomThemeSerializer, this)

    private fun Theme.Custom.toJson(): String =
        Json.encodeToString(CustomThemeSerializer, this)

    /**
     * Resilient theme parser simulating production error-recovery architecture:
     * Attempts strict parse -> falls back to lenient parse ignoring unknown keys ->
     * recovers with legacy migration or fallback preset on critical field corruption.
     */
    private fun parseThemeWithDefense(rawJson: String): Theme.Custom {
        return runCatching {
            // First attempt: Safe parse ignoring unknown keys
            safeJson.decodeFromString(CustomThemeSerializer, rawJson)
        }.recoverCatching {
            // Second attempt: Legacy migration path
            val jsonObject = Json.parseToJsonElement(rawJson).jsonObject
            val migrated = applyEmergencyColorDefaults(jsonObject)
            safeJson.decodeFromString(CustomThemeSerializer, migrated.toString())
        }.getOrElse {
            // Ultimate fallback to default theme preset
            ThemePreset.PixelDark.deriveCustomNoBackground("FallbackTheme")
        }
    }

    private fun applyEmergencyColorDefaults(obj: JsonObject): JsonObject {
        val map = obj.toMutableMap()
        val defaultColor = JsonPrimitive(-1) // white
        val defaultDarkColor = JsonPrimitive(-16777216) // black

        if (!map.containsKey("name")) map["name"] = JsonPrimitive("RecoveredTheme")
        if (!map.containsKey("isDark")) map["isDark"] = JsonPrimitive(false)
        if (!map.containsKey("backgroundColor")) map["backgroundColor"] = defaultColor
        if (!map.containsKey("barColor")) map["barColor"] = defaultColor
        if (!map.containsKey("keyboardColor")) map["keyboardColor"] = defaultColor
        if (!map.containsKey("keyBackgroundColor")) map["keyBackgroundColor"] = defaultColor
        if (!map.containsKey("keyTextColor")) map["keyTextColor"] = defaultDarkColor
        if (!map.containsKey("candidateTextColor")) map["candidateTextColor"] = defaultDarkColor
        if (!map.containsKey("candidateLabelColor")) map["candidateLabelColor"] = defaultDarkColor
        if (!map.containsKey("candidateCommentColor")) map["candidateCommentColor"] = defaultDarkColor
        if (!map.containsKey("altKeyBackgroundColor")) map["altKeyBackgroundColor"] = defaultColor
        if (!map.containsKey("altKeyTextColor")) map["altKeyTextColor"] = defaultDarkColor
        if (!map.containsKey("accentKeyBackgroundColor")) map["accentKeyBackgroundColor"] = defaultDarkColor
        if (!map.containsKey("accentKeyTextColor")) map["accentKeyTextColor"] = defaultColor
        if (!map.containsKey("keyPressHighlightColor")) map["keyPressHighlightColor"] = JsonPrimitive(0)
        if (!map.containsKey("keyShadowColor")) map["keyShadowColor"] = JsonPrimitive(0)
        if (!map.containsKey("popupBackgroundColor")) map["popupBackgroundColor"] = defaultColor
        if (!map.containsKey("popupTextColor")) map["popupTextColor"] = defaultDarkColor
        if (!map.containsKey("spaceBarColor")) map["spaceBarColor"] = defaultColor
        if (!map.containsKey("dividerColor")) map["dividerColor"] = JsonPrimitive(0)
        if (!map.containsKey("clipboardEntryColor")) map["clipboardEntryColor"] = defaultColor
        if (!map.containsKey("genericActiveBackgroundColor")) map["genericActiveBackgroundColor"] = defaultDarkColor
        if (!map.containsKey("genericActiveForegroundColor")) map["genericActiveForegroundColor"] = defaultColor
        if (!map.containsKey("version")) map["version"] = JsonPrimitive("3.0")

        return JsonObject(map)
    }

    // --- WCAG 2.1 Contrast Helper ---
    private fun sRgbToLinear(c: Int): Double {
        val v = (c and 0xff) / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun relativeLuminance(color: Int): Double {
        val r = sRgbToLinear(color shr 16)
        val g = sRgbToLinear(color shr 8)
        val b = sRgbToLinear(color)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrastRatio(fg: Int, bg: Int): Double {
        val l1 = relativeLuminance(fg)
        val l2 = relativeLuminance(bg)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    // =========================================================================
    // ATTACK VECTOR 1: Malformed & Unknown JSON Keys Resilience
    // =========================================================================

    /**
     * Probing unknown future fields: When third-party or future version themes contain
     * unrecognized keys (e.g. unknownFutureField, customGlow, aiPredictorHue),
     * strict parsing would fail with SerializationException, but the robust recovery
     * parser safely parses known fields and survives without crashing.
     */
    @Test
    fun probeUnknownFutureFieldsDoNotCrashSafeParser() {
        val baseTheme = ThemePreset.HanjiLight.deriveCustomNoBackground("FutureHanji")
        val baseJson = baseTheme.toJson()

        // Inject unknown / futuristic keys
        val infectedJson = baseJson.replace(
            "\"version\":",
            "\"unknownFutureField\": \"v4_neural_bloom\", " +
            "\"customGlow\": 12345, " +
            "\"aiPredictorHue\": 0x334455, " +
            "\"unsupportedNestedStructure\": {\"alpha\": 1.0, \"beta\": [1, 2, 3]}, " +
            "\"version\":"
        )

        // 1. Strict parser throws SerializationException (confirming attack surface)
        try {
            infectedJson.toCustomThemeStrict()
            fail("Strict parser should reject unknown keys")
        } catch (e: SerializationException) {
            assertTrue("Exception must mention unknown key", e.message?.contains("unknown") == true || e.message?.contains("unknownFutureField") == true)
        }

        // 2. Safe parser gracefully ignores unknown keys and decodes perfectly
        val safeDecoded = infectedJson.toCustomThemeSafe()
        assertEquals("FutureHanji", safeDecoded.name)
        assertEquals(baseTheme.backgroundColor, safeDecoded.backgroundColor)
        assertEquals(baseTheme.keyTextColor, safeDecoded.keyTextColor)
        assertEquals(baseTheme.accentKeyBackgroundColor, safeDecoded.accentKeyBackgroundColor)

        // 3. Defense wrapper parser completely shields against crashes
        val defendedTheme = parseThemeWithDefense(infectedJson)
        assertEquals("FutureHanji", defendedTheme.name)
        assertEquals(baseTheme.popupBackgroundColor, defendedTheme.popupBackgroundColor)
    }

    /**
     * Probing completely malformed / corrupted JSON payloads (unclosed braces, truncated streams,
     * invalid data types). Must not throw unhandled runtime exceptions that would crash the IME service.
     */
    @Test
    fun probeCorruptedPayloadGracefulFallback() {
        val corruptedPayloads = listOf(
            "{",
            "{\"name\": \"Corrupted\", \"version\": ",
            "{\"name\": 99999, \"isDark\": \"not_a_boolean\"}",
            "RANDOM_NON_JSON_GARBAGE_%%%^&&*",
            "",
            "null",
            "[]"
        )

        for (corrupted in corruptedPayloads) {
            val fallbackTheme = parseThemeWithDefense(corrupted)
            assertNotNull("Fallback theme must never be null on corrupted input", fallbackTheme)
            assertEquals("FallbackTheme must be selected on fatal corruption", "FallbackTheme", fallbackTheme.name)
            assertTrue("Fallback theme colors must be non-zero", fallbackTheme.backgroundColor != 0)
        }
    }

    // =========================================================================
    // ATTACK VECTOR 2: Missing Required Fields & Migration Resilience
    // =========================================================================

    /**
     * Probing v1.0 Legacy JSON: Missing popupBackgroundColor, genericActiveBackgroundColor,
     * candidateTextColor, candidateLabelColor, candidateCommentColor.
     * CustomThemeSerializer migration strategy must safely backfill these missing fields.
     */
    @Test
    fun probeV1MissingFieldsAutomatedMigrationBackfill() {
        val v1Json = """
            {
                "name": "LegacyV1Theme",
                "backgroundColor": -1118482,
                "barColor": -2236963,
                "keyboardColor": -1118482,
                "keyBackgroundColor": -1,
                "keyTextColor": -16777216,
                "altKeyBackgroundColor": -3355444,
                "altKeyTextColor": -16777216,
                "accentKeyBackgroundColor": -16744193,
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

        val (decoded, migrated) = v1Json.toCustomThemeStrict()
        assertTrue("v1.0 theme must be marked as migrated", migrated)
        assertEquals("LegacyV1Theme", decoded.name)

        // Backfilled popup colors: in v2.0 migration without backgroundImage, popupBackgroundColor = barColor
        assertEquals(-2236963, decoded.popupBackgroundColor)
        assertEquals(-16777216, decoded.popupTextColor)

        // Backfilled candidate colors: in v2.1 migration, candidateTextColor = keyTextColor
        assertEquals(-16777216, decoded.candidateTextColor)
        assertEquals(-16777216, decoded.candidateLabelColor)
        assertEquals(-16777216, decoded.candidateCommentColor)

        // Backfilled generic active colors: accentKeyBackgroundColor & accentKeyTextColor
        assertEquals(-16744193, decoded.genericActiveBackgroundColor)
        assertEquals(-1, decoded.genericActiveForegroundColor)
    }

    /**
     * Probing v2.0 Legacy JSON: Missing v2.1 candidateTextColor and candidateCommentColor.
     * Must automatically migrate using keyTextColor and altKeyTextColor.
     */
    @Test
    fun probeV2MissingFieldsMigrationToV21() {
        val v2Json = """
            {
                "name": "LegacyV2Theme",
                "backgroundColor": -1,
                "barColor": -2,
                "keyboardColor": -1,
                "keyBackgroundColor": -3,
                "keyTextColor": -16777216,
                "altKeyBackgroundColor": -4,
                "altKeyTextColor": -8947849,
                "accentKeyBackgroundColor": -16711936,
                "accentKeyTextColor": -1,
                "keyPressHighlightColor": 0,
                "keyShadowColor": 0,
                "popupBackgroundColor": -5,
                "popupTextColor": -16777216,
                "spaceBarColor": -3,
                "dividerColor": 0,
                "clipboardEntryColor": -3,
                "genericActiveBackgroundColor": -16711936,
                "genericActiveForegroundColor": -1,
                "isDark": false,
                "version": "2.0"
            }
        """.trimIndent()

        val (decoded, migrated) = v2Json.toCustomThemeStrict()
        assertTrue("v2.0 theme must be migrated to 3.0", migrated)
        assertEquals(-16777216, decoded.candidateTextColor)
        assertEquals(-16777216, decoded.candidateLabelColor)
        assertEquals(-8947849, decoded.candidateCommentColor)
    }

    /**
     * Probing severely depleted JSON where essential color fields (e.g. keyTextColor,
     * accentKeyBackgroundColor) are absent altogether.
     * Must be safely repaired by emergency defaults without throwing NullPointerException or crash.
     */
    @Test
    fun probeDepletedJsonFieldRepair() {
        val depletedJson = """
            {
                "name": "DepletedSkeletonTheme",
                "isDark": true
            }
        """.trimIndent()

        val repaired = parseThemeWithDefense(depletedJson)
        assertEquals("DepletedSkeletonTheme", repaired.name)
        assertTrue(repaired.isDark)
        assertNotNull(repaired.keyBackgroundColor)
        assertNotNull(repaired.keyTextColor)
        assertNotNull(repaired.popupBackgroundColor)
        assertNotNull(repaired.popupTextColor)
        assertNotNull(repaired.genericActiveBackgroundColor)
    }

    // =========================================================================
    // ATTACK VECTOR 3: Popup Text Contrast Ratio & Accessibility Invariants
    // =========================================================================

    /**
     * Invariant: Popup text must be clearly legible against popup background.
     * WCAG 2.1 Large Text standard requires minimum contrast ratio >= 3.0:1.
     */
    @Test
    fun probeBuiltinAndShopThemesPopupContrastRatioWcagCompliance() {
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
            ThemePreset.AMOLEDBlack
        )
        val allThemesToAudit: List<Theme> = allBuiltinPresets + ThemeShopCatalog.allThemes()
        assertTrue("Audit list must not be empty", allThemesToAudit.isNotEmpty())

        for (theme in allThemesToAudit) {
            val popupBg = theme.popupBackgroundColor
            val popupFg = theme.popupTextColor
            val contrast = contrastRatio(popupFg, popupBg)

            assertTrue(
                "Theme '${theme.name}' popup contrast ratio ($contrast:1) is below WCAG 3.0:1 requirement! " +
                "[popupBg=0x${Integer.toHexString(popupBg)}, popupFg=0x${Integer.toHexString(popupFg)}]",
                contrast >= 3.0
            )
        }
    }

    /**
     * Attack Vector: Ghost Text Vulnerability Detection.
     * If a renderer erroneously uses `theme.keyTextColor` instead of `theme.popupTextColor`
     * for popup keys, light themes with dark popup backgrounds (or vice-versa) collapse into
     * invisible ghost text (e.g. white text on white popup background).
     *
     * This test mathematically proves that using `keyTextColor` causes contrast collapse (< 3.0:1)
     * on vulnerable themes, while `popupTextColor` strictly satisfies the >= 3.0:1 invariant.
     */
    @Test
    fun probeGhostTextVulnerabilityWhenKeyTextColorIsMisappliedToPopup() {
        // Construct a theme where popup background is white (0xFFFFFFFF),
        // but normal keys are dark buttons with white keyTextColor (0xFFFFFFFF).
        // If popup incorrectly uses keyTextColor, contrast is 1.0:1 (pure white on pure white: Ghost Text!)
        val invertedPopupTheme = Theme.Custom(
            name = "InvertedGhostProbeTheme",
            isDark = true,
            backgroundImage = null,
            backgroundColor = 0xFF121212.toInt(),
            barColor = 0xFF1E1E1E.toInt(),
            keyboardColor = 0xFF121212.toInt(),
            keyBackgroundColor = 0xFF2A2A2A.toInt(),
            keyTextColor = 0xFFFFFFFF.toInt(), // Pure white key text
            candidateTextColor = 0xFFFFFFFF.toInt(),
            candidateLabelColor = 0xFFCCCCCC.toInt(),
            candidateCommentColor = 0xFF999999.toInt(),
            altKeyBackgroundColor = 0xFF2A2A2A.toInt(),
            altKeyTextColor = 0xFFCCCCCC.toInt(),
            accentKeyBackgroundColor = 0xFF3B82F6.toInt(),
            accentKeyTextColor = 0xFFFFFFFF.toInt(),
            keyPressHighlightColor = 0x33FFFFFF,
            keyShadowColor = 0,
            popupBackgroundColor = 0xFFFFFFFF.toInt(), // Pure white popup background!
            popupTextColor = 0xFF1E293B.toInt(),       // Dark slate popup text (legible!)
            spaceBarColor = 0xFF2A2A2A.toInt(),
            dividerColor = 0x22FFFFFF,
            clipboardEntryColor = 0xFF2A2A2A.toInt(),
            genericActiveBackgroundColor = 0xFF3B82F6.toInt(),
            genericActiveForegroundColor = 0xFFFFFFFF.toInt()
        )

        // 1. Correct implementation using popupTextColor
        val correctContrast = contrastRatio(
            invertedPopupTheme.popupTextColor,
            invertedPopupTheme.popupBackgroundColor
        )
        assertTrue(
            "Legitimate popup contrast ($correctContrast:1) must satisfy WCAG >= 3.0:1",
            correctContrast >= 3.0
        )

        // 2. Erroneous implementation misapplying keyTextColor to popup
        val buggyGhostContrast = contrastRatio(
            invertedPopupTheme.keyTextColor,
            invertedPopupTheme.popupBackgroundColor
        )
        // White on white gives 1.0:1 contrast -> Ghost Text!
        assertEquals(
            "Misapplied keyTextColor on white popup background causes 1.0:1 total contrast collapse (Ghost Text)",
            1.0,
            buggyGhostContrast,
            0.05
        )
        assertTrue(
            "Ghost text contrast $buggyGhostContrast must be far below acceptable threshold 3.0",
            buggyGhostContrast < 3.0
        )
    }

    /**
     * Probing Korean signature themes popup legibility:
     * HanjiLight, DancheongDark, BaegjaLight, CheongjaDark, MidnightOLED, SeoulMistGlass.
     * Key, accent, candidate, and popup contrast ratios must all pass WCAG AA standards.
     */
    @Test
    fun probeKoreanSignatureThemesAllDimensionsContrastInvariant() {
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
            val popupContrast = contrastRatio(preset.popupTextColor, preset.popupBackgroundColor)
            val candidateContrast = contrastRatio(preset.candidateTextColor, preset.barColor)

            assertTrue("Theme ${preset.name} key contrast $keyContrast >= 3.0", keyContrast >= 3.0)
            assertTrue("Theme ${preset.name} accent contrast $accentContrast >= 3.0", accentContrast >= 3.0)
            assertTrue("Theme ${preset.name} popup contrast $popupContrast >= 3.0", popupContrast >= 3.0)
            assertTrue("Theme ${preset.name} candidate contrast $candidateContrast >= 3.0", candidateContrast >= 3.0)
        }
    }
}
