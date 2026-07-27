/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.junit.Assert.assertEquals
import org.junit.Test

class ImeTouchableTopPolicyTest {
    @Test
    fun `visible prompt expands touchable IME region above keyboard`() {
        assertEquals(
            838,
            ImeTouchableTopPolicy.resolve(
                keyboardTop = 1111,
                promptTop = 838,
                promptVisible = true
            )
        )
    }

    @Test
    fun `hidden or not-yet-laid-out prompt keeps keyboard boundary`() {
        assertEquals(1111, ImeTouchableTopPolicy.resolve(1111, 838, false))
        assertEquals(1111, ImeTouchableTopPolicy.resolve(1111, 0, true))
        assertEquals(1111, ImeTouchableTopPolicy.resolve(1111, 1200, true))
    }
}
