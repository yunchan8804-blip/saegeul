/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.input.OneHandMode

/**
 * Toolbar one-hand mode button: a tap turns the mode off, or back on at the last side the user
 * used. [KawaiiBarComponent] keeps that side in the hidden `last_one_hand_mode_side` preference
 * so it survives keyboard restarts.
 *
 * Pure decision logic (no Android dependency) so it is unit-testable without Robolectric.
 */
internal object OneHandModeTogglePolicy {

    /** Side to remember once the mode became [mode], or null when the mode is off. */
    fun sideToRemember(mode: OneHandMode): OneHandMode? = mode.takeIf { it != OneHandMode.Off }

    /** Mode after a toolbar tap; turning on uses [lastSide], or [OneHandMode.Right] if it holds no side. */
    fun next(current: OneHandMode, lastSide: OneHandMode): OneHandMode =
        if (current == OneHandMode.Off) sideToRemember(lastSide) ?: OneHandMode.Right else OneHandMode.Off
}
