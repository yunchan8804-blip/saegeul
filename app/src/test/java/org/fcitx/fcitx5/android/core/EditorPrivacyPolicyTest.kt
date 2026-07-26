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
}
