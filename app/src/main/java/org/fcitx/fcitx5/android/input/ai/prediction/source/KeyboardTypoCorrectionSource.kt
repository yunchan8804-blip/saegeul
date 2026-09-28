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

/**
 * Keyboard-aware corrections for the typed fragment, plus [handledFragment]: the fragment these
 * corrections cover, which the legacy typo engine must not correct a second time.
 */
internal class KeyboardTypoCorrections(val candidates: List<AiPrediction>, val handledFragment: String?)

/**
 * Top-priority typo correction (`typo_personal`, `typo_keyboard`, `typo_keyboard_stem`): the user's
 * own correction patterns first, then key-distance corrections against the base vocabulary, then a
 * stem correction under a common bound ending.
 */
internal class KeyboardTypoCorrectionSource(
    private val typoCorrector: KeyboardAwareTypoCorrector?,
    private val baseVocabulary: BaseKoreanVocabulary?,
    private val correctionStore: CorrectionPatternStore?,
    private val ngram: PersonalNgramModel
) {

    companion object {
        // Leading/trailing punctuation stripped off the typed fragment before keyboard-aware
        // typo correction runs, so a trailing "???" or similar does not blow the key-distance
        // cost budget and suppress an otherwise-valid correction.
        private val HEAD_PUNCTUATION = "([{«\"'".toSet()
        private val TAIL_PUNCTUATION = ".,!?~…:;)]}»\"'".toSet()

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
                val corrections = typoCorrector.correct(
                    core,
                    limit = if (isPersonalKnownOnly) 1 else 2,
                    contextBoost = contextBoost
                )
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
                            ).firstOrNull()
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
            }
        }
        return KeyboardTypoCorrections(candidates, handledFragment)
    }
}
