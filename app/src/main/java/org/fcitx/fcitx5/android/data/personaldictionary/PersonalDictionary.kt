/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.personaldictionary

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

enum class PersonalWordCategory(val serializedName: String) {
    Name("name"),
    Company("company"),
    TechnicalTerm("term");

    companion object {
        fun fromSerializedName(value: String): PersonalWordCategory? =
            entries.firstOrNull { it.serializedName == value }
    }
}

data class PersonalWord(
    val category: PersonalWordCategory,
    val value: String
) {
    fun normalized(): PersonalWord = copy(value = normalizePersonalWord(value))

    fun validate(): PersonalWord = normalized().also {
        require(isValidPersonalWord(it.value)) { "Personal words must contain 2-32 modern Hangul syllables" }
    }
}

data class PersonalDictionary(
    val enabled: Boolean = false,
    val words: List<PersonalWord> = emptyList()
) {
    fun normalized(): PersonalDictionary {
        val unique = LinkedHashMap<String, PersonalWord>()
        words.forEach { word ->
            val valid = word.validate()
            unique.putIfAbsent(valid.value, valid)
        }
        require(unique.size <= MAX_PERSONAL_WORDS) { "Too many personal words" }
        return copy(words = unique.values.toList())
    }
}

internal fun encodePersonalDictionary(dictionary: PersonalDictionary): ByteArray {
    val normalized = dictionary.normalized()
    return buildString {
        append(PERSONAL_DICTIONARY_HEADER).append('\n')
        append("enabled\t").append(if (normalized.enabled) '1' else '0').append('\n')
        normalized.words.forEach { word ->
            append("word\t")
                .append(word.category.serializedName)
                .append('\t')
                .append(word.value)
                .append('\n')
        }
    }.toByteArray(Charsets.UTF_8)
}

internal fun decodePersonalDictionary(bytes: ByteArray): PersonalDictionary? = runCatching {
    require(bytes.size in 1..MAX_PERSONAL_DICTIONARY_BYTES)
    val text = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
        .replace("\r\n", "\n")
    val lines = text.split('\n').let { if (it.lastOrNull().isNullOrEmpty()) it.dropLast(1) else it }
    require(lines.size in 2..(MAX_PERSONAL_WORDS + 2))
    require(lines[0] == PERSONAL_DICTIONARY_HEADER)
    val enabled = when (lines[1]) {
        "enabled\t0" -> false
        "enabled\t1" -> true
        else -> error("Invalid personal dictionary enabled flag")
    }
    val words = lines.drop(2).map { line ->
        val fields = line.split('\t')
        require(fields.size == 3 && fields[0] == "word")
        PersonalWord(
            category = PersonalWordCategory.fromSerializedName(fields[1])
                ?: error("Invalid personal word category"),
            value = fields[2]
        ).validate()
    }
    PersonalDictionary(enabled, words).normalized()
}.getOrNull()

internal fun normalizePersonalWord(value: String): String =
    Normalizer.normalize(value.trim(), Normalizer.Form.NFC)

internal fun isValidPersonalWord(value: String): Boolean =
    value.length in MIN_PERSONAL_WORD_LENGTH..MAX_PERSONAL_WORD_LENGTH &&
        value.all { it in '\uAC00'..'\uD7A3' }

internal const val PERSONAL_DICTIONARY_HEADER = "# fcitx5-android-personal-dictionary-v1"
internal const val MAX_PERSONAL_WORDS = 500
private const val MIN_PERSONAL_WORD_LENGTH = 2
private const val MAX_PERSONAL_WORD_LENGTH = 32
private const val MAX_PERSONAL_DICTIONARY_BYTES = 64 * 1024
