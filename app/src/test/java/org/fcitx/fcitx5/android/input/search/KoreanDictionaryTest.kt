/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.system.measureTimeMillis

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
    fun binaryIndexRejectsInvalidHeaderAndNonHangulQuery() {
        assertThrows(IllegalArgumentException::class.java) {
            KoreanDictionaryIndex.read(ByteArrayInputStream("not-a-dictionary".toByteArray()))
        }
        val index = indexOf("나무" to listOf("명사" to listOf("여러 해 동안 자라는 식물.")))
        assertTrue(index.lookup("漢字").isEmpty())
    }

    @Test
    fun exactMatchWinsAndPrefixFallbackIsBounded() {
        val index = indexOf(
            "나" to listOf("대명사" to listOf("말하는 이를 가리키는 말.")),
            "나무" to listOf("명사" to listOf("여러 해 동안 자라는 식물.", "재목.")),
            "나뭇가지" to listOf("명사" to listOf("나무에서 뻗어 나온 가지.")),
            "나비" to listOf("명사" to listOf("날개가 있는 곤충."))
        )

        assertEquals(listOf("나"), index.lookup("나").map(KoreanDictionaryEntry::word))
        assertEquals(
            listOf("나뭇가지"),
            index.lookup("나뭇").map(KoreanDictionaryEntry::word)
        )
        assertEquals(listOf("여러 해 동안 자라는 식물.", "재목."), index.lookup("나무").single().definitions)
        assertEquals(1, index.lookup("나", limit = 1).size)
        assertTrue(index.lookup("나", limit = 0).isEmpty())
    }

    @Test
    fun binaryIndexRejectsUnsortedRecords() {
        val bytes = binaryOf(
            "나비" to listOf("명사" to listOf("곤충.")),
            "나무" to listOf("명사" to listOf("식물."))
        )
        assertThrows(IllegalArgumentException::class.java) {
            KoreanDictionaryIndex.read(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun bundledAssetHasExpectedEntriesAndMeetsColdLoadBudget() {
        val workingDirectory = File(System.getProperty("user.dir") ?: ".")
        val asset = sequenceOf(
            workingDirectory.resolve("src/main/assets/korean/dictionary.bin"),
            workingDirectory.resolve("app/src/main/assets/korean/dictionary.bin")
        ).firstOrNull(File::isFile) ?: error("Bundled Korean dictionary asset was not found")

        val coldLoads = List(20) {
            measureTimeMillis {
                val index = asset.inputStream().use(KoreanDictionaryIndex::read)
                assertEquals("나무", index.lookup("나무").first().word)
                assertEquals("되다", index.lookup("되다").first().word)
                assertEquals("안녕", index.lookup("안녕").first().word)
            }
        }.sorted()
        val coldP95 = coldLoads[18]
        println("KoreanDictionary cold-load p95=${coldP95}ms samples=$coldLoads")
        assertTrue("Dictionary cold-load p95 was ${coldP95}ms", coldP95 <= 2_000)

        val index = asset.inputStream().use(KoreanDictionaryIndex::read)
        val warmLookups = List(20) {
            measureTimeMillis { assertTrue(index.lookup("나무").isNotEmpty()) }
        }.sorted()
        val warmP95 = warmLookups[18]
        println("KoreanDictionary warm-lookup p95=${warmP95}ms samples=$warmLookups")
        assertTrue("Dictionary warm lookup p95 was ${warmP95}ms", warmP95 <= 200)
    }

    private fun indexOf(vararg records: Pair<String, List<Pair<String, List<String>>>>) =
        KoreanDictionaryIndex.read(ByteArrayInputStream(binaryOf(*records)))

    private fun binaryOf(
        vararg records: Pair<String, List<Pair<String, List<String>>>>
    ): ByteArray {
        val encoded = records.map { (word, entries) ->
            ByteArrayOutputStream().apply {
                writeString(word)
                writeShort(entries.size)
                entries.forEach { (partOfSpeech, definitions) ->
                    writeString(partOfSpeech)
                    write(definitions.size)
                    definitions.forEach { writeString(it) }
                }
            }.toByteArray()
        }
        return ByteArrayOutputStream().apply {
            write(byteArrayOf('K'.code.toByte(), 'O'.code.toByte(), 'D'.code.toByte(), 'I'.code.toByte(), 'C'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte(), 0))
            writeInt(records.size)
            var offset = 12 + records.size * 4
            encoded.forEach {
                writeInt(offset)
                offset += it.size
            }
            encoded.forEach { write(it) }
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeShort(bytes.size)
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }
}
