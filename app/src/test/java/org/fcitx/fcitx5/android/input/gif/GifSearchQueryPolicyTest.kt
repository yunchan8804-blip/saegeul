/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import org.junit.Assert.assertEquals
import org.junit.Test

class GifSearchQueryPolicyTest {
    @Test
    fun `GIPHY prompt stays within the provider query limit`() {
        val query = "가".repeat(GifSearchQueryPolicy.GIPHY_MAX_CHARACTERS + 8)

        assertEquals(
            GifSearchQueryPolicy.GIPHY_MAX_CHARACTERS,
            GifSearchQueryPolicy.maxCharacters(GifProviderKind.Giphy)
        )
        assertEquals(
            "가".repeat(GifSearchQueryPolicy.GIPHY_MAX_CHARACTERS),
            GifSearchQueryPolicy.normalize("  $query  ", GifProviderKind.Giphy)
        )
    }

    @Test
    fun `other providers keep the common eighty character boundary and blank trending query`() {
        val query = "x".repeat(GifSearchQueryPolicy.DEFAULT_MAX_CHARACTERS + 8)

        assertEquals(
            GifSearchQueryPolicy.DEFAULT_MAX_CHARACTERS,
            GifSearchQueryPolicy.maxCharacters(GifProviderKind.Klipy)
        )
        assertEquals(
            "x".repeat(GifSearchQueryPolicy.DEFAULT_MAX_CHARACTERS),
            GifSearchQueryPolicy.normalize(query, GifProviderKind.AnimatedNoto)
        )
        assertEquals(
            "x".repeat(GifSearchQueryPolicy.DEFAULT_MAX_CHARACTERS),
            GifSearchQueryPolicy.normalize(query, GifProviderKind.Commons)
        )
        assertEquals("", GifSearchQueryPolicy.normalize("   ", GifProviderKind.Klipy))
    }
}
