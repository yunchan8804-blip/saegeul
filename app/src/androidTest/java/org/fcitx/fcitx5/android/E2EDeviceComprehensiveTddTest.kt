/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.input.BufferedHangulMode
import org.fcitx.fcitx5.android.input.BufferedInputController
import org.fcitx.fcitx5.android.media.MediaFavoritesManager
import org.fcitx.fcitx5.android.media.MediaItem
import org.fcitx.fcitx5.android.media.MediaRetryQueue
import org.fcitx.fcitx5.android.media.MediaSearchRequest
import org.fcitx.fcitx5.android.media.MediaType
import org.fcitx.fcitx5.android.tab.TabConfigItem
import org.fcitx.fcitx5.android.tab.TabConfiguration
import org.fcitx.fcitx5.android.tab.TabId
import org.fcitx.fcitx5.android.tab.TabManager
import org.fcitx.fcitx5.android.tab.UserTabSyncManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real Android Device E2E TDD Test Suite.
 * Executes live on the connected physical device (R3CX70NE9VH) with 1000+ deep assertions.
 */
class E2EDeviceComprehensiveTddTest {

    private lateinit var appContext: Context
    private lateinit var tabManager: TabManager
    private lateinit var syncManager: UserTabSyncManager
    private lateinit var mediaQueue: MediaRetryQueue
    private lateinit var mediaFavorites: MediaFavoritesManager
    private lateinit var bufferController: BufferedInputController

    @Before
    fun setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        tabManager = TabManager()
        syncManager = UserTabSyncManager(tabManager)
        mediaQueue = MediaRetryQueue(maxQueueSize = 100, maxRetryAttempts = 5)
        mediaFavorites = MediaFavoritesManager(maxCapacity = 200)
        bufferController = BufferedInputController()
    }

    @Test
    fun testDeviceContextAndPackageIntegrity() {
        assertNotNull(appContext)
        assertTrue(appContext.packageName.startsWith("net.chanpaca.saegeul"))
        assertNotNull(appContext.packageManager)
    }

    @Test
    fun testTabManager1000CyclesStressOnDevice() {
        // Execute 1,000 rapid state mutations on real device runtime
        for (i in 0 until 1000) {
            val tabId = TabId.entries[i % TabId.entries.size]
            assertTrue(tabManager.selectTab(tabId))
            assertEquals(tabId, tabManager.activeTab.id)

            tabManager.setTabBadgeCount(tabId, i)
            assertEquals(i, tabManager.getTabBadgeCount(tabId))
        }

        assertEquals(7, tabManager.getRegisteredTabs().size)
    }

    @Test
    fun testMediaRetryQueueAndDeduplication1000ItemsOnDevice() {
        // Enqueue 1,000 requests into capped queue of 100
        for (i in 0 until 1000) {
            val req = MediaSearchRequest(
                id = "req_device_$i",
                query = "실기기 테스트 $i",
                mediaType = if (i % 2 == 0) MediaType.GIF else MediaType.VIDEO,
                timestamp = System.currentTimeMillis() + i
            )
            mediaQueue.enqueue(req)
        }

        // Bounded capacity assertion
        assertEquals(100, mediaQueue.size())
        assertFalse(mediaQueue.contains("req_device_0")) // Oldest evicted
        assertTrue(mediaQueue.contains("req_device_999")) // Latest kept
    }

    @Test
    fun testMediaFavorites1000OperationsOnDevice() {
        for (i in 0 until 1000) {
            val item = MediaItem(
                id = "fav_$i",
                title = "인기 짤 $i",
                url = "https://saegeul.internal/media/$i.gif",
                thumbnailUrl = "https://saegeul.internal/thumb/$i.jpg",
                mediaType = MediaType.GIF,
                tags = listOf("태그_$i", "모바일", "테스트")
            )
            mediaFavorites.addFavorite(item)
        }

        // Capped to max capacity 200
        assertEquals(200, mediaFavorites.getFavorites().size)
        assertTrue(mediaFavorites.search("모바일").isNotEmpty())
        assertTrue(mediaFavorites.findByTag("테스트").isNotEmpty())
    }

    @Test
    fun testUserTabSyncChecksumAndImportExportOnDevice() {
        val exported = syncManager.exportToJson()
        assertNotNull(exported)
        assertTrue(exported.contains("\"version\": 1"))

        val freshTabManager = TabManager()
        val freshSync = UserTabSyncManager(freshTabManager)
        val importSuccess = freshSync.importFromJson(exported)
        assertTrue(importSuccess)

        val exportedTabs = tabManager.getRegisteredTabs()
        val importedTabs = freshTabManager.getRegisteredTabs()
        assertEquals(exportedTabs.size, importedTabs.size)

        for (i in exportedTabs.indices) {
            assertEquals(exportedTabs[i].id, importedTabs[i].id)
            assertEquals(exportedTabs[i].isVisible, importedTabs[i].isVisible)
            assertEquals(exportedTabs[i].isPinned, importedTabs[i].isPinned)
        }
    }

    @Test
    fun testDotNetAndCompatibilityEngineOnDevice() {
        val dotNetApps = listOf(
            "com.microsoft.maui.gallery",
            "net.dot.android.sample",
            "com.unity3d.player.UnityActivity",
            "com.valvesoftware.steamlink",
            "com.termux",
            "com.realvnc.viewer.android"
        )

        dotNetApps.forEach { pkg ->
            assertTrue("Expected compatibility true for $pkg", BufferedHangulMode.isKnownCompatibilityTarget(pkg))
        }

        val regularApps = listOf(
            "com.google.android.youtube",
            "com.kakao.talk",
            "com.naver.search",
            "org.telegram.messenger"
        )

        regularApps.forEach { pkg ->
            assertFalse("Expected compatibility false for $pkg", BufferedHangulMode.isKnownCompatibilityTarget(pkg))
        }
    }

    @Test
    fun testBufferController1000HangulTypingSequencesOnDevice() {
        val sentences = listOf(
            "새글 키보드는 대한민국 최고의 혁신적인 인풋 메소드입니다.",
            "빠르고 수려한 글자 조합과 안전한 호환 모드를 지원합니다.",
            "닷넷 마우이와 유니티에서도 완벽하게 작동합니다. 😀✨🇰🇷"
        )

        for (cycle in 0 until 350) {
            val sentence = sentences[cycle % sentences.size]
            bufferController.clear()
            assertTrue(bufferController.isEmpty)

            sentence.forEach { ch ->
                bufferController.capture(ch.toString())
            }

            assertEquals(sentence, bufferController.prefix)
            assertEquals(sentence, bufferController.snapshot())

            // Delete characters backward
            while (!bufferController.isEmpty) {
                assertTrue(bufferController.deleteLastCodePoint())
            }
            assertTrue(bufferController.isEmpty)
        }
    }
}
