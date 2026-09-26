/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import kotlin.text.CharCategory.CONTROL
import kotlin.text.CharCategory.FORMAT

object OnDeviceSuggestionPolicy {

    enum class Mode {
        WORD,
        SENTENCE
    }

    class Input(
        val textBeforeCursor: String,
        val packageName: String,
        val inputType: Int,
        val imeAction: Int,
        val mode: Mode,
        /** [org.fcitx.fcitx5.android.input.ai.persona.PersonaRegistry] category id for [packageName]. */
        val appCategory: String = "general",
        /** The editor's hint text, already trimmed/length-capped/PII-checked by the caller. */
        val fieldHint: String? = null,
        /** Sentences this user recently sent in this same app, newest first. */
        val recentSentences: List<String> = emptyList(),
        /** Similar past sentences from this user's personal store, for tone/style only. */
        val styleExamples: List<String> = emptyList()
    ) {
        override fun toString(): String =
            "Input(textBeforeCursor=<redacted>, packageName=<redacted>, inputType=$inputType, " +
                "imeAction=$imeAction, mode=$mode, appCategory=$appCategory, fieldHint=<redacted>, " +
                "recentSentences=<redacted:${recentSentences.size}>, styleExamples=<redacted:${styleExamples.size}>)"
    }

    fun promptFor(input: Input): String {
        require(isValidInput(input)) { "INVALID_SUGGESTION_INPUT" }
        val lines = mutableListOf(
            "{\"app\":\"${escapeJson(input.packageName)}\",\"appType\":\"${appTypeLabel(input.appCategory)}\"," +
                "\"inputType\":${input.inputType},\"imeAction\":${input.imeAction}}"
        )
        input.fieldHint?.takeIf { it.isNotBlank() }?.let {
            lines += "[입력창 안내문] \"${escapeJson(it)}\""
        }
        clampSentences(input.recentSentences).takeIf { it.isNotEmpty() }?.let { recent ->
            lines += "[이 사용자가 이 앱에서 최근 보낸 문장]"
            recent.forEach { lines += "- \"${escapeJson(it)}\"" }
        }
        clampSentences(input.styleExamples).takeIf { it.isNotEmpty() }?.let { style ->
            lines += "[이 사용자가 평소 쓴 비슷한 문장 — 말투와 표현만 참고하고 그대로 베끼지 마세요]"
            style.forEach { lines += "- \"${escapeJson(it)}\"" }
        }
        lines += "현재 입력 다음에 자연스럽게 이어져 문장을 끝맺는 짧은 한국어 글을 예측하세요. 문장 하나만 완성하고 마침표나 물음표로 끝내세요."
        lines += "앞 문맥과 위 문장들의 흐름, 이 사용자의 말투를 따르세요. 이미 입력한 글은 되풀이하지 말고 새로 추가할 글자만 출력하세요. 설명이나 서식은 쓰지 마세요."
        lines += "원문이 공백으로 끝나면 추가 공백 없이, 그렇지 않고 새 어절을 시작하면 공백 하나로 시작하세요. 붙는 조사·어미는 공백 없이 쓰세요."
        lines += "현재 입력: \"${escapeJson(input.textBeforeCursor)}\""
        lines += "이어쓰기:"
        return lines.joinToString("\n")
    }

    /** Maps a [PersonaRegistry] category id to the Korean label shown to the model. Unknown ids fall back to 일반. */
    private fun appTypeLabel(appCategory: String): String = when (appCategory) {
        "messenger" -> "메신저"
        "work" -> "업무 메신저"
        "email" -> "메일"
        "social" -> "SNS"
        "notes" -> "메모"
        "browser" -> "검색·웹"
        "commerce" -> "쇼핑"
        else -> "일반"
    }

    /** Defensive clamp applied regardless of what the caller already trimmed: at most 3 sentences, 80 chars each. */
    private fun clampSentences(sentences: List<String>): List<String> =
        sentences.take(MAX_PROMPT_SENTENCES).map { it.take(MAX_PROMPT_SENTENCE_CHARS) }

    fun parseSuffix(input: Input, raw: String): String? = parse(input, raw).suffix

    /** Returns a short reason code when [raw] is rejected, or null when it yields a suffix. Never contains text. */
    fun rejectionReason(input: Input, raw: String): String? = parse(input, raw).reason

