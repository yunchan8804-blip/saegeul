/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.prediction.source

import org.fcitx.fcitx5.android.input.ai.AiPrediction
import org.fcitx.fcitx5.android.input.ai.BundledKoreanNgram
import org.fcitx.fcitx5.android.input.ai.PersonalNgramModel
import org.fcitx.fcitx5.android.input.typo.BaseKoreanVocabulary
import org.fcitx.fcitx5.android.input.typo.CorrectionPatternStore
import org.fcitx.fcitx5.android.input.typo.DubeolsikKeyMap
import org.fcitx.fcitx5.android.input.typo.KeyboardAwareTypoCorrector
import org.fcitx.fcitx5.android.input.typo.SingleEditTypoProbe
import org.fcitx.fcitx5.android.input.typo.TouchHabit
import org.fcitx.fcitx5.android.input.typo.TouchTrace
import org.fcitx.fcitx5.android.input.typo.TypedKeyReader

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
 *
 * The trie reads the fragment through [typedKeyReader], so a fragment with stray jamo, Latin letters
 * or digits and symbols a letter key also types still reaches the word it was meant to be. A long
 * fragment (ten keys or more) also gets a wider cost limit, used only when neither the standard limit
 * nor the stem correction finds anything, so it never displaces a correction found without it.
 */
