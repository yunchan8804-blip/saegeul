/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.tab

/**
 * Manages tab lifecycle and the active tab.
 */
class TabManager {

    private val tabs = mutableListOf<TabItem>()
    var activeTab: TabItem
        private set

    init {
        resetToDefaults()
        activeTab = tabs.first { it.isVisible }
    }

    fun getVisibleTabs(): List<TabItem> = tabs.filter { it.isVisible }

    fun selectTab(id: TabId): Boolean {
        val target = tabs.find { it.id == id } ?: return false
        if (!target.isVisible) return false

        if (activeTab.id != id) {
            activeTab = target
        }
        return true
    }

    fun setTabBadgeCount(id: TabId, count: Int) {
        tabs.find { it.id == id }?.let { it.badgeCount = count.coerceAtLeast(0) }
    }

    fun resetToDefaults() {
        tabs.clear()
        TabId.entries.forEach { id ->
            tabs.add(TabItem(id = id, isVisible = true, isPinned = false, badgeCount = 0))
        }
        activeTab = tabs.first()
    }
}
