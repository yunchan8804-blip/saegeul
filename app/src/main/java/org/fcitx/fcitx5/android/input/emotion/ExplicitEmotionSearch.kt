/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.emotion

import org.fcitx.fcitx5.android.input.search.KoreanSearchResult

sealed interface ExplicitEmotionSearchOutcome {
    data object Blocked : ExplicitEmotionSearchOutcome
    data class Results(val items: List<KoreanSearchResult>) : ExplicitEmotionSearchOutcome
}

/** Makes the privacy and explicit-query boundary testable without any editor or clipboard access. */
object ExplicitEmotionSearch {
    fun search(allowed: Boolean, explicitQuery: String): ExplicitEmotionSearchOutcome =
        if (!allowed) {
            ExplicitEmotionSearchOutcome.Blocked
        } else {
            ExplicitEmotionSearchOutcome.Results(KoreanEmotionLexicon.recommend(explicitQuery))
        }
}

enum class EmotionCommitResult { Success, Blocked, StaleEditor, AlreadyCommitted, Failed }

/** Exactly-once guard for a result explicitly tapped in one emotion-search window. */
class EmotionCommitGate {
    private var committed = false

    fun reset() {
        committed = false
    }

    fun commit(
        allowed: Boolean,
        sameEditor: Boolean,
        action: () -> Boolean
    ): EmotionCommitResult = when {
        !allowed -> EmotionCommitResult.Blocked
        !sameEditor -> EmotionCommitResult.StaleEditor
        committed -> EmotionCommitResult.AlreadyCommitted
        action() -> {
            committed = true
            EmotionCommitResult.Success
        }
        else -> EmotionCommitResult.Failed
    }
}
