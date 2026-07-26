/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.emotion

import java.util.Locale
import org.fcitx.fcitx5.android.input.search.KoreanSearchEntry
import org.fcitx.fcitx5.android.input.search.KoreanSearchResult
import org.fcitx.fcitx5.android.input.search.KoreanSearchSource

/** Local-only Korean reaction catalog. It never receives editor or clipboard text. */
object KoreanEmotionLexicon {
    val quickQueries: List<String> = listOf(
        "축하", "죄송", "감사", "당황", "웃음", "ㅋㅋ", "ㅎㅎ", "사랑", "화남", "슬픔", "응원", "인정"
    )

    private enum class Emotion(val label: String, vararg val aliases: String) {
        Celebrate("축하", "축하", "생일", "기념", "파티"),
        Apology("죄송", "죄송", "미안", "사과"),
        Thanks("감사", "감사", "고마워", "고맙"),
        Awkward("당황", "당황", "황당", "헉", "민망"),
        Laugh("웃음", "웃음", "웃겨", "폭소", "ㅋㅋ", "ㅎㅎ"),
        Love("사랑", "사랑", "하트", "좋아해"),
        Angry("화남", "화남", "분노", "짜증", "화나"),
        Sad("슬픔", "슬픔", "울음", "눈물", "속상"),
        Cheer("응원", "응원", "화이팅", "파이팅", "힘내"),
        Agree("인정", "인정", "동의", "맞아", "ㅇㅈ")
    }

    private enum class LaughStyle { K, H, Any }

    private data class Candidate(
        val emotion: Emotion,
        val text: String,
        val baseRank: Int,
        val description: String,
        val laughStyle: LaughStyle? = null,
        val laughIntensity: Int? = null
    )

    private val candidates = listOf(
        Candidate(Emotion.Celebrate, "🎉", 0, "축하 · 파티"),
        Candidate(Emotion.Celebrate, "🥳", 1, "축하 · 신남"),
        Candidate(Emotion.Celebrate, "👏", 2, "축하 · 박수"),
        Candidate(Emotion.Celebrate, "ㅊㅋㅊㅋ", 4, "축하 · 한국형 표현"),
        Candidate(Emotion.Celebrate, "٩(๑>∀<๑)۶", 6, "축하 · 신남"),

        Candidate(Emotion.Apology, "🙇", 0, "죄송 · 사과"),
        Candidate(Emotion.Apology, "🙏", 1, "죄송 · 부탁"),
        Candidate(Emotion.Apology, "(_ _)", 3, "죄송 · 고개 숙임"),
        Candidate(Emotion.Apology, "ㅠㅠ", 5, "죄송 · 미안"),

        Candidate(Emotion.Thanks, "🙏", 0, "감사 · 고마움"),
        Candidate(Emotion.Thanks, "🫶", 1, "감사 · 마음"),
        Candidate(Emotion.Thanks, "🙇", 2, "감사 · 인사"),
        Candidate(Emotion.Thanks, "(꾸벅)", 4, "감사 · 한국형 표현"),

        Candidate(Emotion.Awkward, "😳", 0, "당황 · 놀람"),
        Candidate(Emotion.Awkward, "😅", 1, "당황 · 머쓱"),
        Candidate(Emotion.Awkward, "ㅇㅁㅇ", 2, "당황 · 한국형 표정"),
        Candidate(Emotion.Awkward, "(⊙_⊙;)", 4, "당황 · 놀람"),

        Candidate(Emotion.Laugh, "😂", 0, "웃음 · ㅋㅋ", LaughStyle.K, 2),
        Candidate(Emotion.Laugh, "🤣", 1, "폭소 · ㅋㅋㅋㅋ", LaughStyle.K, 3),
        Candidate(Emotion.Laugh, "😊", 2, "미소 · ㅎㅎ", LaughStyle.H, 2),
        Candidate(Emotion.Laugh, "😄", 1, "큰 미소 · ㅎㅎㅎㅎ", LaughStyle.H, 3),
        Candidate(Emotion.Laugh, "ㅋㅋ", 4, "웃음 · 한국형 표현", LaughStyle.K, 2),
        Candidate(Emotion.Laugh, "ㅋㅋㅋㅋ", 5, "폭소 · 한국형 표현", LaughStyle.K, 3),
        Candidate(Emotion.Laugh, "ㅎㅎ", 4, "미소 · 한국형 표현", LaughStyle.H, 2),
        Candidate(Emotion.Laugh, "ㅎㅎㅎㅎ", 5, "큰 미소 · 한국형 표현", LaughStyle.H, 3),
        Candidate(Emotion.Laugh, "(≧▽≦)", 7, "폭소 · 이모티콘", LaughStyle.Any, 3),

        Candidate(Emotion.Love, "❤️", 0, "사랑 · 하트"),
        Candidate(Emotion.Love, "🥰", 1, "사랑 · 행복"),
        Candidate(Emotion.Love, "🫶", 2, "사랑 · 하트 손"),
        Candidate(Emotion.Love, "(´▽`ʃ♡ƪ)", 4, "사랑 · 이모티콘"),

        Candidate(Emotion.Angry, "😠", 0, "화남 · 짜증"),
        Candidate(Emotion.Angry, "😡", 1, "화남 · 분노"),
        Candidate(Emotion.Angry, "ㅡㅡ", 3, "화남 · 한국형 표현"),
        Candidate(Emotion.Angry, "(╬ಠ益ಠ)", 5, "분노 · 이모티콘"),

        Candidate(Emotion.Sad, "😢", 0, "슬픔 · 눈물"),
        Candidate(Emotion.Sad, "😭", 1, "슬픔 · 오열"),
        Candidate(Emotion.Sad, "ㅠㅠ", 3, "슬픔 · 한국형 표현"),
        Candidate(Emotion.Sad, "(´;ω;`)", 5, "슬픔 · 이모티콘"),

        Candidate(Emotion.Cheer, "💪", 0, "응원 · 힘내"),
        Candidate(Emotion.Cheer, "🔥", 1, "응원 · 열정"),
        Candidate(Emotion.Cheer, "화이팅!", 2, "응원 · 한국형 표현"),
        Candidate(Emotion.Cheer, "٩(ˊᗜˋ*)و", 4, "응원 · 이모티콘"),

        Candidate(Emotion.Agree, "👍", 0, "인정 · 동의"),
        Candidate(Emotion.Agree, "💯", 1, "인정 · 완벽"),
        Candidate(Emotion.Agree, "ㅇㅈ", 2, "인정 · 한국형 표현"),
        Candidate(Emotion.Agree, "(끄덕)", 4, "인정 · 한국형 표현")
    )

