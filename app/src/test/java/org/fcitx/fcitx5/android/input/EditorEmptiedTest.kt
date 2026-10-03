/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorEmptiedTest {

    @Test
    fun `cursor at zero with no text on either side means the editor was emptied`() {
        assertTrue(isEditorEmptied(0, 0, "", ""))
    }

    @Test
    fun `text before or after the cursor means the editor was not emptied`() {
        assertFalse(isEditorEmptied(0, 0, "", "남은 글"))
        assertFalse(isEditorEmptied(0, 0, "앞 글", ""))
    }

    @Test
    fun `an unreadable editor is never treated as emptied`() {
        assertFalse(isEditorEmptied(0, 0, null, ""))
        assertFalse(isEditorEmptied(0, 0, "", null))
        assertFalse(isEditorEmptied(0, 0, null))
    }

    @Test
    fun `a cursor away from zero or a selection is an ordinary external move`() {
        assertFalse(isEditorEmptied(3, 3, "", ""))
        assertFalse(isEditorEmptied(0, 2, "", ""))
    }
}
