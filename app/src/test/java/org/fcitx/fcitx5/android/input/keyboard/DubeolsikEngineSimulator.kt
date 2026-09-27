/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * A minimal but real Dubeolsik automaton for tests, shared across this package's Mobile Hangul
 * composer tests. Unlike a flat key-string buffer, it actually composes/decomposes syllables:
 * choseong+jungseong(+jongseong), vowel-combining (ㅗ+ㅏ→ㅘ), compound finals (ㄱ+ㅅ→ㄳ), the
 * "도깨비불" batchim move when a vowel follows a syllable that already has one, and commits a
 * syllable to the editor text the moment a key can no longer attach to it. Backspace undoes
 * exactly one still-open component, or one already-committed character if nothing is open.
 *
 * [RedTeamHangulEngineAutomataTest]'s VirtualDubeolsikBuffer is a different, simpler stand-in
 * used by that red-team suite and is left untouched; this class is for the rest of this package.
 */
class DubeolsikEngineSimulator {
    private val committed = StringBuilder()
    private var choseongKey: Char? = null
    private val jungseongKeys = mutableListOf<Char>()
    private val jongseongKeys = mutableListOf<Char>()

    fun apply(outputs: List<MobileHangulComposer.Output>) {
        outputs.forEach { output ->
            when (output) {
                MobileHangulComposer.Output.Backspace -> backspace()
                MobileHangulComposer.Output.Space -> {
                    flush()
                    committed.append(' ')
                }
                is MobileHangulComposer.Output.Keys -> output.value.forEach(::pressRawKey)
            }
        }
    }

    /** Committed editor text, followed by whatever syllable is still open (being composed). */
    fun content(): String = committed.toString() + openText()

    private fun pressRawKey(rawKey: Char) {
        val jamo = ATOMIC_KEYS[rawKey] ?: return
        if (jamo in CHOSEONG_LIST) pressConsonant(jamo) else pressVowel(jamo)
    }

    private fun pressConsonant(c: Char) {
        when {
            jungseongKeys.isEmpty() -> {
                // No open vowel yet: a bare, un-followed consonant just sits there on its own; a
                // second one commits it as its own bare jamo letter and starts fresh with this one.
                if (choseongKey != null) committed.append(choseongKey)
                choseongKey = c
            }
            jongseongKeys.isEmpty() -> jongseongKeys.add(c)
            else -> {
                val combinable = JONGSEONG_COMBINE.containsKey(jongseongKeys.last() to c)
                if (combinable) {
                    jongseongKeys.add(c)
                } else {
                    flush()
                    choseongKey = c
                }
            }
        }
    }

    private fun pressVowel(v: Char) {
        if (jongseongKeys.isNotEmpty()) {
            // 도깨비불: the jongseong's last consonant moves to become the next syllable's onset.
            val moved = jongseongKeys.removeLast()
            flush()
            choseongKey = moved
            jungseongKeys.add(v)
            return
        }
        if (jungseongKeys.isEmpty()) {
            jungseongKeys.add(v)
            return
        }
        val combinable = JUNGSEONG_COMBINE.containsKey(jungseongValue()!! to v)
        if (combinable) {
            jungseongKeys.add(v)
        } else {
            flush()
            jungseongKeys.add(v)
        }
    }

    private fun backspace() {
        when {
            jongseongKeys.isNotEmpty() -> jongseongKeys.removeAt(jongseongKeys.lastIndex)
            jungseongKeys.isNotEmpty() -> jungseongKeys.removeAt(jungseongKeys.lastIndex)
            choseongKey != null -> choseongKey = null
            committed.isNotEmpty() -> committed.deleteCharAt(committed.length - 1)
        }
    }

    private fun flush() {
        if (choseongKey != null || jungseongKeys.isNotEmpty()) {
            committed.append(openText())
        }
        choseongKey = null
        jungseongKeys.clear()
        jongseongKeys.clear()
    }

    private fun openText(): String {
        val jung = jungseongValue()
        return when {
            choseongKey == null && jung == null -> ""
            jung == null -> choseongKey.toString()
            else -> composeSyllable(choseongKey ?: FILLER_IEUNG, jung, jongseongValue())
        }
    }

    private fun jungseongValue(): Char? = jungseongKeys.fold(null as Char?) { acc, k ->
        if (acc == null) k else JUNGSEONG_COMBINE[acc to k] ?: k
    }

