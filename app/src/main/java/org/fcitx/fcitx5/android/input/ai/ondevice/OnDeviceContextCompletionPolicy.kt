/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import kotlin.text.CharCategory.CONTROL
import kotlin.text.CharCategory.FORMAT

object OnDeviceContextCompletionPolicy {

    private val terminalPunctuation = setOf('.', '?', '!', '。', '！', '？')
    private val disallowedReplyMarkers = setOf('"', '\'', '“', '”', '‘', '’', '{', '}', '[', ']')

    fun isIncompleteContext(context: String): Boolean {
        if (context.isBlank() || context.first().isWhitespace()) return false
        if (context.length > MAX_CONTEXT_CHARS || context.any(::isUnsafeCharacter)) return false
        return context.trimEnd().lastOrNull() !in terminalPunctuation
    }

    fun promptFor(context: String): String {
        require(isIncompleteContext(context)) { "INVALID_CONTEXT" }
        return """
            다음 한국어 원문을 문자 하나도 바꾸거나 반복하지 말고, 원문 바로 뒤에 이어서 자연스러운 완성 문장 하나를 완성하세요.
            응답은 원문 전체와 추가한 접미부만 포함해야 합니다. 설명, 제목, 인용부호, JSON, Markdown을 출력하지 마세요.
            원문:
            $context
        """.trimIndent()
    }

    fun parseCompletion(context: String, raw: String): String? {
        if (!isIncompleteContext(context)) return null
        if (raw.length > MAX_RAW_RESPONSE_CHARS) return null
        val full = raw.trim()
        if (full.any(::isUnsafeCharacter)) return null
        if (!full.startsWith(context)) return null
        val suffix = full.removePrefix(context)
        if (!isValidSuffix(context, suffix)) return null
        return suffix
    }

    private fun isValidSuffix(context: String, suffix: String): Boolean {
        if (suffix.isBlank() || suffix.length > MAX_SUFFIX_CHARS) {
            return false
        }
        if (suffix.any(::isUnsafeCharacter)) return false
        if (suffix.any(disallowedReplyMarkers::contains)) return false
        if (suffix.trimStart().startsWith(context)) return false
        return suffix.trimEnd().lastOrNull() in terminalPunctuation
    }

    private fun isUnsafeCharacter(character: Char): Boolean =
        character.category == CONTROL || character.category == FORMAT

    private const val MAX_CONTEXT_CHARS = 2048
    private const val MAX_SUFFIX_CHARS = 120
    private const val MAX_RAW_RESPONSE_CHARS = 4096
}
