/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

/**
 * 존댓말/반말/업무체 톤 판정의 정본(single source of truth).
 *
 * [KoreanSemanticSentencePredictor.inferTone]/[KoreanSemanticSentencePredictor.inferToneInternal]은
 * 이 객체를 부르는 한 줄 위임으로만 남는다.
 *
 * 문장을 나눠 마지막 문장은 3배, 그 앞 최대 3문장은 1배 가중해 존댓말/반말 점수를 매긴다.
 * 점수는 두 갈래로 더해진다:
 *  1. 표지어(marker) 부분 문자열 일치. 단, 반말 표지어는 바로 뒤에 '요'/'용'이 오면
 *     해요체(존댓말)의 일부이므로 반말로 세지 않는다("정리할게요"의 "할게"는 무시).
 *  2. 종결 어미 점수. 문장이 종결부호(. ? ! ~ …)로 끝났거나 마지막 어절이 '요'/'용'으로
 *     끝날 때만 적용한다("아까", "나 지금"처럼 종결부호 없는 짧은 문맥은 대상에서 뺀다).
 *     그 경우 문장 끝이 '요'/'용'/'니다'/'니까'/'세요'/'시오'면 존댓말 점수를, '어/아/지/자/게/래'로
 *     끝나면 반말 점수를 더한다. '까'는 앞 음절에 ㄹ 받침이 있을 때만("할까","갈까","않을까")
 *     반말로 세고, '니'/'냐'/'야'는 마지막 어절이 2음절 이상일 때만 반말로 센다(명사 끝
 *     "분야" 같은 오탐을 줄이기 위한 최소 기준).
 */
object KoreanToneClassifier {

    private val honorificMarkers = listOf(
        "습니다", "입니다", "합니다", "드립니다", "보내드립니다", "송부드립니다",
        "세요", "해요", "시겠습니까", "감사합니다", "고맙습니다", "죄송합니다",
        "부탁드립니다", "말씀해", "확인했습니다", "알겠습니다", "되세요", "편안한"
    )

    private val informalMarkers = listOf(
        "고마워", "고마웡", "땡큐", "수고했어", "축하해", "어디야", "밥 먹자",
        "치맥", "갈래", "뭐해", "이따 봐", "알겠어", "ㅇㅋ", "ㄱㅅ", "ㅋㅋ", "ㅎㅎ",
        "했어", "갔어", "봤어", "할게", "갈게", "올게", "먹었어", "있어?",
        "올렸어", "해봐", "해줘", "보자", "볼까", "어때", "됐어", "맞아", "편해",
        "뭘", "뭐", "시프지", "시퍼", "하구", "시프니까", "시프면", "싶으니까", "싶으면", "난", "그걸",
        "좋아", "동의해", "오케이", "그래"
    )

    // 바로 뒤에 '요'/'용'이 오면(해요체) 반말 표지로 세지 않기 위한 부정형 lookahead.
    private val informalMarkerPatterns = informalMarkers.associateWith { marker ->
        Regex("${Regex.escape(marker)}(?!요|용)")
    }

    private val honorificMultiEndings = listOf("니다", "니까", "세요", "시오")
    private val honorificSingleEndings = listOf("요", "용")

    // 앞 음절 형태와 무관하게 항상 반말로 세는 종결.
    private val unrestrictedInformalEndings = listOf("어", "아", "지", "자", "게", "래")

    // 마지막 어절이 2음절 이상일 때만 반말로 세는 종결(명사 끝 "분야" 오탐 축소).
    private val eojeolLengthGatedInformalEndings = listOf("니", "냐", "야")

    // '까'는 이 목록이 아니라 별도로 앞 음절 ㄹ 받침 여부로 판단한다("할까"/"갈까"/"않을까").

    private val STRONG_TERMINALS = setOf('.', '?', '!', '~')

    val SENTENCE_SPLIT_REGEX = Regex("[,.!?;~\\n]+")

    private data class Segment(val text: String, val hasStrongTerminal: Boolean)

    fun infer(context: String, preSplitSentences: List<String>? = null): KoreanTone {
        if (context.isBlank()) return KoreanTone.Honorific
        val clean = context.trim().lowercase()
        val (honorificScore, informalScore) = scoreContext(clean, preSplitSentences)
        val lastSentence = lastSegmentText(clean, preSplitSentences)

        return when {
            informalScore > honorificScore -> KoreanTone.Informal
            lastSentence.contains("배포") || lastSentence.contains("커밋") || lastSentence.contains("머지") ||
                lastSentence.contains("pr") || lastSentence.contains("api") || lastSentence.contains("빌드") ||
                clean.contains("배포") || clean.contains("커밋") -> KoreanTone.Technical
            lastSentence.contains("보고서") || lastSentence.contains("품의") || lastSentence.contains("공유드립니다") ||
                lastSentence.contains("회의록") || lastSentence.contains("검토 요청") -> KoreanTone.Business
            else -> KoreanTone.Honorific
        }
    }