    private class Parsed(val suffix: String?, val reason: String?)

    private fun accept(suffix: String) = Parsed(suffix, null)
    private fun reject(reason: String) = Parsed(null, reason)

    private fun parse(input: Input, raw: String): Parsed {
        if (!isValidInput(input)) return reject("INVALID_INPUT")
        if (raw.length > MAX_RAW_RESPONSE_CHARS) return reject("RAW_TOO_LONG")
        if (raw.any(::isUnsafeOutputCharacter)) return reject("UNSAFE_CHAR")

        val normalized = stripReplyDecorations(raw)
        if (normalized.isEmpty()) return reject("EMPTY")
        if (unresolvedLabel.matches(normalized)) return reject("LABEL_ONLY")

        val withoutEcho = removeEchoedContext(input.textBeforeCursor, normalized) ?: return reject("ECHO_ONLY")
        val suffix = withoutEcho.trimEnd()
        if (suffix.isBlank()) return reject("BLANK")
        if (suffix.length > MAX_SUFFIX_CHARS) return reject("SUFFIX_TOO_LONG")
        if (suffix.any(disallowedReplyCharacters::contains)) return reject("DISALLOWED_CHAR")
        if (suffix.trimStart().startsWith(input.textBeforeCursor)) return reject("STARTS_WITH_CONTEXT")
        if (input.textBeforeCursor.trimEnd().endsWith(suffix.trim())) return reject("REPEATS_CONTEXT_TAIL")
        if (hasReplyMarker(suffix)) return reject("REPLY_MARKER")
        if (suffix.startsWith("  ") || (suffix.first().isWhitespace() && suffix.first() != ' ')) {
            return reject("LEADING_WHITESPACE")
        }
        if (input.textBeforeCursor.lastOrNull()?.isWhitespace() == true && suffix.first().isWhitespace()) {
            return reject("DOUBLE_SPACE")
        }

        return when (input.mode) {
            Mode.WORD -> firstEojeolProjection(suffix)?.let(::accept) ?: reject("WORD_EMPTY")
            Mode.SENTENCE -> if (isSingleShortSentence(suffix)) accept(suffix) else reject("NOT_SINGLE_SENTENCE")
        }
    }

    private fun isValidInput(input: Input): Boolean =
        input.textBeforeCursor.isNotBlank() &&
            input.textBeforeCursor.length <= MAX_CONTEXT_CHARS &&
            input.textBeforeCursor.none(::isUnsafeInputCharacter) &&
            input.packageName.length <= MAX_PACKAGE_CHARS &&
            input.packageName.all(::isPackageCharacter)

    /**
     * Small models often restate the whole input or its last eojeols before continuing. Only the
     * text after that restatement is a continuation, so the echoed part is removed instead of the
     * candidate being discarded. A reply that is nothing but an echo yields null.
     */
    private fun removeEchoedContext(context: String, reply: String): String? {
        val trimmedContext = context.trimEnd()
        val start = reply.trimStart()
        if (trimmedContext.isEmpty() || start.isEmpty()) return reply
        val echoed = when {
            start.startsWith(trimmedContext) -> trimmedContext
            else -> {
                val eojeols = trimmedContext.split(whitespaceRun).filter { it.isNotEmpty() }
                (minOf(eojeols.size, MAX_ECHO_EOJEOLS) downTo 1)
                    .asSequence()
                    .map { count -> eojeols.takeLast(count).joinToString(" ") }
                    .firstOrNull { tail -> tail.length >= MIN_ECHO_CHARS && start.startsWith(tail) }
            }
        } ?: return reply
        val remainder = start.substring(echoed.length)
        val continuation = if (context.last().isWhitespace()) remainder.trimStart() else remainder
        return continuation.takeIf { it.isNotBlank() }
    }

    private fun firstEojeolProjection(suffix: String): String? {
        val contentStart = if (suffix.startsWith(' ')) 1 else 0
        var endExclusive = contentStart
        while (endExclusive < suffix.length && !suffix[endExclusive].isWhitespace()) {
            endExclusive++
        }
        return suffix.substring(0, endExclusive).takeIf { endExclusive > contentStart }
    }

