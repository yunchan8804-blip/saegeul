/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rule

/**
 * Stage 1 Lightweight Korean Syntax Rule Filter.
 *
 * Rejects ungrammatical or pragmatically inconsistent sentence continuations within <= 0.05ms (50µs).
 * Targets 5 critical empirical defects (ACC-01 ~ ACC-05):
 *
 * 1. ACC-01 (Causal Subordination Mismatch):
 *    Rejects sentences where a causal clause is followed by an imperative/propositive clause
 *    (`자`, `십시오`, `세요`, or a command-adverb-gated bare casual imperative like `빨리 와`).
 *    The causal clause must be either an unrestricted connective (`-느라고`, `-기에`, `-길래`) or
 *    a `-[아/어]서` ending on one of a fixed list of state/emotion/circumstance predicates (더워서,
 *    피곤해서, 늦어서, 배고파서, 막혀서 등). An action-verb `-[아/어]서` (걸어서, 표시해서, 골라서
 *    등, expressing sequence/manner rather than reason) is never treated as causal here, and an
 *    interrogative ending after a causal clause is never rejected — both are extremely common,
 *    grammatical colloquial Korean ("걸어서 갈까요?", "배고파서 뭐 먹을까?").
 *
 * 2. ACC-02 (Intransitive Predicate with Accusative Object):
 *    Rejects sentences where an accusative noun phrase (`을/를`) directly binds to an
 *    intransitive/adjective predicate (`감사하다`, `고맙다`, `기쁘다`, `슬프다`).
 *
 * 3. ACC-04 (Formality / Honorific Tone Inconsistency):
 *    Rejects text mixing formal endings (`-ㅂ니다/-해요`) with informal endings (`-어/-지/-냐`).
 *
 * 4. ACC-05 (Stem Duplication):
 *    Rejects an eojeol where a `하` verb stem is immediately followed by another `해/했/하여`
 *    stem (e.g. `안녕하해요`, `공부하했어`), while allowing an eojeol that itself starts with
 *    `하` (e.g. the noun `하해(河海)` used on its own).
 */
class KoreanSyntaxRuleFilter {

    enum class ViolationType(val code: String, val description: String) {
        ACC_01_CAUSAL_SUBORDINATION("ACC-01", "선행 이유절(상태 용언 -아서/어서, 느라고/기에/길래) 뒤 명령/청유 호응 오류"),
        ACC_02_INTRANSITIVE_OBJECT("ACC-02", "목적어 격조사(-을/를) 뒤 자동사 술어(감사/고맙/기쁘/슬프) 직접 결합 오류"),
        ACC_03_INTERROGATIVE_DISCORD("ACC-03", "의문사(뭘/무엇을/왜 등) 뒤 평서문 종결 호응 오류"),
        ACC_04_FORMALITY_INCONSISTENCY("ACC-04", "한 문맥 내 격식체(-ㅂ니다/-해요)와 비격식체(-어/-지/-냐) 혼용 오류"),
        ACC_05_STEM_DUPLICATION("ACC-05", "어절 중간에서 '하' 어간이 '해/했/하여'와 중복 결합된 오류")
    }

    sealed class RuleResult {
        object Valid : RuleResult()
        data class Invalid(
            val violationType: ViolationType,
            val reason: String,
            val matchedSnippet: String = ""
        ) : RuleResult()
    }

