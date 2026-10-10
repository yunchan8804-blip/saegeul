/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.typo

import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TypedKeyReaderTest {

    private val reader = TypedKeyReader { TextKeyboard.secondaryCharKeys(numberRowPinned = false) }

    private fun TypedKeyReading.costs(): List<Float> = (0 until keys.length).map(::extraCost)

    @Test
    fun completedSyllablesAndLooseJamoReadAsTheirKeysAtNoExtraCost() {
        val reading = checkNotNull(reader.read("감ㅅㅏ"))

        assertEquals(DubeolsikKeyMap.keySequence("감ㅅㅏ"), reading.keys)
        assertEquals("rkatk", reading.keys)
        assertEquals(List(5) { 0f }, reading.costs())
    }

    @Test
    fun compoundVowelsAndFinalsReadAsTheirSplitKeys() {
        assertEquals("dhk", reader.read("와")?.keys)
        assertEquals("rkqt", reader.read("값")?.keys)
    }

    @Test
    fun latinLettersReadAsTheKeyInTheSamePlaceAtNoExtraCost() {
        val reading = checkNotNull(reader.read("dkssud"))

        assertEquals("dkssud", reading.keys)
        assertEquals(List(6) { 0f }, reading.costs())
        assertEquals(DubeolsikKeyMap.keySequence("안녕"), reading.keys)
    }

    @Test
    fun shiftedLatinLettersKeepTheirShift() {
        assertEquals("Qk", reader.read("Qk")?.keys)
        assertEquals("dk", reader.read("DK")?.keys)
    }

    @Test
    fun secondaryCharBesideLooseJamoReadsAsItsLetterKeyAtTheSecondaryCost() {
        val reading = checkNotNull(reader.read("4ㅏㅁ사"))

        assertEquals("rkatk", reading.keys)
        assertEquals(listOf(TypedKeyReader.SECONDARY_CHAR_COST, 0f, 0f, 0f, 0f), reading.costs())
    }

    @Test
    fun secondaryCharBetweenTwoHangulCharsReadsAsItsLetterKey() {
        val reading = checkNotNull(reader.read("사하1니다"))

        assertEquals("tkgkqslek", reading.keys)
        assertEquals(TypedKeyReader.SECONDARY_CHAR_COST, reading.extraCost(reading.keys.indexOf('q')), 0f)
        assertEquals(1, reading.costs().count { it > 0f })
    }

    @Test
    fun symbolsOfTheSecondAndThirdRowsReadAsTheirLetterKeys() {
        val reading = checkNotNull(reader.read("ㅏ@ㅏ:ㅏ"))

        assertEquals("kakxk", reading.keys)
        assertEquals(listOf(0f, 0.3f, 0f, 0.3f, 0f), reading.costs())
    }

    @Test
    fun numbersAndSymbolsOutsideAStrayPositionStayLiteral() {
        for (text in listOf("10시", "2024년", "3개", "카페24", "100원")) {
            val reading = checkNotNull(reader.read(text))
            assertEquals(text, 0f, reading.costs().sum(), 0f)
            assertEquals(DubeolsikKeyMap.keySequence(text), reading.keys)
        }
    }

    @Test
    fun aCharNoKeyTypesMakesTheWholeFragmentUnreadable() {
        assertNull(reader.read("감사,합니다"))
        assertNull(reader.read("감사😀"))
        assertNull(reader.read("감사합니다。"))
        assertNull(reader.read(""))
    }

    @Test
    fun anEmptySecondaryTableReadsDigitsAsUnreadable() {
        assertNull(TypedKeyReader { emptyMap() }.read("4ㅏㅁ"))
        assertNotNull(TypedKeyReader { emptyMap() }.read("ㅏㅁ"))
    }

    @Test
    fun theSecondaryTableIsReadOnEveryCallSoALayoutChangeApplies() {
        var pinned = false
        val live = TypedKeyReader { TextKeyboard.secondaryCharKeys(pinned) }

        assertNull(live.read("_ㅏ"))
        pinned = true
        assertEquals("rk", live.read("_ㅏ")?.keys)
        pinned = false
        assertNull(live.read("_ㅏ"))
    }
}
