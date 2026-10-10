/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.fcitx.fcitx5.android.input.ai.rule.SuggestionQualityGate
import org.fcitx.fcitx5.android.input.hangul.HangulSyllable
import org.fcitx.fcitx5.android.input.text.SentenceBoundary
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
        fun withMode(mode: Mode): Input = Input(
            textBeforeCursor = textBeforeCursor,
            packageName = packageName,
            inputType = inputType,
            imeAction = imeAction,
            mode = mode,
            appCategory = appCategory,
            fieldHint = fieldHint,
            recentSentences = recentSentences,
            styleExamples = styleExamples
        )

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

        val toneLine = reserve(
            detectTone(split, input.recentSentences, input.appCategory)?.let { "$TONE_LINE_PREFIX${it.label}" }
        )
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
        val sentenceEnd = SentenceBoundary.boundaryEnds(text).lastOrNull() ?: 0
        val completed = text.substring(0, sentenceEnd).trim()
        val current = text.substring(sentenceEnd).trimStart()
        if (current.length <= MAX_CURRENT_CHARS) return SentenceSplit(completed, completed, current)
        val cut = wordBoundaryCut(current, MAX_CURRENT_CHARS) ?: when {
            current.length <= MAX_SHOWN_CURRENT_CHARS -> return SentenceSplit(completed, completed, current)
            else -> wordBoundaryCut(current, MAX_SHOWN_CURRENT_CHARS) ?: characterCut(current, MAX_SHOWN_CURRENT_CHARS)
        }
        val head = current.substring(0, cut).trimEnd()
        val prev = if (completed.isEmpty()) head else "$completed $head"
        return SentenceSplit(completed, prev, current.substring(cut))
    }

    /** The first word start within the last [keepChars] characters of [current], so the kept tail begins on a word. */
    private fun wordBoundaryCut(current: String, keepChars: Int): Int? =
        (current.length - keepChars until current.length).firstOrNull { index ->
            index > 0 && current[index - 1].isWhitespace() && !current[index].isWhitespace()
        }

    /** Keeps exactly the last [keepChars] characters (one more if that would split a surrogate pair), for text with no word boundary to cut at. */
    private fun characterCut(current: String, keepChars: Int): Int {
        val cut = current.length - keepChars
        return if (current[cut].isLowSurrogate()) cut + 1 else cut
    }

    private enum class Tone(val label: String) {
        POLITE("존댓말"),
        CASUAL("반말")
    }

    /** 자동 추천이 프롬프트에 쓰는 말투 판별을 밖에서도 쓰도록 연 것. 반말로 판정되면 true, 존댓말이거나 알 수 없으면 false. */
    fun isCasualTone(
        textBeforeCursor: String,
        recentSentences: List<String> = emptyList(),
        appCategory: String = "general"
    ): Boolean = detectTone(splitSentences(textBeforeCursor), recentSentences, appCategory) == Tone.CASUAL

    private fun detectTone(split: SentenceSplit, recentSentences: List<String>, appCategory: String): Tone? {
        var polite = 0
        var casual = 0
        val sentences = SentenceBoundary.splitSentences(split.completed) +
            recentSentences.map(::oneLine)
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
            appCategory == "work" || appCategory == "email" -> Tone.POLITE
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

    /** 쓰는 중인 문장 뒤에 이어 붙일 접미부. 오타를 고쳐 다시 쓴 응답은 접미부가 아니므로 null이다([parseForStorage] 참고). */
    fun parseSuffix(input: Input, raw: String): String? = parse(input, raw, allowReplacement = false).suffix

    /** Returns a short reason code when [raw] is rejected, or null when it yields a suffix. Never contains text. */
    fun rejectionReason(input: Input, raw: String): String? = parse(input, raw, allowReplacement = false).reason

    /**
     * 저장할 접미부와 그 종류. [mode]가 [Mode.WORD]면 문장으로는 못 써서 첫 어절만 남긴 것이다.
     * [replaces]가 있으면 [suffix]는 이어 붙일 접미부가 아니라, 쓰는 중인 문장 [replaces]를 통째로 바꿀 문장이다.
     * 둘 다 실패하면 [suffix]와 [mode]가 null이다. 거부 이유는 코드뿐이고 글 내용은 담지 않는다.
     */
    class StoredParse(
        val suffix: String?,
        val mode: Mode?,
        val sentenceRejection: String?,
        val wordRejection: String?,
        val replaces: String? = null
    )

    /**
     * 자동 추천이 응답을 저장하기 전에 하는 판정 하나. 문장으로 파싱해 [SuggestionQualityGate]를 통과하면 문장으로
     * 저장하고, 아니면 단어로 파싱해 같은 게이트를 통과할 때만 단어로 저장한다. [Input.mode]는 쓰지 않는다.
     * 응답이 쓰는 중인 문장을 오타만 고쳐 다시 쓴 것이면 문장 대체([StoredParse.replaces])로만 저장하고,
     * 실패해도 단어로 바꿔 이어 붙이지 않는다.
     */
    fun parseForStorage(input: Input, raw: String): StoredParse {
        val sentence = parse(input.withMode(Mode.SENTENCE), raw, allowReplacement = true)
        if (sentence.restatesTyped) {
            val replacementRejection = sentence.reason ?: gateRejection(
                sentence.suffix,
                input.textBeforeCursor.removeSuffix(sentence.replaces.orEmpty()),
                true
            )
            return if (sentence.suffix != null && replacementRejection == null) {
                StoredParse(sentence.suffix, Mode.SENTENCE, null, null, sentence.replaces)
            } else {
                StoredParse(null, null, replacementRejection, RESTATES_TYPED)
            }
        }
        val sentenceRejection = sentence.reason ?: gateRejection(sentence.suffix, input.textBeforeCursor, true)
        if (sentence.suffix != null && sentenceRejection == null) {
            return StoredParse(sentence.suffix, Mode.SENTENCE, null, null)
        }
        val word = parse(input.withMode(Mode.WORD), raw)
        val wordRejection = word.reason ?: gateRejection(word.suffix, input.textBeforeCursor, false)
        if (word.suffix != null && wordRejection == null) {
            return StoredParse(word.suffix, Mode.WORD, sentenceRejection, null)
        }
        return StoredParse(null, null, sentenceRejection, wordRejection)
    }

    private fun gateRejection(suffix: String?, context: String, isSentenceCompletion: Boolean): String? =
        suffix?.let { SuggestionQualityGate.evaluate(it, context, isSentenceCompletion) }?.let { "GATE_${it.name}" }

    private class Parsed(
        val suffix: String?,
        val reason: String?,
        /** 쓰는 중인 문장을 오타만 고쳐 다시 쓴 응답이다. 받아들였으면 [suffix]가 대체 문장이고 [replaces]가 바꿀 원문이다. */
        val restatesTyped: Boolean = false,
        val replaces: String? = null
    )

    private fun accept(suffix: String) = Parsed(suffix, null)
    private fun reject(reason: String) = Parsed(null, reason)

    private class EchoRemoval(val found: Boolean, val text: String?)

    private fun parse(input: Input, raw: String, allowReplacement: Boolean = false): Parsed {
        if (!isValidInput(input)) return reject("INVALID_INPUT")
        if (raw.length > MAX_RAW_RESPONSE_CHARS) return reject("RAW_TOO_LONG")
        val line = firstNonBlankLine(raw)
        if (line.any(::isUnsafeOutputCharacter)) return reject("UNSAFE_CHAR")

        val normalized = stripEmphasisMarks(stripReplyDecorations(line))
        if (normalized.isEmpty()) return reject("EMPTY")
        if (unresolvedLabel.matches(normalized)) return reject("LABEL_ONLY")

        val split = splitSentences(input.textBeforeCursor)
        val echo = removeEchoedContext(input.textBeforeCursor, split.current, normalized)
        val withoutEcho = echo.text ?: return reject("ECHO_ONLY")
        if (!echo.found && restatesTypedSentence(split.current, normalized)) {
            return if (allowReplacement && input.mode == Mode.SENTENCE) {
                parseReplacement(input, split, normalized)
            } else {
                Parsed(null, RESTATES_TYPED, restatesTyped = true)
            }
        }
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
        if (repeatsPrevious(suffix, split.prev) || repeatsPrevious(suffix, split.current)) return reject("REPEATS_PREV")
        if (hasReplyMarker(suffix)) return reject("REPLY_MARKER")
        if (suffix.startsWith("  ") || (suffix.first().isWhitespace() && suffix.first() != ' ')) {
            return reject("LEADING_WHITESPACE")
        }
        if (input.textBeforeCursor.lastOrNull()?.isWhitespace() == true && suffix.first().isWhitespace()) {
            return reject("DOUBLE_SPACE")
        }

        return when (input.mode) {
            Mode.WORD -> firstEojeolProjection(suffix)?.let(::accept) ?: reject("WORD_EMPTY")
            Mode.SENTENCE -> when {
                !endsSentence(suffix) -> reject("NOT_SINGLE_SENTENCE")
                isTooShortSentence(split.current, suffix) -> reject("TOO_SHORT")
                else -> accept(suffix)
            }
        }
    }

    private fun restatesTypedSentence(typed: String, reply: String): Boolean =
        typed.isNotBlank() && typed.none(Char::isISOControl) && TypoRestatement.isRestatement(typed, reply.trim())

    /** 응답 전체를 쓰는 중인 문장의 대체 문장으로 본다. 첫 문장 자르기와 거부 기준은 이어 붙이는 문장과 같다. */
    private fun parseReplacement(input: Input, split: SentenceSplit, normalized: String): Parsed {
        fun fail(reason: String) = Parsed(null, reason, restatesTyped = true)
        val sentence = truncateAfterFirstSentence(stripWrapPairs(normalized).trim()).trim()
        if (sentence.isBlank()) return fail("BLANK")
        if (sentence.length > MAX_SUFFIX_CHARS) return fail("SUFFIX_TOO_LONG")
        if (sentence.any(disallowedReplyCharacters::contains)) return fail("DISALLOWED_CHAR")
        if (hasForeignScript(sentence, input.textBeforeCursor)) return fail("FOREIGN_SCRIPT")
        if (repeatsPrevious(sentence, split.completed)) return fail("REPEATS_PREV")
        if (hasReplyMarker(sentence)) return fail("REPLY_MARKER")
        if (!endsSentence(sentence)) return fail("NOT_SINGLE_SENTENCE")
        if (isTooShortSentence("", sentence)) return fail("TOO_SHORT")
        return Parsed(sentence, null, restatesTyped = true, replaces = split.current)
    }

    /**
     * 모델이 여러 줄로 답하면 비어 있지 않은 첫 줄만 쓴다. 줄 구분은 CRLF와 LF뿐이라 홑 CR이나 다른
     * 제어문자는 그대로 남아 호출한 쪽에서 거부된다. 모두 빈 줄이면 첫 줄을 돌려준다.
     */
    private fun firstNonBlankLine(raw: String): String {
        val lines = raw.split("\r\n", "\n")
        return lines.firstOrNull(String::isNotBlank) ?: lines.first()
    }

    /**
     * 쓰는 중인 문장에 [suffix]를 이어 붙인 문장의 한글 음절이 [MIN_SENTENCE_SYLLABLES]개 미만이면 문장 후보로
     * 너무 짧다(`네~`, `응.`). 이어 붙인 결과로 세므로 긴 문장의 마지막 `요.`는 짧지 않다. 글자·숫자가 하나도
     * 없는 접미부(문장 부호뿐)도 문장 후보가 아니다.
     */
    private fun isTooShortSentence(current: String, suffix: String): Boolean {
        if (suffix.none(Char::isLetterOrDigit)) return true
        return (current + suffix).count(HangulSyllable::isSyllable) < MIN_SENTENCE_SYLLABLES
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
     * context or its last eojeols are tried. A restatement that differs only in spacing ("공유 드릴게요"
     * for a typed "공유드릴게") counts too. A reply that is nothing but an echo yields a null text, and a
     * reply with no echo is returned unchanged with [EchoRemoval.found] false.
     */
    private fun removeEchoedContext(context: String, shownCurrent: String, reply: String): EchoRemoval {
        val start = reply.trimStart()
        if (start.isEmpty()) return EchoRemoval(found = false, text = reply)
        val echoes = echoCandidates(context.trimEnd(), oneLine(shownCurrent))
        val echoedLength = echoes.firstNotNullOfOrNull { (echo, minChars) ->
            echo.length.takeIf { echo.length >= minChars && start.startsWith(echo) }
        } ?: echoes.firstNotNullOfOrNull { (echo, _) -> spacingInsensitiveEchoLength(start, echo) }
            ?: return EchoRemoval(found = false, text = reply)
        val remainder = start.substring(echoedLength)
        if (remainder.isBlank()) return EchoRemoval(found = true, text = null)
        val continuation = when {
            context.last().isWhitespace() -> remainder.trimStart()
            remainder.first().isWhitespace() -> " " + remainder.trimStart()
            else -> remainder
        }
        return EchoRemoval(found = true, text = continuation)
    }

    /** The texts a reply may restate, most specific first, each with the shortest length that counts as an echo. */
    private fun echoCandidates(trimmedContext: String, current: String): List<Pair<String, Int>> {
        val eojeols = trimmedContext.split(whitespaceRun).filter { it.isNotEmpty() }
        return buildList {
            if (current.isNotEmpty()) add(current to 0)
            add(trimmedContext to 0)
            for (count in minOf(eojeols.size, MAX_ECHO_EOJEOLS) downTo 1) {
                add(eojeols.takeLast(count).joinToString(" ") to MIN_ECHO_CHARS)
            }
        }
    }

    /**
     * How many characters of [start] a spacing-insensitive restatement of [echo] covers, or null when [start]
     * does not begin with it. Whitespace is skipped on both sides and the characters are matched one by one.
     */
    private fun spacingInsensitiveEchoLength(start: String, echo: String): Int? {
        val letters = echo.filterNot(Char::isWhitespace)
        if (letters.length < MIN_ECHO_CHARS) return null
        var matched = 0
        var index = 0
        while (matched < letters.length) {
            if (index >= start.length) return null
            val character = start[index]
            if (!character.isWhitespace()) {
                if (character != letters[matched]) return null
                matched++
            }
            index++
        }
        return index
    }

    private fun firstEojeolProjection(suffix: String): String? {
        val contentStart = if (suffix.startsWith(' ')) 1 else 0
        var endExclusive = contentStart
        while (endExclusive < suffix.length && !suffix[endExclusive].isWhitespace()) {
            endExclusive++
        }
        return suffix.substring(0, endExclusive).takeIf { endExclusive > contentStart }
    }

    /** Keeps only the first sentence when a sentence boundary is followed by more text. */
    private fun truncateAfterFirstSentence(suffix: String): String {
        for (end in SentenceBoundary.boundaryEnds(suffix)) {
            if (end < suffix.length && (end until suffix.length).any { !suffix[it].isWhitespace() }) {
                return suffix.substring(0, end)
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
        val body = suffix.substring(0, end)
        val last = body.lastOrNull() ?: return false
        return SentenceBoundary.endsSentence(body) || HangulSyllable.isSyllable(last)
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

    private const val MAX_CONTEXT_CHARS = 2048
    private const val MAX_PACKAGE_CHARS = 255
    private const val MAX_SUFFIX_CHARS = 120
    private const val MAX_RAW_RESPONSE_CHARS = 4096
    private const val MAX_ECHO_EOJEOLS = 4
    private const val MIN_ECHO_CHARS = 2
    private const val MIN_SENTENCE_SYLLABLES = 3
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

    private const val RESTATES_TYPED = "RESTATES_TYPED"
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

    /**
     * Longest part of the sentence being typed that can be shown without the prompt passing
     * [PROMPT_CHAR_BUDGET]: the current line is always sent with [COMPLETE_SENTENCE_INSTRUCTION] and the
     * newline joining them, and everything else optional is squeezed into what is left.
     */
    private const val MAX_SHOWN_CURRENT_CHARS =
        PROMPT_CHAR_BUDGET - COMPLETE_SENTENCE_INSTRUCTION.length - 1 - CURRENT_LINE_PREFIX.length
}
