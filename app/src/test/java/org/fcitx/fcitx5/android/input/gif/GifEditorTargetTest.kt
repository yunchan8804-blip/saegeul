/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class GifEditorTargetTest {
    @Test
    fun factoryRetainsTheOpeningInputSessionEpoch() {
        val info = EditorInfo().apply {
            packageName = "org.example.editor"
            fieldId = 41
            inputType = 7
        }

        val target = GifEditorTarget.from(
            info = info,
            selectionStart = 3,
            selectionEnd = 5,
            inputSessionEpoch = 73L
        )

        assertEquals("org.example.editor", target.packageName)
        assertEquals(41, target.fieldId)
        assertEquals(7, target.inputType)
        assertEquals(3, target.selectionStart)
        assertEquals(5, target.selectionEnd)
        assertEquals(73L, target.inputSessionEpoch)
    }
}
