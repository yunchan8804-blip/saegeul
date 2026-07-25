/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.typo

enum class TypoRecoveryDirection {
    EnglishToHangul,
    HangulToEnglish
}

data class TypoRecoveryProposal(
    val direction: TypoRecoveryDirection,
    val replacement: String
)

data class TypoRecoveryChunk(
    val text: String,
    val trailingPunctuation: String
) {
    val original: String = text + trailingPunctuation
}

data class TypoRecoveryEditorTarget(
    val packageName: String,
    val fieldId: Int,
    val inputType: Int
)

data class TypoRecoverySnapshot(
    val editor: TypoRecoveryEditorTarget,
    val chunk: TypoRecoveryChunk,
    val proposals: List<TypoRecoveryProposal>
)

/** Pure, offline Dubeolsik mistype conversion. It never reads a clipboard or contacts a provider. */
object KoreanTypoRecovery {
    fun proposals(chunk: TypoRecoveryChunk): List<TypoRecoveryProposal> {
        val latinCount = chunk.text.count { it.isAsciiLetter() }
        val hangulCount = chunk.text.count { it.isHangulForRecovery() }
        val candidates = when {
            latinCount > 0 && hangulCount == 0 -> listOf(
                TypoRecoveryProposal(
                    TypoRecoveryDirection.EnglishToHangul,
                    englishToHangul(chunk.text) + chunk.trailingPunctuation
                )
            )
            hangulCount > 0 && latinCount == 0 -> listOf(
                TypoRecoveryProposal(
                    TypoRecoveryDirection.HangulToEnglish,
                    hangulToEnglish(chunk.text) + chunk.trailingPunctuation
                )
            )
            else -> listOf(
                TypoRecoveryProposal(
                    TypoRecoveryDirection.EnglishToHangul,
                    englishToHangul(chunk.text) + chunk.trailingPunctuation
                ),
                TypoRecoveryProposal(
                    TypoRecoveryDirection.HangulToEnglish,
                    hangulToEnglish(chunk.text) + chunk.trailingPunctuation
                )
            )
        }
        return candidates.distinctBy { it.replacement }
            .filter { it.replacement != chunk.original }
    }

    fun lastChunk(beforeCursor: String, maxLength: Int = 64): TypoRecoveryChunk? {
        val tail = beforeCursor.takeLast(maxLength)
        val punctuation = tail.takeLastWhile { it in TRAILING_PUNCTUATION }
        val body = tail.dropLast(punctuation.length).takeLastWhile {
            it.isAsciiLetter() || it.isHangulForRecovery()
        }
        return body.takeIf(String::isNotEmpty)?.let { TypoRecoveryChunk(it, punctuation) }
    }

    fun englishToHangul(input: String): String {
        val output = StringBuilder()
        var leading: Char? = null
        var vowel: Char? = null
        var trailing: Char? = null

        fun flush() {
            when {
                leading != null && vowel != null -> {
                    val leadingIndex = INITIALS.indexOf(leading)
                    val vowelIndex = VOWELS.indexOf(vowel)
                    val trailingIndex = FINALS.indexOf(trailing)
                    if (leadingIndex >= 0 && vowelIndex >= 0 && trailingIndex >= 0) {
                        output.append(
                            (HANGUL_BASE + (leadingIndex * VOWELS.size + vowelIndex) * FINALS.size +
                                trailingIndex).toChar()
                        )
                    } else {
                        leading?.let(output::append)
                        vowel?.let(output::append)
                        trailing?.let(output::append)
                    }
                }
                leading != null -> output.append(leading)
                vowel != null -> output.append(vowel)
            }
            leading = null
            vowel = null
            trailing = null
        }

        fun accept(jamo: Char) {
            if (jamo in VOWELS) {
                when {
                    leading == null && vowel == null -> vowel = jamo
                    leading == null -> {
                        val combined = COMBINED_VOWELS[vowel to jamo]
                        if (combined != null) vowel = combined else {
                            flush()
                            vowel = jamo
                        }
                    }
                    vowel == null -> vowel = jamo
                    trailing == null -> {
                        val combined = COMBINED_VOWELS[vowel to jamo]
                        if (combined != null) vowel = combined else {
                            flush()
                            vowel = jamo
                        }
                    }
                    else -> {
                        val oldTrailing = trailing!!
                        val split = SPLIT_FINALS[oldTrailing]
                        trailing = split?.first
                        flush()
                        leading = split?.second ?: oldTrailing
                        vowel = jamo
                    }
                }
                return
            }

            when {
                leading == null && vowel == null -> leading = jamo
                leading == null -> {
                    flush()
                    leading = jamo
                }
                vowel == null -> {
                    flush()
                    leading = jamo
                }
                trailing == null && jamo in FINALS -> trailing = jamo
                trailing == null -> {
                    flush()
                    leading = jamo
                }
                else -> {
                    val combined = COMBINED_FINALS[trailing to jamo]
                    if (combined != null) trailing = combined else {
                        flush()
                        leading = jamo
                    }
                }
            }
        }

        input.forEach { character ->
            val jamo = englishKeyToJamo(character)
            if (jamo == null) {
                flush()
                output.append(character)
            } else {
                accept(jamo)
            }
        }
        flush()
        return output.toString()
    }

