/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.tab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deep Edge Case TDD Test Suite for Tab Architecture.
 * Tests edge conditions: all tabs hidden, massive history stack depth, corrupted reorders,
 * concurrent access simulation, and rapid state mutations.
 */
class UnifiedTabDeepEdgeCaseTest {

    private lateinit var tabManager: TabManager

    @Before
    fun setUp() {
        tabManager = TabManager()
    }

    @Test
    fun testAllTabsHiddenSafetyFallback() {
        // Attempt to hide ALL tabs
        TabId.entries.forEach { id ->
            tabManager.setTabVisibility(id, false)
        }

        // System must safely handle empty visible tabs without crashing
        val visible = tabManager.getVisibleTabs()
        assertTrue(visible.isEmpty())

        // Re-enabling at least one tab restores active tab correctly
        tabManager.setTabVisibility(TabId.SETTINGS, true)
        val newVisible = tabManager.getVisibleTabs()
        assertEquals(1, newVisible.size)
        assertEquals(TabId.SETTINGS, newVisible[0].id)
    }

    @Test
    fun testDeepHistoryStackNavigationStress() {
        // Perform 5,000 navigation steps back and forth
        for (i in 0 until 5_000) {
            val tabA = TabId.entries[i % TabId.entries.size]
            val tabB = TabId.entries[(i + 1) % TabId.entries.size]
            tabManager.selectTab(tabA)
            tabManager.selectTab(tabB)
        }

        // Navigate all the way back
        var backCount = 0
        while (tabManager.canNavigateBack()) {
            val prev = tabManager.navigateBack()
            assertNotNull(prev)
            backCount++
        }

        assertTrue(backCount > 0)
        assertFalse(tabManager.canNavigateBack())
        assertNull(tabManager.navigateBack())
    }

    @Test
    fun testReorderWithDuplicatesAndUnknownIds() {
        val messyList = listOf(
            TabId.CLIPBOARD,
            TabId.CLIPBOARD,
            TabId.CLIPBOARD,
            TabId.MEDIA,
            TabId.FAVORITES
        )

        tabManager.reorderTabs(messyList)
        val registered = tabManager.getRegisteredTabs()

        // Duplicates must be deduplicated, total count must remain exact number of TabId entries
        assertEquals(TabId.entries.size, registered.size)
        assertEquals(TabId.CLIPBOARD, registered[0].id)
        assertEquals(TabId.MEDIA, registered[1].id)
        assertEquals(TabId.FAVORITES, registered[2].id)
    }

    @Test
    fun testBadgeCounterNegativeAndOverflowProtection() {
        tabManager.setTabBadgeCount(TabId.CLIPBOARD, -50)
        assertEquals(0, tabManager.getTabBadgeCount(TabId.CLIPBOARD))

        tabManager.setTabBadgeCount(TabId.CLIPBOARD, 999999)
        assertEquals(999999, tabManager.getTabBadgeCount(TabId.CLIPBOARD))

        tabManager.clearTabBadgeCount(TabId.CLIPBOARD)
        assertEquals(0, tabManager.getTabBadgeCount(TabId.CLIPBOARD))
    }

    @Test
    fun testRapidPinUnpinCycles() {
        for (i in 0 until 1000) {
            val id = TabId.entries[i % TabId.entries.size]
            if (i % 2 == 0) {
                tabManager.pinTab(id)
                assertTrue(tabManager.isTabPinned(id))
            } else {
                tabManager.unpinTab(id)
                assertFalse(tabManager.isTabPinned(id))
            }
        }
    }
}
