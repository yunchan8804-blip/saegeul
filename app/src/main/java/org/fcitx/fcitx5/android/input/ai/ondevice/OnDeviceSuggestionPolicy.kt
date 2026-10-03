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
        /**
         * Sentences this user recently sent in this same app, newest first. Used only to detect the tone;
         * their text is never put in the prompt, because the small model copies it verbatim and leaks
         * sentences from other conversations into the suggestion.
         */
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
        val split = splitSentences(input.textBeforeCursor)
        val shownCurrent = oneLine(split.current)
        val instruction = if (shownCurrent.isEmpty()) NEXT_SENTENCE_INSTRUCTION else COMPLETE_SENTENCE_INSTRUCTION
        val currentLine = shownCurrent.takeIf { it.isNotEmpty() }?.let { "$CURRENT_LINE_PREFIX$it" }
        var remaining = PROMPT_CHAR_BUDGET - listOfNotNull(instruction, currentLine).joinToString("\n").length

        fun reserve(line: String?): String? {
            if (line == null || line.length + 1 > remaining) return null
            remaining -= line.length + 1
            return line
        }

        val toneLine = reserve(detectTone(split, input)?.let { "$TONE_LINE_PREFIX${it.label}" })
        val appLine = reserve(
            appTypeLabel(input.appCategory).takeIf { it != GENERAL_APP_LABEL }?.let { "$APP_LINE_PREFIX$it" }
        )
        val prevLine = reserve(prevTailLine(oneLine(split.prev), remaining))
        val hintLine = reserve(
            input.fieldHint?.let(::oneLine)?.take(MAX_HINT_CHARS)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { "$HINT_LINE_PREFIX$it" }
        )
        val typedLetters = input.textBeforeCursor.filter(Char::isLetterOrDigit)
        val styleLine = reserve(
            // A sentence finished moments ago is already in the vault and comes back as the most
            // similar one; it only repeats the typed text, so examples already inside it are skipped.
            input.styleExamples.map(::oneLine).firstOrNull { example ->
                example.isNotEmpty() && !typedLetters.contains(example.filter(Char::isLetterOrDigit))
            }
                ?.take(MAX_STYLE_EXAMPLE_CHARS)?.trim()?.let { "$STYLE_LINE_PREFIX$it" }
        )

        return listOfNotNull(appLine, toneLine, hintLine, styleLine, prevLine, instruction, currentLine)
            .joinToString("\n")
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
        else -> GENERAL_APP_LABEL
    }

    /** Collapses newlines and control characters to one space so user text can never add or break a prompt line. */
    private fun oneLine(value: String): String = value.replace(lineBreakOrControlRun, " ").trim()

    private fun prevTailLine(prev: String, remaining: Int): String? {
        val available = minOf(MAX_PREV_TAIL_CHARS, remaining - 1 - PREV_LINE_PREFIX.length)
        if (prev.isEmpty() || available <= 0) return null
        if (prev.length <= available) return "$PREV_LINE_PREFIX$prev"
        val tailMax = available - ELLIPSIS.length
        if (tailMax <= 0) return null
        val tailStart = (prev.length - tailMax until prev.length).firstOrNull { index ->
            index > 0 && prev[index - 1].isWhitespace() && !prev[index].isWhitespace()
        } ?: return null
        val tail = prev.substring(tailStart)
        if (tail.length < MIN_CUT_PREV_TAIL_CHARS) return null
        return "$PREV_LINE_PREFIX$ELLIPSIS$tail"
    }

    private class SentenceSplit(
        /** Completed sentences before the sentence being typed, as typed. */
        val completed: String,
        /** [completed] plus any leading part of a long current sentence that was moved out of [current]. */
        val prev: String,
        /** The part of the sentence being typed that is shown to the model; trailing whitespace is kept. */
        val current: String
    )

    /** Shared by [promptFor] and [parse] so the model is asked about, and the reply is judged against, the same split. */
    private fun splitSentences(text: String): SentenceSplit {
        var completedEnd = 0
        var currentStart = 0
        for (index in text.indices) {
            val character = text[index]
            if (character == '\n' || character == '\r') {
                completedEnd = index
                currentStart = index + 1
            } else if (character in terminalPunctuation && index + 1 < text.length && text[index + 1].isWhitespace()) {
                completedEnd = index + 1
                currentStart = index + 1
            }
        }
        val completed = text.substring(0, completedEnd).trim()
        val current = text.substring(currentStart).trimStart()
        if (current.length <= MAX_CURRENT_CHARS) return SentenceSplit(completed, completed, current)
        val cut = (current.length - MAX_CURRENT_CHARS until current.length).firstOrNull { index ->
            index > 0 && current[index - 1].isWhitespace() && !current[index].isWhitespace()
        } ?: return SentenceSplit(completed, completed, current)
        val head = current.substring(0, cut).trimEnd()
        val prev = if (completed.isEmpty()) head else "$completed $head"
        return SentenceSplit(completed, prev, current.substring(cut))
    }

    private enum class Tone(val label: String) {
        POLITE("존댓말"),
        CASUAL("반말")
    }

    private fun detectTone(split: SentenceSplit, input: Input): Tone? {
        var polite = 0
        var casual = 0
        val sentences = split.completed.split(sentenceBreak).filter { it.isNotBlank() } +
            input.recentSentences.map(::oneLine)
        for (sentence in sentences) {
            when (endingTone(sentence)) {
                Tone.POLITE -> polite++
                Tone.CASUAL -> casual++
                null -> Unit
            }
        }
        if (politeMarkers.containsMatchIn("${split.prev} ${split.current}")) polite++
        return when {
            polite > casual -> Tone.POLITE
            casual > polite -> Tone.CASUAL
            input.appCategory == "work" || input.appCategory == "email" -> Tone.POLITE
            else -> null
        }
    }

    private fun endingTone(sentence: String): Tone? {
        val lastEojeol = sentence.trimEnd(::isTrailingMark).takeLastWhile { !it.isWhitespace() }
        return when {
            politeEndings.any(lastEojeol::endsWith) -> Tone.POLITE
            casualEndings.any(lastEojeol::endsWith) -> Tone.CASUAL
            else -> null
        }
    }

    private fun isTrailingMark(character: Char): Boolean =
        character.isWhitespace() || character in trailingMarks || character.category in punctuationCategories

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

        val normalized = stripEmphasisMarks(stripReplyDecorations(raw))
        if (normalized.isEmpty()) return reject("EMPTY")
        if (unresolvedLabel.matches(normalized)) return reject("LABEL_ONLY")

        val split = splitSentences(input.textBeforeCursor)
        val withoutEcho = removeEchoedContext(input.textBeforeCursor, split.current, normalized)
            ?: return reject("ECHO_ONLY")
        val unwrapped = stripWrapPairs(withoutEcho)
        val suffix = when (input.mode) {
            Mode.WORD -> unwrapped
            Mode.SENTENCE -> truncateAfterFirstSentence(unwrapped)
        }.trimEnd()
        if (suffix.isBlank()) return reject("BLANK")
        if (suffix.length > MAX_SUFFIX_CHARS) return reject("SUFFIX_TOO_LONG")
        if (suffix.any(disallowedReplyCharacters::contains)) return reject("DISALLOWED_CHAR")
        if (hasForeignScript(suffix, input.textBeforeCursor)) return reject("FOREIGN_SCRIPT")
        if (suffix.trimStart().startsWith(input.textBeforeCursor)) return reject("STARTS_WITH_CONTEXT")
        if (input.textBeforeCursor.trimEnd().endsWith(suffix.trim())) return reject("REPEATS_CONTEXT_TAIL")
        if (repeatsPrevious(suffix, split.prev)) return reject("REPEATS_PREV")
        if (hasReplyMarker(suffix)) return reject("REPLY_MARKER")
        if (suffix.startsWith("  ") || (suffix.first().isWhitespace() && suffix.first() != ' ')) {
            return reject("LEADING_WHITESPACE")
        }
        if (input.textBeforeCursor.lastOrNull()?.isWhitespace() == true && suffix.first().isWhitespace()) {
            return reject("DOUBLE_SPACE")
        }

        return when (input.mode) {
            Mode.WORD -> firstEojeolProjection(suffix)?.let(::accept) ?: reject("WORD_EMPTY")
            Mode.SENTENCE -> if (endsSentence(suffix)) accept(suffix) else reject("NOT_SINGLE_SENTENCE")
        }
    }

    /**
     * A letter from a writing system that is neither Hangul nor present in the typed text means the small
     * model drifted into another language. Digits and punctuation (COMMON, INHERITED) are never judged.
     */
    private fun hasForeignScript(suffix: String, context: String): Boolean {
        // Emoji and other pictographs the user did not type show up as garbage inside words (e.g. "발🚚 중").
        if (hasOtherSymbol(suffix) && !hasOtherSymbol(context)) return true
        val contextScripts = lettersScripts(context)
        return lettersScripts(suffix).any { it != Character.UnicodeScript.HANGUL && it !in contextScripts }
    }

    private fun hasOtherSymbol(text: String): Boolean =
        text.codePoints().anyMatch { Character.getType(it) == Character.OTHER_SYMBOL.toInt() }

    private fun lettersScripts(text: String): Set<Character.UnicodeScript> {
        val scripts = HashSet<Character.UnicodeScript>()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            // Combining marks count too: a stray vowel sign (e.g. Bengali U+09C7) is not a letter.
            if (Character.isLetter(codePoint) || Character.getType(codePoint) in combiningMarkTypes) {
                val script = Character.UnicodeScript.of(codePoint)
                if (script != Character.UnicodeScript.COMMON && script != Character.UnicodeScript.INHERITED) {
                    scripts.add(script)
                }
            }
            index += Character.charCount(codePoint)
        }
        return scripts
    }

    private fun isValidInput(input: Input): Boolean =
        input.textBeforeCursor.isNotBlank() &&
            input.textBeforeCursor.length <= MAX_CONTEXT_CHARS &&
            input.textBeforeCursor.none(::isUnsafeInputCharacter) &&
            input.packageName.length <= MAX_PACKAGE_CHARS &&
            input.packageName.all(::isPackageCharacter)

    /**
     * The model is asked to write the whole sentence being typed, so it restates that sentence first.
     * Only the text after the restatement is a continuation, so the echoed part is removed instead of
     * the candidate being discarded. A restated [shownCurrent] is removed first; otherwise the whole
     * context or its last eojeols are tried. A reply that is nothing but an echo yields null, and a
     * reply with no echo is returned unchanged.
     */
    private fun removeEchoedContext(context: String, shownCurrent: String, reply: String): String? {
        val start = reply.trimStart()
        if (start.isEmpty()) return reply
        val current = oneLine(shownCurrent)
        val echoed = (if (current.isNotEmpty() && start.startsWith(current)) current else null)
            ?: echoedContextTail(context.trimEnd(), start)
            ?: return reply
        val remainder = start.substring(echoed.length)
        if (remainder.isBlank()) return null
        return when {
            context.last().isWhitespace() -> remainder.trimStart()
            remainder.first().isWhitespace() -> " " + remainder.trimStart()
            else -> remainder
        }
    }

    private fun echoedContextTail(trimmedContext: String, start: String): String? {
        if (start.startsWith(trimmedContext)) return trimmedContext
        val eojeols = trimmedContext.split(whitespaceRun).filter { it.isNotEmpty() }
        return (minOf(eojeols.size, MAX_ECHO_EOJEOLS) downTo 1)
            .asSequence()
            .map { count -> eojeols.takeLast(count).joinToString(" ") }
            .firstOrNull { tail -> tail.length >= MIN_ECHO_CHARS && start.startsWith(tail) }
    }

    private fun firstEojeolProjection(suffix: String): String? {
        val contentStart = if (suffix.startsWith(' ')) 1 else 0
        var endExclusive = contentStart
        while (endExclusive < suffix.length && !suffix[endExclusive].isWhitespace()) {
            endExclusive++
        }
        return suffix.substring(0, endExclusive).takeIf { endExclusive > contentStart }
    }

    /** Keeps only the first sentence when a terminal mark is followed by whitespace and more text. */
    private fun truncateAfterFirstSentence(suffix: String): String {
        for (index in 0 until suffix.lastIndex) {
            if (suffix[index] in terminalPunctuation && suffix[index + 1].isWhitespace() &&
                (index + 1..suffix.lastIndex).any { !suffix[it].isWhitespace() }
            ) {
                return suffix.substring(0, index + 1)
            }
        }
        return suffix
    }

    /** A terminal mark ends a sentence; messenger style may omit it after a Hangul syllable. */
    private fun endsSentence(suffix: String): Boolean {
        // A trailing emoji (already allowed only when the user typed one) does not end the sentence itself.
        var end = suffix.trimEnd().length
        while (end > 0) {
            val codePoint = Character.codePointBefore(suffix, end)
            if (Character.getType(codePoint) != Character.OTHER_SYMBOL.toInt() && !Character.isWhitespace(codePoint)) break
            end -= Character.charCount(codePoint)
        }
        val last = suffix.substring(0, end).lastOrNull() ?: return false
        return last in terminalPunctuation || last in hangulSyllables
    }

    /**
     * A candidate that restates the previous sentence with small wording changes shares a long run of
     * characters with it. Rejects when the longest common run is at least [MIN_REPEATED_PREV_RUN] and
     * covers [REPEATED_PREV_RUN_PERCENT] percent of the candidate; a candidate fully contained in it is
     * rejected regardless of length.
     */
    private fun repeatsPrevious(suffix: String, prev: String): Boolean {
        val normalizedSuffix = suffix.filter(Char::isLetterOrDigit)
        if (normalizedSuffix.length < MIN_REPEATED_PREV_CHARS) return false
        val normalizedPrev = prev.filter(Char::isLetterOrDigit)
        if (normalizedPrev.contains(normalizedSuffix)) return true
        val run = longestCommonRun(normalizedSuffix, normalizedPrev)
        return run >= MIN_REPEATED_PREV_RUN && run * 100 >= normalizedSuffix.length * REPEATED_PREV_RUN_PERCENT
    }

    private fun longestCommonRun(a: String, b: String): Int {
        var best = 0
        var previousRow = IntArray(b.length + 1)
        var currentRow = IntArray(b.length + 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                currentRow[j] = if (a[i - 1] == b[j - 1]) previousRow[j - 1] + 1 else 0
                if (currentRow[j] > best) best = currentRow[j]
            }
            val swap = previousRow
            previousRow = currentRow
            currentRow = swap
        }
        return best
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

    private fun stripEmphasisMarks(text: String): String =
        text.replace("**", "").replace("__", "").replace("`", "")

    /** Repeatedly removes quote pairs around the content, keeping one leading space that separates words. */
    private fun stripWrapPairs(text: String): String {
        val leading = if (text.startsWith(' ')) " " else ""
        var content = text.substring(leading.length).trimEnd()
        while (true) {
            content = stripWrapPair(content) ?: break
        }
        return leading + content
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
    private val lineBreakOrControlRun = Regex("[\\p{Cc}\\p{Cf}\\u2028\\u2029]+")
    private val sentenceBreak = Regex("(?<=[.?!。！？])\\s+|[\\r\\n]+")
    private val politeMarkers = Regex("하신|드리|드릴|드렸|드립|께서|십시")
    private val politeEndings = listOf("요", "니다", "니까", "세요", "시오")
    private val casualEndings =
        listOf("어", "아", "야", "지", "자", "해", "래", "게", "냐", "니", "네", "라", "걸", "거든", "까", "군", "대")
    private val trailingMarks = setOf('~', '～', '〜', '…')
    private val punctuationCategories = setOf(
        CharCategory.CONNECTOR_PUNCTUATION,
        CharCategory.DASH_PUNCTUATION,
        CharCategory.START_PUNCTUATION,
        CharCategory.END_PUNCTUATION,
        CharCategory.INITIAL_QUOTE_PUNCTUATION,
        CharCategory.FINAL_QUOTE_PUNCTUATION,
        CharCategory.OTHER_PUNCTUATION
    )
    private val wrapPairs = listOf(
        "**" to "**",
        "__" to "__",
        "`" to "`",
        "\"" to "\"",
        "'" to "'",
        "“" to "”",
        "‘" to "’"
    )
    private val combiningMarkTypes = setOf(
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt()
    )
    private val hangulSyllables = '가'..'힣'

    private const val MAX_CONTEXT_CHARS = 2048
    private const val MAX_PACKAGE_CHARS = 255
    private const val MAX_SUFFIX_CHARS = 120
    private const val MAX_RAW_RESPONSE_CHARS = 4096
    private const val MAX_ECHO_EOJEOLS = 4
    private const val MIN_ECHO_CHARS = 2
    private const val MIN_REPEATED_PREV_CHARS = 4
    private const val MIN_REPEATED_PREV_RUN = 5
    private const val REPEATED_PREV_RUN_PERCENT = 60
    private const val MAX_CURRENT_CHARS = 60
    private const val MAX_PREV_TAIL_CHARS = 80
    private const val MIN_CUT_PREV_TAIL_CHARS = 10
    private const val MAX_STYLE_EXAMPLE_CHARS = 30
    private const val MAX_HINT_CHARS = 20

    /**
     * Longest prompt string, in characters. Emulator measurement: a prompt of about 207 characters or
     * fewer produced its first character in 0.3-0.4 s, while 226 or more took 2.5 s or longer
     * (a prefill threshold), so everything optional must fit under this budget.
     */
    private const val PROMPT_CHAR_BUDGET = 190

    private const val GENERAL_APP_LABEL = "일반"
    private const val ELLIPSIS = "…"
    private const val APP_LINE_PREFIX = "앱: "
    private const val TONE_LINE_PREFIX = "말투: "
    private const val HINT_LINE_PREFIX = "입력창: "
    private const val STYLE_LINE_PREFIX = "비슷한 글: "
    private const val PREV_LINE_PREFIX = "앞 내용: "
    private const val CURRENT_LINE_PREFIX = "쓰는 중인 문장: "
    private const val COMPLETE_SENTENCE_INSTRUCTION =
        "쓰는 중인 문장을 앞 내용에 이어 짧게 끝맺어 그 문장 하나만 쓰세요. 쓴 부분은 그대로 두고 앞 내용은 되풀이하지 마세요."
    private const val NEXT_SENTENCE_INSTRUCTION =
        "앞 내용에 자연스럽게 이어질 짧은 다음 문장 하나만 쓰세요. 앞 내용은 되풀이하지 마세요."
}
