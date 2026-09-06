/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

data class AiPrediction(
    val text: String,
    val confidenceScore: Float,
    val isSentenceCompletion: Boolean = false,
    val source: String = "local_ai",
    val badge: String = "✨ AI완성"
)

/**
 * Realtime AI Stroke-Level Next Word & Sentence Prediction Engine for Saegeul Keyboard.
 * Combines Jaso decomposition, Choseong matching, Personalized N-gram Markov context,
 * and built-in Korean conversational templates for sub-5ms instant suggestions.
 */
class AiContextualPredictor(
    private val lexicon: PersonalizedLexiconModel,
    private val morphology: ChoseongMorphologyEngine,
    val semanticPredictor: KoreanSemanticSentencePredictor = KoreanSemanticSentencePredictor(),
    val prefetcher: AiSentenceCompletionPrefetcher? = null,
    val personalizedStore: PersonalizedSentenceStore? = null,
    val typoEngine: KoreanTypoCorrectionEngine = KoreanTypoCorrectionEngine(),
    val collocationModel: KoreanCollocationModel = KoreanCollocationModel()
) {

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
        val lastWordInContext = cleanContext
            .substringAfterLast(' ')
            .substringAfterLast('\n')
            .substringAfterLast('\t')
            .substringAfterLast('\r')
            .trim()
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

        // 0. Personalized Learned & Synthetic Sentences (Priority: ✨)
        if (personalizedStore != null && (cleanStroke.isNotBlank() || normalizedFullContext.isNotBlank())) {
            val personalMatches = personalizedStore.query(
                queryChoseong = cleanStroke,
                context = normalizedFullContext,
                limit = limit
            )
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

        // 2. On-Device Semantic Intent & Context Predictions (Entire written text context)
        if (normalizedFullContext.isNotBlank()) {
            val semanticProposals = semanticPredictor.predictNextSentences(normalizedFullContext, cleanStroke, limit = limit)
            semanticProposals.forEach { proposal ->
                addPrediction(
                    AiPrediction(
                        text = proposal.text,
                        confidenceScore = proposal.confidenceScore,
                        isSentenceCompletion = proposal.isCompleteSentence,
                        source = "semantic_sentence",
                        badge = proposal.badge
                    )
                )
            }
        }

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

        // 3. Personalized N-gram Context Predictions
        if (cleanContext.isNotBlank()) {
            val lastWord = cleanContext.split(Regex("\\s+")).lastOrNull() ?: cleanContext
            val transitions = lexicon.getTransitions(lastWord, packageName)

            transitions.forEach { candidate ->
                if (cleanStroke.isBlank() || candidate.word.startsWith(cleanStroke) || morphology.matchesChoseong(candidate.word, cleanStroke)) {
                    val score = (0.92f + (candidate.frequency * 0.02f)).coerceAtMost(0.99f)
                    addPrediction(
                        AiPrediction(
                            candidate.word,
                            score,
                            isSentenceCompletion = candidate.word.contains(" "),
                            badge = "⭐"
                        )
                    )
                }
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
                            badge = if (isSentence) "✨ AI완성" else "✨ AI단어"
                        )
                    )
                }
            }
        }

        val words = results.filter { !it.isSentenceCompletion }.sortedByDescending { it.confidenceScore }.take(limit)
        val sentences = results.filter { it.isSentenceCompletion }.sortedByDescending { it.confidenceScore }.take(limit)
        return (words + sentences).sortedByDescending { it.confidenceScore }
    }

    fun learnSentence(sentence: String, packageName: String) {
        val words = sentence.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 2) {
            if (words.isNotEmpty()) {
                lexicon.recordTransition("", words[0], packageName)
            }
            return
        }

        for (i in 0 until words.size - 1) {
            lexicon.recordTransition(words[i], words[i + 1], packageName)
        }
    }
}