internal class KeyboardTypoCorrectionSource(
    private val typoCorrector: KeyboardAwareTypoCorrector?,
    private val baseVocabulary: BaseKoreanVocabulary?,
    private val correctionStore: CorrectionPatternStore?,
    private val ngram: PersonalNgramModel,
    private val touchTrace: (() -> TouchTrace?)? = null,
    private val typedKeyReader: TypedKeyReader = TypedKeyReader { emptyMap() },
    touchHabit: TouchHabit? = null,
    private val bundledNgram: () -> BundledKoreanNgram?
) {

    private val probe = SingleEditTypoProbe(baseVocabulary, ngram, touchHabit)

    companion object {
        private const val MAX_KEYBOARD_CORRECTIONS = 3

        // Trie and probe hits gathered before the ranking filter and the final cut.
        private const val CORRECTION_POOL = 10

        // Trie hits gathered under the widened cost limit of a long fragment, before the split into
        // hits within the standard limit and hits only the widened limit reaches.
        private const val WIDENED_POOL = 40

        // An input outside the trie but inside the base vocabulary is an ordinary rare word: a
        // correction has to be this many times more frequent than it to be offered. A Shift
        // undo (햇는데 -> 했는데) is a likelier slip, so it only has to clear
        // SingleEditTypoProbe.UPGRADE_RANK_RATIO.
        private const val RARE_WORD_RANK_RATIO = 1000

        // Leading/trailing punctuation stripped off the typed fragment before keyboard-aware
        // typo correction runs, so a trailing "???" or similar does not blow the key-distance
        // cost budget and suppress an otherwise-valid correction.
        internal val HEAD_PUNCTUATION = "([{«\"'“‘「『〈《".toSet()
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
        val trace = touchTrace?.invoke()
        val reading = typedKeyReader.read(core)
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
        } else if (core.any(Char::isLetter) && reading != null) {
            val isBaseKnown = baseVocabulary?.containsWithinTop(core, BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) == true
            if (!isBaseKnown) {
                val isPersonalKnownOnly = ngram.unigramCount(core) >= 2f
                val ctxProb = ngram.predictNext(input.contextBeforeCursor, packageName, 20)
                    .associate { it.word to it.probability }
                // 오타 어절이 문장 첫 어절이면 문장 시작 분포(`<s>`)도 문맥 가중에 더한다.
                val sentenceStart = bundledNgram()?.takeIf {
                    input.isContextKnown && BundledKoreanNgram.isSentenceStart(contextBeforeTarget(input))
                }
                val contextBoost: (String) -> Float = { w ->
                    1.5f * (ctxProb[w] ?: 0f) + 1.5f * (sentenceStart?.sentenceStartProbability(w) ?: 0f)
                }
                val limit = if (isPersonalKnownOnly) 1 else MAX_KEYBOARD_CORRECTIONS
                val inputRank = baseVocabulary?.rankOf(core) ?: 0
                val probeHits = probe.probe(core, limit = CORRECTION_POOL, contextBoost = contextBoost, touchTrace = trace)
                val shiftWords = probeHits.filter { it.isShiftEdit }.map { it.correction.word }.toSet()
                val accepts = { word: String -> inputRank == 0 || outranksRareWord(word, inputRank, word in shiftWords) }
                // 긴 어절이 받아들이는 넓힌 상한까지의 트라이 후보. 표준 상한 안의 후보가 하나라도 있으면 늘 그것만 쓰고,
                // 넓힌 상한에서만 닿는 후보는 표준 상한 후보도 어미를 뗀 교정도 없을 때만 쓴다.
                val standardMaxCost = KeyboardAwareTypoCorrector.standardMaxCost(reading.keys.length)
                val trieHits = typoCorrector.correct(
                    core,
                    limit = WIDENED_POOL,
                    maxCost = KeyboardAwareTypoCorrector.keyboardMaxCost(reading.keys.length),
                    contextBoost = contextBoost,
                    touchTrace = trace,
                    reading = reading
                )
                fun emitKeyboardCorrections(found: List<KeyboardAwareTypoCorrector.Correction>) {
                    found.forEachIndexed { idx, correction ->
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
                }
                val corrections = mergeCorrections(
                    trieHits.filter { it.cost <= standardMaxCost }.take(CORRECTION_POOL),
                    probeHits.map { it.correction },
                    limit,
                    accepts
                )
                if (corrections.isNotEmpty()) {
                    handledFragment = typed
                }
                emitKeyboardCorrections(corrections)
                if (corrections.isEmpty()) {
                    var stemFound = false
                    val ending = BOUND_ENDINGS.firstOrNull { core.endsWith(it) }
                    if (ending != null) {
                        val stem = core.dropLast(ending.length)
                        if (DubeolsikKeyMap.keySequence(stem).length >= 3 &&
                            baseVocabulary?.containsWithinTop(stem, BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) != true
                        ) {
                            // 어미를 뗀 앞부분의 흔적: 어절 흔적에서 어미를 친 키들을 뺀다.
                            val stemTrace = trace?.withoutLast(DubeolsikKeyMap.keySequence(ending).length)
                            val stemReading = typedKeyReader.read(stem)
                            val stemCorrection = typoCorrector.correct(
                                stem,
                                limit = 1,
                                maxCost = KeyboardAwareTypoCorrector.defaultMaxCost(stem),
                                contextBoost = contextBoost,
                                touchTrace = stemTrace,
                                reading = stemReading
                            ).firstOrNull()?.takeIf { it.cost <= 1.0f }
                                ?: probe.correct(
                                    stem,
                                    limit = 1,
                                    contextBoost = contextBoost,
                                    touchTrace = stemTrace
                                ).firstOrNull()
                            if (stemCorrection != null && stemCorrection.cost <= 1.0f) {
                                stemFound = true
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
                    if (!stemFound) {
                        val widened = mergeCorrections(
                            trieHits.filter { it.cost > standardMaxCost }.take(CORRECTION_POOL),
                            emptyList(),
                            limit,
                            accepts
                        )
                        if (widened.isNotEmpty()) {
                            handledFragment = typed
                        }
                        emitKeyboardCorrections(widened)
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

    // The text before the typo target's first character: the context minus the stroke being typed,
    // or minus the committed last word when the target is that word.
    private fun contextBeforeTarget(input: PredictionInput): String =
        if (input.cleanStroke.isNotBlank()) {
            input.contextBeforeCursor.removeSuffix(input.cleanStroke)
        } else {
            input.cleanContext.removeSuffix(input.lastWordInContext)
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
