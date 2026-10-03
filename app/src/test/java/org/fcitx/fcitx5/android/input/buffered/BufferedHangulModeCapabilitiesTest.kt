/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.buffered

import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferedHangulModeCapabilitiesTest {

    private val hangul = InputMethodEntry(
        uniqueName = "hangul",
        name = "Hangul",
        icon = "fcitx-hangul",
        nativeName = "",
        label = "한",
        languageCode = "ko",
        addon = "hangul",
        isConfigurable = true
    )

    private val english = InputMethodEntry(
        uniqueName = "keyboard-us",
        name = "English",
        icon = "fcitx-keyboard-us",
        nativeName = "",
        label = "En",
        languageCode = "en",
        addon = "androidkeyboard",
        isConfigurable = false
    )

    private val remote = "com.microsoft.rdc.androidx"
    private val ordinary = "com.example.notes"

    private val input = CapabilityFlags(
        CapabilityFlag.Preedit,
        CapabilityFlag.ClientUnfocusCommit,
        CapabilityFlag.SurroundingText
    )

    private fun effective(ime: InputMethodEntry, enabled: Boolean, packageName: String?) =
        BufferedHangulMode.effectiveCapabilities(input, enabled, ime, packageName)

    @Test
    fun hangulWithCompatibilityOnDropsOnlyPreedit() {
        listOf(ordinary, remote, null).forEach { pkg ->
            val result = effective(hangul, enabled = true, packageName = pkg)
            assertFalse(result.has(CapabilityFlag.Preedit))
            assertFalse(result.has(CapabilityFlag.NoSpellCheck))
            assertTrue(result.has(CapabilityFlag.ClientUnfocusCommit))
            assertTrue(result.has(CapabilityFlag.SurroundingText))
        }
    }

    @Test
    fun hangulWithCompatibilityOffIsUntouchedEvenInRemoteApps() {
        listOf(ordinary, remote, null).forEach { pkg ->
            assertEquals(input, effective(hangul, enabled = false, packageName = pkg))
        }
    }

    @Test
    fun englishWithCompatibilityOnDropsPreeditAndDisablesWordHint() {
        listOf(ordinary, remote, null).forEach { pkg ->
            val result = effective(english, enabled = true, packageName = pkg)
            assertFalse(result.has(CapabilityFlag.Preedit))
            assertTrue(result.has(CapabilityFlag.NoSpellCheck))
            assertTrue(result.has(CapabilityFlag.ClientUnfocusCommit))
            assertTrue(result.has(CapabilityFlag.SurroundingText))
        }
    }

    @Test
    fun englishInRemoteAppDropsPreeditAndDisablesWordHintWithoutCompatibility() {
        val result = effective(english, enabled = false, packageName = remote)
        assertFalse(result.has(CapabilityFlag.Preedit))
        assertTrue(result.has(CapabilityFlag.NoSpellCheck))
        assertTrue(result.has(CapabilityFlag.ClientUnfocusCommit))
        assertTrue(result.has(CapabilityFlag.SurroundingText))
    }

    @Test
    fun englishInOrdinaryAppWithoutCompatibilityIsUntouched() {
        assertEquals(input, effective(english, enabled = false, packageName = ordinary))
        assertEquals(input, effective(english, enabled = false, packageName = null))
    }

    @Test
    fun remotePackagesMatchExactly() {
        assertTrue(RemoteEditorPackages.isRemote("com.termux"))
        assertTrue(RemoteEditorPackages.isRemote("com.iiordanov.bVNC"))
        assertTrue(RemoteEditorPackages.isRemote("org.connectbot"))
        assertFalse(RemoteEditorPackages.isRemote("com.termux.api"))
        assertFalse(RemoteEditorPackages.isRemote("com.microsoft.rdc"))
        assertFalse(RemoteEditorPackages.isRemote("com.iiordanov.bvnc"))
        assertFalse(RemoteEditorPackages.isRemote(null))
        assertFalse(RemoteEditorPackages.isRemote(""))
    }
}
