/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.dashboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostSyncAdSequenceTest {
    private val events = mutableListOf<String>()
    private val sequence = PostSyncAdSequence(
        recordSync = { events += "record" },
        showAd = { events += "show" }
    )

    @Test
    fun `without a guide the sync is counted and the ad follows at once`() = runBlocking {
        sequence.run { null }

        assertEquals(listOf("record", "show"), events)
    }

    @Test
    fun `the ad waits until the guide is closed but the sync is counted first`() = runBlocking {
        var closeGuide: (() -> Unit)? = null

        sequence.run {
            events += "guide"
            ShownGuide { action -> closeGuide = action }
        }

        assertEquals(listOf("record", "guide"), events)
        closeGuide!!.invoke()
        assertEquals(listOf("record", "guide", "show"), events)
    }

    @Test
    fun `a failed guide step still counts the sync and shows the ad right away`() = runBlocking {
        val failure = runCatching {
            sequence.run { throw IllegalStateException("enrichment request failed") }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(listOf("record", "show"), events)
    }

    @Test
    fun `a cancelled guide step counts the sync but shows no ad`() = runBlocking {
        val failure = runCatching {
            sequence.run { throw CancellationException("screen closed") }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(listOf("record"), events)
    }
}
