/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.input.keyboard.HangulKeyLegends

/**
 * Compatibility policy for editors that cannot handle Hangul composing spans.
 */
object BufferedHangulMode {

    fun isActive(enabled: Boolean, ime: InputMethodEntry): Boolean =
        enabled && HangulKeyLegends.isHangulInputMethod(ime.addon, ime.languageCode)

    fun effectiveCapabilities(
        capabilities: CapabilityFlags,
        enabled: Boolean,
        ime: InputMethodEntry
    ): CapabilityFlags {
        if (!isActive(enabled, ime)) return capabilities
        return CapabilityFlags(capabilities.flags and CapabilityFlag.Preedit.flag.inv())
    }

    fun mustAvoidClipboard(capabilities: CapabilityFlags): Boolean =
        capabilities.has(CapabilityFlag.Password) ||
            capabilities.has(CapabilityFlag.Sensitive)
}