    companion object {
        const val MAX_SAFE_INPUT_LENGTH = 2000

        // ==========================================
        // ACC-01 Patterns: Causal Subordination
        // ==========================================
        // -[아/어]서 attached to a fixed list of state/emotion/circumstance predicates only.
        // 2026-09-27 정밀화: 동작 용언의 -아서/어서(걸어서, 표시해서, 골라서, 타서, 가져와서, 만들어서
        // 등, 순서·방법을 뜻함)는 여기서 다루지 않는다. 활용형은 표준 규칙/불규칙 활용을 그대로 반영한다
        // (ㅂ 불규칙: 덥→더워서, 춥→추워서, 무섭→무서워서, 가깝→가까워서, 어렵→어려워서, 쉽→쉬워서,
        // 고맙→고마워서, 시끄럽→시끄러워서; ㅡ 불규칙: 바쁘→바빠서, 아프→아파서, 배고프→배고파서;
        // 르 불규칙: 모르→몰라서; 축약: 막히→막혀서, 밀리→밀려서, 졸리→졸려서).
        private val STATE_CAUSAL_FORMS = listOf(
            "더워서", "추워서", "바빠서", "아파서", "피곤해서", "늦어서", "없어서", "있어서",
            "몰라서", "좋아서", "싫어서", "힘들어서", "무서워서", "배고파서", "졸려서", "급해서",
            "멀어서", "가까워서", "비싸서", "싸서", "어려워서", "쉬워서", "귀찮아서", "미안해서",
            "고마워서", "괜찮아서", "시끄러워서", "막혀서", "밀려서"
        )
        private val STATE_CAUSAL_PATTERN = Regex(
            "(?:${STATE_CAUSAL_FORMS.joinToString("|")})(?=[^가-힣]|${'$'})"
        )

        // -느라고/-기에/-길래는 용언 종류를 가리지 않고 항상 이유절로 본다(기존과 동일, 변경 없음).
        private val UNRESTRICTED_CAUSAL_PATTERN = Regex(
            """[가-힣]*(?:느라고|기에|길래)(?=[^가-힣]|${'$'})"""
        )

        // Imperative / Propositive endings:
        // 세요, 으세요, 십시오, 으십시오, 자(하자 포함), 합시다, 읍시다, 시지요, 어라, 아라, 렴
        private val IMPERATIVE_PROPOSITIVE_PATTERN = Regex(
            """(?:십시오|으십시오|세요|으세요|시지요|합시다|읍시다|자|어라|아라|렴)[\s.!]*${'$'}"""
        )

        // 반말 축약 명령형(와라/아라 같은 어미가 붙지 않는 "와.", "빨리 가." 류)은 평서문과 표면형이
        // 같아 명령·청유 어미만으로는 구분할 수 없다. 오탐을 줄이기 위해 "빨리/제발/당장/얼른/이리"
        // 같은 명령 신호 부사가 함께 있고, 그 부사 뒤 마지막 어절이 이 짧은 동사 원형 그대로일 때만
        // 명령으로 본다.
        private val COMMAND_ADVERBS = setOf("빨리", "제발", "당장", "얼른", "이리")
        private val BARE_CASUAL_IMPERATIVE_ENDING_PATTERN = Regex(
            """(?:^|\s)(?:와|가|줘|봐|해)[.!]?${'$'}"""
        )

        private fun isImperativeOrPropositive(followingText: String): Boolean {
            if (IMPERATIVE_PROPOSITIVE_PATTERN.containsMatchIn(followingText)) return true
            return COMMAND_ADVERBS.any(followingText::contains) &&
                BARE_CASUAL_IMPERATIVE_ENDING_PATTERN.containsMatchIn(followingText)
        }

        // ==========================================
        // ACC-02 Patterns: Accusative + Intransitive
        // ==========================================
        // Accusative marker: [가-힣]+(을|를)
        // Optional intervening adverbs: 정말, 정말로, 너무, 너무나, 너무나도, 진심으로, 대단히, 무척, 무척이나, 매우, 깊이, 항상, 늘, 다시, 진짜, 진짜로
        // Intransitive/adjective roots: 감사, 고맙, 고마워, 기쁘, 기뻐, 슬프, 슬퍼, 죄송, 미안
        private val ACC02_PATTERN = Regex(
            """([가-힣]+[을를])\s*(?:(?:정말|정말로|너무|너무나|너무나도|진심으로|대단히|무척|무척이나|매우|깊이|항상|늘|다시|진짜|진짜로|가장|제일)\s*)?(감사(?:하다|해요|합니다|해|드립니다|드려요|드림|하네요|하군요|하지)|고맙(?:다|습니다|네|군요|지)|고마워(?:요)?|고마웠(?:어|습니다|어요)|기쁘(?:다|네요|군요|ㅂ니다|십니까|지)|기뻐(?:요)?|기뻤(?:어|습니다|어요)|기쁩니다|슬프(?:다|네요|군요|ㅂ니다|십니까|지)|슬퍼(?:요)?|슬펐(?:어|습니다|어요)|슬픕니다|죄송(?:하다|해요|합니다|해)|미안(?:하다|해요|합니다|해))""",
            RegexOption.IGNORE_CASE
        )

        // ==========================================
        // ACC-03 Patterns: Interrogative Discord
        // ==========================================
        private val INTERROGATIVE_WH_PATTERN = Regex(
            """(?<=[\s,.?!]|^)(뭘|무엇을|무엇|무얼|어째서|왜|누굴|누구를|누가|누구|언제|어디서|어디|어떻게)(?=[\s,.?!]|${'$'})"""
        )
        private val FORMAL_DECLARATIVE_ENDING_PATTERN = Regex(
            """(?:합니다|했습니다|하겠습니다|입니다|드립니다|올립니다|[가-힣]*(?:습니다|ㅂ니다))[\s.?!]*${'$'}"""
        )

        // ==========================================
        // ACC-04 Patterns: Formality Tone Check
        // ==========================================
        // Formal endings (하십시오체 / 해요체):
        // -ㅂ니다/-습니다: 습니다, 니다, 입니다, 드립니다, 올립니다
        // -ㅂ니까/-습니까: 습니까, 니까, 입니까
        // 해요체: -세요, -으세요, -어요, -아요, -해요, -지요, -고요, -예요, -이에요, -게요, -ㄹ게요, -을게요
        // Common formal greetings/closings: 안녕하세요, 안녕하십니까, 감사합니다, 고맙습니다, 죄송합니다
        private val FORMAL_ENDING_PATTERN = Regex(
            """(?:[가-힣]*(?:습니다|니다|습니까|니까|십시오|으십시오|세요|으세요|어요|아요|해요|지요|고요|예요|이에요|드립니다|계세요|있어요|없어요|게요|ㄹ게요|을게요|뵐게요)|안녕하세요|안녕하십니까|감사합니다|고맙습니다|죄송합니다)[\s.?!]*${'$'}"""
        )

        // Informal endings (해체 / 해라체 - 반말):
        // -어, -아, -지, -냐, -자, -어라, -아라, -니, -을게, -ㄹ게, -대, -군, -마
        // Common informal words: 안녕, 밥 먹었어, 고마워, 미안해, 내일 봐, 어디야, 뭐해, 확인해줘
        private val INFORMAL_ENDING_PATTERN = Regex(
            """(?:[가-힣]*(?:었어|았어|했어|먹었어|봤어|갈게|할게|올게|있어|없어|어디야|뭐해|어때|하자|보자|먹자|가자|했지|맞지|그렇지|있지|없지|좋지|해줘|알려줘|줘|봐|해|냐|으니|니|어라|아라|군|마)|안녕|고마워|미안해|잘 가|내일 봐)[\s.?!]*${'$'}"""
        )

        // Sentence delimiters: punctuation followed by space or newline
        private val SENTENCE_DELIMITERS = Regex("""(?<=[.?!])\s+|\n+""")

        // ==========================================
        // ACC-05 Patterns: Stem Duplication (하 + 해/했/하여)
        // ==========================================
        // Requires a preceding Hangul syllable so the eojeol-initial "하해" (e.g. the noun
        // 하해(河海) used on its own) is never flagged - only a "하" glued onto an earlier
        // stem within the same eojeol (안녕+하해요, 공부+하했어) is a duplication error.
        // "하해서" is also covered since it contains the "하해" substring.
        private val ACC05_STEM_DUPLICATION_PATTERN = Regex(
            """(?<=[가-힣])하(?:해|했|하여)"""
        )

        // Formal start / Informal start without punctuation (e.g. "안녕하세요 밥 먹었어?")
        private val FORMAL_START_PATTERN = Regex(
            """^(?:안녕하세요|안녕하십니까|반갑습니다|감사합니다|고맙습니다|죄송합니다)"""
        )
        private val INFORMAL_START_PATTERN = Regex(
            """^(?:안녕|고마워|미안해)\b"""
        )

        private val DEFAULT_INSTANCE = KoreanSyntaxRuleFilter()

        /**
         * Verifies whether [text] (optionally concatenated after [context]) is grammatically sound.
         */
        @JvmStatic
        @JvmOverloads
        fun isGrammaticallySound(text: String, context: String = ""): Boolean {
            val trimmedContext = context.trim()
            val fullText = when {
                trimmedContext.isEmpty() -> text.trim()
                text.trim().startsWith(trimmedContext) -> text.trim()
                else -> "$trimmedContext $text".trim()
            }
            return DEFAULT_INSTANCE.isValid(fullText)
        }
    }

