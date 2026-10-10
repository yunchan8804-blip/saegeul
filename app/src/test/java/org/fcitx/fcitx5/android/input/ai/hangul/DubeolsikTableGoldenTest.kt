/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.hangul

import org.fcitx.fcitx5.android.input.ai.KoreanTypoCorrectionEngine
import org.fcitx.fcitx5.android.input.typo.KoreanTypoRecovery
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 두벌식 키·자모 대응표를 한 곳으로 모으기 전후에 같은 결과가 나오는지 고정한다.
 * 엔진과 복구는 같은 키 입력에도 결과가 다르다. 엔진은 정확히 일치하는 키만 받고 모음 결합이 7개이며,
 * 복구는 Shift 7개 외에는 소문자로 바꿔 찾고 모음 결합이 9개다. 그 차이를 그대로 고정한다.
 */
class DubeolsikTableGoldenTest {

    private val inputs = listOf(
        "ksk", "kfr", "kfrk", "Dkssud", "rK", "dhkl", "dnjl", "hkl", "ekfrk", "rt", "qt", "wnfrk"
    )

    private val engineGolden = listOf(
        "ㅏㄴㅏ", "ㅏㄺ", "ㅏㄺㅏ", "Dㅏㄴ녕", "ㄱK", "와ㅣ", "워ㅣ", "ㅘㅣ", "달가", "ㄱㅅ", "ㅂㅅ", "줄가"
    )

    private val recoveryGolden = listOf(
        "ㅏ나", "ㅏㄹㄱ", "ㅏㄹ가", "안녕", "가", "왜", "웨", "ㅙ", "달가", "ㄱㅅ", "ㅂㅅ", "줄가"
    )

    @Test
    fun `엔진 qwertyToHangul 현재 결과`() {
        assertEquals(engineGolden, inputs.map { KoreanTypoCorrectionEngine.qwertyToHangul(it) })
    }

    @Test
    fun `복구 englishToHangul 현재 결과`() {
        assertEquals(recoveryGolden, inputs.map { KoreanTypoRecovery.englishToHangul(it) })
    }

    private val asciiPrintable = (0x20..0x7E).map { it.toChar().toString() }

    private val sequenceAlphabet = "rRsefqQtwkhloijnmgxa".toList()

    private fun fourKeySequences(): List<String> {
        val result = ArrayList<String>(sequenceAlphabet.size * sequenceAlphabet.size * sequenceAlphabet.size * sequenceAlphabet.size)
        for (a in sequenceAlphabet) for (b in sequenceAlphabet) for (c in sequenceAlphabet) for (d in sequenceAlphabet) {
            result.add("$a$b$c$d")
        }
        return result
    }

    @Test
    fun `엔진 ASCII 한 글자 결과`() {
        assertEquals(ENGINE_ASCII_GOLDEN, asciiPrintable.joinToString("|") { KoreanTypoCorrectionEngine.qwertyToHangul(it) })
    }

    @Test
    fun `복구 ASCII 한 글자 결과`() {
        assertEquals(RECOVERY_ASCII_GOLDEN, asciiPrintable.joinToString("|") { KoreanTypoRecovery.englishToHangul(it) })
    }

    @Test
    fun `복구 호환 자모 전체를 키로 되돌린 결과`() {
        val jamo = (HangulSyllable.COMPAT_JAMO_START..LAST_KEYED_JAMO).map { it.toChar().toString() }
        assertEquals(RECOVERY_JAMO_KEYS_GOLDEN, jamo.joinToString("|") { KoreanTypoRecovery.hangulToEnglish(it) })
    }

    @Test
    fun `엔진 네 키 조합 전체 결과`() {
        val joined = fourKeySequences().joinToString("|") { KoreanTypoCorrectionEngine.qwertyToHangul(it) }
        assertEquals(ENGINE_FOUR_KEY_HASH, joined.hashCode())
    }

    @Test
    fun `복구 네 키 조합 전체 결과`() {
        val joined = fourKeySequences().joinToString("|") { KoreanTypoRecovery.englishToHangul(it) }
        assertEquals(RECOVERY_FOUR_KEY_HASH, joined.hashCode())
    }

    private companion object {
        const val LAST_KEYED_JAMO = 0x3163
        const val ENGINE_ASCII_GOLDEN = " |!|\"|#|\$|%|&|'|(|)|*|+|,|-|.|/|0|1|2|3|4|5|6|7|8|9|:|;|<|=|>|?|@|A|B|C|D|ㄸ|F|G|H|I|J|K|L|M|N|ㅒ|ㅖ|ㅃ|ㄲ|S|ㅆ|U|V|ㅉ|X|Y|Z|[|\\|]|^|_|`|ㅁ|ㅠ|ㅊ|ㅇ|ㄷ|ㄹ|ㅎ|ㅗ|ㅑ|ㅓ|ㅏ|ㅣ|ㅡ|ㅜ|ㅐ|ㅔ|ㅂ|ㄱ|ㄴ|ㅅ|ㅕ|ㅍ|ㅈ|ㅌ|ㅛ|ㅋ|{|||}|~"
        const val RECOVERY_ASCII_GOLDEN = " |!|\"|#|\$|%|&|'|(|)|*|+|,|-|.|/|0|1|2|3|4|5|6|7|8|9|:|;|<|=|>|?|@|ㅁ|ㅠ|ㅊ|ㅇ|ㄸ|ㄹ|ㅎ|ㅗ|ㅑ|ㅓ|ㅏ|ㅣ|ㅡ|ㅜ|ㅒ|ㅖ|ㅃ|ㄲ|ㄴ|ㅆ|ㅕ|ㅍ|ㅉ|ㅌ|ㅛ|ㅋ|[|\\|]|^|_|`|ㅁ|ㅠ|ㅊ|ㅇ|ㄷ|ㄹ|ㅎ|ㅗ|ㅑ|ㅓ|ㅏ|ㅣ|ㅡ|ㅜ|ㅐ|ㅔ|ㅂ|ㄱ|ㄴ|ㅅ|ㅕ|ㅍ|ㅈ|ㅌ|ㅛ|ㅋ|{|||}|~"
        const val RECOVERY_JAMO_KEYS_GOLDEN = "r|R|rt|s|sw|sg|e|E|f|fr|fa|fq|ft|fx|fv|fg|a|q|Q|qt|t|T|d|w|W|c|z|x|v|g|k|o|i|O|j|p|u|P|h|hk|ho|hl|y|n|nj|np|nl|b|m|ml|l"
        const val ENGINE_FOUR_KEY_HASH = 294361294
        const val RECOVERY_FOUR_KEY_HASH = -2046312416
    }
}
