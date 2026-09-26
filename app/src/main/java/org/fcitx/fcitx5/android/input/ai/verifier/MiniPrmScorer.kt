/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.verifier

/**
 * EAI-11: Mini Process Reward Model (PRM) Scorer.
 *
 * Scores completion candidates on a scale of 0.0f to 1.0f based on:
 * 1. Semantic relevance (문맥 호응도)
 * 2. Tone / Style consistency (문체 일치도)
 * 3. Ending completeness (어미 완성도)
 */
class MiniPrmScorer {

    companion object {
        // Formal endings regex (하십시오체 / 해요체)
        private val FORMAL_ENDING_PATTERN = Regex(
            """(?:[가-힣]*(?:습니다|니다|습니까|니까|십시오|으십시오|세요|으세요|어요|아요|해요|지요|고요|예요|이에요|드립니다|계세요|있어요|없어요|게요|ㄹ게요|을게요|봬요)|감사합니다|고맙습니다|죄송합니다)[\s.?!]*${'$'}"""
        )

        // Informal endings regex (해체 / 해라체)
        private val INFORMAL_ENDING_PATTERN = Regex(
            """(?:[가-힣]*(?:었어|았어|했어|먹었어|봤어|갈게|할게|올게|있어|없어|어디야|뭐해|하자|보자|먹자|가자|했지|맞지|그렇지|있지|없지|좋지|해줘|알려줘|줘|봐|해|냐|으니|니|어라|아라|군|마)|안녕|고마워|미안해)[\s.?!]*${'$'}"""
        )

        // Complete sentence endings (punct or declarative/interrogative/imperative verb endings)
        private val COMPLETE_SENTENCE_ENDING = Regex(
            """[.?!]|(?:[가-힣]*(?:다|요|죠|까|자|네|군|음|임|시오|세요|오|든가|구나))[\s.?!]*${'$'}"""
        )

        // Incomplete / trailing patterns (e.g. dangling particles, verb stems, conjunctive suffixes)
        private val INCOMPLETE_TRAILING_PATTERN = Regex(
            """(?:[가-힣]*(?:하|겠|되었|됐|싶|않|없|있|가|오|보|주|받|만들|나누|모으|비우|채우|적|으|고|며|면서|지만|는데|은데|어서|아서|여서|려고|으려고|기에|길래)|[을를은는이가와과에로])$"""
        )

        // Related topic keywords for semantic cohesion
        private val TOPIC_CLUSTERS = listOf(
            setOf("회의", "미팅", "일정", "참석", "시간", "공유", "논의", "안건", "장소"),
            setOf("자료", "준비", "문서", "보고서", "정리", "파일", "작성", "검토", "공유"),
            setOf("감사", "고맙", "도움", "덕분", "진심", "은혜", "수고"),
            setOf("내일", "오늘", "약속", "시간", "봬요", "뵙겠습니다", "만나요", "연락"),
            setOf("확인", "부탁", "검토", "요청", "답변", "피드백")
        )
    }

    /**
     * Scores the candidate completion in the given context (0.0f ~ 1.0f).
     */
    fun scoreCandidate(context: String, candidate: String): Float {
        val trimmedCandidate = candidate.trim()
        if (trimmedCandidate.isEmpty()) return 0.0f

        val semanticScore = evaluateSemanticRelevance(context.trim(), trimmedCandidate)
        val toneScore = evaluateToneConsistency(context.trim(), trimmedCandidate)
        val endingScore = evaluateEndingCompleteness(trimmedCandidate)

        val totalScore = if (endingScore <= 0.2f) {
            // Incomplete endings should not pass acceptance threshold (>= 0.5f)
            ((semanticScore * 0.35f) + (toneScore * 0.35f) + (endingScore * 0.30f)).coerceAtMost(0.40f)
        } else {
            (semanticScore * 0.35f) + (toneScore * 0.35f) + (endingScore * 0.30f)
        }
        return totalScore.coerceIn(0.0f, 1.0f)
    }

    /**
     * Evaluates semantic relevance / cohesion between context and candidate (0.0f ~ 1.0f).
     */
    private fun evaluateSemanticRelevance(context: String, candidate: String): Float {
        if (context.isEmpty()) return 0.9f // Neutral context

        // Check topic cluster overlap
        var hasClusterMatch = false
        for (cluster in TOPIC_CLUSTERS) {
            val contextMatches = cluster.any { context.contains(it) }
            val candidateMatches = cluster.any { candidate.contains(it) }
            if (contextMatches && candidateMatches) {
                hasClusterMatch = true
                break
            }
        }

        if (hasClusterMatch) return 1.0f

        // Token overlap or general continuation
        val contextTokens = context.split(Regex("""\s+""")).filter { it.length >= 2 }.toSet()
        val candidateTokens = candidate.split(Regex("""\s+""")).filter { it.length >= 2 }.toSet()

        val overlap = contextTokens.intersect(candidateTokens).size
        return when {
            overlap > 0 -> 0.95f
            else -> 0.80f // General plausible continuation
        }
    }

    /**
     * Evaluates honorific / formal tone consistency between context and candidate (0.0f ~ 1.0f).
     */
    private fun evaluateToneConsistency(context: String, candidate: String): Float {
        val isContextFormal = FORMAL_ENDING_PATTERN.containsMatchIn(context) ||
                context.contains("안녕하세요") || context.contains("감사합니다") ||
                context.contains("안녕하십니까") || context.contains("드립니다")
        val isContextInformal = INFORMAL_ENDING_PATTERN.containsMatchIn(context) ||
                context.contains("안녕") || context.contains("고마워") || context.contains("미안해")

        val isCandidateFormal = FORMAL_ENDING_PATTERN.containsMatchIn(candidate)
        val isCandidateInformal = INFORMAL_ENDING_PATTERN.containsMatchIn(candidate)

        return when {
            isContextFormal && isCandidateInformal -> 0.0f // Explicit tone clash
            isContextInformal && isCandidateFormal -> 0.3f // Overly polite response to informal context
            isContextFormal && isCandidateFormal -> 1.0f
            isContextInformal && isCandidateInformal -> 1.0f
            else -> 0.85f // Neutral / single style
        }
    }

    /**
     * Evaluates grammatical completeness of the candidate ending (0.0f ~ 1.0f).
     */
    private fun evaluateEndingCompleteness(candidate: String): Float {
        // If trailing incomplete verb stem or dangling particle
        if (INCOMPLETE_TRAILING_PATTERN.containsMatchIn(candidate)) {
            // Check if it ends with punctuation despite matching stem
            val lastChar = candidate.last()
            if (lastChar != '.' && lastChar != '!' && lastChar != '?') {
                return 0.15f
            }
        }

        // Check if ends with standard Korean ending or punctuation
        if (COMPLETE_SENTENCE_ENDING.containsMatchIn(candidate)) {
            return 1.0f
        }

        return 0.5f
    }
}
