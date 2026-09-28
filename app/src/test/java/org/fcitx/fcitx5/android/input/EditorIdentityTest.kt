/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorIdentityTest {

    @Test
    fun `of builds identity from an EditorInfo's package, field id, and input type`() {
        val info = EditorInfo().apply {
            packageName = "org.example.editor"
            fieldId = 41
            inputType = 7
        }

        val identity = EditorIdentity.of(info)

        assertEquals("org.example.editor", identity.packageName)
        assertEquals(41, identity.fieldId)
        assertEquals(7, identity.inputType)
    }

    @Test
    fun `sameField is true only when package, field id, and input type all match`() {
        val base = EditorIdentity("org.example.editor", 41, 7)

        assertTrue(base.sameField(EditorIdentity("org.example.editor", 41, 7)))
        assertFalse(base.sameField(EditorIdentity("org.example.other", 41, 7)))
        assertFalse(base.sameField(EditorIdentity("org.example.editor", 42, 7)))
        assertFalse(base.sameField(EditorIdentity("org.example.editor", 41, 8)))
    }

    @Test
    fun `collapsed selection has an equal start and end`() {
        val selection = EditorSelection.collapsed(9)

        assertEquals(9, selection.start)
        assertEquals(9, selection.end)
        assertEquals(EditorSelection(9, 9), selection)
    }

    @Test
    fun `selection equality compares both bounds`() {
        assertTrue(EditorSelection(3, 5) == EditorSelection(3, 5))
        assertFalse(EditorSelection(3, 5) == EditorSelection(3, 6))
        assertFalse(EditorSelection(3, 5) == EditorSelection(4, 5))
    }
}
