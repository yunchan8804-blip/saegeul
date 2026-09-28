/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import org.fcitx.fcitx5.android.core.CandidateWord

/**
 * Ordering and de-duplication rules for the horizontal candidate bar's rows, kept free of
 * Android/service state so they can be unit tested on the JVM. [HorizontalCandidateComponent]
 * gathers the native, contextual and automatic candidates and decides whether the editor is an
 * address (email/URL) field; this object only decides the order in which they are shown.
 */
internal object HorizontalCandidateMerger {

    private const val TYPO_CORRECTION_BADGE = "✏️"

    /**
     * Merges native engine candidates with contextual (sentence pack / personalization)
     * candidates. Duplicates are dropped by [CandidateWord.text], keeping the first occurrence.
     *
     * In an [addressField] (email/URL), the actively composed native word comes first, followed by
     * the domain / TLD suggestion chips in [contextualWords] and then the remaining native
     * alternatives; [contextualSentences] are not shown. With no native candidates the contextual
     * words are returned as they are.
     *
     * In a conversational field, typo corrections (comment starting with the ✏️ badge) come right
     * after the composed native word for immediate single-tap correction, then the remaining native
     * alternatives, then the other contextual words and sentences.
     */
    fun merge(
        nativeList: List<CandidateWord>,
        contextualWords: List<CandidateWord>,
        contextualSentences: List<CandidateWord>,
        addressField: Boolean
    ): Array<CandidateWord> {
        val merged = mutableListOf<CandidateWord>()
        if (addressField) {
            if (nativeList.isEmpty()) return contextualWords.toTypedArray()
            merged.add(nativeList[0])
            merged.addNewTexts(contextualWords)
            merged.addNewTexts(nativeList.drop(1))
            return merged.toTypedArray()
        }

        val (typoWords, otherWords) = contextualWords.partition { it.isTypoCorrection() }
        val (typoSentences, otherSentences) = contextualSentences.partition { it.isTypoCorrection() }
        if (nativeList.isNotEmpty()) {
            merged.add(nativeList[0])
            merged.addNewTexts(typoWords)
            merged.addNewTexts(typoSentences)
            merged.addNewTexts(nativeList.drop(1))
        } else {
            merged.addNewTexts(typoWords)
            merged.addNewTexts(typoSentences)
        }
        merged.addNewTexts(otherWords)
        merged.addNewTexts(otherSentences)
        return merged.toTypedArray()
    }

    /**
     * Puts the on-device automatic suggestions in front of [legacy], dropping every legacy
     * candidate whose text an automatic suggestion already shows.
     */
    fun prependAutomatic(
        automatic: List<CandidateWord>,
        legacy: Array<CandidateWord>
    ): Array<CandidateWord> = (automatic + legacy.filterNot { legacyCandidate ->
        automatic.any { automaticCandidate -> automaticCandidate.text == legacyCandidate.text }
    }).toTypedArray()

    private fun CandidateWord.isTypoCorrection(): Boolean = comment.startsWith(TYPO_CORRECTION_BADGE)

    private fun MutableList<CandidateWord>.addNewTexts(candidates: List<CandidateWord>) {
        candidates.forEach { candidate ->
            if (none { it.text == candidate.text }) add(candidate)
        }
    }
}
