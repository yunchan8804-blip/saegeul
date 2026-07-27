/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.emotion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KoreanEmotionLexiconTest {
    @Test
    fun everyRequiredEmotionChipHasRankedEmojiAndKoreanExpressionCandidates() {
        val required = listOf(
            "축하", "죄송", "감사", "당황", "웃음", "사랑", "화남", "슬픔", "응원", "인정"
        )

        required.forEach { query ->
            val results = KoreanEmotionLexicon.recommend(query)
            assertTrue(query, results.size >= 4)
            assertTrue(query, results.zipWithNext().all { (left, right) -> left.score <= right.score })
            assertEquals(query, results.map { it.entry.commitText }.distinct().size, results.size)
        }
    }

    @Test
    fun laughterStrengthChangesTheTopRankedEmoji() {
        assertEquals("😂", KoreanEmotionLexicon.recommend("ㅋㅋ").first().entry.commitText)
        assertEquals("🤣", KoreanEmotionLexicon.recommend("ㅋㅋㅋㅋ").first().entry.commitText)
        assertEquals("😊", KoreanEmotionLexicon.recommend("ㅎㅎ").first().entry.commitText)
        assertEquals("😄", KoreanEmotionLexicon.recommend("ㅎㅎㅎㅎ").first().entry.commitText)
    }

    @Test
    fun weakAndStrongLaughterProfilesAreDirectlyAvailableAsQuickQueries() {
        assertTrue(KoreanEmotionLexicon.quickQueries.containsAll(listOf(
            "ㅋㅋ", "ㅋㅋㅋㅋ", "ㅎㅎ", "ㅎㅎㅎㅎ"
        )))
    }

    @Test
    fun unifiedCatalogHasNoDuplicateCommitText() {
        assertEquals(
            KoreanEmotionLexicon.entries.size,
            KoreanEmotionLexicon.entries.map { it.commitText }.distinct().size
        )
    }

    @Test
    fun unknownOrBlankQueryReturnsNothing() {
        assertTrue(KoreanEmotionLexicon.recommend(" ").isEmpty())
        assertTrue(KoreanEmotionLexicon.recommend("주소").isEmpty())
    }
}
