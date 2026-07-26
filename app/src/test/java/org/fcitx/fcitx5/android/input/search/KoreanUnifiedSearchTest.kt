/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KoreanUnifiedSearchTest {
    @Test
    fun extractsInitialsFromHangulAndIgnoresSeparators() {
        assertEquals("ㄱㅅㅎㄴㄷ", KoreanInitials.extract("감사 합니다!"))
        assertEquals("ㄱㅅ", KoreanInitials.extract("ㄱㅅ"))
        assertTrue(KoreanInitials.isInitialQuery("ㄱ ㅅ"))
        assertFalse(KoreanInitials.isInitialQuery("감사"))
    }

    @Test
    fun findsConsecutiveInitialsInQuickPhrasesClipboardAndEmojiKeywords() {
        val results = KoreanUnifiedSearch.search("ㄱㅅ", listOf(
            entry("quick", KoreanSearchSource.QuickPhrase, "감사합니다"),
            entry("clipboard", KoreanSearchSource.Clipboard, "오늘도 고생했어"),
            KoreanSearchEntry(
                id = "emoji",
                source = KoreanSearchSource.Emoji,
                primaryText = "🙏",
                searchTerms = listOf("감사")
            ),
            entry("miss", KoreanSearchSource.QuickPhrase, "안녕하세요")
        ))

        assertEquals(listOf("emoji", "quick", "clipboard"), results.map { it.entry.id })
    }

    @Test
    fun ordinaryTextMatchingIsCaseInsensitiveAndPrefixFirst() {
        val results = KoreanUnifiedSearch.search("hello", listOf(
            entry("contains", KoreanSearchSource.QuickPhrase, "say HELLO now"),
            entry("prefix", KoreanSearchSource.Clipboard, "Hello world"),
            entry("exact", KoreanSearchSource.Emoji, "ignored", listOf("HELLO"))
        ))

        assertEquals(listOf("exact", "prefix", "contains"), results.map { it.entry.id })
    }

    @Test
    fun sourcePriorityBreaksEqualScores() {
        val results = KoreanUnifiedSearch.search("감사", listOf(
            entry("emoji", KoreanSearchSource.Emoji, "감사 이모지"),
            entry("clipboard", KoreanSearchSource.Clipboard, "감사 클립"),
            entry("quick", KoreanSearchSource.QuickPhrase, "감사 문구")
        ))

        assertEquals(listOf("quick", "clipboard", "emoji"), results.map { it.entry.id })
    }

    @Test
    fun excludesSensitiveEntriesAndDeduplicatesWithinSource() {
        val results = KoreanUnifiedSearch.search("계좌", listOf(
            entry("sensitive", KoreanSearchSource.Clipboard, "계좌 123", sensitive = true),
            entry("first", KoreanSearchSource.Clipboard, "계좌 안내"),
            entry("duplicate", KoreanSearchSource.Clipboard, "계좌 안내"),
            entry("other-source", KoreanSearchSource.QuickPhrase, "계좌 안내")
        ))

        assertEquals(listOf("other-source", "first"), results.map { it.entry.id })
    }

    @Test
    fun emotionAndLegacyEmojiCandidatesDeduplicateByCommittedExpression() {
        val results = KoreanUnifiedSearch.search("감사", listOf(
            entry("emotion", KoreanSearchSource.Emotion, "🙏", listOf("감사")),
            entry("emoji", KoreanSearchSource.Emoji, "🙏", listOf("감사")),
            entry("other", KoreanSearchSource.Emoji, "🫶", listOf("감사"))
        ))

        assertEquals(listOf("emotion", "other"), results.map { it.entry.id })
    }

    @Test
    fun blankQueryAndLimitAreFailClosed() {
        val entries = (1..5).map {
            entry("$it", KoreanSearchSource.QuickPhrase, "감사 $it")
        }
        assertTrue(KoreanUnifiedSearch.search(" ", entries).isEmpty())
        assertEquals(2, KoreanUnifiedSearch.search("감사", entries, limit = 2).size)
        assertTrue(KoreanUnifiedSearch.search("감사", entries, limit = 0).isEmpty())
    }

    private fun entry(
        id: String,
        source: KoreanSearchSource,
        text: String,
        terms: List<String> = listOf(text),
        sensitive: Boolean = false
    ) = KoreanSearchEntry(
        id = id,
        source = source,
        primaryText = text,
        commitText = text,
        searchTerms = terms,
        sensitive = sensitive
    )
}
