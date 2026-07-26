/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/** Keeps the ?123 key pointed at a symbol surface, never at a resolved text surface. */
internal object KeyboardLayoutMemory {
    fun shouldRememberAsSymbolLayout(target: String): Boolean =
        target == NumberKeyboard.Name
}
