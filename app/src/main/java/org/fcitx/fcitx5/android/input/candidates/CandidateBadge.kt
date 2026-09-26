/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

/**
 * Classifies a candidate's `comment` field as an AI badge (an icon glyph describing the
 * candidate's origin/tone) rather than literal text that should be appended after the candidate.
 */
object CandidateBadge {

    private const val VARIATION_SELECTOR_16 = 0xFE0F
    private const val SYMBOL_ONLY_MAX_LENGTH = 4

    fun iconFor(comment: String?): String? {
        if (comment.isNullOrBlank()) return null
        val clean = comment.trim()
        return when {
            clean == "기본문장" -> "📖"
            clean.contains("일정") || clean.contains("시간") -> "📅"
            clean.contains("업무") || clean.contains("보고") || clean.contains("비즈니스") || clean.contains("개발") -> "💼"
            clean.contains("양해") || clean.contains("안심") || clean.contains("지연") -> "⏳"
            clean.contains("감사") || clean.contains("응원") || clean.contains("축하") || clean.contains("존댓말") -> "🙏"
            clean.contains("제안") || clean.contains("방안") || clean.contains("아이디어") -> "💡"
            clean.contains("이메일") || clean.contains("📧") -> "📧"
            clean.contains("교정") || clean.contains("✏️") -> "✏️"
            clean.contains("이어쓰기") || clean.contains("Gemma") || clean.contains("생성") || clean.contains("완성") -> "✨"
            clean.contains("동의") || clean.contains("확인") || clean.contains("인사") || clean.contains("요청") -> "💬"
            clean.contains("답변") || clean.contains("대화") || clean.contains("친근") || clean.contains("구문") || clean.contains("자주") || clean.contains("일상") -> "💬"
            clean.contains("맞춤") || clean.contains("AI") || clean.contains("스타일") || clean.contains("✨") -> "✨"
            clean.contains("웹") || clean.contains("🌐") -> "🌐"
            else -> {
                val noSpace = clean.replace(Regex("\\s"), "")
                if (noSpace.isNotEmpty() && noSpace.length <= SYMBOL_ONLY_MAX_LENGTH && isSymbolOnly(noSpace)) {
                    noSpace
                } else {
                    null
                }
            }
        }
    }

    fun isBadge(comment: String?): Boolean = iconFor(comment) != null

    private val NEXT_WORD_MARKERS = setOf("next word", "다음 단어")

    /** The Hangul engine tags next-word candidates with a "Next word" note; it is noise in the candidate bar. */
    fun isNextWordMarker(comment: String?): Boolean =
        comment != null && comment.trim().lowercase() in NEXT_WORD_MARKERS

    private fun isSymbolOnly(text: String): Boolean {
        val codePoints = text.codePoints().toArray()
        if (codePoints.isEmpty()) return false
        return codePoints.all { cp ->
            cp == VARIATION_SELECTOR_16 || when (Character.getType(cp)) {
                Character.OTHER_SYMBOL.toInt(),
                Character.SURROGATE.toInt(),
                Character.NON_SPACING_MARK.toInt(),
                Character.FORMAT.toInt() -> true
                else -> false
            }
        }
    }
}
