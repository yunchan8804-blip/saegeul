/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.prediction.source

import org.fcitx.fcitx5.android.input.ai.AiPrediction
import org.fcitx.fcitx5.android.input.ai.PersonalNgramModel
import org.fcitx.fcitx5.android.input.ai.typo.BaseKoreanVocabulary
import org.fcitx.fcitx5.android.input.ai.typo.CorrectionPatternStore
import org.fcitx.fcitx5.android.input.ai.typo.DubeolsikKeyMap
import org.fcitx.fcitx5.android.input.ai.typo.KeyboardAwareTypoCorrector
import org.fcitx.fcitx5.android.input.ai.typo.SingleEditTypoProbe

/**
 * Keyboard-aware corrections for the typed fragment, plus [handledFragment]: the fragment these
 * corrections cover, which the legacy typo engine must not correct a second time.
 */
internal class KeyboardTypoCorrections(val candidates: List<AiPrediction>, val handledFragment: String?)

/**
 * Top-priority typo correction (`typo_personal`, `typo_keyboard`, `typo_keyboard_stem`,
 * `typo_keyboard_known`): the user's own correction patterns first, then key-distance corrections
 * against the base vocabulary merged with single-edit probe hits over the whole vocabulary, then a
 * stem correction under a common bound ending. A word the trie knows is only replaced when the
 * probe finds a far more common word one Shift or compound-vowel edit away, and a rare word the
 * trie lacks but the base vocabulary has only takes corrections far more common than itself.
 */
