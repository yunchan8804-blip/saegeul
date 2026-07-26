/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StatusAreaEntryTest {
    @Test
    fun hanjaConversionUsesDescriptiveActionLabelInsteadOfAmbiguousModeLabel() {
        val entry = StatusAreaEntry.fromAction(
            action(
                icon = "fcitx-hanja-inactive",
                shortText = "한자",
                longText = "한자로 변환"
            )
        )

        assertEquals("한자로 변환", entry.label)
        assertEquals(R.drawable.ic_status_hangul, entry.icon)
        assertFalse(entry.active)
    }

    @Test
    fun ordinaryStatusActionKeepsItsShortLabel() {
        val entry = StatusAreaEntry.fromAction(
            action(
                icon = "tools-check-spelling",
                shortText = "맞춤법",
                longText = "맞춤법 검사"
            )
        )

        assertEquals("맞춤법", entry.label)
    }

    private fun action(icon: String, shortText: String, longText: String) = Action(
        id = 1,
        isSeparator = false,
        isCheckable = false,
        isChecked = false,
        name = "test",
        icon = icon,
        shortText = shortText,
        longText = longText,
        menu = null
    )
}