    /**
     * Checks all Stage 1 syntax rules on the given text.
     * Returns [RuleResult.Valid] if all rules pass, or [RuleResult.Invalid] on violation.
     */
    fun check(text: String): RuleResult {
        if (text.isBlank()) return RuleResult.Valid
        if (text.length > MAX_SAFE_INPUT_LENGTH) {
            // ReDoS and OOM Fail-Safe for pathological length inputs
            return RuleResult.Valid
        }

        // Rule 1: ACC-01 (Causal Subordination)
        val acc01Result = checkAcc01(text)
        if (acc01Result is RuleResult.Invalid) return acc01Result

        // Rule 2: ACC-02 (Intransitive Predicate with Object)
        val acc02Result = checkAcc02(text)
        if (acc02Result is RuleResult.Invalid) return acc02Result

        // Rule 3: ACC-03 (Interrogative Discord)
        val acc03Result = checkAcc03(text)
        if (acc03Result is RuleResult.Invalid) return acc03Result

        // Rule 4: ACC-04 (Formality Inconsistency)
        val acc04Result = checkAcc04(text)
        if (acc04Result is RuleResult.Invalid) return acc04Result

        // Rule 5: ACC-05 (Stem Duplication: 하 + 해/했/하여)
        val acc05Result = checkAcc05(text)
        if (acc05Result is RuleResult.Invalid) return acc05Result

        return RuleResult.Valid
    }

