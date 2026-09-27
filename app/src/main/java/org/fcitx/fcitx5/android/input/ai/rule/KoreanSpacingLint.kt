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

    /** [text]에 흔한 붙여쓰기 오류 패턴이 하나라도 있으면 true. */
    fun hasSpacingIssue(text: String): Boolean = PATTERNS.any { it.containsMatchIn(text) }
}
