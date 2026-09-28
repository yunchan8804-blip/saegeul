/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rule

import org.fcitx.fcitx5.android.input.ai.KoreanTypoCorrectionEngine

/**
 * 정본 최종 추천 품질 게이트: [org.fcitx.fcitx5.android.input.ai.AiContextualPredictor]와
 * [org.fcitx.fcitx5.android.input.ai.ImmediateContextualPredictions]가 후보를 최종 반환하기
 * 직전에 이 게이트 하나로 모든 소스의 후보를 거른다. 개인 데이터(n-gram·금고·RAG)는 지우지
 * 않고, 화면에 보여줄 때만 거른다.
 *
 * 검사 순서(먼저 걸리는 사유가 최종 사유):
 * 1. [RejectionReason.UNGRAMMATICAL] — 문장 후보([KoreanSyntaxRuleFilter.isGrammaticallySound])만 검사.
 * 2. [RejectionReason.SPACING] — [KoreanSpacingLint.hasSpacingIssue], 또는 후보를 문맥 뒤에 이어
 *    붙이면 '-드리다'가 앞 명사와 띄어지는 경우([KoreanSpacingLint.detachesDeurida]).
 * 3. [RejectionReason.DUPLICATED_WORD] — 공백으로 나눈 어절이 바로 이어서 같은 문자열로 반복.
 * 4. [RejectionReason.MIXED_JAMO] — 한 어절 안에서 한글 음절 뒤에 호환 자모가 붙은 경우
 *    ("안녕ㅎ", "고마워ㅜ" 등). 어절 전체가 호환 자모만인 것("ㅋㅋ", "ㅠㅠ", "ㅇㅋ")은 허용.
 * 5. [RejectionReason.KNOWN_TYPO] — [KoreanTypoCorrectionEngine]이 그 어절에 대해 이미 확정 교정형을
 *    알고 있는 경우(사전 매칭·형태소 규칙만; 자모 편집거리 기반 확률적 교정은 쓰지 않는다).
 *    엔진에 없는 오타는 이번 게이트 범위 밖이다.
 */
object SuggestionQualityGate {

    enum class RejectionReason { UNGRAMMATICAL, SPACING, DUPLICATED_WORD, MIXED_JAMO, KNOWN_TYPO }

    // KoreanTypoCorrectionEngine은 사전/형태소 규칙만 담은 상태 없는 정본 클래스라 공유 인스턴스로 둔다.
    private val typoEngine = KoreanTypoCorrectionEngine()

    /**
     * [candidate]가 게이트를 통과하지 못하는 이유를 돌려준다(통과하면 null).
     * [context]는 문장 후보일 때 [KoreanSyntaxRuleFilter]에 넘겨 문맥 포함 문법 검사를 하고,
     * 모든 후보에 대해 문맥 뒤에 이어 붙였을 때의 띄어쓰기 검사에 쓴다.
     */
    fun evaluate(candidate: String, context: String = "", isSentenceCompletion: Boolean = false): RejectionReason? {
        if (candidate.isBlank()) return null

        if (isSentenceCompletion && !KoreanSyntaxRuleFilter.isGrammaticallySound(candidate, context)) {
            return RejectionReason.UNGRAMMATICAL
        }

        if (KoreanSpacingLint.hasSpacingIssue(candidate) || KoreanSpacingLint.detachesDeurida(context, candidate)) {
            return RejectionReason.SPACING
        }

        val words = candidate.split(' ', '\n').filter(String::isNotBlank)

        if (words.zipWithNext().any { (a, b) -> a == b }) {
            return RejectionReason.DUPLICATED_WORD
        }

        if (words.any(::hasHangulSyllableMixedWithJamo)) {
            return RejectionReason.MIXED_JAMO
        }

        if (words.any(::hasKnownTypo)) {
            return RejectionReason.KNOWN_TYPO
        }

        return null
    }

    /** [evaluate]가 null(=통과)이면 true. */
    fun accepts(candidate: String, context: String = "", isSentenceCompletion: Boolean = false): Boolean =
        evaluate(candidate, context, isSentenceCompletion) == null

    private fun hasHangulSyllableMixedWithJamo(word: String): Boolean {
        val hasHangulSyllable = word.any { it in '가'..'힣' }
        if (!hasHangulSyllable) return false // "ㅋㅋ", "ㅠㅠ", "ㅇㅋ"처럼 자모만인 어절은 허용
        return word.any { it.code in 0x3131..0x318E }
    }

    private fun hasKnownTypo(word: String): Boolean = typoEngine.findTypoCorrectionsInSentence(word).isNotEmpty()
}
