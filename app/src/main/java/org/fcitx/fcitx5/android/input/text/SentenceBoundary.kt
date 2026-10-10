/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.text

/**
 * 문장 끝 판정의 단일 정책. 끝 부호는 바로 다음 글자가 없거나 ASCII 영문자·숫자가 아닐 때 경계다.
 * 그래서 `naver.com`, `3.5km`, `v1.2`, `1~2`는 경계가 아니고 `좋아요.`, `합니다.밥`, `정말?!`, `그래…`는 경계다.
 */
object SentenceBoundary {

    const val TERMINALS = ".?!…~。！？"
    const val LINE_BREAKS = "\n\r"
    const val CLOSERS = "\"')]」』”’》〉"

    fun isTerminal(c: Char): Boolean = c in TERMINALS

    private fun isLineBreak(c: Char): Boolean = c in LINE_BREAKS

    // 줄바꿈은 문장 경계라 걷어내지 않는다.
    private fun isInlineWhitespace(c: Char): Boolean = c.isWhitespace() && !isLineBreak(c)

    fun isBoundaryAt(text: CharSequence, i: Int): Boolean {
        val c = text[i]
        if (isLineBreak(c)) return true
        if (c !in TERMINALS) return false
        val next = text.getOrNull(i + 1)
        return next == null || !isAsciiLetterOrDigit(next)
    }

    private fun isAsciiLetterOrDigit(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    fun endsSentence(text: CharSequence): Boolean {
        var end = text.length
        while (end > 0 && isInlineWhitespace(text[end - 1])) end--
        if (end == 0) return false
        if (isLineBreak(text[end - 1])) return true
        while (end > 0 && text[end - 1] in CLOSERS) end--
        return end > 0 && text[end - 1] in TERMINALS
    }

    /** 마지막 경계 바로 뒤의 인덱스. 경계가 없으면 0. 경계에 이어지는 끝 부호·닫는 기호·공백은 건너뛴다. */
    fun lastSentenceStart(text: CharSequence): Int {
        var boundary = text.length - 1
        while (boundary >= 0 && !isBoundaryAt(text, boundary)) boundary--
        if (boundary < 0) return 0
        var start = boundary + 1
        while (start < text.length) {
            val c = text[start]
            if (!isTerminal(c) && c !in CLOSERS && !c.isWhitespace()) break
            start++
        }
        return start
    }

    fun currentSentence(text: String): String = text.substring(lastSentenceStart(text))

    /**
     * 각 경계가 끝나는 오프셋(배타). 경계 뒤에 붙은 끝 부호·닫는 기호와 그 뒤 줄바꿈까지 앞 문장에 넣는다.
     * 줄바꿈 경계는 뒤따르는 줄바꿈만 붙이고, 다음 줄 첫머리의 따옴표 같은 글자는 붙이지 않는다.
     */
    fun boundaryEnds(text: CharSequence): List<Int> {
        val ends = mutableListOf<Int>()
        var i = 0
        while (i < text.length) {
            if (!isBoundaryAt(text, i)) {
                i++
                continue
            }
            var end = i + 1
            if (!isLineBreak(text[i])) {
                while (end < text.length && (isTerminal(text[end]) || text[end] in CLOSERS)) end++
            }
            while (end < text.length && isLineBreak(text[end])) end++
            ends.add(end)
            i = end
        }
        return ends
    }

    /** 경계에서 나눈다. 끝 부호와 닫는 기호는 앞 문장에 붙이고, 각 문장은 trim하며 빈 것은 버린다. */
    fun splitSentences(text: String): List<String> {
        val sentences = mutableListOf<String>()
        var start = 0
        for (end in boundaryEnds(text)) {
            text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(sentences::add)
            start = end
        }
        text.substring(start).trim().takeIf { it.isNotEmpty() }?.let(sentences::add)
        return sentences
    }
}
