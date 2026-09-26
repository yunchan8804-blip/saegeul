/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rule

/**
 * Stage 1 Lightweight Korean Syntax Rule Filter.
 *
 * Rejects ungrammatical or pragmatically inconsistent sentence continuations within <= 0.05ms (50µs).
 * Targets 5 critical empirical defects (ACC-01 ~ ACC-04):
 *
 * 1. ACC-01 (Causal Subordination Mismatch):
 *    Rejects sentences where a causal clause ending in `-[아/어/여]서` or `-느라고` is followed by
 *    an interrogative clause (`?`, `인가요`, `나요`, `까`) or an imperative/propositive clause (`자`, `십시오`, `세요`).
 *
 * 2. ACC-02 (Intransitive Predicate with Accusative Object):
 *    Rejects sentences where an accusative noun phrase (`을/를`) directly binds to an
 *    intransitive/adjective predicate (`감사하다`, `고맙다`, `기쁘다`, `슬프다`).
 *
 * 3. ACC-04 (Formality / Honorific Tone Inconsistency):
 *    Rejects text mixing formal endings (`-ㅂ니다/-해요`) with informal endings (`-어/-지/-냐`).
 */
class KoreanSyntaxRuleFilter {

    enum class ViolationType(val code: String, val description: String) {
        ACC_01_CAUSAL_SUBORDINATION("ACC-01", "선행 이유절(-아서/어서/느라고) 뒤 의문/명령/청유 호응 오류"),
        ACC_02_INTRANSITIVE_OBJECT("ACC-02", "목적어 격조사(-을/를) 뒤 자동사 술어(감사/고맙/기쁘/슬프) 직접 결합 오류"),
        ACC_03_INTERROGATIVE_DISCORD("ACC-03", "의문사(뭘/무엇을/왜 등) 뒤 평서문 종결 호응 오류"),
        ACC_04_FORMALITY_INCONSISTENCY("ACC-04", "한 문맥 내 격식체(-ㅂ니다/-해요)와 비격식체(-어/-지/-냐) 혼용 오류")
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
        // Causal connective ending: -어서, -아서, -여서, -와서, -봐서, -돼서, -되어서, -느라고, -기에, -길래
        // Includes 7 major irregular conjugations (ㄷ, ㅂ, ㅅ, 르, ㅎ, 우, 여)
        // Note: '-니까', '-니' intentionally excluded as they grammatically allow question/imperative.
        private val CAUSAL_CONNECTIVE_PATTERN = Regex(
            """([가-힣]*(?:[아어여]서|[와봐가사타자나돼해줘써커켜쳐혀겨셔서펴퍼파]서|워서|려서|러서|라서|얘서|래서|개서|져서|느라고|기에|길래))(?=[^가-힣]|${'$'})""",
            RegexOption.IGNORE_CASE
        )

        // Interrogative / Question endings:
        // ?, 인가요, 나요, 까, 습니까, ㅂ니까, 을까요, ㄹ까요, 을까, ㄹ까, 니, 냐, 던가, 어떡하죠, 어쩌죠
        private val QUESTION_ENDING_PATTERN = Regex(
            """(?:\?|(?:[가-힣]*(?:인가요|은가요|나요|가요|습니까|ㅂ니까|을까요|ㄹ까요|을까|ㄹ까|는가|은가|던가|는지요|니|냐|까)|어떡하죠|어쩌죠)\s*[\?]?)[\s.]*${'$'}"""
        )

        // Imperative / Propositive endings:
        // 세요, 으세요, 십시오, 으십시오, 자, 합시다, 읍시다, 시지요, 어라, 아라, 렴
        private val IMPERATIVE_PROPOSITIVE_PATTERN = Regex(
            """(?:십시오|으십시오|세요|으세요|시지요|합시다|읍시다|자|어라|아라|렴)[\s.!]*${'$'}"""
        )

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
     * ACC-01: Rejects sentences where causal clauses (-어서, -아서, -여서, -느라고) are followed
     * by interrogative or imperative/propositive endings.
     */
    fun checkAcc01(text: String): RuleResult {
        val matches = CAUSAL_CONNECTIVE_PATTERN.findAll(text)
        for (match in matches) {
            val causalVerb = match.groupValues[1]
            val followingText = text.substring(match.range.last + 1).trim()
            if (followingText.isEmpty()) continue

            // 1. Interrogative check (e.g. "?", "인가요", "나요", "까")
            if (followingText.contains('?') || QUESTION_ENDING_PATTERN.containsMatchIn(followingText)) {
                return RuleResult.Invalid(
                    violationType = ViolationType.ACC_01_CAUSAL_SUBORDINATION,
                    reason = "이유 접속어미('$causalVerb') 뒤에 의문문('$followingText')이 결합되었습니다. (-니까/-니로 교체 필요)",
                    matchedSnippet = "$causalVerb $followingText"
                )
            }

            // 2. Imperative / Propositive check (e.g. "자", "십시오", "세요")
            if (IMPERATIVE_PROPOSITIVE_PATTERN.containsMatchIn(followingText)) {
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
}
