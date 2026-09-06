/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault
import org.fcitx.fcitx5.android.input.ai.typo.BaseKoreanVocabulary
import org.fcitx.fcitx5.android.input.ai.typo.CorrectionPatternStore
import org.fcitx.fcitx5.android.input.ai.typo.DubeolsikKeyMap
import org.fcitx.fcitx5.android.input.ai.typo.KeyboardAwareTypoCorrector

data class AiPrediction(
    val text: String,
    val confidenceScore: Float,
    val isSentenceCompletion: Boolean = false,
    val source: String = "local_ai",
    val badge: String = "✨ AI완성",
    val replaceLength: Int = 0
)

/**
 * Realtime AI Stroke-Level Next Word & Sentence Prediction Engine for Saegeul Keyboard.
 * Combines Jaso decomposition, Choseong matching, Personalized N-gram Markov context,
 * and built-in Korean conversational templates for sub-5ms instant suggestions.
 */
class AiContextualPredictor(
    private val morphology: ChoseongMorphologyEngine,
    val semanticPredictor: KoreanSemanticSentencePredictor = KoreanSemanticSentencePredictor(),
    val prefetcher: AiSentenceCompletionPrefetcher? = null,
    val personalizedStore: PersonalizedSentenceStore? = null,
    val typoEngine: KoreanTypoCorrectionEngine = KoreanTypoCorrectionEngine(),
    val collocationModel: KoreanCollocationModel = KoreanCollocationModel(),
    private val ngram: PersonalNgramModel = PersonalNgramModel(),
    private val typoCorrector: KeyboardAwareTypoCorrector? = null,
    private val baseVocabulary: BaseKoreanVocabulary? = null,
    private val correctionStore: CorrectionPatternStore? = null,
    private val sentenceContinuation: KoreanSentenceContinuation? = null,
    private val personalSentenceVault: PersonalSentenceVault? = null
) {

    companion object {
        // The only sources allowed to produce a full-sentence (isSentenceCompletion=true)
        // candidate: input_continuation (learned/typed-prefix continuation), personalized_style
        // (the user's own SOURCE_USER_PHRASE sentences), and llm_cached (LLM-generated
        // continuation of the user's own context, or corrections of what the user typed).
        // Only hardcoded-content sources are blocked from the sentence line at the end of predict().
        private val SENTENCE_LINE_SOURCE_BLOCKLIST = setOf("collocation_next_word", "base_lexicon")
    }

    // Falls back to this predictor's own ngram/collocationModel when no explicit instance is
    // wired in, so callers that don't pass sentenceContinuation still get input-preserving
    // completions. Created once and cached, never per predict() call.
    private val effectiveSentenceContinuation: KoreanSentenceContinuation by lazy {
        sentenceContinuation ?: KoreanSentenceContinuation(ngram = ngram, collocation = collocationModel)
    }

    private val baseKoreanLexicon = listOf(
        "안녕하세요", "감사합니다", "고맙습니다", "반갑습니다", "오늘", "내일", "모레", "어제",
        "판교", "강남", "홍대", "성수", "여의도", "종로", "신촌", "잠실", "광화문",
        "을지로", "역삼", "선릉", "삼성", "용산", "마포", "합정", "분당", "수원", "사무실", "회사", "회의실",
        "회의", "미팅", "배포", "일정", "약속", "시간", "확인했습니다", "부탁드립니다",
        "지금", "맛있어", "뭐해", "안돼요", "어떻게", "도착했습니다", "수고하셨습니다",
        "축하드립니다", "알겠습니다", "죄송합니다", "연락드리겠습니다", "진행하겠습니다",
        "검토하겠습니다", "공유드립니다", "송부드립니다", "확인 부탁드립니다", "자료", "보고서", "기획서",
        "커밋", "머지", "빌드", "코드", "리뷰", "이슈", "핫픽스", "릴리스",
        "좋은 하루", "좋은 아침", "조심히 들어가세요", "수고 많으셨습니다", "편안한 밤",
        "식사", "점심", "저녁", "커피", "치맥", "휴일", "주말", "휴가",
        "괜찮습니다", "문제없습니다", "동의합니다", "좋은 생각입니다",
        "출발했습니다", "이동 중입니다", "도착 직전입니다", "곧 뵙겠습니다",
        "언제든 말씀해 주세요", "천천히 하셔도 됩니다", "확인 후 회신드리겠습니다",
        "고생 많으셨습니다", "정말 감사합니다", "도움이 되셨길 바랍니다",
        "파이팅입니다", "응원합니다", "축하합니다", "힘내세요", "파이팅",
        "잘 부탁드립니다", "신경 써주셔서 감사합니다", "언제든 연락 주세요"
    )

    private val choseongAbbreviations = mapOf(
        "ㄱㅅ" to listOf("감사합니다", "고맙습니다", "고마워", "감사"),
        "ㅈㅅ" to listOf("죄송합니다", "죄송해요", "죄송"),
        "ㅇㅋ" to listOf("오케이", "알겠습니다", "알겠어"),
        "ㅅㄱ" to listOf("수고하셨습니다", "수고하세요", "수고했어"),
        "ㅊㅋ" to listOf("축하드립니다! 🎉", "축하해! 🎉", "축하"),
        "ㅂㅍ" to listOf("배포", "발표"),
        "ㅁㅌ" to listOf("미팅"),
        "ㅎㅇ" to listOf("회의", "확인"),
        "ㅈㄱ" to listOf("지금"),
        "ㄴㅇ" to listOf("내일"),
        "ㅇㄴ" to listOf("오늘"),
        "ㅁㄹ" to listOf("모레"),
        "ㄱㄷ" to listOf("기다려", "기다려주세요"),
        "ㅇㄷ" to listOf("어디야?", "어디"),
        "ㄹㅇ" to listOf("레알", "정말"),
        "ㅂㅂ" to listOf("잘 가", "바이바이")
    )

    fun predict(
        currentStroke: String,
        contextBeforeCursor: String,
        packageName: String,
        limit: Int = 5
    ): List<AiPrediction> {
        val results = mutableListOf<AiPrediction>()
        val seen = mutableSetOf<String>()

        fun addPrediction(pred: AiPrediction): Boolean {
            val key = if (pred.isSentenceCompletion) "s:${pred.text}" else "w:${pred.text}"
            if (seen.add(key)) {
                results.add(pred)
                return true
            }
            return false
        }

        val cleanStroke = currentStroke.trim()
        val cleanContext = contextBeforeCursor.trim()
        val hasTrailingSpace = contextBeforeCursor.endsWith(" ") || contextBeforeCursor.endsWith("\n")
        val lastWordInContext = cleanContext
            .substringAfterLast(' ')
            .substringAfterLast('\n')
            .substringAfterLast('\t')
            .substringAfterLast('\r')
            .trim()

        // -2. Keyboard-Aware Typo Correction (top-priority, ahead of the legacy typo_* sources below)
        val typoTarget: Pair<String, Int>? = when {
            cleanStroke.isNotBlank() -> cleanStroke to cleanStroke.length
            hasTrailingSpace && lastWordInContext.isNotBlank() -> {
                val trailingSpaces = contextBeforeCursor.length - contextBeforeCursor.trimEnd().length
                lastWordInContext to (lastWordInContext.length + trailingSpaces)
            }
            else -> null
        }
        // A `typed` fragment the new keyboard-aware engine already handled must not also be
        // re-corrected by the legacy word/sentence typo engine below, which knows nothing about
        // the full vocabulary and can produce a lower-confidence, sometimes nonsensical rewrite
        // for the very same fragment (e.g. blindly swapping a "-함니다" ending).
        var newEngineHandledTyped: String? = null
        if (typoCorrector != null && typoTarget != null) {
            val (typed, replaceLen) = typoTarget
            if (DubeolsikKeyMap.keySequence(typed).length >= 3) {
                val personalHits = correctionStore?.lookup(typed, 2).orEmpty()
                if (personalHits.isNotEmpty()) {
                    newEngineHandledTyped = typed
                    personalHits.forEachIndexed { idx, hit ->
                        addPrediction(
                            AiPrediction(
                                text = hit.corrected,
                                confidenceScore = 0.998f - idx * 0.001f,
                                isSentenceCompletion = false,
                                source = "typo_personal",
                                badge = "✏️",
                                replaceLength = replaceLen
                            )
                        )
                    }
                } else {
                    val isKnownWord = baseVocabulary?.contains(typed) == true || ngram.unigramCount(typed) >= 2f
                    if (!isKnownWord) {
                        val ctxProb = ngram.predictNext(contextBeforeCursor, packageName, 20)
                            .associate { it.word to it.probability }
                        val corrections = typoCorrector.correct(
                            typed,
                            limit = 2,
                            contextBoost = { w -> 1.5f * (ctxProb[w] ?: 0f) }
                        )
                        if (corrections.isNotEmpty()) {
                            newEngineHandledTyped = typed
                        }
                        corrections.forEachIndexed { idx, correction ->
                            val confidence = if (correction.cost <= 0.6f) 0.996f else 0.994f - idx * 0.002f
                            addPrediction(
                                AiPrediction(
                                    text = correction.word,
                                    confidenceScore = confidence,
                                    isSentenceCompletion = false,
                                    source = "typo_keyboard",
                                    badge = "✏️",
                                    replaceLength = replaceLen
                                )
                            )
                        }
                    }
                }
            }
        }

        // -1. Realtime Typo Correction (Top-priority sentence & word suggestion for mistyped text)
        val baseSentence = cleanContext
            .substringAfterLast('.')
            .substringAfterLast('?')
            .substringAfterLast('!')
            .substringAfterLast('\n')
            .trim()

        val currentSentence = if (cleanStroke.isNotBlank()) {
            if (baseSentence.isNotBlank()) {
                if (baseSentence.endsWith(cleanStroke)) {
                    baseSentence
                } else if (hasTrailingSpace || cleanContext.endsWith(" ")) {
                    "$baseSentence $cleanStroke"
                } else {
                    "$baseSentence$cleanStroke"
                }
            } else {
                cleanStroke
            }
        } else {
            baseSentence
        }

        var sentenceCorrectedText: String? = null
        if (currentSentence.isNotBlank()) {
            val sentenceTypoPairs = typoEngine.findTypoCorrectionsInSentence(currentSentence)
                .filter { (coreWord, _) -> coreWord != newEngineHandledTyped }
            if (sentenceTypoPairs.isNotEmpty()) {
                val corrected = typoEngine.correctSentence(currentSentence)
                if (corrected != null && corrected != currentSentence) {
                    sentenceCorrectedText = corrected
                    val isSentence = corrected.contains(" ")
                    addPrediction(
                        AiPrediction(
                            text = corrected,
                            confidenceScore = 0.999f,
                            isSentenceCompletion = isSentence,
                            source = "typo_sentence_correction",
                            badge = "✏️"
                        )
                    )
                }
                sentenceTypoPairs.forEach { (_, correctedWord) ->
                    addPrediction(
                        AiPrediction(
                            text = correctedWord,
                            confidenceScore = 0.996f,
                            isSentenceCompletion = correctedWord.contains(" "),
                            source = "typo_word_correction",
                            badge = "✏️"
                        )
                    )
                }
            }
        }

        val typoCandidates = mutableListOf<String>()
        if (cleanStroke.isNotBlank()) {
            typoCandidates.add(cleanStroke)
        }
        if (lastWordInContext.isNotBlank()) {
            if (!hasTrailingSpace) {
                typoCandidates.add(lastWordInContext)
                if (cleanStroke.isNotBlank() && !lastWordInContext.endsWith(cleanStroke)) {
                    typoCandidates.add("$lastWordInContext$cleanStroke")
                }
            } else if (typoEngine.hasExplicitTypo(lastWordInContext)) {
                // If trailing whitespace exists after the word, only correct explicit known typos
                // (e.g. "시프지 ", "오눌 ") to avoid false-positive fuzzy matches on normal words
                typoCandidates.add(lastWordInContext)
            }
        }

        typoCandidates.distinct().forEach { candidateWord ->
            if (candidateWord == newEngineHandledTyped) return@forEach
            val corrections = typoEngine.correct(candidateWord)
            corrections.forEach { correctedWord ->
                addPrediction(
                    AiPrediction(
                        text = correctedWord,
                        confidenceScore = 0.995f,
                        isSentenceCompletion = correctedWord.contains(" "),
                        source = "typo_correction",
                        badge = "✏️"
                    )
                )
            }
        }

        val fullContext = if (cleanStroke.isNotBlank() && !cleanContext.endsWith(cleanStroke)) {
            "$cleanContext $cleanStroke".trim()
        } else {
            cleanContext
        }

        // Context normalization for typos in the current sentence
        val correctedLastWord = if (lastWordInContext.isNotBlank()) {
            typoEngine.correct(lastWordInContext).firstOrNull()
        } else null

        val normalizedFullContext = if (sentenceCorrectedText != null && currentSentence.isNotBlank()) {
            fullContext.replace(currentSentence, sentenceCorrectedText)
        } else if (correctedLastWord != null && correctedLastWord != lastWordInContext) {
            val prefix = fullContext.dropLast(lastWordInContext.length)
            "$prefix$correctedLastWord".trim()
        } else {
            fullContext
        }

        // 0. Personalized Learned Sentences (Priority: ✨). Only the user's own previously
        // typed sentences (SOURCE_USER_PHRASE) reach the sentence line — synthetic/LLM-authored
        // records are content templates, not something the user actually typed, so they are
        // excluded here to keep the sentence line entirely user-data-driven.
        if (personalizedStore != null && (cleanStroke.isNotBlank() || normalizedFullContext.isNotBlank())) {
            val personalMatches = personalizedStore.query(
                queryChoseong = cleanStroke,
                context = normalizedFullContext,
                limit = limit
            ).filter { it.source == PersonalizedSentenceRecord.SOURCE_USER_PHRASE }
            personalMatches.forEach { record ->
                val score = (0.96f + (record.score * 0.01f)).coerceAtMost(0.999f)
                addPrediction(
                    AiPrediction(
                        text = record.sentence,
                        confidenceScore = score,
                        isSentenceCompletion = true,
                        source = "personalized_style",
                        badge = "✨ 내스타일"
                    )
                )
            }
        }

        // 0-B. Personal Sentence RAG (on-device BM25 search over the user's own past sentences).
        if (personalSentenceVault != null && contextBeforeCursor.isNotBlank()) {
            val ragMatches = personalSentenceVault.retrieve(contextBeforeCursor, packageName, limit)
            // retrieve() returns matches sorted by an unbounded BM25-derived magnitude. Map that
            // magnitude into a fixed [0.90, 0.95] band relative to the top match so a strongly
            // relevant sentence keeps its lead over a weakly relevant one (instead of collapsing the
            // gap to a flat per-index step), while staying in a predictable range beside other sources.
            val topRagScore = ragMatches.firstOrNull()?.score ?: 0f
            ragMatches.forEach { retrieved ->
                val relative = if (topRagScore > 0f) (retrieved.score / topRagScore).coerceIn(0f, 1f) else 0f
                var score = 0.90f + 0.05f * relative
                if (retrieved.startsWithLastWord) score += 0.02f
                addPrediction(
                    AiPrediction(
                        text = retrieved.sentence,
                        confidenceScore = score.coerceAtMost(0.999f),
                        isSentenceCompletion = true,
                        source = "rag_personal",
                        badge = "✨ 내기록"
                    )
                )
            }
        }

        // 1. LLM Cached Semantic Predictions (Highest intelligence & zero latency)
        if (normalizedFullContext.isNotBlank() && prefetcher != null) {
            val cached = prefetcher.getCachedPredictions(normalizedFullContext)
                ?: if (normalizedFullContext != fullContext) prefetcher.getCachedPredictions(fullContext) else null
            cached?.forEach { llmProposal ->
                val proposal = llmProposal.trim()
                if (proposal.isBlank()) return@forEach

                val matches = if (cleanStroke.isBlank()) {
                    true
                } else {
                    proposal.contains(cleanStroke) ||
                    proposal.startsWith(cleanStroke) ||
                    morphology.matchesChoseong(proposal, cleanStroke)
                }
                if (matches) {
                    val isSentence = proposal.contains(" ") && proposal.length > 8
                    val isTypoCorrection = (sentenceCorrectedText != null && proposal == sentenceCorrectedText) ||
                        (cleanContext.isNotBlank() && typoEngine.hasExplicitTypo(lastWordInContext) &&
                            typoEngine.correct(lastWordInContext).contains(proposal))
                    val badge = when {
                        isTypoCorrection -> "✏️ AI수정"
                        !isSentence -> "✨ AI단어"
                        else -> "✨ AI완성"
                    }
                    val score = when {
                        isTypoCorrection -> 0.998f
                        cleanStroke.isBlank() -> if (!isSentence) 0.985f else 0.980f
                        else -> if (!isSentence) 0.920f else 0.860f
                    }
                    addPrediction(
                        AiPrediction(
                            text = proposal,
                            confidenceScore = score,
                            isSentenceCompletion = isSentence,
                            source = "llm_cached",
                            badge = badge
                        )
                    )
                }
            }
            // Trigger background prefetch for subsequent sentence progression
            prefetcher.schedulePrefetch(normalizedFullContext)
        }

        // 1-B. Input-Preserving Sentence Continuation (keeps typed word(s) as a literal prefix,
        // e.g. "회의 참석" -> "회의 참석하겠습니다", instead of an unrelated fixed template).
        run {
            val committedWords = cleanContext.split(Regex("\\s+")).filter { it.isNotBlank() }
            val contextTail = (if (cleanStroke.isNotBlank()) committedWords + cleanStroke else committedWords)
                .takeLast(3)
            if (contextTail.isNotEmpty()) {
                val continuationTone = if (normalizedFullContext.isNotBlank()) {
                    if (semanticPredictor.inferTone(normalizedFullContext) == KoreanTone.Informal) {
                        ContinuationTone.Informal
                    } else {
                        ContinuationTone.Honorific
                    }
                } else if (TypingDnaVault.categorizePackage(packageName) == TypingDnaVault.CATEGORY_MESSENGER) {
                    ContinuationTone.Informal
                } else {
                    ContinuationTone.Honorific
                }
                val continuedSentences = effectiveSentenceContinuation.continuations(
                    contextTail,
                    continuationTone,
                    packageName,
                    3
                )
                continuedSentences.forEachIndexed { idx, text ->
                    addPrediction(
                        AiPrediction(
                            text = text,
                            confidenceScore = 0.985f - idx * 0.005f,
                            isSentenceCompletion = true,
                            source = "input_continuation",
                            badge = "✨ AI완성"
                        )
                    )
                }
            }
        }

        // 2. semantic_sentence is intentionally NOT consumed here: KoreanSemanticSentencePredictor's
        // intent-classified proposals are fixed content templates unrelated to what the user typed,
        // so they never reach the sentence line. semanticPredictor.inferTone() above (and below) is
        // still used for tone inference, which is not a content template.

        val isInformal = semanticPredictor.inferTone(normalizedFullContext) == KoreanTone.Informal

        // 1. Choseong Abbreviation Instant Expansion (e.g. ㄱㅅ -> 감사합니다, ㅈㅅ -> 죄송합니다, ㅇㅋ -> 알겠습니다)
        if (cleanStroke.isNotBlank()) {
            choseongAbbreviations[cleanStroke]?.forEachIndexed { idx, abbrev ->
                val score = 0.97f - (idx * 0.01f)
                addPrediction(
                    AiPrediction(
                        text = abbrev,
                        confidenceScore = score,
                        isSentenceCompletion = abbrev.contains(" "),
                        source = "choseong_abbrev",
                        badge = "⚡"
                    )
                )
            }
        }

        // 2. Korean Collocation & Next-Word Transition Model
        val effectiveLastWord = if (cleanStroke.isBlank() || hasTrailingSpace) {
            lastWordInContext
        } else {
            val precedingBeforeStroke = cleanContext.removeSuffix(cleanStroke).trim()
            precedingBeforeStroke.substringAfterLast(' ').substringAfterLast('\n').trim()
        }
        if (effectiveLastWord.isNotBlank()) {
            val nextWords = collocationModel.predictNextWords(effectiveLastWord, isInformal, limit = limit * 2)
            nextWords.forEachIndexed { idx, nextWord ->
                val matches = if (cleanStroke.isBlank()) {
                    true
                } else {
                    nextWord.startsWith(cleanStroke) || morphology.matchesChoseong(nextWord, cleanStroke)
                }
                if (matches) {
                    val score = (0.95f - (idx * 0.01f)).coerceAtLeast(0.85f)
                    val isSentence = nextWord.contains(" ") && nextWord.length > 8
                    addPrediction(
                        AiPrediction(
                            text = nextWord,
                            confidenceScore = score,
                            isSentenceCompletion = isSentence,
                            source = "collocation_next_word",
                            badge = if (isSentence) "✨ AI완성" else "✨ AI단어"
                        )
                    )
                }
            }
        }

        // 3. Personal N-gram Context Predictions
        val ngramCandidates = if (cleanStroke.isBlank()) {
            ngram.predictNext(contextBeforeCursor, packageName, 4)
        } else {
            ngram.complete(cleanStroke, contextBeforeCursor, packageName, 4)
        }
        ngramCandidates.forEachIndexed { idx, candidate ->
            val score = when {
                candidate.evidence >= 2f -> 0.99f - idx * 0.005f
                candidate.evidence >= 1f -> 0.975f - idx * 0.005f
                else -> 0.94f - idx * 0.005f
            }
            addPrediction(
                AiPrediction(
                    text = candidate.word,
                    confidenceScore = score,
                    isSentenceCompletion = false,
                    source = "personal_ngram",
                    badge = "⭐"
                )
            )
        }

        // 3-B. Base Korean Vocabulary Completion (bundled TSV, right after personal n-gram)
        if (cleanStroke.isNotBlank() && baseVocabulary != null) {
            val personalWords = ngramCandidates.map { it.word }.toSet()
            val baseCtxProb = ngram.predictNext(contextBeforeCursor, packageName, 20)
                .associate { it.word to it.probability }
            val vocabCandidates = baseVocabulary.completions(cleanStroke, 8)
                .filter { (word, _) -> word !in personalWords }
                .map { (word, prior) -> Triple(word, prior, prior * (1f + 3f * (baseCtxProb[word] ?: 0f))) }
                .sortedByDescending { it.third }
                .take(4)
            vocabCandidates.forEachIndexed { idx, (word, _, _) ->
                val ctxP = baseCtxProb[word] ?: 0f
                val confidence = if (ctxP > 0f) 0.955f - idx * 0.005f else 0.93f - idx * 0.005f
                addPrediction(
                    AiPrediction(
                        text = word,
                        confidenceScore = confidence,
                        isSentenceCompletion = false,
                        source = "base_vocab",
                        badge = ""
                    )
                )
            }
        }

        // 4. Choseong & Prefix Realtime Matching from Base Lexicon (honest word suggestions)
        if (cleanStroke.isNotBlank()) {
            baseKoreanLexicon.forEach { template ->
                val isPrefixMatch = template.startsWith(cleanStroke)
                val isChoseongMatch = if (!isPrefixMatch) morphology.matchesChoseong(template, cleanStroke) else false

                if (isPrefixMatch || isChoseongMatch) {
                    val score = if (isPrefixMatch) 0.92f else 0.88f
                    val isSentence = template.contains(" ")
                    addPrediction(
                        AiPrediction(
                            template,
                            score,
                            isSentenceCompletion = isSentence,
                            source = "base_lexicon",
                            badge = if (isSentence) "✨ AI완성" else "✨ AI단어"
                        )
                    )
                }
            }
        }

        // Sentence-line whitelist: only sources backed by the user's own data or model output
        // may appear as a full-sentence candidate. This blocks hardcoded content templates
        // (collocation_next_word, baseKoreanLexicon, etc.) that occasionally produce a
        // multi-word string long enough to be marked isSentenceCompletion=true from leaking
        // into the sentence line. Word-line candidates are unaffected.
        val words = results.filter { !it.isSentenceCompletion }.sortedByDescending { it.confidenceScore }.take(limit)
        val sentenceCandidates = results
            .filter { it.isSentenceCompletion && it.source !in SENTENCE_LINE_SOURCE_BLOCKLIST }
        val sentences = SentenceRelevanceReranker.rerank(
            sentenceCandidates, contextBeforeCursor, ngram, packageName, limit
        )
        return (words + sentences).sortedByDescending { it.confidenceScore }
    }

    fun learnSentence(sentence: String, packageName: String) {
        ngram.learn(sentence, packageName)
    }
}
