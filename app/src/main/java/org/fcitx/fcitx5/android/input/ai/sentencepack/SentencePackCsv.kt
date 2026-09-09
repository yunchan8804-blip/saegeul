/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.sentencepack

internal object SentencePackCsv {
    fun parseSelected(csv: String): List<String> {
        val rows = parseRows(csv)
        if (rows.isEmpty() || rows.first().mapIndexed { index, value ->
                if (index == 0) value.removePrefix("\uFEFF") else value
            } != listOf("Q", "A", "label")
        ) {
            throw SentencePackFormatException("CSV 헤더가 올바르지 않습니다.")
        }
        return buildList {
            rows.drop(1).forEach { row ->
                if (row.size != 3) throw SentencePackFormatException("CSV 열 수가 올바르지 않습니다.")
                if (row[2].trim() == "0") {
                    SentencePackText.normalizeAccepted(row[0])?.let(::add)
                    SentencePackText.normalizeAccepted(row[1])?.let(::add)
                }
            }
        }.distinct()
    }

    private fun parseRows(source: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var atFieldStart = true
        var justClosedQuote = false
        var index = 0
        while (index < source.length) {
            when (val character = source[index]) {
                '"' -> when {
                    quoted && index + 1 < source.length && source[index + 1] == '"' -> {
                        field.append('"')
                        index++
                    }
                    quoted -> {
                        quoted = false
                        justClosedQuote = true
                    }
                    atFieldStart -> {
                        quoted = true
                        atFieldStart = false
                    }
                    else -> throw SentencePackFormatException("CSV 따옴표 형식이 올바르지 않습니다.")
                }
                ',' -> {
                    if (quoted) field.append(character) else {
                        row.add(field.toString())
                        field.clear()
                        atFieldStart = true
                        justClosedQuote = false
                    }
                }
                '\r', '\n' -> {
                    if (quoted) field.append(character) else {
                        if (character == '\r' && index + 1 < source.length && source[index + 1] == '\n') index++
                        row.add(field.toString())
                        rows.add(row)
                        row = mutableListOf()
                        field.clear()
                        atFieldStart = true
                        justClosedQuote = false
                    }
                }
                else -> {
                    if (justClosedQuote) throw SentencePackFormatException("CSV 따옴표 형식이 올바르지 않습니다.")
                    field.append(character)
                    atFieldStart = false
                }
            }
            index++
        }
        if (quoted) throw SentencePackFormatException("CSV 따옴표가 닫히지 않았습니다.")
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }
}

internal class SentencePackFormatException(message: String) : IllegalArgumentException(message)
