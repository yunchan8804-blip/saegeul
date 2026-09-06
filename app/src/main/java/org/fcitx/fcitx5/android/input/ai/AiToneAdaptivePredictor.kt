/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

/**
 * Korean conversational and grammatical tones.
 */
enum class KoreanTone {
    Honorific,   // 존댓말 (-습니다, -해요, -드립니다)
    Informal,    // 반말/친근 (-어, -아, -지, -자, -야)
    Business,    // 비즈니스/격식 (보고서, 공유드립니다, 검토 요청)
    Technical,   // IT/엔지니어링 (배포, 빌드, 커밋, 머지, PR, 이슈)
    Neutral      // 중립/일반
}

/**
 * Enhanced prediction item with contextual tone, badge tags, and UI theme tokens.
 */
data class AiTonePrediction(
    val text: String,
    val confidenceScore: Float,
    val isSentenceCompletion: Boolean,
    val detectedTone: KoreanTone,
    val badgeLabel: String,
    val badgeColorHex: String,
    val source: String = "adaptive_ai"
)

/**
 * Tone-Adaptive AI Prediction Engine.
 * Dynamically classifies input context tone, infuses celebratory/conversational emojis,
 * and attaches design-token badges for the Saegeul Keyboard KawaiiBar / Candidate UI.
 */