    /**
     * Returns true if the text complies with all syntax rules.
     */
    fun isValid(text: String): Boolean = check(text) is RuleResult.Valid

    /**
     * Filters candidate continuations that violate any syntax rule when appended to [context].
     */
    fun filterCandidates(candidates: List<String>, context: String = ""): List<String> {
        val trimmedContext = context.trim()
        return candidates.filter { candidate ->
            val fullText = if (trimmedContext.isNotEmpty()) "$trimmedContext $candidate".trim() else candidate
            isValid(fullText) && isValid(candidate)
        }
    }

    /**
     * ACC-01: Rejects sentences where a causal clause (a state/emotion/circumstance predicate's
     * -[아/어]서, or an unrestricted -느라고/-기에/-길래) is followed by an explicit imperative or
     * propositive ending. An interrogative ending after a causal clause is never rejected — that
     * combination is common, grammatical, colloquial Korean ("배고파서 뭐 먹을까?").
     */
    fun checkAcc01(text: String): RuleResult {
        (STATE_CAUSAL_PATTERN.findAll(text) + UNRESTRICTED_CAUSAL_PATTERN.findAll(text)).forEach { match ->
            val causalVerb = match.value
            val followingText = text.substring(match.range.last + 1).trim()
            if (followingText.isEmpty()) return@forEach

            if (isImperativeOrPropositive(followingText)) {
                return RuleResult.Invalid(
                    violationType = ViolationType.ACC_01_CAUSAL_SUBORDINATION,
                    reason = "이유 접속어미('$causalVerb') 뒤에 명령/청유문('$followingText')이 결합되었습니다. (-니까/-니로 교체 필요)",
                    matchedSnippet = "$causalVerb $followingText"
                )
            }
        }
        return RuleResult.Valid
    }

    /**
     * ACC-02: Rejects sentences where accusative particles (-을/를) directly bind to
     * intransitive/adjective predicates like 감사하다, 고맙다, 기쁘다, 슬프다.
     */
    fun checkAcc02(text: String): RuleResult {
        val match = ACC02_PATTERN.find(text)
        if (match != null) {
            val objectNoun = match.groupValues[1]
            val predicate = match.groupValues[2]
            return RuleResult.Invalid(
                violationType = ViolationType.ACC_02_INTRANSITIVE_OBJECT,
                reason = "목적어 격조사('$objectNoun')가 자동사/형용사 술어('$predicate')와 직접 결합했습니다. (처격 조사 -에 또는 타동사로 교체 필요)",
                matchedSnippet = match.value
            )
        }
        return RuleResult.Valid
    }

    /**
     * ACC-03: Rejects sentences where interrogative wh-words (뭘, 무엇을, 왜, etc.)
     * are paired with formal declarative endings (합니다/했습니다/하겠습니다/ㅂ니다/습니다)
     * instead of interrogative endings.
     */
    fun checkAcc03(text: String): RuleResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return RuleResult.Valid
        if (trimmed.contains("왜냐하면") || trimmed.contains("왜냐면")) return RuleResult.Valid

