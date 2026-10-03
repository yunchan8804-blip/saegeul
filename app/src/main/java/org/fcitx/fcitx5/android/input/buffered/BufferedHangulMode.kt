/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.buffered

import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.input.keyboard.HangulKeyLegends

/**
 * Compatibility policy for editors that cannot handle composing spans.
 */
object BufferedHangulMode {

    fun isActive(enabled: Boolean, ime: InputMethodEntry): Boolean =
        enabled && HangulKeyLegends.isHangulInputMethod(ime.addon, ime.languageCode)

    fun effectiveCapabilities(
        capabilities: CapabilityFlags,
        enabled: Boolean,
        ime: InputMethodEntry,
        packageName: String?
    ): CapabilityFlags {
        val withoutPreedit = capabilities.flags and CapabilityFlag.Preedit.flag.inv()
        if (HangulKeyLegends.isHangulInputMethod(ime.addon, ime.languageCode)) {
            return if (enabled) CapabilityFlags(withoutPreedit) else capabilities
        }
        if (!enabled && !RemoteEditorPackages.isRemote(packageName)) return capabilities
        return CapabilityFlags(withoutPreedit or CapabilityFlag.NoSpellCheck.flag)
    }

    fun mustAvoidClipboard(capabilities: CapabilityFlags): Boolean =
        capabilities.has(CapabilityFlag.Password) ||
            capabilities.has(CapabilityFlag.Sensitive)

    private val NAVIGATION_KEYS = setOf(
        FcitxKeyMapping.FcitxKey_Left,
        FcitxKeyMapping.FcitxKey_Right,
        FcitxKeyMapping.FcitxKey_Up,
        FcitxKeyMapping.FcitxKey_Down,
        FcitxKeyMapping.FcitxKey_Home,
        FcitxKeyMapping.FcitxKey_End,
        FcitxKeyMapping.FcitxKey_Page_Up,
        FcitxKeyMapping.FcitxKey_Page_Down
    )

    /**
     * Whether a forwarded key submits the pending segment and then reaches the editor as a key
     * instead of being appended to the buffer: navigation keys, and keys whose character is a
     * control code such as Tab, Escape or Delete. Return and BackSpace have their own handling.
     * A `unicode` of 0 means the key has no character.
     */
    fun submitsBeforeForwarding(sym: Int, unicode: Int): Boolean {
        if (sym == FcitxKeyMapping.FcitxKey_Return || sym == FcitxKeyMapping.FcitxKey_BackSpace) {
            return false
        }
        return sym in NAVIGATION_KEYS || unicode in 0x01..0x1F || unicode == 0x7F
    }
}