    /**
     * 존댓말/반말 표지어나 종결 어미 근거가 하나라도 있으면 그 근거가 가리키는 톤을,
     * 근거가 전혀 없으면(주제어만 있거나 완전히 중립적인 짧은 문맥) null을 돌려준다.
     * [infer]와 달리 배포/보고서 등 주제 기반 기본값으로 대체하지 않는다.
     */
    fun evidence(context: String, preSplitSentences: List<String>? = null): KoreanTone? {
        if (context.isBlank()) return null
        val clean = context.trim().lowercase()
        val (honorificScore, informalScore) = scoreContext(clean, preSplitSentences)
        if (honorificScore == 0 && informalScore == 0) return null
        return if (informalScore > honorificScore) KoreanTone.Informal else KoreanTone.Honorific
    }

    private fun scoreContext(clean: String, preSplitSentences: List<String>?): Pair<Int, Int> {
        val segments = segmentsFor(clean, preSplitSentences)
        if (segments.isEmpty()) return 0 to 0

        var honorificScore = 0
        var informalScore = 0

        // Historic sentences evaluated with 1x weight
        segments.dropLast(1).takeLast(3).forEach { segment ->
            val (honorific, informal) = scoreSegment(segment)
            honorificScore += honorific
            informalScore += informal
        }

        // Latest sentence evaluated with 3x weight for rapid nuance reaction
        val (lastHonorific, lastInformal) = scoreSegment(segments.last())
        honorificScore += lastHonorific * 3
        informalScore += lastInformal * 3

        return honorificScore to informalScore
    }

    private fun lastSegmentText(clean: String, preSplitSentences: List<String>?): String =
        segmentsFor(clean, preSplitSentences).lastOrNull()?.text ?: clean

    private fun segmentsFor(clean: String, preSplitSentences: List<String>?): List<Segment> {
        if (preSplitSentences != null) {
            return preSplitSentences.mapNotNull { raw ->
                val trimmed = raw.trim()
                if (trimmed.isBlank()) return@mapNotNull null
                Segment(
                    text = trimmed.trimEnd('.', '?', '!', '~', '…', ',', ';'),
                    hasStrongTerminal = trimmed.last() in STRONG_TERMINALS || trimmed.endsWith("…")
                )
            }
        }
        return segmentSentences(clean)
    }

    /** [SENTENCE_SPLIT_REGEX]로 나누되, 각 조각이 강한 종결부호(. ? ! ~ …)로 끝났는지도 함께 가져간다. */
    private fun segmentSentences(clean: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        var cursor = 0
        for (match in SENTENCE_SPLIT_REGEX.findAll(clean)) {
            val piece = clean.substring(cursor, match.range.first).trim()
            if (piece.isNotBlank()) {
                segments += Segment(piece, match.value.any { it in STRONG_TERMINALS })
            }
            cursor = match.range.last + 1
        }
        // '…'는 SENTENCE_SPLIT_REGEX의 구분자가 아니라서 마지막 조각에 그대로 남아있다.
        val tail = clean.substring(cursor).trim()
        if (tail.isNotBlank()) {
            segments += Segment(tail, tail.endsWith("…"))
        }
        return segments
    }

    /** 한 문장(조각)의 (존댓말 점수, 반말 점수)를 계산한다. */
    private fun scoreSegment(segment: Segment): Pair<Int, Int> {
        var honorific = 0
        var informal = 0
        val text = segment.text

        honorificMarkers.forEach { marker ->
            if (text.contains(marker)) honorific += 1
        }
        informalMarkers.forEach { marker ->
            if (informalMarkerPatterns.getValue(marker).containsMatchIn(text)) informal += 1
        }

        val endsInYoOrYong = honorificSingleEndings.any { text.endsWith(it) }
        val allowEndingScore = segment.hasStrongTerminal || endsInYoOrYong
        if (allowEndingScore) {
            when {
                honorificMultiEndings.any { text.endsWith(it) } -> honorific += 1
                endsInYoOrYong -> honorific += 1
                unrestrictedInformalEndings.any { text.endsWith(it) } -> informal += 1
                text.endsWith("까") && precedingCharHasRieulBatchim(text) -> informal += 1
                eojeolLengthGatedInformalEndings.any { text.endsWith(it) } && lastEojeol(text).length >= 2 -> informal += 1
            }
        }

        return honorific to informal
    }

    private fun lastEojeol(text: String): String =
        text.substringAfterLast(' ').substringAfterLast('\n').substringAfterLast('\t')

    /** 문장 끝에서 두 번째 글자가 한글 음절이고 종성이 ㄹ인지("할"/"갈"/"될"/"을" 등). */
    private fun precedingCharHasRieulBatchim(text: String): Boolean {
        if (text.length < 2) return false
        val c = text[text.length - 2]
        if (c !in '가'..'힣') return false
        return (c.code - 0xAC00) % 28 == 8
    }
}
