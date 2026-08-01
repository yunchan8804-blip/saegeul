/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.core

/** Product-owned first-run input methods, independent of the device locale fallback. */
internal object ProductInputMethodPolicy {
    val defaultEnabled = listOf(
        "hangul",
        "keyboard-us"
    )
}