    /** Duplicate-free catalog consumed by the existing unified-search ranker. */
    val entries: List<KoreanSearchEntry> = candidates
        .groupBy(Candidate::text)
        .entries
        .mapIndexed { index, (text, sameText) ->
            val emotions = sameText.map(Candidate::emotion).distinct()
            KoreanSearchEntry(
                id = "emotion:$index",
                source = KoreanSearchSource.Emotion,
                primaryText = text,
                secondaryText = sameText.map(Candidate::description).distinct().joinToString(" · "),
                searchTerms = (emotions.flatMap { it.aliases.toList() } +
                    emotions.map(Emotion::label) + text).distinct()
            )
        }

    fun recommend(explicitQuery: String, limit: Int = 16): List<KoreanSearchResult> {
        val query = explicitQuery.trim().lowercase(Locale.ROOT).take(40)
        if (query.isEmpty() || limit <= 0) return emptyList()
        val laughProfile = laughProfile(query)
        val emotionScores = Emotion.entries.mapNotNull { emotion ->
            val score = when {
                emotion == Emotion.Laugh && laughProfile != null -> 0
                else -> emotion.aliases.minOfOrNull { matchScore(query, it) ?: Int.MAX_VALUE }
                    ?.takeIf { it != Int.MAX_VALUE }
            } ?: return@mapNotNull null
            emotion to score
        }.toMap()

        return candidates.asSequence()
            .mapIndexedNotNull { index, candidate ->
                val emotionScore = emotionScores[candidate.emotion] ?: return@mapIndexedNotNull null
                val intensityPenalty = laughterPenalty(candidate, laughProfile)
                RankedCandidate(candidate, emotionScore + candidate.baseRank + intensityPenalty, index)
            }
            .sortedWith(compareBy<RankedCandidate> { it.score }.thenBy { it.index })
            .distinctBy { it.candidate.text }
            .take(limit)
            .map { ranked ->
                KoreanSearchResult(entryFor(ranked.candidate), ranked.score)
            }
            .toList()
    }

    private fun entryFor(candidate: Candidate): KoreanSearchEntry = KoreanSearchEntry(
        id = "emotion:${candidate.emotion.name}:${candidate.text}",
        source = KoreanSearchSource.Emotion,
        primaryText = candidate.text,
        secondaryText = candidate.description,
        searchTerms = (candidate.emotion.aliases.toList() + candidate.emotion.label + candidate.text)
            .distinct()
    )

    private fun matchScore(query: String, alias: String): Int? = when {
        query == alias -> 0
        query.startsWith(alias) || alias.startsWith(query) -> 10
        query.contains(alias) || alias.contains(query) -> 20
        else -> null
    }

    private fun laughProfile(query: String): LaughProfile? {
        val kCount = MAX_LAUGH_RUN.findAll(query)
            .filter { it.value.firstOrNull() == 'ㅋ' }
            .maxOfOrNull { it.value.length } ?: 0
        val hCount = MAX_LAUGH_RUN.findAll(query)
            .filter { it.value.firstOrNull() == 'ㅎ' }
            .maxOfOrNull { it.value.length } ?: 0
        if (kCount == 0 && hCount == 0) return null
        val style = if (kCount >= hCount) LaughStyle.K else LaughStyle.H
        val count = maxOf(kCount, hCount)
        val intensity = when {
            count >= 4 -> 3
            count >= 2 -> 2
            else -> 1
        }
        return LaughProfile(style, intensity)
    }

    private fun laughterPenalty(candidate: Candidate, profile: LaughProfile?): Int {
        if (candidate.emotion != Emotion.Laugh || profile == null) return 0
        val stylePenalty = when (candidate.laughStyle) {
            profile.style, LaughStyle.Any -> 0
            else -> 30
        }
        val intensityPenalty = candidate.laughIntensity?.let {
            kotlin.math.abs(it - profile.intensity) * 20
        } ?: 20
        return stylePenalty + intensityPenalty
    }

    private data class LaughProfile(val style: LaughStyle, val intensity: Int)
    private data class RankedCandidate(val candidate: Candidate, val score: Int, val index: Int)
    private val MAX_LAUGH_RUN = Regex("ㅋ+|ㅎ+")
}
