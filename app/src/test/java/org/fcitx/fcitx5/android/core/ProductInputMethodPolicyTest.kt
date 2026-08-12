/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductInputMethodPolicyTest {
    @Test
    fun firstRunEnablesKoreanAndEnglishInProductOrder() {
        assertEquals(
            listOf("hangul", "keyboard-us"),
            ProductInputMethodPolicy.defaultEnabled
        )
    }
}
