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

    fun isEmailAddressField(
        info: EditorInfo,
        capabilities: CapabilityFlags = CapabilityFlags.fromEditorInfo(info)
    ): Boolean {
        if (capabilities.has(CapabilityFlag.Email)) return true
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        if (inputClass == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        ) {
            return true
        }
        val text = "${info.hintText} ${info.label}".lowercase()
        return text.contains("이메일") || text.contains("email")
    }

    fun isPhoneField(
        info: EditorInfo,
        capabilities: CapabilityFlags = CapabilityFlags.fromEditorInfo(info)
    ): Boolean {
        if (capabilities.has(CapabilityFlag.Dialable)) return true
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        if (inputClass == InputType.TYPE_CLASS_PHONE) return true
        val text = "${info.hintText} ${info.label}".lowercase()
        return text.contains("전화번호") || text.contains("휴대폰") || text.contains("휴대전화") || text.contains("phone")
    }

    fun isNumericField(
        info: EditorInfo,
        capabilities: CapabilityFlags = CapabilityFlags.fromEditorInfo(info)
    ): Boolean {
        if (capabilities.has(CapabilityFlag.Number) || capabilities.has(CapabilityFlag.Digit)) return true
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        return inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_DATETIME
    }

    fun isUrlField(
        info: EditorInfo,
        capabilities: CapabilityFlags = CapabilityFlags.fromEditorInfo(info)
    ): Boolean {
        if (capabilities.has(CapabilityFlag.Url)) return true
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return inputClass == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI
    }

    /**
     * Determines whether the editor is a conversational, prose, or free-form text entry field.
     * Non-conversational fields include passwords, phone numbers, numeric inputs, emails, URLs,
     * or fields with explicit NO_SUGGESTIONS flags.
     */
    fun isConversationalTextField(
        info: EditorInfo,
        capabilities: CapabilityFlags = CapabilityFlags.fromEditorInfo(info)
    ): Boolean {
        if (forbidsTextInspection(info, capabilities)) return false
        if (isPhoneField(info, capabilities)) return false
        if (isNumericField(info, capabilities)) return false
        if (isEmailAddressField(info, capabilities)) return false
        if (isUrlField(info, capabilities)) return false

        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        if (inputClass != InputType.TYPE_CLASS_TEXT) return false

        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        if (variation == InputType.TYPE_TEXT_VARIATION_FILTER) return false

        val flags = info.inputType and InputType.TYPE_MASK_FLAGS
        if (flags.hasFlag(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)) return false

        return true
    }
}
