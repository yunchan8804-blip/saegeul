/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/** Pure routing contract for the in-keyboard Korean surface picker. */
internal object MobileHangulSurfaceSwitcher {
    fun isAvailable(engineLayout: String?) = engineLayout == "Dubeolsik" || engineLayout == "0"

    fun target(layout: MobileHangulLayout) = when (layout) {
        MobileHangulLayout.Physical -> TextKeyboard.Name
        else -> MobileHangulKeyboard.name(layout)
    }
}
