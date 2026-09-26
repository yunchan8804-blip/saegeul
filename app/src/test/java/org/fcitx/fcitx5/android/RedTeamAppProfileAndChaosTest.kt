/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.BufferedInputTransport
import org.fcitx.fcitx5.android.input.keyboard.MobileHangulLayout
import org.fcitx.fcitx5.android.input.profile.AppFeaturePolicy
import org.fcitx.fcitx5.android.input.profile.AppKeyboardGlobalDefaults
import org.fcitx.fcitx5.android.input.profile.AppKeyboardProfile
import org.fcitx.fcitx5.android.input.profile.AppKeyboardProfileResolver
import org.fcitx.fcitx5.android.input.profile.decodeAppKeyboardProfiles
import org.fcitx.fcitx5.android.input.profile.encodeAppKeyboardProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Red Team Adversarial Unit Tests: AppKeyboardProfile fault tolerance,
 * case insensitivity, blank package defense, and concurrent chaos stress.
 */
class RedTeamAppProfileAndChaosTest {

    private val defaults = AppKeyboardGlobalDefaults(
        mobileHangulLayout = MobileHangulLayout.Physical,
        themeName = "DefaultTheme",
        toolbarExpanded = true,
        bufferedInputTransport = BufferedInputTransport.SystemPaste,
        offlineMode = false,
        networkAllowed = true,
        aiAllowed = true
    )

    /**
     * Test 1: Corrupted array element does not discard entire profile list.
     * When malformed elements (primitive string, number) exist within the "profiles" array,
     * the parser must gracefully skip them and recover valid profile elements.
     */
    @Test
    fun `corrupted array element does not discard entire profile list`() {
        val json = """
            {
              "version": 1,
              "profiles": [
                {"package": "com.good.chat", "ai": "Allow"},
                "corrupted_primitive_string",
                12345,
                {"package": "com.another.good", "theme": "Dark"}
              ]
            }
        """.trimIndent()

        val decoded = decodeAppKeyboardProfiles(json.toByteArray(Charsets.UTF_8))

        assertEquals("Valid profiles must not be discarded due to corrupted array elements", 2, decoded.size)
        val packageMap = decoded.associateBy { it.packageName }
        val goodChat = packageMap["com.good.chat"]
        val anotherGood = packageMap["com.another.good"]

        assertNotNull("com.good.chat profile must be recovered", goodChat)
        assertEquals(AppFeaturePolicy.Allow, goodChat?.aiPolicy)

        assertNotNull("com.another.good profile must be recovered", anotherGood)
        assertEquals("Dark", anotherGood?.themeName)
    }

    /**
     * Test 2: Null or blank package name never matches empty package profile.
     * An empty package name profile with Block policy must not intercept queries
     * for null or whitespace package names, preserving default permissions.
     */
    @Test
    fun `null or blank package name never matches empty package profile`() {
        val emptyPackageProfile = AppKeyboardProfile(
            packageName = "",
            aiPolicy = AppFeaturePolicy.Block
        )
        val profiles = listOf(emptyPackageProfile)

        val effectiveNull = AppKeyboardProfileResolver.resolve(
            packageName = null,
            profiles = profiles,
            defaults = defaults,
            privateEditor = false
        )
        assertNull("effective.source must be null when package name is null", effectiveNull.source)
        assertTrue("AI permission must remain allowed (defaults) when package name is null", effectiveNull.allowsAi)

        val effectiveBlank = AppKeyboardProfileResolver.resolve(
            packageName = "   ",
            profiles = profiles,
            defaults = defaults,
            privateEditor = false
        )
        assertNull("effective.source must be null when package name is blank", effectiveBlank.source)
        assertTrue("AI permission must remain allowed (defaults) when package name is blank", effectiveBlank.allowsAi)
    }

    /**
     * Test 3: Package name case insensitivity and whitespace normalization.
     * Package name resolution must be case-insensitive and trim surrounding whitespaces.
     * In addition, encoding must deduplicate package names regardless of casing.
     */
    @Test
    fun `package name case insensitivity and whitespace normalization`() {
        val registered = AppKeyboardProfile(
            packageName = "com.example.chat",
            aiPolicy = AppFeaturePolicy.Allow
        )
        val profiles = listOf(registered)

        // Upper case query
        val effectiveUpper = AppKeyboardProfileResolver.resolve(
            packageName = "COM.EXAMPLE.CHAT",
            profiles = profiles,
            defaults = defaults,
            privateEditor = false
        )
        assertEquals("com.example.chat", effectiveUpper.source?.packageName)
        assertTrue(effectiveUpper.allowsAi)

        // Whitespace surrounding query
        val effectiveWhitespace = AppKeyboardProfileResolver.resolve(
            packageName = "  com.example.chat  ",
            profiles = profiles,
            defaults = defaults,
            privateEditor = false
        )
        assertEquals("com.example.chat", effectiveWhitespace.source?.packageName)
        assertTrue(effectiveWhitespace.allowsAi)

        // Case-variant deduplication during encoding
        val profileLower = AppKeyboardProfile("com.example.app", themeName = "ThemeLower")
        val profileUpper = AppKeyboardProfile("Com.Example.App", themeName = "ThemeUpper")
        val encoded = encodeAppKeyboardProfiles(listOf(profileLower, profileUpper))
        val decoded = decodeAppKeyboardProfiles(encoded)

        assertEquals("Profiles differing only in case must be unified to a single profile", 1, decoded.size)
    }

