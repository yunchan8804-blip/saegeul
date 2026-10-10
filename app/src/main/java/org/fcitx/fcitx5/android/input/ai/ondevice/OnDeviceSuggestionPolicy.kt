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
        /**
         * Sentences this user recently sent in this same app, newest first. Used only to detect the tone;
         * their text is never put in the prompt, because the small model copies it verbatim and leaks
         * sentences from other conversations into the suggestion.
         */
        val recentSentences: List<String> = emptyList()
    ) {
        fun withMode(mode: Mode): Input = Input(
            textBeforeCursor = textBeforeCursor,
            packageName = packageName,
            inputType = inputType,
            imeAction = imeAction,
            mode = mode,
            appCategory = appCategory,
            recentSentences = recentSentences
        )

        override fun toString(): String =
            "Input(textBeforeCursor=<redacted>, packageName=<redacted>, inputType=$inputType, " +
                "imeAction=$imeAction, mode=$mode, appCategory=$appCategory, " +
                "recentSentences=<redacted:${recentSentences.size}>)"
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

        return listOfNotNull(appLine, toneLine, prevLine, instruction, currentLine)
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
        val available = remaining - 1 - PREV_LINE_PREFIX.length
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
    fun parseSuffix(input: Input, raw: String): String? = parse(input, raw).suffix

    /** Returns a short reason code when [raw] is rejected, or null when it yields a suffix. Never contains text. */
    fun rejectionReason(input: Input, raw: String): String? = parse(input, raw).reason

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
     * 응답이 쓰는 중인 문장을 다시 쓴 것이거나 처음부터 다시 시작한 것이면, [typoEvidence]가 오타라고 한 어절만 응답에서 받아 온 문장 대체
     * ([StoredParse.replaces])로만 저장한다. 오타 증거가 없거나 실패해도 단어로 바꿔 이어 붙이지 않는다.
     */
    fun parseForStorage(input: Input, raw: String, typoEvidence: TypoEvidence): StoredParse {
        val sentence = parse(input.withMode(Mode.SENTENCE), raw, typoEvidence)
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
        val wordRejection = word.reason
            ?: gateRejection(word.suffix, input.textBeforeCursor, false)
            ?: tooShortWordHead(input, word)
        if (word.suffix != null && wordRejection == null) {
            return StoredParse(word.suffix, Mode.WORD, sentenceRejection, null)
        }
        return StoredParse(null, null, sentenceRejection, wordRejection)
    }

    /** 사용자가 이미 쓴 [typed]의 위반은 거부 사유로 치지 않고, 이어 붙는 [suffix] 탓인 위반만 친다. */
    private fun gateRejection(suffix: String?, typed: String, isSentenceCompletion: Boolean): String? =
        suffix?.let { SuggestionQualityGate.evaluateAppended(it, typed, isSentenceCompletion) }?.let { "GATE_${it.name}" }

    /**
     * 문장으로 못 쓴 응답에서 첫 어절만 단어로 남길 때, 그 어절이 한글 한 음절뿐인 새 어절(`잘`, `꼭`)이면
     * 쓸모 있는 단어 후보가 아니다. 응답 전체가 한 어절인 짧은 답(`네~`)과, 쓰던 어절에 붙는 조사·어미(`를`)는
     * 그대로 둔다.
     */
    private fun tooShortWordHead(input: Input, word: Parsed): String? {
        val suffix = word.suffix ?: return null
        if (!word.truncatedToFirstEojeol) return null
        val startsNewEojeol = suffix.startsWith(' ') || input.textBeforeCursor.lastOrNull()?.isWhitespace() != false
        if (!startsNewEojeol) return null
        val letters = suffix.filter(Char::isLetterOrDigit)
        return if (letters.length == 1 && HangulSyllable.isSyllable(letters[0])) WORD_TOO_SHORT else null
    }

    private class Parsed(
        val suffix: String?,
        val reason: String?,
        /** 쓰는 중인 문장을 오타만 고쳐 다시 쓴 응답이다. 받아들였으면 [suffix]가 대체 문장이고 [replaces]가 바꿀 원문이다. */
        val restatesTyped: Boolean = false,
        val replaces: String? = null,
        /** 단어 모드에서 응답의 첫 어절만 남기고 뒤를 버렸다. */
        val truncatedToFirstEojeol: Boolean = false
    )

    private fun accept(suffix: String) = Parsed(suffix, null)
    private fun reject(reason: String) = Parsed(null, reason)

    private class EchoRemoval(val found: Boolean, val text: String?)

    /** [typoEvidence]가 있어야 쓰는 중인 문장을 다시 쓴 응답을 대체 문장으로 받아들인다. 없으면 거부한다. */
    private fun parse(input: Input, raw: String, typoEvidence: TypoEvidence? = null): Parsed {
        if (!isValidInput(input)) return reject("INVALID_INPUT")
        if (raw.length > MAX_RAW_RESPONSE_CHARS) return reject("RAW_TOO_LONG")
        val line = firstNonBlankLine(raw)
        if (line.any(::isUnsafeOutputCharacter)) return reject("UNSAFE_CHAR")

        val normalized = stripEmphasisMarks(stripReplyDecorations(line))
        if (normalized.isEmpty()) return reject("EMPTY")
        if (unresolvedLabel.matches(normalized)) return reject("LABEL_ONLY")

        val split = splitSentences(input.textBeforeCursor)
        val withoutPrevRewording = removeRewordedPrevious(split.prev, normalized) ?: return reject("REPEATS_PREV")
        val echo = removeEchoedContext(input.textBeforeCursor, split.current, withoutPrevRewording)
        val withoutEcho = echo.text ?: return reject("ECHO_ONLY")
        if (!echo.found && (restatesTypedSentence(split.current, withoutPrevRewording) ||
                restartsTypedSentence(split.current, withoutPrevRewording))
        ) {
            return if (typoEvidence != null && input.mode == Mode.SENTENCE) {
                parseReplacement(input, split, withoutPrevRewording, typoEvidence)
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
            Mode.WORD -> firstEojeolProjection(suffix)
                ?.let { Parsed(it, null, truncatedToFirstEojeol = it.length < suffix.length) }
                ?: reject("WORD_EMPTY")
            Mode.SENTENCE -> when {
                !endsSentence(suffix) -> reject("NOT_SINGLE_SENTENCE")
                isTooShortSentence(split.current, suffix) -> reject("TOO_SHORT")
                else -> accept(suffix)
            }
        }
    }

    /**
     * 모델이 앞 내용의 마지막 문장을 말투만 바꿔 맨 앞에 다시 쓰면, 그 문장은 이어 쓸 글이 아니다. 정확히 같지 않아
     * [repeatsPrevious]에는 걸리지 않으므로 여기서 첫 문장을 떼고 남은 부분으로 이어서 판정한다. 떼고 남은 것이
     * 없으면 null이다. 앞 내용이 없거나 첫 문장이 앞 문장을 되풀이한 것이 아니면 [reply]를 그대로 돌려준다.
     */
    private fun removeRewordedPrevious(prev: String, reply: String): String? {
        val previousSentence = SentenceBoundary.splitSentences(prev).lastOrNull() ?: return reply
        val start = reply.trimStart()
        val firstEnd = SentenceBoundary.boundaryEnds(start).firstOrNull() ?: start.length
        if (!TypoRestatement.isRewordingOf(previousSentence, start.substring(0, firstEnd))) return reply
        return start.substring(firstEnd).trimStart().takeIf { it.isNotEmpty() }
    }

    private fun restatesTypedSentence(typed: String, reply: String): Boolean =
        typed.isNotBlank() && typed.none(Char::isISOControl) && TypoRestatement.isRestatement(typed, reply.trim())

    /**
     * 쓰는 중인 문장의 첫 어절로 응답이 다시 시작하고, 쓰는 중인 문장 글자(공백 제외)의 [RESTART_COVERAGE_PERCENT]퍼센트
     * 이상을 순서대로 다시 담으면 이어 쓸 글이 아니라 문장을 처음부터 다시 쓴 것이다
     * (`내일 몇시에 만나여` → `내일 몇 시 몇 분대에 만나여?`). 첫 어절만 같고 뒷내용이 새로우면
     * (`회의 끝나면 ` → `회의록 보내드릴게요.`) 이어 쓰기다.
     */
    private fun restartsTypedSentence(typed: String, reply: String): Boolean {
        if (typed.isBlank() || typed.any(Char::isISOControl)) return false
        val typedHead = typed.trim().split(whitespaceRun).first()
        val replyHead = reply.trim().split(whitespaceRun).first()
        if (typedHead.length < 2 || !replyHead.startsWith(typedHead)) return false
        val typedLetters = typed.filterNot(Char::isWhitespace)
        val replyLetters = reply.filterNot(Char::isWhitespace)
        return longestCommonSubsequence(typedLetters, replyLetters) * 100 >= typedLetters.length * RESTART_COVERAGE_PERCENT
    }

    private fun longestCommonSubsequence(a: String, b: String): Int {
        var previous = IntArray(b.length + 1)
        var current = IntArray(b.length + 1)
        for (x in a) {
            for (j in b.indices) {
                current[j + 1] = if (x == b[j]) previous[j] + 1 else maxOf(previous[j + 1], current[j])
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /**
     * 응답의 첫 문장에서 [TypoEvidence]가 오타라고 한 어절만 받아 쓰는 중인 문장의 대체 문장을 만든다([TypoReplacement]).
     * 응답의 거부 기준은 이어 붙이는 문장과 같고, 문장 끝과 길이는 만든 대체 문장으로 본다. 오타 증거가 없거나
     * 바뀐 것이 없으면 [RESTATES_TYPED]로 거부한다.
     */
    private fun parseReplacement(input: Input, split: SentenceSplit, normalized: String, typoEvidence: TypoEvidence): Parsed {
        fun fail(reason: String) = Parsed(null, reason, restatesTyped = true)
        val reply = truncateAfterFirstSentence(stripWrapPairs(normalized).trim()).trim()
        if (reply.isBlank()) return fail("BLANK")
        if (reply.length > MAX_SUFFIX_CHARS) return fail("SUFFIX_TOO_LONG")
        if (reply.any(disallowedReplyCharacters::contains)) return fail("DISALLOWED_CHAR")
        if (hasForeignScript(reply, input.textBeforeCursor)) return fail("FOREIGN_SCRIPT")
        if (repeatsPrevious(reply, split.completed)) return fail("REPEATS_PREV")
        if (stutters(split.current, reply)) return fail("STUTTER")
        if (hasReplyMarker(reply)) return fail("REPLY_MARKER")
        val sentence = TypoReplacement.compose(split.current, reply, typoEvidence) ?: return fail(RESTATES_TYPED)
        if (sentence.length > MAX_SUFFIX_CHARS) return fail("SUFFIX_TOO_LONG")
        if (!endsSentence(sentence)) return fail("NOT_SINGLE_SENTENCE")
        if (isTooShortSentence("", sentence)) return fail("TOO_SHORT")
        return Parsed(sentence, null, restatesTyped = true, replaces = split.current)
    }

    /**
     * 대체 문장이 쓰는 중인 문장의 첫 어절 앞부분을 먼저 한 번 더 말하고 시작하는지 본다(`오랜만에`를 쓰는 중인데
     * `오 오랜만에 …`로 온 경우). 첫 어절이 쓰는 중인 첫 어절보다 짧은 앞부분이고, 바로 다음 어절이 그 조각으로
     * 다시 시작하면 말더듬이다. `몇시에시작해여`를 `몇 시에 시작해요`로 띄어 쓴 것은 다음 어절이 `시`로 시작하므로 아니다.
     */
    private fun stutters(typed: String, replacement: String): Boolean {
        val typedHead = typed.trim().split(whitespaceRun).first()
        val words = replacement.trim().split(whitespaceRun)
        val head = words.first()
        return words.size > 1 && head.length < typedHead.length && typedHead.startsWith(head) &&
            words[1].startsWith(head)
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
    private const val RESTART_COVERAGE_PERCENT = 60
    private const val MAX_CURRENT_CHARS = 60
    private const val MIN_CUT_PREV_TAIL_CHARS = 10

    /**
     * Longest prompt string, in characters. Emulator measurement: a prompt of about 207 characters or
     * fewer produced its first character in 0.3-0.4 s, while 226 or more took 2.5 s or longer
     * (a prefill threshold), so everything optional must fit under this budget.
     *
     * 2026-10-10 emulator measurement through the real IME path: the model copied the prompt's own lines
     * into the suggestion, so the field hint line (`입력창: First name` came back as the suggestion for
     * `안녕하세여`) and the similar-sentence line (`비슷한 글: ...` came back as a whole sentence from another
     * conversation) are no longer sent. The budget is unchanged; what those two lines and the shorter
     * instruction freed goes to the previous text, which takes everything that is left after the tone and
     * app lines instead of the old 80-character cap.
     */
    private const val PROMPT_CHAR_BUDGET = 190

    private const val RESTATES_TYPED = "RESTATES_TYPED"
    private const val WORD_TOO_SHORT = "WORD_TOO_SHORT"
    private const val GENERAL_APP_LABEL = "일반"
    private const val ELLIPSIS = "…"
    private const val APP_LINE_PREFIX = "앱: "
    private const val TONE_LINE_PREFIX = "말투: "
    private const val PREV_LINE_PREFIX = "앞 내용: "
    private const val CURRENT_LINE_PREFIX = "쓰는 중인 문장: "
    private const val COMPLETE_SENTENCE_INSTRUCTION =
        "쓰는 중인 문장을 오타는 고쳐 앞 내용에 맞게 완성한 문장 하나만 쓰세요. 앞 내용은 되풀이하지 마세요."
    private const val NEXT_SENTENCE_INSTRUCTION =
        "앞 내용에 자연스럽게 이어질 다음 문장 하나만 쓰세요. 앞 내용은 되풀이하지 마세요."

    /**
     * Longest part of the sentence being typed that can be shown without the prompt passing
     * [PROMPT_CHAR_BUDGET]: the current line is always sent with [COMPLETE_SENTENCE_INSTRUCTION] and the
     * newline joining them, and everything else optional is squeezed into what is left.
     */
    private const val MAX_SHOWN_CURRENT_CHARS =
        PROMPT_CHAR_BUDGET - COMPLETE_SENTENCE_INSTRUCTION.length - 1 - CURRENT_LINE_PREFIX.length
}
