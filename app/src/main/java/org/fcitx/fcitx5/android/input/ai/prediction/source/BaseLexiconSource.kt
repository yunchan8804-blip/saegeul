/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.prediction.source

import org.fcitx.fcitx5.android.input.ai.AiPrediction
import org.fcitx.fcitx5.android.input.ai.ChoseongMorphologyEngine

/** Choseong and prefix matching against a small built-in lexicon (`base_lexicon`, honest word suggestions). */
internal class BaseLexiconSource(private val morphology: ChoseongMorphologyEngine) : CandidateSource {

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

    override fun collect(request: CandidateRequest): List<AiPrediction> {
        val cleanStroke = request.input.cleanStroke
        if (cleanStroke.isBlank()) return emptyList()
        return baseKoreanLexicon.mapNotNull { template ->
            val isPrefixMatch = template.startsWith(cleanStroke)
            val isChoseongMatch = if (!isPrefixMatch) morphology.matchesChoseong(template, cleanStroke) else false
            if (!isPrefixMatch && !isChoseongMatch) return@mapNotNull null
            val isSentence = template.contains(" ")
            AiPrediction(
                template,
                if (isPrefixMatch) 0.92f else 0.88f,
                isSentenceCompletion = isSentence,
                source = "base_lexicon",
                badge = if (isSentence) "AI 완성" else "AI 단어"
            )
        }
    }
}