    private fun isSingleShortSentence(suffix: String): Boolean {
        if (suffix.trimEnd().lastOrNull() !in terminalPunctuation) return false
        // This rejects only an explicit terminal mark followed by whitespace and more text.
        // It is a format guard, not evidence that the candidate is semantically one sentence.
        return !(0 until suffix.lastIndex).any { index ->
            suffix[index] in terminalPunctuation &&
                suffix.drop(index + 1).let { rest ->
                    rest.firstOrNull()?.isWhitespace() == true && rest.any { !it.isWhitespace() }
                }
        }
    }

    private fun hasReplyMarker(suffix: String): Boolean {
        val start = suffix.trimStart()
        return replyMarkers.any { marker -> start.startsWith(marker) }
    }

    private fun stripReplyDecorations(raw: String): String {
        var text = raw
        while (true) {
            val stripped = stripOneReplyDecoration(text)
            if (stripped == text) return text
            text = stripped
        }
    }

    private fun stripOneReplyDecoration(text: String): String {
        stripPrefixMatch(text, englishLabelPrefix)?.let { return it }
        stripPrefixMatch(text, koreanLabelPrefix)?.let { return it }
        stripPrefixMatch(text, bracketMarkerPrefix)?.let { return it }
        stripPrefixMatch(text, bulletPrefix)?.let { return it }
        stripWrapPair(text)?.let { return it }
        return text
    }

    private fun stripPrefixMatch(text: String, prefix: Regex): String? {
        val match = prefix.find(text) ?: return null
        return text.substring(match.value.length)
    }

    private fun stripWrapPair(text: String): String? {
        for ((open, close) in wrapPairs) {
            if (text.length > open.length + close.length && text.startsWith(open) && text.endsWith(close)) {
                return text.substring(open.length, text.length - close.length)
            }
        }
        return null
    }

    private fun escapeJson(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }

    private fun isPackageCharacter(character: Char): Boolean =
        character.code <= 0x7F && (character.isLetterOrDigit() || character == '_' || character == '.')

    private fun isUnsafeInputCharacter(character: Char): Boolean =
        character == '\uFFFD' ||
            character.category == FORMAT ||
            (character.category == CONTROL && character !in allowedInputControls)

    private fun isUnsafeOutputCharacter(character: Char): Boolean =
        character == '\uFFFD' || character.category == CONTROL || character.category == FORMAT

    private val terminalPunctuation = setOf('.', '?', '!', '。', '！', '？')
    private val disallowedReplyCharacters = setOf('"', '\'', '“', '”', '‘', '’', '{', '}', '[', ']', '(', ')', '（', '）', '`', '*')
    private val replyMarkers = setOf("답변:", "설명:", "출력:", "json:", "markdown:")
    private val allowedInputControls = setOf('\n', '\r', '\t')

    private val englishLabelPrefix = Regex("^\\s*[A-Za-z_]{1,16}\\s*[:：]\\s*")
    private val koreanLabelPrefix =
        Regex("(?i)^\\s*(이어쓰기|다음|답변|답|설명|출력|문장|추천|결과|json|markdown)\\s*[:：]\\s*")
    private val bracketMarkerPrefix = Regex("^\\s*[\\[<(【]\\s*[A-Za-z가-힣_ ]{1,16}\\s*[\\]>)】]\\s*")
    private val bulletPrefix = Regex("^\\s*([-•*·]|\\d+[.)])\\s+")
    private val unresolvedLabel = Regex("^[A-Z_]{2,16}$")
    private val whitespaceRun = Regex("\\s+")
    private val wrapPairs = listOf(
        "**" to "**",
        "__" to "__",
        "`" to "`",
        "\"" to "\"",
        "'" to "'",
        "“" to "”",
        "‘" to "’"
    )

    private const val MAX_CONTEXT_CHARS = 2048
    private const val MAX_PACKAGE_CHARS = 255
    private const val MAX_SUFFIX_CHARS = 120
    private const val MAX_RAW_RESPONSE_CHARS = 4096
    private const val MAX_ECHO_EOJEOLS = 4
    private const val MIN_ECHO_CHARS = 2
    private const val MAX_PROMPT_SENTENCES = 3
    private const val MAX_PROMPT_SENTENCE_CHARS = 80
}
