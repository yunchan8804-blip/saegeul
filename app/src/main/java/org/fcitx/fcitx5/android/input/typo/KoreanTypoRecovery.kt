/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.typo

import org.fcitx.fcitx5.android.input.hangul.HangulSyllable

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
                        output.append(HangulSyllable.compose(leadingIndex, vowelIndex, trailingIndex))
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
                HangulSyllable.isSyllable(character) -> {
                    val leading = INITIALS[HangulSyllable.choseongIndex(character)]
                    val vowel = VOWELS[HangulSyllable.jungseongIndex(character)]
                    val trailing = FINALS[HangulSyllable.jongseongIndex(character)]
                    append(JAMO_TO_KEYS.getValue(leading))
                    append(JAMO_TO_KEYS.getValue(vowel))
                    trailing?.let { append(JAMO_TO_KEYS.getValue(it)) }
                }
                character in JAMO_TO_KEYS -> append(JAMO_TO_KEYS.getValue(character))
                else -> append(character)
            }
        }
    }

    // Shift 7개(Q W E R T O P)만 대문자 그대로 찾고, 나머지는 소문자로 바꿔 찾는다.
    private fun englishKeyToJamo(character: Char): Char? =
        if (character in DubeolsikKeyMap.SHIFTED_KEYS) {
            DubeolsikKeyMap.KEY_TO_JAMO[character]
        } else {
            DubeolsikKeyMap.KEY_TO_JAMO[character.lowercaseChar()]
        }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isHangulForRecovery() =
        HangulSyllable.isSyllable(this) || this in JAMO_TO_KEYS

    private const val TRAILING_PUNCTUATION = ".,!?…;:)]}'\""

    private val INITIALS = HangulSyllable.CHOSEONG.toList()
    private val VOWELS = HangulSyllable.JUNGSEONG.toList()
    private val FINALS: List<Char?> = listOf<Char?>(null) + HangulSyllable.JONGSEONG_TAIL.toList()

    private val JAMO_TO_KEYS = DubeolsikKeyMap.JAMO_TO_KEYS

    // 엔진은 7개, 모바일은 13개만 받는다. 복구만 ㅘ+ㅣ→ㅙ, ㅝ+ㅣ→ㅞ를 받으므로 모으지 않는다.
    private val COMBINED_VOWELS = mapOf(
        ('ㅗ' to 'ㅏ') to 'ㅘ', ('ㅗ' to 'ㅐ') to 'ㅙ', ('ㅗ' to 'ㅣ') to 'ㅚ',
        ('ㅘ' to 'ㅣ') to 'ㅙ', ('ㅜ' to 'ㅓ') to 'ㅝ', ('ㅜ' to 'ㅔ') to 'ㅞ',
        ('ㅜ' to 'ㅣ') to 'ㅟ', ('ㅝ' to 'ㅣ') to 'ㅞ', ('ㅡ' to 'ㅣ') to 'ㅢ'
    )
    private val COMBINED_FINALS = HangulSyllable.COMPOUND_JONGSEONG_JOIN
    private val SPLIT_FINALS = HangulSyllable.COMPOUND_JONGSEONG_SPLIT
}