    private fun jongseongValue(): Char? = jongseongKeys.fold(null as Char?) { acc, k ->
        if (acc == null) k else JONGSEONG_COMBINE[acc to k] ?: k
    }

    private fun composeSyllable(cho: Char, jung: Char, jong: Char?): String {
        val choIdx = CHOSEONG_LIST.indexOf(cho)
        val jungIdx = JUNGSEONG_LIST.indexOf(jung)
        val jongIdx = if (jong == null) 0 else JONGSEONG_LIST.indexOf(jong) + 1
        if (choIdx < 0 || jungIdx < 0 || jongIdx < 0) return buildString {
            append(cho); append(jung); jong?.let(::append)
        }
        val codePoint = 0xAC00 + (choIdx * 21 + jungIdx) * 28 + jongIdx
        return codePoint.toChar().toString()
    }

    companion object {
        private const val FILLER_IEUNG = 'ㅇ'

        private const val CHOSEONG_LIST = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
        private const val JUNGSEONG_LIST = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"

        /** The 27 possible jongseong, index 0 (none) handled separately by [composeSyllable]. */
        private const val JONGSEONG_LIST = "ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

        private val JUNGSEONG_COMBINE = mapOf(
            ('ㅏ' to 'ㅣ') to 'ㅐ', ('ㅑ' to 'ㅣ') to 'ㅒ',
            ('ㅓ' to 'ㅣ') to 'ㅔ', ('ㅕ' to 'ㅣ') to 'ㅖ',
            ('ㅗ' to 'ㅏ') to 'ㅘ', ('ㅗ' to 'ㅐ') to 'ㅙ', ('ㅗ' to 'ㅣ') to 'ㅚ',
            ('ㅜ' to 'ㅓ') to 'ㅝ', ('ㅜ' to 'ㅔ') to 'ㅞ', ('ㅜ' to 'ㅣ') to 'ㅟ',
            ('ㅡ' to 'ㅣ') to 'ㅢ', ('ㅘ' to 'ㅣ') to 'ㅙ', ('ㅝ' to 'ㅣ') to 'ㅞ'
        )

        private val JONGSEONG_COMBINE = mapOf(
            ('ㄱ' to 'ㅅ') to 'ㄳ',
            ('ㄴ' to 'ㅈ') to 'ㄵ', ('ㄴ' to 'ㅎ') to 'ㄶ',
            ('ㄹ' to 'ㄱ') to 'ㄺ', ('ㄹ' to 'ㅁ') to 'ㄻ', ('ㄹ' to 'ㅂ') to 'ㄼ',
            ('ㄹ' to 'ㅅ') to 'ㄽ', ('ㄹ' to 'ㅌ') to 'ㄾ', ('ㄹ' to 'ㅍ') to 'ㄿ',
            ('ㄹ' to 'ㅎ') to 'ㅀ',
            ('ㅂ' to 'ㅅ') to 'ㅄ'
        )

        /** Reverse of [MobileHangulComposer]'s own dubeolsik key map: raw ASCII key -> jamo. */
        private val ATOMIC_KEYS: Map<Char, Char> = mapOf(
            'r' to 'ㄱ', 'R' to 'ㄲ', 's' to 'ㄴ', 'e' to 'ㄷ', 'E' to 'ㄸ',
            'f' to 'ㄹ', 'a' to 'ㅁ', 'q' to 'ㅂ', 'Q' to 'ㅃ', 't' to 'ㅅ',
            'T' to 'ㅆ', 'd' to 'ㅇ', 'w' to 'ㅈ', 'W' to 'ㅉ', 'c' to 'ㅊ',
            'z' to 'ㅋ', 'x' to 'ㅌ', 'v' to 'ㅍ', 'g' to 'ㅎ',
            'k' to 'ㅏ', 'o' to 'ㅐ', 'i' to 'ㅑ', 'O' to 'ㅒ', 'j' to 'ㅓ',
            'p' to 'ㅔ', 'u' to 'ㅕ', 'P' to 'ㅖ', 'h' to 'ㅗ', 'y' to 'ㅛ',
            'n' to 'ㅜ', 'b' to 'ㅠ', 'm' to 'ㅡ', 'l' to 'ㅣ'
        )
    }
}
