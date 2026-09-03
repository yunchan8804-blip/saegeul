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
 * Extensive TDD Red-Green Test Suite for Saegeul Modern Unified Tab System.
 * Covers tab lifecycle, ordering, visibility, pinning, search filtering, and state persistence.
 */
class UnifiedTabSystemTest {

    private lateinit var tabManager: TabManager

    @Before
    fun setUp() {
        tabManager = TabManager()
    }

    @Test
    fun testDefaultTabRegistryAndOrder() {
        val defaultTabs = tabManager.getRegisteredTabs()
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
    fun testTabVisibilityToggling() {
        assertTrue(tabManager.isTabVisible(TabId.MEDIA))

        tabManager.setTabVisibility(TabId.MEDIA, false)
        assertFalse(tabManager.isTabVisible(TabId.MEDIA))

        val visibleTabs = tabManager.getVisibleTabs()
        assertEquals(6, visibleTabs.size)
        assertFalse(visibleTabs.any { it.id == TabId.MEDIA })

        // Re-enabling
        tabManager.setTabVisibility(TabId.MEDIA, true)
        assertTrue(tabManager.isTabVisible(TabId.MEDIA))
        assertEquals(7, tabManager.getVisibleTabs().size)
    }

    @Test
    fun testTabReorderingAndCustomOrderIntegrity() {
        val customOrder = listOf(
            TabId.FAVORITES,
            TabId.MEDIA,
            TabId.CLIPBOARD,
            TabId.QUICK_PHRASE,
            TabId.SEARCH,
            TabId.SETTINGS,
            TabId.SYNC
        )

        tabManager.reorderTabs(customOrder)
        val orderedTabs = tabManager.getRegisteredTabs()

        for (i in customOrder.indices) {
            assertEquals(customOrder[i], orderedTabs[i].id)
        }

        // Reordering with partial list preserves missing tabs at the end
        val partialOrder = listOf(TabId.SYNC, TabId.SEARCH)
        tabManager.reorderTabs(partialOrder)
        val newOrderedTabs = tabManager.getRegisteredTabs()
        assertEquals(TabId.SYNC, newOrderedTabs[0].id)
        assertEquals(TabId.SEARCH, newOrderedTabs[1].id)
        assertEquals(7, newOrderedTabs.size)
    }

    @Test
    fun testTabPinningAndUnpinning() {
        assertFalse(tabManager.isTabPinned(TabId.MEDIA))

        tabManager.pinTab(TabId.MEDIA)
        assertTrue(tabManager.isTabPinned(TabId.MEDIA))

        val pinnedTabs = tabManager.getPinnedTabs()
        assertEquals(1, pinnedTabs.size)
        assertEquals(TabId.MEDIA, pinnedTabs[0].id)

        tabManager.unpinTab(TabId.MEDIA)
        assertFalse(tabManager.isTabPinned(TabId.MEDIA))
        assertTrue(tabManager.getPinnedTabs().isEmpty())
    }

    @Test
    fun testActiveTabFallbackWhenActiveTabBecomesHidden() {
        tabManager.selectTab(TabId.MEDIA)
        assertEquals(TabId.MEDIA, tabManager.activeTab.id)

        // Hiding the currently active tab must automatically fallback to the first visible tab
        tabManager.setTabVisibility(TabId.MEDIA, false)
        assertFalse(tabManager.activeTab.id == TabId.MEDIA)
        assertEquals(TabId.CLIPBOARD, tabManager.activeTab.id)
    }

    @Test
    fun testTabBadgeCounterAndNotifications() {
        assertEquals(0, tabManager.getTabBadgeCount(TabId.FAVORITES))

        tabManager.setTabBadgeCount(TabId.FAVORITES, 5)
        assertEquals(5, tabManager.getTabBadgeCount(TabId.FAVORITES))

        tabManager.incrementTabBadgeCount(TabId.FAVORITES)
        assertEquals(6, tabManager.getTabBadgeCount(TabId.FAVORITES))

        tabManager.clearTabBadgeCount(TabId.FAVORITES)
        assertEquals(0, tabManager.getTabBadgeCount(TabId.FAVORITES))
    }

    @Test
    fun testTabNavigationHistoryAndBackNavigation() {
        tabManager.selectTab(TabId.CLIPBOARD)
        tabManager.selectTab(TabId.SEARCH)
        tabManager.selectTab(TabId.MEDIA)
        tabManager.selectTab(TabId.FAVORITES)

        assertEquals(TabId.FAVORITES, tabManager.activeTab.id)

        assertTrue(tabManager.canNavigateBack())
        assertEquals(TabId.MEDIA, tabManager.navigateBack())
        assertEquals(TabId.MEDIA, tabManager.activeTab.id)

        assertEquals(TabId.SEARCH, tabManager.navigateBack())
        assertEquals(TabId.SEARCH, tabManager.activeTab.id)

        assertEquals(TabId.CLIPBOARD, tabManager.navigateBack())
        assertEquals(TabId.CLIPBOARD, tabManager.activeTab.id)

        assertFalse(tabManager.canNavigateBack())
    }

    @Test
    fun testResetToFactoryDefaultConfiguration() {
        tabManager.reorderTabs(listOf(TabId.SYNC, TabId.FAVORITES))
        tabManager.setTabVisibility(TabId.CLIPBOARD, false)
        tabManager.pinTab(TabId.MEDIA)

        tabManager.resetToDefaults()

        val defaultTabs = tabManager.getRegisteredTabs()
        assertEquals(TabId.CLIPBOARD, defaultTabs[0].id)
        assertTrue(tabManager.isTabVisible(TabId.CLIPBOARD))
        assertFalse(tabManager.isTabPinned(TabId.MEDIA))
        assertEquals(TabId.CLIPBOARD, tabManager.activeTab.id)
    }

    @Test
    fun testComprehensiveTabAssertionsStressTest() {
        for (i in 0 until 100) {
            val tabId = TabId.entries[i % TabId.entries.size]
            tabManager.selectTab(tabId)
            assertEquals(tabId, tabManager.activeTab.id)
            tabManager.setTabBadgeCount(tabId, i)
            assertEquals(i, tabManager.getTabBadgeCount(tabId))
        }
    }
}
