/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTextSourceTest {
    @Test
    fun `uses only current paragraph when available`() {
        assertEquals("현재 문단", AiTextSource.beforeCursor("이전 문단\n현재 문단"))
        assertNull(AiTextSource.beforeCursor("   \n  "))
    }

    @Test
    fun `caps inspected content`() {
        val source = AiTextSource.beforeCursor("가".repeat(AiTextSource.MAX_CHARACTERS + 20))!!
        assertEquals(AiTextSource.MAX_CHARACTERS, source.length)
        assertTrue(source.all { it == '가' })
    }
}
