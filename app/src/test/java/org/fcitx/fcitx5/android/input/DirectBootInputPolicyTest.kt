/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBootInputPolicyTest {
    @Test
    fun directBootKeepsCredentialProtectedFeaturesClosed() {
        assertFalse(DirectBootInputPolicy.allowsCredentialProtectedFeatures(isDirectBootMode = true))
        assertFalse(
            DirectBootInputPolicy.allowsTextInspection(
                isDirectBootMode = true,
                editorAllowsTextInspection = true
            )
        )
    }

    @Test
    fun unlockedModeStillHonorsEditorPrivacy() {
        assertTrue(DirectBootInputPolicy.allowsCredentialProtectedFeatures(isDirectBootMode = false))
        assertTrue(
            DirectBootInputPolicy.allowsTextInspection(
                isDirectBootMode = false,
                editorAllowsTextInspection = true
            )
        )
        assertFalse(
            DirectBootInputPolicy.allowsTextInspection(
                isDirectBootMode = false,
                editorAllowsTextInspection = false
            )
        )
    }
}
