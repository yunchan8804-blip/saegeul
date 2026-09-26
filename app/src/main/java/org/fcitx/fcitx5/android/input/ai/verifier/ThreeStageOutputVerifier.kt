/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.verifier

import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter

/**
 * EAI-11: Three-Stage Output Verifier.
 *
 * Combines three filtering stages to verify candidate generations within < 6.0ms:
 * - Stage 1: Deterministic Korean syntax & pragmatic rules (KoreanSyntaxRuleFilter)
 * - Stage 2: N-gram language model perplexity filter (KenLmScorer, threshold PPL <= 150.0)
 * - Stage 3: Lightweight Process Reward Model (MiniPrmScorer, threshold score >= 0.5f)
 */
class ThreeStageOutputVerifier(
    private val kenLmScorer: KenLmScorer = KenLmScorer(),
    private val miniPrmScorer: MiniPrmScorer = MiniPrmScorer()
) {

    data class VerificationResult(
        val isValid: Boolean,
        val score: Float,
        val stage: Int,
        val reason: String
    )

    companion object {
        const val MAX_PPL_THRESHOLD = 150.0
        const val MIN_PRM_SCORE_THRESHOLD = 0.5f

        val PROMPT_INJECTION_PATTERNS = listOf(
            "ignore previous",
            "system prompt",
            "private key",
            "dump vault",
            "drop table",
            "<script",
            "exec(",
            "eval("
        )
    }

    /**
     * Verifies the given [candidate] continuation against [context] across all three stages.
     */
    fun verify(context: String, candidate: String): VerificationResult {
        // Stage 0: Security & Prompt Injection Gate
        val lower = candidate.lowercase()
        if (PROMPT_INJECTION_PATTERNS.any { lower.contains(it) }) {
            return VerificationResult(
                isValid = false,
                score = 0.0f,
                stage = 0,
                reason = "Stage 0 Security: Prompt injection pattern detected"
            )
        }

        // Stage 1: Korean Syntax Rule Filter
        val isSound = KoreanSyntaxRuleFilter.isGrammaticallySound(candidate, context)
        if (!isSound) {
            return VerificationResult(
                isValid = false,
                score = 0.0f,
                stage = 1,
                reason = "Stage 1 KoreanSyntaxRuleFilter violation"
            )
        }

        // Stage 2: KenLM Perplexity Scorer
        val ppl = kenLmScorer.calculatePerplexity(candidate)
        if (ppl > MAX_PPL_THRESHOLD) {
            return VerificationResult(
                isValid = false,
                score = 0.0f,
                stage = 2,
                reason = "Stage 2 KenLmScorer high perplexity: %.2f > $MAX_PPL_THRESHOLD".format(ppl)
            )
        }

        // Stage 3: Mini PRM Scorer
        val prmScore = miniPrmScorer.scoreCandidate(context, candidate)
        if (prmScore < MIN_PRM_SCORE_THRESHOLD) {
            return VerificationResult(
                isValid = false,
                score = prmScore,
                stage = 3,
                reason = "Stage 3 MiniPrmScorer low score: %.2f < $MIN_PRM_SCORE_THRESHOLD".format(prmScore)
            )
        }

        return VerificationResult(
            isValid = true,
            score = prmScore,
            stage = 3,
            reason = "Accepted"
        )
    }
}
