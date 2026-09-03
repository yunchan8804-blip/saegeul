/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deep E2E TDD Test Suite for Keyboard Buffer Compatibility Engine.
 * Tests .NET MAUI / Unity / Termux / VNC package detection, double-commit suppression,
 * composing span isolation, password safety, and multiline buffer transitions.
 */
class KeyboardBufferCompatibilityDeepEngineTest {

    private lateinit var controller: BufferedInputController
    private val hangulIme = InputMethodEntry(
        uniqueName = "hangul",
        name = "Hangul",
        icon = "fcitx-hangul",
        nativeName = "",
        label = "한",
        languageCode = "ko",
        addon = "hangul",
        isConfigurable = true
    )

    @Before
    fun setUp() {
        controller = BufferedInputController()
    }

    @Test
    fun testKnownDotNetAndRemoteCompatibilityTargets() {
        val testCases = listOf(
            "com.microsoft.maui.sample" to true,
            "net.dot.android.testapp" to true,
            "com.unity3d.player.UnityPlayerActivity" to true,
            "com.valvesoftware.steamlink" to true,
            "com.termux" to true,
            "com.realvnc.viewer.android" to true,
            "com.teamviewer.teamviewer.market.mobile" to true,
            "com.anydesk.anydeskandroid" to true,
            "com.parsecgaming.parsec" to true,
            "com.moonlightstream.moonlight" to true,
            "com.kakao.talk" to false,
            "com.google.android.apps.messaging" to false,
            "org.telegram.messenger" to false
        )

        testCases.forEach { (pkg, expected) ->
            assertEquals(
                "Package $pkg compatibility detection mismatch",
                expected,
                BufferedHangulMode.isKnownCompatibilityTarget(pkg)
            )
        }
    }

    @Test
    fun testBufferCaptureSnapshotAndClearCycle() {
        assertTrue(controller.isEmpty)
        assertEquals("", controller.prefix)

        controller.capture("안")
        assertFalse(controller.isEmpty)
        assertEquals("안", controller.prefix)
        assertEquals("안녕", controller.snapshot("녕"))

        controller.capture("녕")
        assertEquals("안녕", controller.prefix)
        assertEquals("안녕하세요", controller.snapshot("하세요"))

        controller.clear()
        assertTrue(controller.isEmpty)
        assertEquals("", controller.prefix)
    }

    @Test
    fun testDeleteLastCodePointWithSurrogatePairsAndHangul() {
        controller.capture("새글😀")
        assertEquals("새글😀", controller.prefix)

        assertTrue(controller.deleteLastCodePoint()) // deletes surrogate pair emoji 😀
        assertEquals("새글", controller.prefix)

        assertTrue(controller.deleteLastCodePoint()) // deletes '글'
        assertEquals("새", controller.prefix)

        assertTrue(controller.deleteLastCodePoint()) // deletes '새'
        assertTrue(controller.isEmpty)

        assertFalse(controller.deleteLastCodePoint()) // empty buffer returns false
    }

    @Test
    fun testCapabilityFlagsTransformationForCompatibility() {
        val normalFlags = CapabilityFlags(
            CapabilityFlag.Preedit,
            CapabilityFlag.ClientUnfocusCommit,
            CapabilityFlag.SurroundingText
        )

        // When buffered mode is active, Preedit capability must be stripped so the engine keeps composing internal
        val effective = BufferedHangulMode.effectiveCapabilities(normalFlags, enabled = true, hangulIme)
        assertFalse(effective.has(CapabilityFlag.Preedit))
        assertTrue(effective.has(CapabilityFlag.ClientUnfocusCommit))
        assertTrue(effective.has(CapabilityFlag.SurroundingText))

        // When buffered mode is inactive, Preedit capability is preserved
        val inactiveEffective = BufferedHangulMode.effectiveCapabilities(normalFlags, enabled = false, hangulIme)
        assertTrue(inactiveEffective.has(CapabilityFlag.Preedit))
    }

    @Test
    fun testSecurityIsolationForPasswordAndSensitiveFields() {
        val passwordFlags = CapabilityFlags(CapabilityFlag.Password)
        val sensitiveFlags = CapabilityFlags(CapabilityFlag.Sensitive)
        val plainFlags = CapabilityFlags(CapabilityFlag.Multiline)

        assertTrue(BufferedHangulMode.mustAvoidClipboard(passwordFlags))
        assertTrue(BufferedHangulMode.mustAvoidClipboard(sensitiveFlags))
        assertFalse(BufferedHangulMode.mustAvoidClipboard(plainFlags))
    }

    @Test
    fun testBufferDoubleCommitSuppressionSequence() {
        // Simulates rapid typing with intermittent commitments
        val testWords = listOf("대한민국", "만세", "새로운", "글자", "입력기")
        testWords.forEach { word ->
            controller.clear()
            word.forEach { char ->
                controller.capture(char.toString())
            }
            assertEquals(word, controller.prefix)
            assertEquals(word, controller.snapshot())
            controller.clear()
            assertTrue(controller.isEmpty)
        }
    }
}
