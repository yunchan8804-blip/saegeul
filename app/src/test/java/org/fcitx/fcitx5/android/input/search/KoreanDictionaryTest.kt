/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class KoreanDictionaryTest {
    @Test
    fun extractsSelectedOrCursorAdjacentModernHangulOnly() {
        assertEquals("나무", KoreanDictionaryQuery.extract(" 나무 ", "무시"))
        assertEquals("안녕하세요", KoreanDictionaryQuery.extract(null, "오늘 안녕하세요! ".trimEnd(' ')))
        assertEquals("되다", KoreanDictionaryQuery.extract(null, "잘 되다"))
        assertEquals(null, KoreanDictionaryQuery.extract("漢字", "hello"))
        assertEquals(null, KoreanDictionaryQuery.extract(null, "hello"))
    }

    @Test
    fun parserSkipsMetadataMalformedAndNonHangulRows() {
        val index = indexOf(
            "# source metadata",
            "나무\t명사\t여러 해 동안 자라는 식물.\t재목.",
            "漢字\t명사\t보이면 안 됨.",
            "잘못된 행"
        )

        val result = index.lookup("나무")
        assertEquals(1, result.size)
        assertEquals(listOf("여러 해 동안 자라는 식물.", "재목."), result.single().definitions)
        assertTrue(index.lookup("漢字").isEmpty())
    }

    @Test
    fun exactMatchWinsAndPrefixFallbackIsBounded() {
        val index = indexOf(
            "나\t대명사\t말하는 이를 가리키는 말.",
            "나무\t명사\t여러 해 동안 자라는 식물.",
            "나비\t명사\t날개가 있는 곤충.",
            "나뭇가지\t명사\t나무에서 뻗어 나온 가지."
        )

        assertEquals(listOf("나"), index.lookup("나").map(KoreanDictionaryEntry::word))
        assertEquals(
            listOf("나뭇가지"),
            index.lookup("나뭇").map(KoreanDictionaryEntry::word)
        )
        assertEquals(1, index.lookup("나", limit = 1).size)
        assertTrue(index.lookup("나", limit = 0).isEmpty())
    }

    private fun indexOf(vararg lines: String): KoreanDictionaryIndex =
        KoreanDictionaryIndex.read(StringReader(lines.joinToString("\n")).buffered())
}
