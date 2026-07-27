/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import org.junit.Assert.assertEquals
import org.junit.Test

class AppProfileSettingsFragmentTest {
    @Test
    fun `dialog viewport stays within screen budget and configured maximum`() {
        assertEquals(960, appProfileDialogViewportHeight(2000, 1200, 600))
        assertEquals(900, appProfileDialogViewportHeight(3000, 900, 600))
    }

    @Test
    fun `small screen never expands viewport past available height`() {
        assertEquals(240, appProfileDialogViewportHeight(500, 1200, 600))
    }
}
