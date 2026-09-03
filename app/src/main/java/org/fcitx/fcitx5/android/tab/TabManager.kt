/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.tab

import java.util.ArrayDeque

/**
 * Manages tab lifecycle, ordering, visibility, pinning, badge counts, and history navigation.
 */
class TabManager {

    private val tabs = mutableListOf<TabItem>()
    private val history = ArrayDeque<TabId>()
    var activeTab: TabItem
        private set

    init {
        resetToDefaults()
        activeTab = tabs.first { it.isVisible }
    }

    fun getRegisteredTabs(): List<TabItem> = tabs.toList()

    fun getVisibleTabs(): List<TabItem> = tabs.filter { it.isVisible }

    fun getPinnedTabs(): List<TabItem> = tabs.filter { it.isPinned }

    fun isTabVisible(id: TabId): Boolean = tabs.find { it.id == id }?.isVisible ?: false

    fun isTabPinned(id: TabId): Boolean = tabs.find { it.id == id }?.isPinned ?: false

    fun selectTab(id: TabId): Boolean {
        val target = tabs.find { it.id == id } ?: return false
        if (!target.isVisible) return false

        if (activeTab.id != id) {
            history.push(activeTab.id)
            activeTab = target
        }
        return true
    }

    fun setTabVisibility(id: TabId, visible: Boolean) {
        val tab = tabs.find { it.id == id } ?: return
        tab.isVisible = visible

        if (!visible && activeTab.id == id) {
            // Fallback to first visible tab
            val fallback = tabs.firstOrNull { it.isVisible }
            if (fallback != null) {
                activeTab = fallback
            }
        }
    }

    fun pinTab(id: TabId) {
        tabs.find { it.id == id }?.let { it.isPinned = true }
    }

    fun unpinTab(id: TabId) {
        tabs.find { it.id == id }?.let { it.isPinned = false }
    }

    fun reorderTabs(orderedIds: List<TabId>) {
        val reordered = mutableListOf<TabItem>()
        orderedIds.distinct().forEach { id ->
            tabs.find { it.id == id }?.let {
                if (!reordered.contains(it)) {
                    reordered.add(it)
                }
            }
        }
        // Append any remaining tabs not present in orderedIds
        tabs.forEach { tab ->
            if (!reordered.contains(tab)) {
                reordered.add(tab)
            }
        }
        tabs.clear()
        tabs.addAll(reordered)
    }

    fun getTabBadgeCount(id: TabId): Int = tabs.find { it.id == id }?.badgeCount ?: 0

    fun setTabBadgeCount(id: TabId, count: Int) {
        tabs.find { it.id == id }?.let { it.badgeCount = count.coerceAtLeast(0) }
    }

    fun incrementTabBadgeCount(id: TabId) {
        tabs.find { it.id == id }?.let { it.badgeCount++ }
    }

    fun clearTabBadgeCount(id: TabId) {
        setTabBadgeCount(id, 0)
    }

    fun canNavigateBack(): Boolean = history.isNotEmpty()

    fun navigateBack(): TabId? {
        while (history.isNotEmpty()) {
            val prevId = history.pop()
            val target = tabs.find { it.id == prevId }
            if (target != null && target.isVisible) {
                activeTab = target
                return target.id
            }
        }
        return null
    }

    fun resetToDefaults() {
        tabs.clear()
        TabId.entries.forEach { id ->
            tabs.add(TabItem(id = id, isVisible = true, isPinned = false, badgeCount = 0))
        }
        history.clear()
        activeTab = tabs.first()
    }
}