class AiToneAdaptivePredictor(
    private val basePredictor: AiContextualPredictor,
    private val morphology: ChoseongMorphologyEngine
) {

    private val honorificPatterns = listOf(
        "감사합니다", "고맙습니다", "부탁드립니다", "안녕하십니까", "수고하셨습니다",
        "축하드립니다", "알겠습니다", "확인했습니다", "죄송합니다", "좋은 하루 되세요",
        "드리겠습니다", "보내드립니다", "송부드립니다"
    )

    private val informalPatterns = listOf(
        "고마워", "고마웡", "땡큐", "수고했어", "축하해", "어디야", "밥 먹자",
        "치맥", "갈래", "뭐해", "이따 봐", "알겠어", "ㅇㅋ", "ㄱㅅ"
    )

    private val businessPatterns = listOf(
        "보고서", "실적", "공유드립니다", "검토 요청", "회의록", "송부드립니다",
        "기획안", "제안서", "품의", "대사", "수익화", "정산"
    )

    private val technicalPatterns = listOf(
        "배포", "빌드", "커밋", "머지", "브랜치", "핫픽스", "릴리스",
        "이슈", "PR", "깃허브", "안드로이드", "서버", "API", "토큰"
    )

    fun detectTone(context: String): KoreanTone {
        if (context.isBlank()) return KoreanTone.Neutral
        val clean = context.lowercase()

        // Check technical keywords first
        if (technicalPatterns.any { clean.contains(it.lowercase()) }) {
            return KoreanTone.Technical
        }

        // Check business keywords
        if (businessPatterns.any { clean.contains(it.lowercase()) }) {
            return KoreanTone.Business
        }

        // Check honorific suffixes and endings
        val hasHonorificEndings = clean.contains("습니다") || clean.contains("드립니다") ||
            clean.contains("세요") || clean.contains("해요") || clean.contains("시겠습니까") ||
            clean.contains("감사") || clean.contains("부탁")
        if (hasHonorificEndings) {
            return KoreanTone.Honorific
        }

        // Check informal indicators
        val hasInformalEndings = clean.contains("야 ") || clean.contains("갈래") ||
            clean.contains("먹자") || clean.contains("어?") || clean.contains("지?") ||
            clean.contains("해?") || clean.contains("치맥") || clean.contains("이따")
        if (hasInformalEndings) {
            return KoreanTone.Informal
        }

        return KoreanTone.Neutral
    }

    fun predictWithTone(
        currentStroke: String,
        contextBeforeCursor: String,
        packageName: String,
        limit: Int = 6
    ): List<AiTonePrediction> {
        val detectedTone = detectTone(contextBeforeCursor)
        val basePredictions = basePredictor.predict(currentStroke, contextBeforeCursor, packageName, limit = limit * 2)

        val enriched = mutableListOf<AiTonePrediction>()
        val seen = mutableSetOf<String>()

        // 1. Process base predictions and adapt their confidence based on tone
        basePredictions.forEach { base ->
            var score = base.confidenceScore
            var adaptedText = base.text

            // Tone adjustment
            when (detectedTone) {
                KoreanTone.Honorific, KoreanTone.Business -> {
                    if (honorificPatterns.any { adaptedText.contains(it) }) {
                        score += 0.15f
                    }
                }
                KoreanTone.Informal -> {
                    if (informalPatterns.any { adaptedText.contains(it) }) {
                        score += 0.15f
                    }
                }
                KoreanTone.Technical -> {
                    if (technicalPatterns.any { adaptedText.contains(it) }) {
                        score += 0.15f
                    }
                }
                KoreanTone.Neutral -> {}
            }

            // Contextual Emoji Infusion for celebratory or supportive contexts
            if (contextBeforeCursor.contains("축하") || currentStroke.contains("축하")) {
                if (!adaptedText.contains("🎉") && !adaptedText.contains("👏")) {
                    adaptedText = "$adaptedText 🎉"
                }
            } else if (contextBeforeCursor.contains("합격") || contextBeforeCursor.contains("승진")) {
                if (!adaptedText.contains("✨") && !adaptedText.contains("🎉")) {
                    adaptedText = "$adaptedText ✨"
                }
            } else if (contextBeforeCursor.contains("감사") || currentStroke == "ㄱㅅ") {
                if (detectedTone == KoreanTone.Informal && !adaptedText.contains("고마워")) {
                    adaptedText = "고마워! 😊"
                }
            }

            if (seen.add(adaptedText)) {
                val (badgeLabel, badgeColor) = getBadgeToken(adaptedText, base.isSentenceCompletion, detectedTone)
                enriched.add(
                    AiTonePrediction(
                        text = adaptedText,
                        confidenceScore = score.coerceIn(0.1f, 0.99f),
                        isSentenceCompletion = base.isSentenceCompletion,
                        detectedTone = detectedTone,
                        badgeLabel = badgeLabel,
                        badgeColorHex = badgeColor
                    )
                )
            }
        }

        // 2. Add tone-specific custom completions if query matches
        if (currentStroke.isNotBlank()) {
            val candidates = when (detectedTone) {
                KoreanTone.Honorific, KoreanTone.Business -> honorificPatterns
                KoreanTone.Informal -> informalPatterns
                KoreanTone.Technical -> technicalPatterns
                KoreanTone.Neutral -> honorificPatterns + informalPatterns
            }

            candidates.forEach { candidate ->
                if (candidate.startsWith(currentStroke) || morphology.matchesChoseong(candidate, currentStroke)) {
                    if (seen.add(candidate)) {
                        val (badgeLabel, badgeColor) = getBadgeToken(candidate, candidate.length > 5, detectedTone)
                        enriched.add(
                            AiTonePrediction(
                                text = candidate,
                                confidenceScore = 0.85f,
                                isSentenceCompletion = candidate.length > 5,
                                detectedTone = detectedTone,
                                badgeLabel = badgeLabel,
                                badgeColorHex = badgeColor
                            )
                        )
                    }
                }
            }
        }

        return enriched.sortedByDescending { it.confidenceScore }.take(limit)
    }

    private fun getBadgeToken(text: String, isSentence: Boolean, tone: KoreanTone): Pair<String, String> {
        return when {
            isSentence -> Pair("문장완성", "#79f1c2")       // Jade / Action
            tone == KoreanTone.Business -> Pair("비즈니스", "#82b5ff")  // Info Blue
            tone == KoreanTone.Technical -> Pair("개발/IT", "#82b5ff")  // Info Blue
            tone == KoreanTone.Honorific -> Pair("존댓말", "#79f1c2")   // Jade
            tone == KoreanTone.Informal -> Pair("친근", "#f0c466")      // Amber
            else -> Pair("", "")
        }
    }
}
