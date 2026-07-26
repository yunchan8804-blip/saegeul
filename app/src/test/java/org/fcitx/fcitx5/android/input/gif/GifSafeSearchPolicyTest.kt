/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GifSafeSearchPolicyTest {
    @Test
    fun allowsOrdinaryKoreanReactionAndMemeQueries() {
        listOf("퇴근 짤", "월요일 반응", "대박", "ㅋㅋㅋ", "축하").forEach {
            assertTrue(it, GifSafeSearchPolicy.isAllowedQuery(it))
        }
    }

    @Test
    fun blocksExplicitQueriesBeforeProviderRequest() {
        listOf("NSFW", "xxx gif", "야동", "포르노 짤", "18+").forEach {
            assertFalse(it, GifSafeSearchPolicy.isAllowedQuery(it))
        }
    }

    @Test
    fun filtersExplicitProviderMetadata() {
        assertFalse(GifSafeSearchPolicy.isAllowedResult("NSFW animation", "unsafe-1"))
        assertFalse(GifSafeSearchPolicy.isAllowedResult("GIF", "adult-only-scene"))
        assertTrue(GifSafeSearchPolicy.isAllowedResult("퇴근이다", "happy-reaction"))
    }
}
