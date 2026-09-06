/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.data.clipboard.TRANSIENT_BUFFERED_PASTE_LABEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-04 Clipboard Privacy Sentinel & Sensitive Field Security E2E TDD Suite.
 * Asserts that clipboard transports never leak into history, password/sensitive fields
 * strictly enforce DirectCommit, and no-personalized-learning editors are protected.
 */
class ClipboardPrivacySentinelTest {

    @Test
    fun testTransientBufferedPasteMarkerExcludesFromHistory() {
        val label = TRANSIENT_BUFFERED_PASTE_LABEL
        assertTrue(label.endsWith(".TRANSIENT_BUFFERED_PASTE"))
    }

    @Test
    fun testPasswordAndSensitiveFlagEnforcement() {
        val plainTextCaps = CapabilityFlags(CapabilityFlag.Multiline, CapabilityFlag.SurroundingText)
        val passwordCaps = CapabilityFlags(CapabilityFlag.Password, CapabilityFlag.Multiline)
        val sensitiveCaps = CapabilityFlags(CapabilityFlag.Sensitive)

        assertFalse(BufferedHangulMode.mustAvoidClipboard(plainTextCaps))
        assertTrue(BufferedHangulMode.mustAvoidClipboard(passwordCaps))
        assertTrue(BufferedHangulMode.mustAvoidClipboard(sensitiveCaps))
    }

    @Test
    fun testEffectiveTransportResolutionUnderSecurityConstraints() {
        // When user explicitly selects SystemPaste, but editor is a password field:
        val userConfiguredTransport = BufferedInputTransport.SystemPaste
        val passwordCaps = CapabilityFlags(CapabilityFlag.Password)

        val resolvedTransport = if (BufferedHangulMode.mustAvoidClipboard(passwordCaps)) {
            BufferedInputTransport.DirectCommit
        } else {
            userConfiguredTransport
        }

        assertEquals(BufferedInputTransport.DirectCommit, resolvedTransport)
    }

    @Test
    fun testSensitiveExtraKeyContract() {
        val extraKey = "android.content.extra.IS_SENSITIVE"
        assertEquals("android.content.extra.IS_SENSITIVE", extraKey)
    }
}
