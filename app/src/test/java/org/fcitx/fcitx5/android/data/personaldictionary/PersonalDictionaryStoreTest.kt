/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.personaldictionary

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PersonalDictionaryStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dictionary = PersonalDictionary(
        enabled = true,
        words = listOf(
            PersonalWord(PersonalWordCategory.Name, "윤찬"),
            PersonalWord(PersonalWordCategory.Company, "오픈에이아이")
        )
    )

    private fun wordsFile() = File(tempFolder.root, "words.txt")

    private fun preservedFiles() =
        tempFolder.root.listFiles { _, name -> name.startsWith("words.txt.unreadable-") }.orEmpty().toList()

    @Test
    fun emptyBaseFileFallsBackToTheBackup() {
        val file = wordsFile()
        file.writeBytes(ByteArray(0))
        File(file.path + ".bak").writeBytes(encodePersonalDictionary(dictionary))

        assertEquals(dictionary, PersonalDictionaryStore(file).load())
    }

    @Test
    fun missingBaseFileFallsBackToTheBackup() {
        val file = wordsFile()
        File(file.path + ".bak").writeBytes(encodePersonalDictionary(dictionary))

        assertEquals(dictionary, PersonalDictionaryStore(file).load())
    }

    @Test
    fun emptyFileWithoutBackupIsAnEmptyDictionaryAndNothingIsPreserved() {
        val file = wordsFile()
        file.writeBytes(ByteArray(0))

        assertEquals(PersonalDictionary(), PersonalDictionaryStore(file).load())
        assertTrue(preservedFiles().isEmpty())
    }

    @Test
    fun undecodableFileIsKeptAndTheNextEditStartsFromAnEmptyDictionary() {
        val file = wordsFile()
        val garbage = "not a personal dictionary".toByteArray()
        file.writeBytes(garbage)
        val store = PersonalDictionaryStore(file)

        assertEquals(PersonalDictionary(), store.load())

        val kept = preservedFiles().single()
        assertArrayEquals(garbage, kept.readBytes())
        assertFalse(file.exists())

        store.upsert(PersonalWord(PersonalWordCategory.Name, "윤찬"))
        assertEquals(listOf("윤찬"), store.load().words.map { it.value })
        assertArrayEquals(garbage, preservedFiles().single().readBytes())
    }

    @Test
    fun oversizedFileIsKeptInsteadOfOverwritten() {
        val file = wordsFile()
        val oversized = ByteArray(64 * 1024 + 1) { 'a'.code.toByte() }
        file.writeBytes(oversized)
        val store = PersonalDictionaryStore(file)

        store.setEnabled(true)

        assertArrayEquals(oversized, preservedFiles().single().readBytes())
        assertTrue(store.load().enabled)
    }

    @Test
    fun editedDictionaryRoundTrips() {
        val store = PersonalDictionaryStore(wordsFile())

        store.upsert(PersonalWord(PersonalWordCategory.Name, "윤찬"))
        store.setEnabled(true)

        val loaded = PersonalDictionaryStore(wordsFile()).load()
        assertTrue(loaded.enabled)
        assertEquals(listOf("윤찬"), loaded.words.map { it.value })
    }
}
