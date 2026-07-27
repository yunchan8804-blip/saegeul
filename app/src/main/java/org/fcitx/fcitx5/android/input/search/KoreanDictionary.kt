/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.search

import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class KoreanDictionaryEntry(
    val word: String,
    val partOfSpeech: String,
    val definitions: List<String>
) {
    val sourceUrl: String
        get() = "https://ko.wiktionary.org/wiki/" + URLEncoder.encode(word, "UTF-8")
            .replace("+", "%20")
}

/** Pure extraction boundary for an explicitly opened, local-only dictionary lookup. */
object KoreanDictionaryQuery {
    const val MAX_LENGTH = 40
    private val headword = Regex("^[가-힣]+(?:[- ][가-힣]+)*$")
    private val cursorWord = Regex("[가-힣]+(?:-[가-힣]+)*$")
    private const val TRAILING_PUNCTUATION = ".,!?…:;'\"()[]{}<>"

    fun normalize(value: String?): String? {
        val normalized = value.orEmpty().trim().replace(Regex("\\s+"), " ")
        return normalized.takeIf {
            it.length in 1..MAX_LENGTH && headword.matches(it)
        }
    }

    fun extract(selectedText: String?, beforeCursor: String?): String? =
        normalize(selectedText) ?: cursorWord.find(
            beforeCursor.orEmpty().trimEnd().trimEnd { it in TRAILING_PUNCTUATION }
        )
            ?.value
            ?.let(::normalize)
}

/**
 * Immutable binary index. Initial loading retains only the asset bytes and an IntArray of record
 * offsets. Headwords are compared in place and definitions are decoded only for matching records.
 */
