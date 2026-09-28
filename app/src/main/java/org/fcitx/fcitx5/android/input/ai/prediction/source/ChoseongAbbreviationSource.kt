/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.prediction.source

import org.fcitx.fcitx5.android.input.ai.AiPrediction

/** Choseong abbreviation instant expansion (`choseong_abbrev`), e.g. ㄱㅅ -> 감사합니다, ㅇㅋ -> 알겠습니다. */
internal class ChoseongAbbreviationSource : CandidateSource {

    private val choseongAbbreviations = mapOf(
        "ㄱㅅ" to listOf("감사합니다", "고맙습니다", "고마워", "감사"),
        "ㅈㅅ" to listOf("죄송합니다", "죄송해요", "죄송"),
        "ㅇㅋ" to listOf("오케이", "알겠습니다", "알겠어"),
        "ㅅㄱ" to listOf("수고하셨습니다", "수고하세요", "수고했어"),
        "ㅊㅋ" to listOf("축하드립니다!", "축하해!", "축하"),
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

    override fun collect(request: CandidateRequest): List<AiPrediction> {
        val cleanStroke = request.input.cleanStroke
        if (cleanStroke.isBlank()) return emptyList()
        return choseongAbbreviations[cleanStroke].orEmpty().mapIndexed { idx, abbrev ->
            AiPrediction(
                text = abbrev,
                confidenceScore = 0.97f - (idx * 0.01f),
                isSentenceCompletion = abbrev.contains(" "),
                source = "choseong_abbrev",
                badge = "⚡"
            )
        }
    }
}
