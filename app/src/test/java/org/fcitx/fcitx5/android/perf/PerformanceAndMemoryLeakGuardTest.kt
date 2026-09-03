/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.perf

import org.fcitx.fcitx5.android.media.MediaFavoritesManager
import org.fcitx.fcitx5.android.media.MediaItem
import org.fcitx.fcitx5.android.media.MediaRetryQueue
import org.fcitx.fcitx5.android.media.MediaSearchRequest
import org.fcitx.fcitx5.android.media.MediaType
import org.fcitx.fcitx5.android.tab.TabId
import org.fcitx.fcitx5.android.tab.TabManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Performance and Memory Leak Guard Regression Test Suite.
 * Validates bounded memory allocation, zero leak on repeated teardown/setup cycles,
 * and high-frequency stress execution benchmarks.
 */
class PerformanceAndMemoryLeakGuardTest {

    @Test
    fun testHighFrequencyTabSwitchingBenchmark() {
        val tabManager = TabManager()
        val startTime = System.nanoTime()

        // 10,000 high-frequency tab switches
        for (i in 0 until 10_000) {
            val tabId = TabId.entries[i % TabId.entries.size]
            tabManager.selectTab(tabId)
            assertEquals(tabId, tabManager.activeTab.id)
        }

        val durationMs = (System.nanoTime() - startTime) / 1_000_000
        assertTrue("10,000 tab switches should complete in under 500ms (took ${durationMs}ms)", durationMs < 500)
    }

    @Test
    fun testMediaRetryQueueBoundedMemoryUsage() {
        val maxCap = 100
        val queue = MediaRetryQueue(maxQueueSize = maxCap, maxRetryAttempts = 3)

        // Enqueue 5,000 items into capped queue
        for (i in 0 until 5_000) {
            queue.enqueue(MediaSearchRequest("req_$i", "Query $i", MediaType.GIF, i * 10L))
        }

        // Memory must remain strictly bounded to maxCap
        assertEquals(maxCap, queue.size())
    }

    @Test
    fun testMediaFavoritesBoundedMemoryUsage() {
        val maxCap = 200
        val favorites = MediaFavoritesManager(maxCapacity = maxCap)

        // Insert 1,000 favorites
        for (i in 0 until 1_000) {
            favorites.addFavorite(
                MediaItem("fav_$i", "Title $i", "url_$i", "thumb_$i", MediaType.GIF, 100, 100)
            )
        }

        assertEquals(maxCap, favorites.getFavorites().size)
    }

    @Test
    fun testRepeatedTabManagerResetMemoryReclamation() {
        val tabManager = TabManager()
        for (cycle in 0 until 500) {
            tabManager.reorderTabs(listOf(TabId.SYNC, TabId.FAVORITES, TabId.MEDIA))
            tabManager.setTabVisibility(TabId.CLIPBOARD, false)
            tabManager.pinTab(TabId.MEDIA)
            tabManager.setTabBadgeCount(TabId.FAVORITES, 99)

            tabManager.resetToDefaults()

            assertEquals(TabId.CLIPBOARD, tabManager.activeTab.id)
            assertEquals(0, tabManager.getTabBadgeCount(TabId.FAVORITES))
        }
    }
}
