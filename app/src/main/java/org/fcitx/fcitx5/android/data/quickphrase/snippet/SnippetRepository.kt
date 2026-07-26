/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase.snippet

import org.fcitx.fcitx5.android.data.quickphrase.QuickPhraseManager

object SnippetRepository {
    /** File IO belongs on a background dispatcher. Values are never logged or persisted here. */
    fun load(): SnippetCatalog {
        val definitions = QuickPhraseManager.listQuickPhrase()
            .asSequence()
            .filter { it.isEnabled }
            .flatMap { quickPhrase ->
                runCatching { quickPhrase.loadData().asSequence() }
                    .getOrDefault(emptySequence())
            }
            .filter { SnippetCatalog.isValidTrigger(it.keyword) }
            .map { SnippetDefinition(it.keyword, it.phrase) }
            .toList()
        return SnippetCatalog.fromUserDefinitions(definitions)
    }
}
