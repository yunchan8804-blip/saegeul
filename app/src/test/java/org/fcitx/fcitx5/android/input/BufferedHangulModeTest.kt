/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferedHangulModeTest {

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

    @Test
    fun onlyActivatesForEnabledHangulAddon() {
        assertTrue(BufferedHangulMode.isActive(enabled = true, hangul))
        assertFalse(BufferedHangulMode.isActive(enabled = false, hangul))
        assertFalse(
            BufferedHangulMode.isActive(
                enabled = true,
                hangul.copy(uniqueName = "keyboard-us", languageCode = "en", addon = "androidkeyboard")
            )
        )
    }

    @Test
    fun removesOnlyClientPreeditCapability() {
        val input = CapabilityFlags(
            CapabilityFlag.Preedit,
            CapabilityFlag.ClientUnfocusCommit,
            CapabilityFlag.SurroundingText
        )

        val result = BufferedHangulMode.effectiveCapabilities(input, enabled = true, hangul)

        assertFalse(result.has(CapabilityFlag.Preedit))
        assertTrue(result.has(CapabilityFlag.ClientUnfocusCommit))
        assertTrue(result.has(CapabilityFlag.SurroundingText))
        assertEquals(
            input,
            BufferedHangulMode.effectiveCapabilities(input, enabled = false, hangul)
        )
    }

    @Test
    fun avoidsClipboardForEitherPasswordOrSensitiveFields() {
        assertTrue(
            BufferedHangulMode.mustAvoidClipboard(CapabilityFlags(CapabilityFlag.Password))
        )
        assertTrue(
            BufferedHangulMode.mustAvoidClipboard(CapabilityFlags(CapabilityFlag.Sensitive))
        )
        assertFalse(
            BufferedHangulMode.mustAvoidClipboard(CapabilityFlags(CapabilityFlag.Multiline))
        )
    }
}
