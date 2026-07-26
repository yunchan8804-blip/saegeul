/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GifSearchGateTest {
    @Test
    fun privateEditorMakesZeroProviderRequests() = runBlocking {
        val provider = CountingProvider()
        val outcome = GifSearchGate(provider).search(allowed = false, query = "축하")

        assertSame(GifSearchOutcome.Blocked, outcome)
        assertEquals(0, provider.requests)
    }

    @Test
    fun unsafeQueryMakesZeroProviderRequests() = runBlocking {
        val provider = CountingProvider()
        val outcome = GifSearchGate(provider).search(allowed = true, query = "nsfw")

        assertSame(GifSearchOutcome.SafeSearchBlocked, outcome)
        assertEquals(0, provider.requests)
    }

    @Test
    fun pageNumberIsForwardedOnlyToPagedProvider() = runBlocking {
        val provider = CountingPagedProvider()
        val outcome = GifSearchGate(provider).search(
            allowed = true,
            query = "퇴근",
            page = 3,
            limit = 12
        ) as GifSearchOutcome.Results

        assertEquals(3, provider.page)
        assertEquals(12, provider.limit)
        assertEquals(true, outcome.hasNext)
    }

    private class CountingProvider : GifProvider {
        override val displayName = "test"
        var requests = 0

        override suspend fun search(query: String, limit: Int): List<GifResult> {
            requests++
            return emptyList()
        }
    }

    private class CountingPagedProvider : PagedGifProvider {
        override val displayName = "paged"
        var page = 0
        var limit = 0

        override suspend fun searchPage(query: String, page: Int, limit: Int): GifSearchPage {
            this.page = page
            this.limit = limit
            return GifSearchPage(emptyList(), hasNext = true)
        }
    }
}
