/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.tab

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Deep Edge Case TDD Test Suite for Tab Architecture.
 * Tests edge conditions: negative and very large badge counts.
 */
class UnifiedTabDeepEdgeCaseTest {

    private lateinit var tabManager: TabManager

    @Before
    fun setUp() {
        tabManager = TabManager()
    }

    @Test
    fun testBadgeCounterNegativeAndOverflowProtection() {
        tabManager.setTabBadgeCount(TabId.CLIPBOARD, -50)
        assertEquals(0, badgeCountOf(TabId.CLIPBOARD))

        tabManager.setTabBadgeCount(TabId.CLIPBOARD, 999999)
        assertEquals(999999, badgeCountOf(TabId.CLIPBOARD))

        tabManager.setTabBadgeCount(TabId.CLIPBOARD, 0)
        assertEquals(0, badgeCountOf(TabId.CLIPBOARD))
    }

    private fun badgeCountOf(id: TabId): Int = tabManager.getVisibleTabs().first { it.id == id }.badgeCount
}
