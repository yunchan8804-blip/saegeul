/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhraseManager
import org.fcitx.fcitx5.android.input.emotion.KoreanEmotionLexicon

class KoreanSearchRepository {
    suspend fun loadEntries(clipboardLimit: Int = 200): List<KoreanSearchEntry> =
        withContext(Dispatchers.IO) {
            val emotionEntries = KoreanEmotionLexicon.entries
            val emotionCommitTexts = emotionEntries.map(KoreanSearchEntry::commitText).toHashSet()
            loadQuickPhrases() + loadClipboard(clipboardLimit) + emotionEntries +
                KoreanEmojiKeywords.entries.filterNot { it.commitText in emotionCommitTexts }
        }

    private fun loadQuickPhrases(): List<KoreanSearchEntry> = runCatching {
        QuickPhraseManager.listQuickPhrase()
            .asSequence()
            .filter { it.isEnabled }
            .flatMap { phraseFile ->
                phraseFile.loadData().asSequence().mapIndexed { index, entry ->
                    KoreanSearchEntry(
                        id = "quick:${phraseFile.name}:$index",
                        source = KoreanSearchSource.QuickPhrase,
                        primaryText = entry.phrase,
                        secondaryText = entry.keyword,
                        searchTerms = listOf(entry.keyword, entry.phrase)
                    )
                }
            }
            .toList()
    }.getOrDefault(emptyList())

    private suspend fun loadClipboard(limit: Int): List<KoreanSearchEntry> =
        ClipboardManager.searchableEntries(limit)
            .filterNot { it.sensitive }
            .map { entry ->
                KoreanSearchEntry(
                    id = "clipboard:${entry.id}",
                    source = KoreanSearchSource.Clipboard,
                    primaryText = entry.text,
                    searchTerms = listOf(entry.text),
                    sensitive = entry.sensitive
                )
            }
}
