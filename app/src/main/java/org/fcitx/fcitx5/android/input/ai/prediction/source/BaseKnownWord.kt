/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.prediction.source

import org.fcitx.fcitx5.android.input.ai.typo.BaseKoreanVocabulary

/**
 * Whether [word], less the punctuation around it, is an ordinary word the base vocabulary knows
 * (top [BaseKoreanVocabulary.TYPO_VOCAB_LIMIT]). The legacy fuzzy typo engine must leave such
 * words alone; only its explicit typo rules may correct them.
 */
internal fun BaseKoreanVocabulary?.knowsOrdinaryWord(word: String): Boolean {
    val core = word.trim {
        it in KeyboardTypoCorrectionSource.HEAD_PUNCTUATION || it in KeyboardTypoCorrectionSource.TAIL_PUNCTUATION
    }
    return this?.containsWithinTop(core, BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) == true
}
