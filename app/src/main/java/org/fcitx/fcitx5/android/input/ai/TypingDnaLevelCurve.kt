/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import kotlin.math.pow

/**
 * Level curve for the Typing DNA vault.
 *
 * Levels 1..5 keep the historical thresholds (15/45/100/200 sentences) so
 * existing users never regress. From level 6 the required sentences grow
 * geometrically up to level 2500 (~1M analyzed sentences, roughly ten years
 * of heavy daily typing), so the endgame stays out of reach instead of
 * running out.
 */
object TypingDnaLevelCurve {
    const val MAX_LEVEL = 2500

    data class Progress(
        val level: Int,
        val title: String,
        val nextTargetSentences: Int,
        val progressPercent: Int
    )

    fun describe(analyzedSentences: Int): Progress {
        val level = levelFor(analyzedSentences)
        val title = titleFor(level)
        if (level >= MAX_LEVEL) {
            return Progress(MAX_LEVEL, title, analyzedSentences, 100)
        }
        val current = thresholdFor(level)
        val next = thresholdFor(level + 1)
        val progress = if (next <= current) {
            0
        } else {
            ((analyzedSentences - current) * 100 / (next - current)).coerceIn(0, 99)
        }
        return Progress(level, title, next, progress)
    }

    fun levelFor(analyzedSentences: Int): Int {
        if (analyzedSentences <= 0) return 1
        var level = 1
        while (level < MAX_LEVEL && thresholdFor(level + 1) <= analyzedSentences) {
            level++
        }
        return level
    }

    fun titleFor(level: Int): String = when {
        level <= 1 -> "새싹 학습자"
        level == 2 -> "성장하는 AI 파트너"
        level == 3 -> "어휘 습관 형성"
        level == 4 -> "정밀 문체 동기화"
        level == 5 -> "언어 지문 마스터"
        level in 6..9 -> "언어 지문 장인"
        level in 10..24 -> "언어 지문 대가"
        level in 25..99 -> "언어 지문 현자"
        level in 100..249 -> "언어 지문 전설"
        level in 250..999 -> "언어 지문 신화"
        level in 1000 until MAX_LEVEL -> "언어 지문 항성"
        else -> "언어 지문 우주"
    }

    fun thresholdFor(level: Int): Int {
        if (level <= 1) return 0
        val legacy = intArrayOf(0, 0, 15, 45, 100, 200)
        if (level <= 5) return legacy[level]
        val exponent = 1.3232
        return kotlin.math.ceil(BASE_XP * (level - 1).toDouble().pow(exponent)).toInt()
    }

    fun rewardBonusPoints(level: Int): Int = when {
        level <= 0 -> 0
        level >= 1000 -> 3
        level >= 100 -> 2
        level >= 25 -> 1
        else -> 0
    }

    private const val BASE_XP = 31.9
}
