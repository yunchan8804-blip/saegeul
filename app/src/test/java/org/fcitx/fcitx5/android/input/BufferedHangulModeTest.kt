/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
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

    @Test
    fun navigationKeysSubmitBeforeForwarding() {
        listOf(
            FcitxKeyMapping.FcitxKey_Left,
            FcitxKeyMapping.FcitxKey_Right,
            FcitxKeyMapping.FcitxKey_Up,
            FcitxKeyMapping.FcitxKey_Down,
            FcitxKeyMapping.FcitxKey_Home,
            FcitxKeyMapping.FcitxKey_End,
            FcitxKeyMapping.FcitxKey_Page_Up,
            FcitxKeyMapping.FcitxKey_Page_Down
        ).forEach { sym ->
            assertTrue(BufferedHangulMode.submitsBeforeForwarding(sym, unicode = 0))
        }
    }

    @Test
    fun controlCharactersAreNeverBufferedAsText() {
        assertTrue(BufferedHangulMode.submitsBeforeForwarding(FcitxKeyMapping.FcitxKey_Tab, 0x09))
        assertTrue(BufferedHangulMode.submitsBeforeForwarding(FcitxKeyMapping.FcitxKey_Escape, 0x1B))
        assertTrue(BufferedHangulMode.submitsBeforeForwarding(FcitxKeyMapping.FcitxKey_Delete, 0x7F))
        assertTrue(BufferedHangulMode.submitsBeforeForwarding(FcitxKeyMapping.FcitxKey_KP_Enter, 0x0D))
        ((0x01..0x1F) + 0x7F).forEach { code ->
            assertTrue(BufferedHangulMode.submitsBeforeForwarding(0x01000000 + code, code))
        }
    }

    @Test
    fun returnAndBackSpaceKeepTheirOwnHandling() {
        assertFalse(BufferedHangulMode.submitsBeforeForwarding(FcitxKeyMapping.FcitxKey_Return, 0x0D))
        assertFalse(BufferedHangulMode.submitsBeforeForwarding(FcitxKeyMapping.FcitxKey_BackSpace, 0x08))
    }

    @Test
    fun printableCharactersAndOtherKeysWithoutCharacterAreNotSubmittedFirst() {
        assertFalse(BufferedHangulMode.submitsBeforeForwarding(FcitxKeyMapping.FcitxKey_a, 'a'.code))
        assertFalse(BufferedHangulMode.submitsBeforeForwarding(FcitxKeyMapping.FcitxKey_space, ' '.code))
        assertFalse(BufferedHangulMode.submitsBeforeForwarding(FcitxKeyMapping.FcitxKey_period, '.'.code))
        assertFalse(BufferedHangulMode.submitsBeforeForwarding(FcitxKeyMapping.FcitxKey_F1, 0))
        assertFalse(BufferedHangulMode.submitsBeforeForwarding(FcitxKeyMapping.FcitxKey_Shift_L, 0))
    }

    @Test
    fun verifyBufferedInputTransportEnumIntegrity() {
        assertEquals(3, BufferedInputTransport.entries.size)
        assertEquals(BufferedInputTransport.SystemPaste, BufferedInputTransport.valueOf("SystemPaste"))
        assertEquals(BufferedInputTransport.CtrlV, BufferedInputTransport.valueOf("CtrlV"))
        assertEquals(BufferedInputTransport.DirectCommit, BufferedInputTransport.valueOf("DirectCommit"))
    }
}
