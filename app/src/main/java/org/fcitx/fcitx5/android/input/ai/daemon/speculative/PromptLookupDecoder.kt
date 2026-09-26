/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.daemon.speculative

import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter

/**
 * Speculative Prompt Lookup Decoding (PLD) engine for mobile IME.
 * Performs sub-millisecond n-gram match against context and filters candidates
 * through phonological Josa bitmasking and syntactic rule verification.
 */
object PromptLookupDecoder {

    /**
     * Decodes the speculative continuation draft based on n-gram matching in [referenceContext] and [prompt].
     *
     * @param prompt Current user input prefix.
     * @param referenceContext Preceding discourse, document context, or conversation history.
     * @param maxTokens Maximum number of words/tokens to speculatively extract.
     * @param ngramSize Length of the terminal n-gram used for matching (default 2).
     * @return Grammatically validated and phonologically corrected speculative draft, or empty string on failure.
     */
    fun decode(
        prompt: String,
        referenceContext: String,
        maxTokens: Int = 16,
        ngramSize: Int = 2
    ): String {
        val trimmedPrompt = prompt.trimEnd()
        if (trimmedPrompt.isEmpty() || (referenceContext.isBlank() && prompt.isBlank())) {
            return ""
        }

        val searchCorpus = if (referenceContext.isBlank()) {
            prompt
        } else {
            "$referenceContext $prompt"
        }

        // 1. Extract terminal n-gram (character-level or word-level)
        val effectiveNgramSize = ngramSize.coerceAtLeast(1)
        val terminalNgram = if (trimmedPrompt.length >= effectiveNgramSize) {
            trimmedPrompt.takeLast(effectiveNgramSize)
        } else {
            trimmedPrompt
        }

        // 2. Find previous occurrence in referenceContext + prompt
        var matchIndex = findPreviousOccurrence(searchCorpus, terminalNgram)
        var matchedNgramLength = terminalNgram.length

        // Fallback: try word-level n-gram if character n-gram didn't find a match
        if (matchIndex == -1) {
            val lastWord = trimmedPrompt.split(Regex("\\s+")).lastOrNull()?.trim()
            if (!lastWord.isNullOrEmpty() && lastWord != terminalNgram) {
                matchIndex = findPreviousOccurrence(searchCorpus, lastWord)
                matchedNgramLength = lastWord.length
            }
        }

        if (matchIndex == -1) {
            return ""
        }

        // 3. Extract draft candidate following the matched occurrence
        val draftStart = matchIndex + matchedNgramLength
        if (draftStart >= searchCorpus.length) {
            return ""
        }

        val maxDraftChars = (maxTokens.coerceAtLeast(1) * 32 + 64).coerceAtLeast(256)
        val draftEnd = (draftStart + maxDraftChars).coerceAtMost(searchCorpus.length)
        val following = searchCorpus.substring(draftStart, draftEnd)
        val hasLeadingSpace = following.startsWith(" ") || prompt.endsWith(" ")
        val safePrompt = if (trimmedPrompt.length > 256) trimmedPrompt.takeLast(256) else trimmedPrompt
        val mergedText = if (hasLeadingSpace) {
            "$safePrompt ${following.trimStart()}"
        } else {
            "$safePrompt$following"
        }

        // 4. Correct Josa mismatches across prompt boundary and draft
        val correctedMerged = KoreanJosaBitmaskEngine.correctJosaMismatch(mergedText)
        val extractedDraftPart = if (hasLeadingSpace) {
            val prefixWithSpace = "$safePrompt "
            if (correctedMerged.length >= prefixWithSpace.length) {
                correctedMerged.substring(prefixWithSpace.length).trimStart()
            } else {
                correctedMerged.removePrefix(safePrompt).trimStart()
            }
        } else {
            correctedMerged.substring(safePrompt.length.coerceAtMost(correctedMerged.length))
        }

        val words = extractedDraftPart.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) {
            return ""
        }

        val candidateWords = words.take(maxTokens.coerceAtLeast(1))
        val candidateDraft = candidateWords.joinToString(" ")
        val correctedDraft = KoreanJosaBitmaskEngine.correctJosaMismatch(candidateDraft)
        if (correctedDraft.isBlank()) {
            return ""
        }

        if (KoreanSyntaxRuleFilter.isGrammaticallySound(correctedDraft, prompt)) {
            return correctedDraft
        }

        // 5. If full draft fails validation, iteratively trim from tail to find sound partial draft
        val draftWords = correctedDraft.split(" ")
        for (count in (draftWords.size - 1) downTo 1) {
            val partial = draftWords.take(count).joinToString(" ")
            val partialCorrected = KoreanJosaBitmaskEngine.correctJosaMismatch(partial)
            if (partialCorrected.isNotBlank() && KoreanSyntaxRuleFilter.isGrammaticallySound(partialCorrected, prompt)) {
                return partialCorrected
            }
        }

        return ""
    }

    private fun findPreviousOccurrence(corpus: String, pattern: String): Int {
        if (pattern.isEmpty()) return -1
        val lastIdx = corpus.lastIndexOf(pattern)
        return if (lastIdx > 0) {
            corpus.lastIndexOf(pattern, lastIdx - 1)
        } else {
            -1
        }
    }
}