internal class KeyboardTypoCorrectionSource(
    private val typoCorrector: KeyboardAwareTypoCorrector?,
    private val baseVocabulary: BaseKoreanVocabulary?,
    private val correctionStore: CorrectionPatternStore?,
    private val ngram: PersonalNgramModel
) {

    private val probe = SingleEditTypoProbe(baseVocabulary, ngram)

    companion object {
        private const val MAX_KEYBOARD_CORRECTIONS = 3

        // Trie and probe hits gathered before the ranking filter and the final cut.
        private const val CORRECTION_POOL = 10

        // An input outside the trie but inside the base vocabulary is an ordinary rare word: a
        // correction has to be this many times more frequent than it to be offered. A Shift
        // undo (햇는데 -> 했는데) is a likelier slip, so it only has to clear
        // SingleEditTypoProbe.UPGRADE_RANK_RATIO.
        private const val RARE_WORD_RANK_RATIO = 1000

        // Leading/trailing punctuation stripped off the typed fragment before keyboard-aware
        // typo correction runs, so a trailing "???" or similar does not blow the key-distance
        // cost budget and suppress an otherwise-valid correction.
        internal val HEAD_PUNCTUATION = "([{«\"'".toSet()
        internal val TAIL_PUNCTUATION = ".,!?~…:;)]}»\"'".toSet()

        // Common bound-ending suffixes (조사/어미) tried, longest-first, when the typed fragment
        // itself has no direct keyboard-aware correction: the stem before the ending is corrected
        // instead so an inflected form like "걸림건가" (stem "걸림" + ending "건가") still resolves.
        private val BOUND_ENDINGS = listOf(
            "건가요", "건데요", "거든요", "잖아요", "인가요", "이라고",
            "건가", "건데", "건지", "거야", "거지", "거든", "거임", "네요", "세요", "어요", "아요",
            "는데", "은데", "을까", "를까", "니까", "지만", "다고", "라고", "잖아", "인가", "인데",
            "이야", "이지", "까지", "부터", "에서", "으로", "한테", "에게", "처럼", "보다", "마다",
            "조차", "마저", "밖에", "이나", "든지", "는지"
        )
    }

    fun correct(input: PredictionInput, packageName: String): KeyboardTypoCorrections {
        val target = input.typoTarget
        if (typoCorrector == null || target == null) return KeyboardTypoCorrections(emptyList(), null)
        val typed = target.typed
        val replaceLen = target.replaceLength
        val head = typed.takeWhile { it in HEAD_PUNCTUATION }
        val afterHead = typed.substring(head.length)
        val tail = afterHead.takeLastWhile { it in TAIL_PUNCTUATION }
        val core = afterHead.substring(0, afterHead.length - tail.length)
        if (DubeolsikKeyMap.keySequence(core).length < 3) return KeyboardTypoCorrections(emptyList(), null)

        val candidates = mutableListOf<AiPrediction>()
        var handledFragment: String? = null
        val personalHits = correctionStore?.lookup(core, 2).orEmpty()
        if (personalHits.isNotEmpty()) {
            handledFragment = typed
            personalHits.forEachIndexed { idx, hit ->
                candidates += AiPrediction(
                    text = "$head${hit.corrected}$tail",
                    confidenceScore = 0.998f - idx * 0.001f,
                    isSentenceCompletion = false,
                    source = "typo_personal",
                    badge = "✏️",
                    replaceLength = replaceLen
                )
            }
        } else if (core.any(Char::isLetter)) {
            val isBaseKnown = baseVocabulary?.containsWithinTop(core, BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) == true
            if (!isBaseKnown) {
                val isPersonalKnownOnly = ngram.unigramCount(core) >= 2f
                val ctxProb = ngram.predictNext(input.contextBeforeCursor, packageName, 20)
                    .associate { it.word to it.probability }
                val contextBoost: (String) -> Float = { w -> 1.5f * (ctxProb[w] ?: 0f) }
                val limit = if (isPersonalKnownOnly) 1 else MAX_KEYBOARD_CORRECTIONS
                val inputRank = baseVocabulary?.rankOf(core) ?: 0
                val probeHits = probe.probe(core, limit = CORRECTION_POOL, contextBoost = contextBoost)
                val shiftWords = probeHits.filter { it.isShiftEdit }.map { it.correction.word }.toSet()
                val corrections = mergeCorrections(
                    typoCorrector.correct(core, limit = CORRECTION_POOL, contextBoost = contextBoost),
                    probeHits.map { it.correction },
                    limit
                ) { word -> inputRank == 0 || outranksRareWord(word, inputRank, word in shiftWords) }
                if (corrections.isNotEmpty()) {
                    handledFragment = typed
                }
                corrections.forEachIndexed { idx, correction ->
                    var confidence = if (correction.cost <= 0.6f) 0.996f else 0.994f - idx * 0.002f
                    if (isPersonalKnownOnly) confidence -= 0.006f
                    candidates += AiPrediction(
                        text = "$head${correction.word}$tail",
                        confidenceScore = confidence,
                        isSentenceCompletion = false,
                        source = "typo_keyboard",
                        badge = "✏️",
                        replaceLength = replaceLen
                    )
                }
                if (corrections.isEmpty()) {
                    val ending = BOUND_ENDINGS.firstOrNull { core.endsWith(it) }
                    if (ending != null) {
                        val stem = core.dropLast(ending.length)
                        if (DubeolsikKeyMap.keySequence(stem).length >= 3 &&
                            baseVocabulary?.containsWithinTop(stem, BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) != true
                        ) {
                            val stemCorrection = typoCorrector.correct(
                                stem,
                                limit = 1,
                                contextBoost = contextBoost
                            ).firstOrNull()?.takeIf { it.cost <= 1.0f }
                                ?: probe.correct(stem, limit = 1, contextBoost = contextBoost).firstOrNull()
                            if (stemCorrection != null && stemCorrection.cost <= 1.0f) {
                                handledFragment = typed
                                candidates += AiPrediction(
                                    text = "$head${stemCorrection.word}$ending$tail",
                                    confidenceScore = 0.993f,
                                    isSentenceCompletion = false,
                                    source = "typo_keyboard_stem",
                                    badge = "✏️",
                                    replaceLength = replaceLen
                                )
                            }
                        }
                    }
                }
            } else {
                val upgrade = probe.upgradeKnownWord(core)
                if (upgrade != null) {
                    handledFragment = typed
                    candidates += AiPrediction(
                        text = "$head${upgrade.word}$tail",
                        confidenceScore = 0.990f,
                        isSentenceCompletion = false,
                        source = "typo_keyboard_known",
                        badge = "✏️",
                        replaceLength = replaceLen
                    )
                }
            }
        }
        return KeyboardTypoCorrections(candidates, handledFragment)
    }

    // A word only the user's own vocabulary knows has no rank and is kept.
    private fun outranksRareWord(word: String, inputRank: Int, isShiftUndo: Boolean): Boolean {
        val rank = baseVocabulary?.rankOf(word) ?: 0
        val ratio = if (isShiftUndo) SingleEditTypoProbe.UPGRADE_RANK_RATIO else RARE_WORD_RANK_RATIO
        return rank == 0 || rank.toLong() * ratio < inputRank
    }

    // Trie and probe hits merged: one entry per word (the cheaper edit), best score first.
    private fun mergeCorrections(
        trie: List<KeyboardAwareTypoCorrector.Correction>,
        probed: List<KeyboardAwareTypoCorrector.Correction>,
        limit: Int,
        accepts: (String) -> Boolean
    ): List<KeyboardAwareTypoCorrector.Correction> =
        (trie + probed)
            .filter { accepts(it.word) }
            .groupBy { it.word }
            .map { (_, sameWord) -> sameWord.minBy { it.cost } }
            .sortedWith(compareByDescending<KeyboardAwareTypoCorrector.Correction> { it.score }.thenBy { it.cost })
            .take(limit)
}
