/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

/**
 * Restores the software keyboard exactly once after an IME-owned settings activity interrupts
 * the current input session. Persistent physical-keyboard policy remains untouched.
 */
internal class VirtualKeyboardResumeGate {
    private data class Target(
        val packageName: String?,
        val fieldId: Int,
        val inputType: Int
    )

    private var target: Target? = null

    fun request(packageName: String?, fieldId: Int, inputType: Int) {
        target = Target(packageName, fieldId, inputType)
    }

    fun consume(packageName: String?, fieldId: Int, inputType: Int): Boolean {
        val expected = target ?: return false
        if (expected != Target(packageName, fieldId, inputType)) return false
        target = null
        return true
    }
}
