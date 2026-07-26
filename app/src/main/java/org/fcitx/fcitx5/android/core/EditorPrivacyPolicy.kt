/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.os.Build
import android.text.InputType
import android.view.inputmethod.EditorInfo
import splitties.bitflags.hasFlag

/** Fail-closed policy for features that inspect editor text or expose it to another service. */
object EditorPrivacyPolicy {
    fun forbidsTextInspection(
        info: EditorInfo,
        capabilities: CapabilityFlags = CapabilityFlags.fromEditorInfo(info)
    ): Boolean =
        capabilities.has(CapabilityFlag.Password) ||
            capabilities.has(CapabilityFlag.Sensitive) ||
            isPasswordInputType(info.inputType) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                info.imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))

    fun isPasswordInputType(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
