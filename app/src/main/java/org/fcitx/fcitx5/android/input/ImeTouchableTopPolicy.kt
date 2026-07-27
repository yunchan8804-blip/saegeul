/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

/** Selects the top edge that Android must treat as belonging to the IME window. */
internal object ImeTouchableTopPolicy {
    fun resolve(
        keyboardTop: Int,
        promptTop: Int,
        promptVisible: Boolean
    ): Int = if (promptVisible && promptTop in 1 until keyboardTop) {
        promptTop
    } else {
        keyboardTop
    }
}
