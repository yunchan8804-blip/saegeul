/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.typo.DubeolsikKeyMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 글자 키에 함께 적힌 보조 문자(밀어 올리거나 길게 눌러 치는 문자)와 글자 키의 대응이 화면에 보이는
 * 배치와 같은지. 기대값은 에뮬레이터 화면의 배치를 글자 그대로 옮긴 표이고, 실제 값은 키 정의에서 읽는다.
 */
class SecondaryCharKeysTest {

    private fun expected(vararg rows: Pair<String, String>): Map<Char, Char> =
        rows.flatMap { (symbols, letters) ->
            require(symbols.length == letters.length)
            symbols.zip(letters)
        }.toMap()

    private val lowerRows = arrayOf(
        "@*+-=/#()" to "asdfghjkl",
        "':\"?!~\\" to "zxcvbnm"
    )

    @Test
    fun withoutAPinnedNumberRowTheTopRowSwipesTypeDigits() {
        val table = expected("1234567890" to "qwertyuiop", *lowerRows)

        assertEquals(table, TextKeyboard.secondaryCharKeys(numberRowPinned = false))
    }

    @Test
    fun withAPinnedNumberRowTheTopRowSwipesTypeSymbolsAndTheDigitKeysSitAboveTheTopRow() {
        val table = expected("%^&_[]{}<>" to "qwertyuiop", "1234567890" to "qwertyuiop", *lowerRows)

        assertEquals(table, TextKeyboard.secondaryCharKeys(numberRowPinned = true))
    }

    @Test
    fun everyMappedLetterKeyIsOnTheDubeolsikLayout() {
        for (pinned in listOf(false, true)) {
            for ((symbol, letter) in TextKeyboard.secondaryCharKeys(pinned)) {
                assertNotNull("$symbol -> $letter", DubeolsikKeyMap.keyCoordinate(letter))
            }
        }
    }
}
