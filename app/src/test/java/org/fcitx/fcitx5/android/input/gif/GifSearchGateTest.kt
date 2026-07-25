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

    private class CountingProvider : GifProvider {
        override val displayName = "test"
        var requests = 0

        override suspend fun search(query: String, limit: Int): List<GifResult> {
            requests++
            return emptyList()
        }
    }
}