    fun hangulToEnglish(input: String): String = buildString {
        input.forEach { character ->
            when {
                character.code in HANGUL_BASE..HANGUL_END -> {
                    val offset = character.code - HANGUL_BASE
                    val leading = INITIALS[offset / (VOWELS.size * FINALS.size)]
                    val vowel = VOWELS[(offset % (VOWELS.size * FINALS.size)) / FINALS.size]
                    val trailing = FINALS[offset % FINALS.size]
                    append(JAMO_TO_KEYS.getValue(leading))
                    append(JAMO_TO_KEYS.getValue(vowel))
                    trailing?.let { append(JAMO_TO_KEYS.getValue(it)) }
                }
                character in JAMO_TO_KEYS -> append(JAMO_TO_KEYS.getValue(character))
                else -> append(character)
            }
        }
    }

    private fun englishKeyToJamo(character: Char): Char? = when (character) {
        'Q' -> 'ㅃ'
        'W' -> 'ㅉ'
        'E' -> 'ㄸ'
        'R' -> 'ㄲ'
        'T' -> 'ㅆ'
        'O' -> 'ㅒ'
        'P' -> 'ㅖ'
        else -> ENGLISH_TO_JAMO[character.lowercaseChar()]
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isHangulForRecovery() =
        code in HANGUL_BASE..HANGUL_END || this in JAMO_TO_KEYS

    private const val HANGUL_BASE = 0xAC00
    private const val HANGUL_END = 0xD7A3
    private const val TRAILING_PUNCTUATION = ".,!?…;:)]}'\""

    private val INITIALS = listOf(
        'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
        'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    )
    private val VOWELS = listOf(
        'ㅏ', 'ㅐ', 'ㅑ', 'ㅒ', 'ㅓ', 'ㅔ', 'ㅕ', 'ㅖ', 'ㅗ', 'ㅘ',
        'ㅙ', 'ㅚ', 'ㅛ', 'ㅜ', 'ㅝ', 'ㅞ', 'ㅟ', 'ㅠ', 'ㅡ', 'ㅢ', 'ㅣ'
    )
    private val FINALS: List<Char?> = listOf(
        null, 'ㄱ', 'ㄲ', 'ㄳ', 'ㄴ', 'ㄵ', 'ㄶ', 'ㄷ', 'ㄹ', 'ㄺ',
        'ㄻ', 'ㄼ', 'ㄽ', 'ㄾ', 'ㄿ', 'ㅀ', 'ㅁ', 'ㅂ', 'ㅄ', 'ㅅ',
        'ㅆ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    )

    private val ENGLISH_TO_JAMO = mapOf(
        'q' to 'ㅂ', 'w' to 'ㅈ', 'e' to 'ㄷ', 'r' to 'ㄱ', 't' to 'ㅅ',
        'y' to 'ㅛ', 'u' to 'ㅕ', 'i' to 'ㅑ', 'o' to 'ㅐ', 'p' to 'ㅔ',
        'a' to 'ㅁ', 's' to 'ㄴ', 'd' to 'ㅇ', 'f' to 'ㄹ', 'g' to 'ㅎ',
        'h' to 'ㅗ', 'j' to 'ㅓ', 'k' to 'ㅏ', 'l' to 'ㅣ', 'z' to 'ㅋ',
        'x' to 'ㅌ', 'c' to 'ㅊ', 'v' to 'ㅍ', 'b' to 'ㅠ', 'n' to 'ㅜ',
        'm' to 'ㅡ'
    )

    private val JAMO_TO_KEYS = mapOf(
        'ㄱ' to "r", 'ㄲ' to "R", 'ㄳ' to "rt", 'ㄴ' to "s", 'ㄵ' to "sw",
        'ㄶ' to "sg", 'ㄷ' to "e", 'ㄸ' to "E", 'ㄹ' to "f", 'ㄺ' to "fr",
        'ㄻ' to "fa", 'ㄼ' to "fq", 'ㄽ' to "ft", 'ㄾ' to "fx", 'ㄿ' to "fv",
        'ㅀ' to "fg", 'ㅁ' to "a", 'ㅂ' to "q", 'ㅃ' to "Q", 'ㅄ' to "qt",
        'ㅅ' to "t", 'ㅆ' to "T", 'ㅇ' to "d", 'ㅈ' to "w", 'ㅉ' to "W",
        'ㅊ' to "c", 'ㅋ' to "z", 'ㅌ' to "x", 'ㅍ' to "v", 'ㅎ' to "g",
        'ㅏ' to "k", 'ㅐ' to "o", 'ㅑ' to "i", 'ㅒ' to "O", 'ㅓ' to "j",
        'ㅔ' to "p", 'ㅕ' to "u", 'ㅖ' to "P", 'ㅗ' to "h", 'ㅘ' to "hk",
        'ㅙ' to "ho", 'ㅚ' to "hl", 'ㅛ' to "y", 'ㅜ' to "n", 'ㅝ' to "nj",
        'ㅞ' to "np", 'ㅟ' to "nl", 'ㅠ' to "b", 'ㅡ' to "m", 'ㅢ' to "ml",
        'ㅣ' to "l"
    )

    private val COMBINED_VOWELS = mapOf(
        ('ㅗ' to 'ㅏ') to 'ㅘ', ('ㅗ' to 'ㅐ') to 'ㅙ', ('ㅗ' to 'ㅣ') to 'ㅚ',
        ('ㅘ' to 'ㅣ') to 'ㅙ', ('ㅜ' to 'ㅓ') to 'ㅝ', ('ㅜ' to 'ㅔ') to 'ㅞ',
        ('ㅜ' to 'ㅣ') to 'ㅟ', ('ㅝ' to 'ㅣ') to 'ㅞ', ('ㅡ' to 'ㅣ') to 'ㅢ'
    )
    private val COMBINED_FINALS = mapOf(
        ('ㄱ' to 'ㅅ') to 'ㄳ', ('ㄴ' to 'ㅈ') to 'ㄵ', ('ㄴ' to 'ㅎ') to 'ㄶ',
        ('ㄹ' to 'ㄱ') to 'ㄺ', ('ㄹ' to 'ㅁ') to 'ㄻ', ('ㄹ' to 'ㅂ') to 'ㄼ',
        ('ㄹ' to 'ㅅ') to 'ㄽ', ('ㄹ' to 'ㅌ') to 'ㄾ', ('ㄹ' to 'ㅍ') to 'ㄿ',
        ('ㄹ' to 'ㅎ') to 'ㅀ', ('ㅂ' to 'ㅅ') to 'ㅄ'
    )
    private val SPLIT_FINALS = COMBINED_FINALS.entries.associate { (pair, combined) ->
        combined to pair
    }
}
