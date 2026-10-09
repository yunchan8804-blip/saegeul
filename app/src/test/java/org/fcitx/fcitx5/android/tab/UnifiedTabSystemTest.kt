/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.tab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Extensive TDD Red-Green Test Suite for Saegeul Modern Unified Tab System.
 * Covers tab lifecycle, selection, badge counts, and reset to defaults.
 */
class UnifiedTabSystemTest {

    private lateinit var tabManager: TabManager

    @Before
    fun setUp() {
        tabManager = TabManager()
    }

    @Test
    fun testDefaultTabRegistryAndOrder() {
        val defaultTabs = tabManager.getVisibleTabs()
        assertEquals(7, defaultTabs.size)
        assertEquals(TabId.CLIPBOARD, defaultTabs[0].id)
        assertEquals(TabId.QUICK_PHRASE, defaultTabs[1].id)
        assertEquals(TabId.SEARCH, defaultTabs[2].id)
        assertEquals(TabId.MEDIA, defaultTabs[3].id)
        assertEquals(TabId.FAVORITES, defaultTabs[4].id)
        assertEquals(TabId.SETTINGS, defaultTabs[5].id)
        assertEquals(TabId.SYNC, defaultTabs[6].id)

        defaultTabs.forEach { tab ->
            assertTrue(tab.isVisible)
            assertNotNull(tab.titleRes)
            assertNotNull(tab.iconRes)
        }
    }

    @Test
    fun testTabSwitchingAndActiveState() {
        assertEquals(TabId.CLIPBOARD, tabManager.activeTab.id)

        assertTrue(tabManager.selectTab(TabId.MEDIA))
        assertEquals(TabId.MEDIA, tabManager.activeTab.id)

        assertTrue(tabManager.selectTab(TabId.FAVORITES))
        assertEquals(TabId.FAVORITES, tabManager.activeTab.id)

        assertTrue(tabManager.selectTab(TabId.SYNC))
        assertEquals(TabId.SYNC, tabManager.activeTab.id)

        // Selecting same tab returns false or true consistently without state corruption
        assertTrue(tabManager.selectTab(TabId.SYNC))
        assertEquals(TabId.SYNC, tabManager.activeTab.id)
    }

    @Test
    fun testTabBadgeCounterAndReset() {
        tabManager.selectTab(TabId.MEDIA)
        tabManager.setTabBadgeCount(TabId.FAVORITES, 5)
        assertEquals(5, badgeCountOf(TabId.FAVORITES))

        tabManager.resetToDefaults()

        assertEquals(TabId.CLIPBOARD, tabManager.activeTab.id)
        assertEquals(0, badgeCountOf(TabId.FAVORITES))
    }

    @Test
    fun testComprehensiveTabAssertionsStressTest() {
        for (i in 0 until 100) {
            val tabId = TabId.entries[i % TabId.entries.size]
            tabManager.selectTab(tabId)
            assertEquals(tabId, tabManager.activeTab.id)
            tabManager.setTabBadgeCount(tabId, i)
            assertEquals(i, badgeCountOf(tabId))
        }
    }

    private fun badgeCountOf(id: TabId): Int = tabManager.getVisibleTabs().first { it.id == id }.badgeCount
}