    /**
     * Test 4: Persona attribute length cap and blank trimming.
     * Persona string must be trimmed, blank strings must become null,
     * and long strings must be capped at 64 characters.
     */
    @Test
    fun `persona attribute length cap and blank trimming`() {
        // Trimming whitespace
        val trimmedProfile = AppKeyboardProfile(
            packageName = "com.test.app",
            persona = "   work_persona   "
        ).normalized()
        assertEquals("work_persona", trimmedProfile.persona)

        // Blank becomes null
        val blankProfile = AppKeyboardProfile(
            packageName = "com.test.app",
            persona = "   "
        ).normalized()
        assertNull("Blank persona must be normalized to null", blankProfile.persona)

        // 1000 chars capped at 64
        val largePersona = "a".repeat(1000)
        val cappedProfile = AppKeyboardProfile(
            packageName = "com.test.app",
            persona = largePersona
        ).normalized()
        assertEquals("Persona must be capped at 64 chars", 64, cappedProfile.persona?.length)
        assertEquals("a".repeat(64), cappedProfile.persona)
    }

    /**
     * Test 5: Unknown nested JSON structures in profile do not crash parser.
     * Profile containing arbitrary nested JSON objects or arrays must parse safely.
     */
    @Test
    fun `unknown nested json structures in profile do not crash parser`() {
        val json = """
            {
              "version": 1,
              "profiles": [
                {
                  "package": "com.test.app",
                  "extra": {"a": [1, 2, 3]},
                  "theme": "Matrix"
                }
              ]
            }
        """.trimIndent()

        val decoded = decodeAppKeyboardProfiles(json.toByteArray(Charsets.UTF_8))
        assertEquals(1, decoded.size)
        assertEquals("com.test.app", decoded.first().packageName)
        assertEquals("Matrix", decoded.first().themeName)
    }

    /**
     * Test 6: Profile store concurrent read write chaos stress.
     * 10 threads/coroutines performing 500 interleaved encode and decode operations
     * must complete without deadlock or unhandled exceptions.
     */
    @Test
    fun `profile store concurrent read write chaos stress`() {
        val threadCount = 10
        val iterationCount = 500
        val executor = Executors.newFixedThreadPool(threadCount)

        val sampleThemes = listOf("Dark", "Light", "Hanji", "Matrix", null)
        val samplePolicies = listOf(AppFeaturePolicy.Inherit, AppFeaturePolicy.Allow, AppFeaturePolicy.Block)

        try {
            val tasks = (1..iterationCount).map { i ->
                Callable<Boolean> {
                    val pkgIndex = Random.nextInt(1, 20)
                    val profile = AppKeyboardProfile(
                        packageName = "com.chaos.app$pkgIndex",
                        themeName = sampleThemes[Random.nextInt(sampleThemes.size)],
                        networkPolicy = samplePolicies[Random.nextInt(samplePolicies.size)],
                        aiPolicy = samplePolicies[Random.nextInt(samplePolicies.size)],
                        persona = if (Random.nextBoolean()) "persona_$pkgIndex" else null
                    )

                    // Interleaved encode and decode
                    val encoded = encodeAppKeyboardProfiles(listOf(profile))
                    val decoded = decodeAppKeyboardProfiles(encoded)

                    if (profile.hasOverrides) {
                        assertEquals(1, decoded.size)
                        assertEquals(profile.packageName, decoded.first().packageName)
                    } else {
                        assertEquals(0, decoded.size)
                    }
                    true
                }
            }

            val futures = executor.invokeAll(tasks, 30, TimeUnit.SECONDS)
            assertEquals("All chaos stress tasks must be submitted", iterationCount, futures.size)

            for (future in futures) {
                assertTrue("Chaos stress task must not be cancelled", !future.isCancelled)
                assertTrue("Chaos stress task must succeed", future.get())
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
