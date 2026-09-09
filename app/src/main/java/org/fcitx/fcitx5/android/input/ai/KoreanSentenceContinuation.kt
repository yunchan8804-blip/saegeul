/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

/**
 * Conversational tone used to pick a natural sentence ending.
 */
enum class ContinuationTone { Honorific, Informal }

/**
 * Input-preserving Korean sentence completion.
 *
 * Unlike [KoreanSemanticSentencePredictor], which classifies intent and returns a fixed
 * template unrelated to what was actually typed, this engine always keeps the typed
 * word(s) ([contextTail]) as a literal prefix and only appends a natural ending or a
 * learned continuation on top of it (e.g. "회의 참석" -> "회의 참석하겠습니다").
 */
class KoreanSentenceContinuation(
    private val ngram: PersonalNgramModel? = null,
    private val collocation: KoreanCollocationModel? = null,
) {

    companion object {
        /** Maximum number of extra next-word lookups chained after the first one. */
        private const val MAX_CHAIN_DEPTH = 2

        /**
         * Nouns that combine with "하다" to form a verb/adjective. Used as the cold-start
         * fallback when no personal n-gram or collocation data is available yet.
         */
        private val HADA_NOUNS = setOf(
            "참석", "확인", "검토", "진행", "준비", "공유", "배포", "회의", "미팅", "발표",
            "시작", "완료", "처리", "정리", "요청", "참고", "연락", "도착", "출발", "감사",
            "노력", "결정", "동의", "참여", "신청", "예약", "취소", "변경", "수정", "추가",
            "삭제", "확정", "보고", "공지", "안내", "논의", "검색", "조사", "개발", "배송",
            "결제", "예정", "계획", "대기", "이동", "출근", "퇴근", "식사", "운동", "청소",
            "공부", "연습", "후회", "사과", "축하", "응원", "반성", "정산", "마감", "제출",
            "접수", "승인", "반려"
        )

        /** Full "하다" suffixes appended directly after the base (already carry the 하 stem). */
        private val HONORIFIC_ENDINGS = listOf("하겠습니다", "했습니다", "합니다")
        private val INFORMAL_ENDINGS = listOf("할게", "했어", "하자")

        /** Sentence-final endings. A chained candidate ending in one of these is a complete sentence. */
        private val FINAL_ENDINGS = listOf(
            "니다", "습니다", "세요", "어요", "아요", "해요", "예요", "이에요",
            "죠", "네요", "군요", "자", "어", "아", "지", "까", "요",
            "ㅋㅋ", "ㅎㅎ", "ㅠㅠ"
        )

        private fun isTerminal(word: String): Boolean = FINAL_ENDINGS.any { word.endsWith(it) }

        private fun endingsFor(tone: ContinuationTone): List<String> =
            if (tone == ContinuationTone.Honorific) HONORIFIC_ENDINGS else INFORMAL_ENDINGS
    }

    /**
     * Returns natural sentence completions that keep [contextTail] as a literal prefix.
     *
     * @param contextTail the last up-to-3 word(s) before the cursor, closest word last.
     * @param tone honorific or informal ending style.
     * @param packageName current foreign app package, used to scope personal n-gram lookups.
     * @param limit maximum number of completions to return.
     */
    fun continuations(
        contextTail: List<String>,
        tone: ContinuationTone,
        packageName: String,
        limit: Int = 3
    ): List<String> {
        val base = contextTail.joinToString(" ").trim()
        if (base.isBlank()) return emptyList()
        val lastWord = contextTail.last()
        val lastWordIsTerminal = isTerminal(lastWord)

        val ordered = LinkedHashSet<String>()

        // 2. Ending attachment: works with zero learned data (cold start).
        if (!lastWordIsTerminal && lastWord in HADA_NOUNS) {
            endingsFor(tone).forEach { ending -> ordered.add("$base$ending") }
        }

        // 3. Observed n-gram chaining: reflects learned personal data.
        collectChain(base, tone, packageName, MAX_CHAIN_DEPTH).forEach { ordered.add(it) }

        return ordered.filter { it.startsWith(base) }.take(limit)
    }

    private fun collectChain(
        chainBase: String,
        tone: ContinuationTone,
        packageName: String,
        depthRemaining: Int
    ): List<String> {
        val nextWords = LinkedHashSet<String>()
        ngram?.predictContextualNext(chainBase, packageName, 5)?.forEach { nextWords.add(it.word) }

        val results = mutableListOf<String>()
        for (next in nextWords) {
            if (next.isBlank()) continue
            val extended = "$chainBase $next"
            when {
                isTerminal(next) -> results.add(extended)
                depthRemaining > 1 -> results.addAll(collectChain(extended, tone, packageName, depthRemaining - 1))
                next in HADA_NOUNS -> endingsFor(tone).forEach { ending -> results.add("$extended$ending") }
                // depth exhausted and the chain can't be closed with a 하다-ending: discard.
            }
        }
        return results
    }
}
