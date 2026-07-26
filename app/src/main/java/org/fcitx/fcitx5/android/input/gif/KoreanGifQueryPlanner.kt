/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import java.util.Locale

internal data class WeightedGifSearchTerm(
    val value: String,
    val weight: Int
)

/**
 * Korean reaction vocabulary used only by providers that permit client-side discovery help.
 * GIPHY deliberately does not use this planner because its API requires the exact user query.
 */
internal object KoreanGifQueryPlanner {
    private data class ReactionIntent(
        val aliases: Set<String>,
        val klipyFallbacks: List<String>,
        val localTags: List<String>
    )

    fun emptyResultFallbacks(query: String, limit: Int = 2): List<String> {
        val normalized = normalize(query)
        if (normalized.isEmpty() || limit <= 0 || !GifSafeSearchPolicy.isAllowedQuery(normalized)) {
            return emptyList()
        }
        return INTENTS.asSequence()
            .filter { intent -> intent.aliases.any(normalized::contains) }
            .flatMap { it.klipyFallbacks.asSequence() }
            .map(::normalize)
            .filter { it.isNotEmpty() && it != normalized }
            .filter(GifSafeSearchPolicy::isAllowedQuery)
            .distinct()
            .take(limit)
            .toList()
    }

    fun localSearchTerms(query: String): List<WeightedGifSearchTerm> {
        val normalized = normalize(query)
        if (normalized.isEmpty() || !GifSafeSearchPolicy.isAllowedQuery(normalized)) {
            return emptyList()
        }
        val terms = linkedMapOf<String, Int>()
        fun add(value: String, weight: Int) {
            val term = normalize(value)
            if (term.isNotEmpty()) terms[term] = maxOf(terms[term] ?: 0, weight)
        }
        add(normalized, 160)
        normalized.split(' ').filter(String::isNotBlank).forEach { add(it, 140) }
        INTENTS.filter { intent -> intent.aliases.any(normalized::contains) }.forEach { intent ->
            intent.aliases.forEach { add(it, 125) }
            intent.localTags.forEach { add(it, 100) }
        }
        return terms.map { (value, weight) -> WeightedGifSearchTerm(value, weight) }
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\s_-]+"), " ")
        .trim()

    private val INTENTS = listOf(
        ReactionIntent(
            setOf("ㅋㅋ", "웃겨", "웃김", "폭소"),
            listOf("웃긴 반응", "폭소"),
            listOf("laughing", "joy", "rofl", "grin", "tears of joy")
        ),
        ReactionIntent(
            setOf("ㅎㅎ", "미소", "행복"),
            listOf("행복한 반응", "미소"),
            listOf("smile", "happy", "joy", "relieved")
        ),
        ReactionIntent(
            setOf("축하", "축하해", "생일", "파티"),
            listOf("축하해", "파티 축하"),
            listOf("partying face", "party popper", "confetti", "birthday cake", "clapping hands")
        ),
        ReactionIntent(
            setOf("퇴근", "칼퇴"),
            listOf("칼퇴", "퇴근 행복"),
            listOf("party", "freedom", "running", "celebrate", "relieved")
        ),
        ReactionIntent(
            setOf("월요일", "월요병", "출근"),
            listOf("월요병", "출근 싫어"),
            listOf("tired", "sleepy", "weary", "crying", "coffee")
        ),
        ReactionIntent(
            setOf("어색", "머쓱", "민망"),
            listOf("머쓱", "민망한 반응"),
            listOf("awkward", "grimacing", "flushed", "sweat", "melting")
        ),
        ReactionIntent(
            setOf("인정", "동의", "맞아", "ㅇㅈ"),
            listOf("인정", "맞아"),
            listOf("agree", "yes", "thumbs up", "check mark", "hundred points")
        ),
        ReactionIntent(
            setOf("놀람", "충격", "헐", "대박"),
            listOf("헐", "충격 반응"),
            listOf("surprised", "astonished", "screaming", "exploding head", "wow")
        ),
        ReactionIntent(
            setOf("화이팅", "파이팅", "응원", "힘내"),
            listOf("화이팅", "응원"),
            listOf("cheer", "raising hands", "clapping hands", "flexed biceps", "fire")
        ),
        ReactionIntent(
            setOf("감사", "고마워", "고맙"),
            listOf("고마워", "감사합니다"),
            listOf("thank you", "folded hands", "heart hands", "bow")
        ),
        ReactionIntent(
            setOf("미안", "죄송", "사과"),
            listOf("미안해", "죄송합니다"),
            listOf("sorry", "apology", "folded hands", "bow", "pleading")
        ),
        ReactionIntent(
            setOf("사랑", "좋아해", "하트", "설렘"),
            listOf("좋아해", "사랑해"),
            listOf("love", "heart eyes", "heart face", "red heart", "heart hands")
        ),
        ReactionIntent(
            setOf("화남", "분노", "빡침", "짜증"),
            listOf("분노", "짜증난 반응"),
            listOf("angry", "rage", "cursing", "steam")
        ),
        ReactionIntent(
            setOf("슬픔", "울음", "눈물", "서운"),
            listOf("눈물", "슬픈 반응"),
            listOf("sad", "crying", "loudly crying", "pensive", "broken heart")
        ),
        ReactionIntent(
            setOf("당황", "황당", "어이없"),
            listOf("황당", "당황한 반응"),
            listOf("confused", "unamused", "eye roll", "dotted line face", "grimacing")
        ),
        ReactionIntent(
            setOf("최고", "잘했어", "굿", "멋져"),
            listOf("최고", "잘했어"),
            listOf("thumbs up", "clapping hands", "fire", "hundred points", "trophy")
        )
    )
}
