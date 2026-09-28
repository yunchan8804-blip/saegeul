/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rule

/**
 * 기기 생성 문장(온디바이스 Gemma 등)에서 나타나는 흔한 붙여쓰기 오류만 잡는 정본 린트.
 *
 * 오탐을 피하려고 표준 표기가 명확히 띄어 써야 하는 형태소 결합만 다룬다. 수정(자동 교정)은
 * 하지 않고 걸리면 그 후보를 버리는 용도로만 쓴다.
 *
 * - 의존명사 '수' + 있다/없다: "할수있어요", "갈수없다" (정상: "할 수 있어요")
 * - '것' + '같다': "먹을것같아" (정상: "먹을 것 같아")
 * - '~까' + '싶다': "될까싶어", "않을까싶어요" (정상: "될까 싶어")
 * - '~지' + '싶다': "그런지싶어" (정상: "그런지 싶어")
 * - '~듯' + '하다': "그런듯하다" (정상: "그런 듯하다")
 * - 표준 표기가 띄어 쓰는 명사 + '있게/있는/없는': "자신감있게" (정상: "자신감 있게").
 *   '재미있게'/'맛있게'/'멋있는'처럼 형용사 어간 자체에 '있'이 포함된 정상 붙여쓰기는
 *   명사 목록에서 제외해 건드리지 않는다.
 *
 * 후보를 문맥 뒤에 이어 붙일 때 생기는 일부 오류도 여기서 판정한다([detachesDeurida]).
 */
object KoreanSpacingLint {

    private val ATTACHED_NOUNS_REQUIRING_SPACE = listOf(
        "자신감", "책임감", "여유", "관심", "능력", "가치", "매력", "개성"
    )

    private val PATTERNS = listOf(
        Regex("[가-힣]수(?:있|없)"),
        Regex("것같"),
        Regex("[가-힣]까싶"),
        Regex("[가-힣]지싶"),
        Regex("[가-힣]듯하"),
        Regex("(?:${ATTACHED_NOUNS_REQUIRING_SPACE.joinToString("|")})(?:있게|있는|없는)")
    )

    // 앞 명사에 붙여 쓰는 겸양 '-드리다'("부탁드립니다", "연락드릴게요") 활용형의 첫머리.
    private val DEURIDA_PREFIXES = listOf("드리", "드립", "드려", "드렸", "드릴", "드린", "드림")
    private val DEURIDA_ATTACHING_NOUNS = setOf("부탁", "연락", "감사", "인사")

    /** [text]에 흔한 붙여쓰기 오류 패턴이 하나라도 있으면 true. */
    fun hasSpacingIssue(text: String): Boolean = PATTERNS.any { it.containsMatchIn(text) }

    /**
     * [context] 뒤에 [candidate]를 이어 붙이면 '-드리다'가 앞 명사와 띄어지는지.
     * [context]가 공백으로 끝나고 [candidate]가 '드리다' 활용형으로 시작하며 앞 어절이 명시한
     * 명사면 true다("부탁 " + "드립니다", 정상: "부탁드립니다"). 목적어가 생략된
     * "커피 드립니다"처럼 다른 명사 뒤의 본동사 용법은 걸지 않는다.
     */
    fun detachesDeurida(context: String, candidate: String): Boolean {
        if (!context.endsWith(' ')) return false
        if (DEURIDA_PREFIXES.none { candidate.startsWith(it) }) return false
        val previousWord = context.dropLast(1).substringAfterLast(' ').substringAfterLast('\n')
        return previousWord in DEURIDA_ATTACHING_NOUNS
    }
}
