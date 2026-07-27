/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualKeyboardResumeGateTest {
    @Test
    fun settingsRoundTripForcesExactlyOneSoftwareKeyboardStart() {
        val gate = VirtualKeyboardResumeGate()

        gate.request("com.example.editor", 7, 1)

        assertTrue(gate.consume("com.example.editor", 7, 1))
        assertFalse(gate.consume("com.example.editor", 7, 1))
    }

    @Test
    fun repeatedRequestsRemainOneShot() {
        val gate = VirtualKeyboardResumeGate()

        gate.request("com.example.editor", 7, 1)
        gate.request("com.example.editor", 7, 1)

        assertTrue(gate.consume("com.example.editor", 7, 1))
        assertFalse(gate.consume("com.example.editor", 7, 1))
    }

    @Test
    fun settingsEditorDoesNotConsumeOriginalEditorResume() {
        val gate = VirtualKeyboardResumeGate()
        gate.request("com.example.editor", 7, 1)

        assertFalse(gate.consume("org.fcitx.fcitx5.android.debug", 42, 129))
        assertTrue(gate.consume("com.example.editor", 7, 1))
    }
}
