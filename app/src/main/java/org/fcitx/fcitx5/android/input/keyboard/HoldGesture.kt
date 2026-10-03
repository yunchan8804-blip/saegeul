/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * State of one [KeyDef.Behavior.Hold] key. The end action is only sent for a hold whose start
 * action was sent, so a plain tap or a press that slid away before the long press ends silently.
 */
internal class HoldGesture(private val behavior: KeyDef.Behavior.Hold) {
    private var held = false

    fun onLongPress(): KeyAction {
        held = true
        return behavior.start
    }

    /** Called when the touch is lifted or cancelled; null when no hold was running. */
    fun onRelease(): KeyAction? {
        if (!held) return null
        held = false
        return behavior.end
    }
}
