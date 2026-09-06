/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPrivacyPolicyTest {
    @Test
    fun rawPasswordTypeBlocksInspectionEvenWithStaleDefaultCapabilities() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        assertTrue(
            EditorPrivacyPolicy.forbidsTextInspection(info, CapabilityFlags.DefaultFlags)
        )
    }

    @Test
    fun everyAndroidPasswordVariationIsBlocked() {
        val variations = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )

        variations.forEach { inputType ->
            assertTrue(
                EditorPrivacyPolicy.forbidsTextInspection(
                    EditorInfo().apply { this.inputType = inputType }
                )
            )
        }
    }

    @Test
    fun noPersonalizedLearningBlocksInspection() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }

        assertTrue(EditorPrivacyPolicy.forbidsTextInspection(info))
    }

    @Test
    fun ordinaryTextAndEmailRemainAvailable() {
        val ordinary = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        val email = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        assertFalse(EditorPrivacyPolicy.forbidsTextInspection(ordinary))
        assertFalse(EditorPrivacyPolicy.forbidsTextInspection(email))
    }

    @Test
    fun testEmailFieldDetection() {
        val emailVariation = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val webEmailVariation = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        }
        val emailCapability = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val flags = CapabilityFlags.fromEditorInfo(emailVariation)
        val hintEmail = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hintText = "이메일을 입력하세요"
        }

        assertTrue(EditorPrivacyPolicy.isEmailAddressField(emailVariation))
        assertTrue(EditorPrivacyPolicy.isEmailAddressField(webEmailVariation))
        assertTrue(EditorPrivacyPolicy.isEmailAddressField(emailCapability, flags))
        assertTrue(EditorPrivacyPolicy.isEmailAddressField(hintEmail))
        assertFalse(EditorPrivacyPolicy.isConversationalTextField(emailVariation))
    }

    @Test
    fun testPhoneAndNumericFieldDetection() {
        val phoneField = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_PHONE
        }
        val numberField = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val dateField = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_DATETIME
        }
        val phoneHintField = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hintText = "휴대전화 번호 (-없이 입력)"
        }

        assertTrue(EditorPrivacyPolicy.isPhoneField(phoneField))
        assertTrue(EditorPrivacyPolicy.isPhoneField(phoneHintField))
        assertTrue(EditorPrivacyPolicy.isNumericField(numberField))
        assertTrue(EditorPrivacyPolicy.isNumericField(dateField))

        assertFalse(EditorPrivacyPolicy.isConversationalTextField(phoneField))
        assertFalse(EditorPrivacyPolicy.isConversationalTextField(numberField))
        assertFalse(EditorPrivacyPolicy.isConversationalTextField(phoneHintField))
    }

    @Test
    fun testUrlFieldDetection() {
        val urlField = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        assertTrue(EditorPrivacyPolicy.isUrlField(urlField))
        assertFalse(EditorPrivacyPolicy.isConversationalTextField(urlField))
    }

    @Test
    fun testConversationalTextFieldAllowed() {
        val normalChat = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val plainText = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val filterSearch = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER
        }
        val noSuggestions = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        assertTrue(EditorPrivacyPolicy.isConversationalTextField(normalChat))
        assertTrue(EditorPrivacyPolicy.isConversationalTextField(plainText))
        assertFalse(EditorPrivacyPolicy.isConversationalTextField(filterSearch))
        assertFalse(EditorPrivacyPolicy.isConversationalTextField(noSuggestions))
    }
}
