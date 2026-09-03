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
 * TDD Test Suite for User Tab Synchronization & Custom Configuration Store.
 * Validates JSON export/import, schema validation, checksum verification, and backward compatibility.
 */
class UserTabSyncManagerTest {

    private lateinit var syncManager: UserTabSyncManager
    private lateinit var tabManager: TabManager

    @Before
    fun setUp() {
        tabManager = TabManager()
        syncManager = UserTabSyncManager(tabManager)
    }

    @Test
    fun testExportTabConfigurationToJson() {
        val json = syncManager.exportToJson()
        assertNotNull(json)
        assertTrue(json.contains("\"version\":"))
        assertTrue(json.contains("\"tabs\":"))
        assertTrue(json.contains("\"checksum\":"))
    }

    @Test
    fun testImportValidTabConfigurationFromJson() {
        // Customize local tabs
        tabManager.reorderTabs(listOf(TabId.FAVORITES, TabId.MEDIA, TabId.CLIPBOARD))
        tabManager.setTabVisibility(TabId.SYNC, false)
        tabManager.pinTab(TabId.FAVORITES)

        val exportedJson = syncManager.exportToJson()

        // Create new fresh tabManager and import
        val freshTabManager = TabManager()
        val freshSyncManager = UserTabSyncManager(freshTabManager)

        val success = freshSyncManager.importFromJson(exportedJson)
        assertTrue(success)

        val importedTabs = freshTabManager.getRegisteredTabs()
        assertEquals(TabId.FAVORITES, importedTabs[0].id)
        assertEquals(TabId.MEDIA, importedTabs[1].id)
        assertEquals(TabId.CLIPBOARD, importedTabs[2].id)

        assertFalse(freshTabManager.isTabVisible(TabId.SYNC))
        assertTrue(freshTabManager.isTabPinned(TabId.FAVORITES))
    }

    @Test
    fun testRejectCorruptedJsonOrInvalidChecksum() {
        val validJson = syncManager.exportToJson()
        val corruptedJson = validJson.replace("\"version\": 1", "\"version\": 999") // Tampered content with wrong checksum

        val freshTabManager = TabManager()
        val freshSyncManager = UserTabSyncManager(freshTabManager)

        val result = freshSyncManager.importFromJson(corruptedJson)
        assertFalse(result)
        // TabManager state must remain untouched
        assertEquals(TabId.CLIPBOARD, freshTabManager.getRegisteredTabs()[0].id)
    }

    @Test
    fun testHandleMissingOrExtraUnknownTabsGracefully() {
        val legacyJsonWithoutMedia = """
            {
              "version": 1,
              "timestamp": 1700000000000,
              "tabs": [
                {"id": "CLIPBOARD", "visible": true, "pinned": false},
                {"id": "SETTINGS", "visible": true, "pinned": false},
                {"id": "UNKNOWN_FUTURE_TAB", "visible": true, "pinned": false}
              ],
              "checksum": "bypass_test"
            }
        """.trimIndent()

        val result = syncManager.importFromJson(legacyJsonWithoutMedia, ignoreChecksumForTest = true)
        assertTrue(result)

        // Missing default tabs (like MEDIA, FAVORITES) should automatically be appended safely
        val tabs = tabManager.getRegisteredTabs()
        assertEquals(TabId.CLIPBOARD, tabs[0].id)
        assertEquals(TabId.SETTINGS, tabs[1].id)
        assertTrue(tabs.any { it.id == TabId.MEDIA })
        assertTrue(tabs.any { it.id == TabId.FAVORITES })
    }

    @Test
    fun testChecksumDeterministicConsistency() {
        val config = TabConfiguration(
            version = 1,
            timestamp = 123456789L,
            tabs = listOf(
                TabConfigItem(TabId.CLIPBOARD, visible = true, pinned = false),
                TabConfigItem(TabId.MEDIA, visible = true, pinned = true)
            )
        )

        val hash1 = syncManager.calculateChecksum(config)
        val hash2 = syncManager.calculateChecksum(config)
        assertEquals(hash1, hash2)
        assertFalse(hash1.isBlank())
    }

    @Test
    fun testMultiDeviceTabStateMerge() {
        val deviceAState = TabConfiguration(
            version = 1,
            timestamp = 1000L,
            tabs = listOf(
                TabConfigItem(TabId.CLIPBOARD, visible = true, pinned = true),
                TabConfigItem(TabId.SEARCH, visible = false, pinned = false)
            )
        )

        val deviceBState = TabConfiguration(
            version = 1,
            timestamp = 2000L,
            tabs = listOf(
                TabConfigItem(TabId.MEDIA, visible = true, pinned = true),
                TabConfigItem(TabId.FAVORITES, visible = true, pinned = false)
            )
        )

        val merged = syncManager.mergeConfigurations(deviceAState, deviceBState)
        assertNotNull(merged)
        assertTrue(merged.tabs.any { it.id == TabId.CLIPBOARD && it.pinned })
        assertTrue(merged.tabs.any { it.id == TabId.MEDIA && it.pinned })
    }
}