        val whMatch = INTERROGATIVE_WH_PATTERN.find(trimmed)
        if (whMatch != null) {
            val whWord = whMatch.groupValues[1]
            if (FORMAL_DECLARATIVE_ENDING_PATTERN.containsMatchIn(trimmed)) {
                return RuleResult.Invalid(
                    violationType = ViolationType.ACC_03_INTERROGATIVE_DISCORD,
                    reason = "의문사('$whWord')가 포함된 문장이 격식체 평서 종결(-ㅂ니다/-습니다/-합니다)로 결합되었습니다: '$trimmed'",
                    matchedSnippet = trimmed
                )
            }
        }
        return RuleResult.Valid
    }

    /**
     * ACC-04: Rejects text where formal honorific tone (-ㅂ니다/-해요) and informal tone (-어/-지/-냐)
     * are mixed across sentences or clauses.
     */
    fun checkAcc04(text: String): RuleResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return RuleResult.Valid

        // Check if text starts with formal greeting but ends with informal ending without sentence delimiter
        if (FORMAL_START_PATTERN.containsMatchIn(trimmed) || trimmed.startsWith("안녕하십니까") || trimmed.startsWith("안녕하세요")) {
            if (INFORMAL_ENDING_PATTERN.containsMatchIn(trimmed)) {
                return RuleResult.Invalid(
                    violationType = ViolationType.ACC_04_FORMALITY_INCONSISTENCY,
                    reason = "격식체 시작과 비격식체 종결이 혼용되었습니다: '$trimmed'",
                    matchedSnippet = trimmed
                )
            }
        } else if (INFORMAL_START_PATTERN.containsMatchIn(trimmed)) {
            if (FORMAL_ENDING_PATTERN.containsMatchIn(trimmed)) {
                return RuleResult.Invalid(
                    violationType = ViolationType.ACC_04_FORMALITY_INCONSISTENCY,
                    reason = "비격식체 시작과 격식체 종결이 혼용되었습니다: '$trimmed'",
                    matchedSnippet = trimmed
                )
            }
        }

        // Split multi-sentence text
        val sentences = SENTENCE_DELIMITERS.split(trimmed).map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.size < 2) return RuleResult.Valid

        var hasFormal = false
        var hasInformal = false

        for (sentence in sentences) {
            if (FORMAL_ENDING_PATTERN.containsMatchIn(sentence) ||
                FORMAL_START_PATTERN.containsMatchIn(sentence) ||
                sentence.contains("안녕하십니까") || sentence.contains("안녕하세요") ||
                sentence.contains("감사합니다") || sentence.contains("고맙습니다") ||
                sentence.contains("죄송합니다") || sentence.contains("뵐게요") ||
                sentence.contains("드릴게요") || sentence.contains("드리겠습니다")) {
                hasFormal = true
            }
            if (INFORMAL_ENDING_PATTERN.containsMatchIn(sentence) ||
                INFORMAL_START_PATTERN.containsMatchIn(sentence)) {
                hasInformal = true
            }
        }

        if (hasFormal && hasInformal) {
            return RuleResult.Invalid(
                violationType = ViolationType.ACC_04_FORMALITY_INCONSISTENCY,
                reason = "한 문맥 내에서 격식체와 비격식체가 혼용되었습니다: '$trimmed'",
                matchedSnippet = trimmed
            )
        }

        return RuleResult.Valid
    }

    /**
     * ACC-05: Rejects an eojeol where a `하` verb stem is immediately followed by another
     * `해/했/하여` stem (e.g. `안녕하해요`, `공부하했어`). An eojeol that itself starts with `하`
     * (e.g. the noun `하해(河海)` used on its own) is not flagged.
     */
    fun checkAcc05(text: String): RuleResult {
        val match = ACC05_STEM_DUPLICATION_PATTERN.find(text)
        if (match != null) {
            return RuleResult.Invalid(
                violationType = ViolationType.ACC_05_STEM_DUPLICATION,
                reason = "어절 중간에서 '하' 어간이 중복 결합되었습니다('${match.value}'). (예: 하해요→해요, 하했어→했어)",
                matchedSnippet = match.value
            )
        }
        return RuleResult.Valid
    }
}