class KoreanDictionaryIndex private constructor(
    private val data: ByteArray,
    private val offsets: IntArray
) {
    fun lookup(query: String?, limit: Int = DEFAULT_LIMIT): List<KoreanDictionaryEntry> {
        val normalized = KoreanDictionaryQuery.normalize(query) ?: return emptyList()
        if (limit <= 0) return emptyList()
        val queryBytes = normalized.toByteArray(StandardCharsets.UTF_8)
        val start = lowerBound(queryBytes)
        if (start >= offsets.size || !wordStartsWith(start, queryBytes)) return emptyList()

        val results = ArrayList<KoreanDictionaryEntry>(limit)
        var index = start
        while (index < offsets.size && wordStartsWith(index, queryBytes)) {
            for (entry in decodeRecord(index)) {
                results += entry
                if (results.size == limit) return results
            }
            // An exact headword always wins over longer prefix matches.
            if (wordLength(index) == queryBytes.size) return results
            index += 1
        }
        return results
    }

    private fun lowerBound(query: ByteArray): Int {
        var low = 0
        var high = offsets.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (compareWord(middle, query) < 0) low = middle + 1 else high = middle
        }
        return low
    }

    private fun compareWord(index: Int, query: ByteArray): Int {
        val offset = offsets[index]
        val length = readUnsignedShort(data, offset)
        val start = offset + 2
        val shared = minOf(length, query.size)
        for (position in 0 until shared) {
            val left = data[start + position].toInt() and 0xff
            val right = query[position].toInt() and 0xff
            if (left != right) return left - right
        }
        return length - query.size
    }

    private fun compareWords(leftIndex: Int, rightIndex: Int): Int {
        val leftOffset = offsets[leftIndex]
        val rightOffset = offsets[rightIndex]
        val leftLength = readUnsignedShort(data, leftOffset)
        val rightLength = readUnsignedShort(data, rightOffset)
        val shared = minOf(leftLength, rightLength)
        for (position in 0 until shared) {
            val difference =
                (data[leftOffset + 2 + position].toInt() and 0xff) -
                    (data[rightOffset + 2 + position].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return leftLength - rightLength
    }

    private fun wordStartsWith(index: Int, prefix: ByteArray): Boolean {
        val offset = offsets[index]
        val length = readUnsignedShort(data, offset)
        if (length < prefix.size) return false
        val start = offset + 2
        for (position in prefix.indices) {
            if (data[start + position] != prefix[position]) return false
        }
        return true
    }

    private fun wordLength(index: Int): Int = readUnsignedShort(data, offsets[index])

    private fun decodeRecord(index: Int): List<KoreanDictionaryEntry> {
        val end = if (index + 1 < offsets.size) offsets[index + 1] else data.size
        val reader = RecordReader(data, offsets[index], end)
        val word = reader.readString()
        val entryCount = reader.readUnsignedShort()
        require(entryCount in 1..MAX_ENTRIES_PER_WORD) { "Invalid dictionary entry count" }
        return List(entryCount) {
            val partOfSpeech = reader.readString()
            val definitionCount = reader.readUnsignedByte()
            require(definitionCount in 1..MAX_DEFINITIONS) { "Invalid definition count" }
            KoreanDictionaryEntry(
                word = word,
                partOfSpeech = partOfSpeech,
                definitions = List(definitionCount) { reader.readString() }
            )
        }.also {
            require(reader.isAtEnd()) { "Dictionary record has trailing data" }
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 24
        private const val MAX_ASSET_BYTES = 32 * 1024 * 1024
        private const val MAX_WORDS = 100_000
        private const val MAX_ENTRIES_PER_WORD = 64
        private const val MAX_DEFINITIONS = 4
        private const val HEADER_SIZE = 12
        private val MAGIC = byteArrayOf(
            'K'.code.toByte(), 'O'.code.toByte(), 'D'.code.toByte(), 'I'.code.toByte(),
            'C'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte(), 0
        )

        fun read(source: InputStream): KoreanDictionaryIndex {
            val data = source.readBytes()
            require(data.size in HEADER_SIZE..MAX_ASSET_BYTES) { "Invalid dictionary size" }
            require(data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                "Invalid dictionary magic"
            }
            val wordCount = readInt(data, MAGIC.size)
            require(wordCount in 1..MAX_WORDS) { "Invalid dictionary word count" }
            val tableEnd = HEADER_SIZE + wordCount * 4
            require(tableEnd in HEADER_SIZE..data.size) { "Invalid dictionary offset table" }

            val offsets = IntArray(wordCount)
            var previous = tableEnd - 1
            for (index in offsets.indices) {
                val offset = readInt(data, HEADER_SIZE + index * 4)
                require(offset in tableEnd until data.size && offset > previous) {
                    "Invalid dictionary record offset"
                }
                offsets[index] = offset
                previous = offset
            }
            val result = KoreanDictionaryIndex(data, offsets)
            result.validateRecords()
            return result
        }

        private fun readInt(data: ByteArray, offset: Int): Int {
            require(offset >= 0 && offset + 4 <= data.size) { "Truncated dictionary integer" }
            return (data[offset].toInt() and 0xff) or
                ((data[offset + 1].toInt() and 0xff) shl 8) or
                ((data[offset + 2].toInt() and 0xff) shl 16) or
                ((data[offset + 3].toInt() and 0xff) shl 24)
        }

        private fun readUnsignedShort(data: ByteArray, offset: Int): Int {
            require(offset >= 0 && offset + 2 <= data.size) { "Truncated dictionary short" }
            return (data[offset].toInt() and 0xff) or
                ((data[offset + 1].toInt() and 0xff) shl 8)
        }
    }

    private fun validateRecords() {
        for (index in offsets.indices) {
            val end = if (index + 1 < offsets.size) offsets[index + 1] else data.size
            val reader = RecordReader(data, offsets[index], end)
            require(wordLength(index) > 0) { "Empty dictionary headword" }
            if (index > 0) {
                require(compareWords(index - 1, index) < 0) { "Dictionary words are not sorted" }
            }
            reader.skipBytes()
            val entryCount = reader.readUnsignedShort()
            require(entryCount in 1..MAX_ENTRIES_PER_WORD) { "Invalid dictionary entry count" }
            repeat(entryCount) {
                reader.skipBytes()
                val definitionCount = reader.readUnsignedByte()
                require(definitionCount in 1..MAX_DEFINITIONS) { "Invalid definition count" }
                repeat(definitionCount) { reader.skipBytes() }
            }
            require(reader.isAtEnd()) { "Dictionary record length mismatch" }
        }
    }

    private class RecordReader(
        private val data: ByteArray,
        private var position: Int,
        private val end: Int
    ) {
        fun readUnsignedByte(): Int {
            require(position < end) { "Truncated dictionary byte" }
            return data[position++].toInt() and 0xff
        }

        fun readUnsignedShort(): Int {
            require(position + 2 <= end) { "Truncated dictionary short" }
            return (data[position++].toInt() and 0xff) or
                ((data[position++].toInt() and 0xff) shl 8)
        }

        fun readBytes(): ByteArray {
            val length = readUnsignedShort()
            require(position + length <= end) { "Truncated dictionary field" }
            return data.copyOfRange(position, position + length).also { position += length }
        }

        fun skipBytes() {
            val length = readUnsignedShort()
            require(position + length <= end) { "Truncated dictionary field" }
            position += length
        }

        fun readString(): String = String(readBytes(), StandardCharsets.UTF_8)

        fun isAtEnd(): Boolean = position == end
    }
}
