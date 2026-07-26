/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KoreanGifQueryPlannerTest {
    @Test
    fun koreanChatReactionGetsBoundedDistinctFallbacks() {
        val fallbacks = KoreanGifQueryPlanner.emptyResultFallbacks("오늘 진짜 ㅋㅋㅋ")

        assertEquals(listOf("웃긴 반응", "폭소"), fallbacks)
    }

    @Test
    fun localTermsKeepExactQueryAndAddReactionMetadata() {
        val terms = KoreanGifQueryPlanner.localSearchTerms("월요일 출근")

        assertTrue(terms.any { it.value == "월요일 출근" && it.weight == 160 })
        assertTrue(terms.any { it.value == "월요일" })
        assertTrue(terms.any { it.value == "tired" })
        assertTrue(terms.any { it.value == "coffee" })
    }

    @Test
    fun unsafeOrUnknownQueryDoesNotCreateNetworkFallbacks() {
        assertTrue(KoreanGifQueryPlanner.emptyResultFallbacks("nsfw").isEmpty())
        assertTrue(KoreanGifQueryPlanner.emptyResultFallbacks("완전히 새로운 단어").isEmpty())
    }
}
