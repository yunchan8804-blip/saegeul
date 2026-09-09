/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.sentencepack

internal object SentencePackText {
    private val whitespace = Regex("\\s+")
    private val url = Regex("(?i)(https?://|www\\.)")
    private val email = Regex("(?i)\\b[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}\\b")
    private val phone = Regex("(?:\\d{2,3}[- ]?){2}\\d{3,4}")
    private val prohibitedTerms = listOf("자살", "자해", "살인", "강간", "성폭행", "혐오", "죽어", "죽여", "야동", "성매매")

    fun normalizeAccepted(value: String): String? {
        if (value.any(Character::isISOControl)) return null
        val normalized = normalize(value)
        val tokens = normalized.split(' ')
        if (tokens.size !in 3..12 || normalized.length > 240) return null
        if (!normalized.any { it in '가'..'힣' }) return null
        if (normalized.any(Character::isISOControl) || normalized.any { it in '\u1100'..'\u11FF' || it in '\u3130'..'\u318F' || it in '\u4E00'..'\u9FFF' }) return null
        if (normalized.any(Char::isDigit) || normalized.any { it in 'A'..'Z' || it in 'a'..'z' }) return null
        if (url.containsMatchIn(normalized) || email.containsMatchIn(normalized) || phone.containsMatchIn(normalized)) return null
        if (prohibitedTerms.any(normalized::contains)) return null
        return normalized
    }

    fun normalize(value: String): String = value.trim().replace(whitespace, " ")

    fun isQuerySafe(value: String): Boolean = value.length <= 1024 && value.none {
        Character.isISOControl(it) && it != '\n' && it != '\r' && it != '\t'
    } &&
        value.none { it in '\u1100'..'\u11FF' || it in '\u3130'..'\u318F' || it in '\u4E00'..'\u9FFF' }
}
