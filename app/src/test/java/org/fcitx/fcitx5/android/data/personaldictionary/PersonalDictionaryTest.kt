/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.personaldictionary

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalDictionaryTest {
    @Test
    fun `codec round trip preserves opt-in state and ordered categories`() {
        val dictionary = PersonalDictionary(
            enabled = true,
            words = listOf(
                PersonalWord(PersonalWordCategory.Name, "윤찬"),
                PersonalWord(PersonalWordCategory.Company, "오픈에이아이"),
                PersonalWord(PersonalWordCategory.TechnicalTerm, "트랜스포머")
            )
        )

        assertEquals(dictionary, decodePersonalDictionary(encodePersonalDictionary(dictionary)))
    }

    @Test
    fun `dictionary is disabled by default and adding data does not opt in`() {
        val dictionary = PersonalDictionary(words = listOf(
            PersonalWord(PersonalWordCategory.Name, "윤찬")
        ))

        assertFalse(dictionary.enabled)
        assertFalse(decodePersonalDictionary(encodePersonalDictionary(dictionary))!!.enabled)
    }

    @Test
    fun `normalization trims NFC data and deduplicates by word`() {
        val normalized = PersonalDictionary(
            enabled = true,
            words = listOf(
                PersonalWord(PersonalWordCategory.Name, "  가\u1100\u1161  "),
                PersonalWord(PersonalWordCategory.Company, "가가")
            )
        ).normalized()

        assertEquals(listOf(PersonalWord(PersonalWordCategory.Name, "가가")), normalized.words)
    }

    @Test
    fun `corruption and unsupported fields fail closed`() {
        val malformed = listOf(
            "garbage",
            "$PERSONAL_DICTIONARY_HEADER\nenabled\t2\n",
            "$PERSONAL_DICTIONARY_HEADER\nenabled\t1\nword\tunknown\t윤찬\n",
            "$PERSONAL_DICTIONARY_HEADER\nenabled\t1\nword\tname\t윤찬\textra\n",
            "$PERSONAL_DICTIONARY_HEADER\nenabled\t1\nword\tname\tyunchan\n"
        )

        malformed.forEach { assertNull(decodePersonalDictionary(it.toByteArray())) }
        assertNull(decodePersonalDictionary(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    @Test
    fun `serialized data has no learning source editor text or package metadata`() {
        val encoded = encodePersonalDictionary(
            PersonalDictionary(true, listOf(PersonalWord(PersonalWordCategory.Name, "윤찬")))
        ).toString(Charsets.UTF_8)

        assertTrue(encoded.contains("word\tname\t윤찬"))
        listOf("source", "history", "editor", "package", "selection").forEach {
            assertFalse(encoded.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun `no personalized learning editor maps to native sensitive gate`() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }

        assertTrue(CapabilityFlags.fromEditorInfo(info).has(CapabilityFlag.Sensitive))
    }
}
