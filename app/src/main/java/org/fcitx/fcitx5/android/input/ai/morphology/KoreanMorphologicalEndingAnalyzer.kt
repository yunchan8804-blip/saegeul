/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.morphology

/**
 * Korean Morphological Ending Analyzer (EAI-03 / B22).
 *
 * Systematically decomposes Korean eojeol into stem and morphological endings,
 * resolving B22 (0 collected endings in chat/messenger without periods) across:
 * 1. Formal style (격식체): -습니다, -ㅂ니다, -습니까, -ㅂ니까, -시오, -소서, -십시오, -십시다
 * 2. Polite style (해요체): -어요, -아요, -여요, -해요, -세요, -게요, -ㄹ게요, -을게요, -지요, -죠,
 *    -네요, -군요, -대요, -래요, -거든요, -잖아요, -나요, -가요, -을까요, -ㄹ까요, -을텐데요
 * 3. Informal style (해체): -어, -아, -여, -해, -지, -네, -군, -구나, -구만, -자, -마, -어라, -아라,
 *    -어봐, -아봐, -을게, -ㄹ게, -을까, -ㄹ까, -는다, -ㄴ다, -다, -냐, -니, -란다, -단다, -거든, -잖아
 * 4. Colloquial / Chat style (구어/통신체): -어용, -아용, -했음, -음, -ㅁ, -임, -음요, -ㅁ요, -네용, -구요,
 *    -ㅋㅋ, -ㅎㅎ, -ㅠㅠ, -ㅜㅜ
 * 5. Pre-final endings (선어말어미 결합): -았/었/였-, -겠-, -시/으시-, -더-
 */
object KoreanMorphologicalEndingAnalyzer {

    // --- Hangul Syllable Constants ---
    private const val HANGUL_BASE = 0xAC00
    private const val HANGUL_END = 0xD7A3
    private const val MEDIAL_COUNT = 21
    private const val FINAL_COUNT = 28

    // Final consonants (받침 종성 인덱스)
    // 0: 없음, 4: ㄴ, 8: ㄹ, 16: ㅁ, 17: ㅂ, 20: ㅆ
    private const val FINAL_NONE = 0
    private const val FINAL_NIEUN = 4
    private const val FINAL_RIEUL = 8
    private const val FINAL_MIEUM = 16
    private const val FINAL_BIEUP = 17
    private const val FINAL_SSANGSIOT = 20

    private val TRAILING_SYMBOLS = setOf(
        '.', ',', '!', '?', '~', '^', ';', ':', '…',
        '。', '！', '？', '\"', '\'', '”', '’', ')', ']', '}', '〉', '》', '」', '』', '】'
    )

    /**
     * Known multi-syllable idiomatic and complex terminal endings.
     * Sorted descending by length to ensure longest-match priority.
     */
    private val COMPLEX_TERMINAL_ENDINGS = listOf(
        // High-level honorific / formal composite endings
        "부탁드립니다", "드리겠습니다", "부탁드려요",
        "을텐데요", "ㄹ텐데요",

        // Pre-final combinations: -시/으시-
        "으시겠습니까", "으시겠습니다", "으셨습니까", "으셨습니다",
        "시겠습니까", "시겠습니다", "셨습니까", "셨습니다",
        "으십시오", "으십시다", "으십니다", "으십니까",
        "하십시오", "하십시다", "하십니까",
        "으시네요", "으시지요", "으시군요", "으세요",
        "셨어요", "셨어용", "셨네용", "셨네요", "셨군요", "셨잖아", "셨지요", "셨어", "셨지", "셨네", "셨음",

        // Pre-final combinations: -겠-
        "하겠습니까", "하겠습니다", "하겠어요", "하겠다", "하겠어", "하겠지", "하겠네",
        "겠습니까", "겠습니다", "겠어요", "겠어용", "겠네용", "겠네요", "겠군요", "겠지요", "겠죠", "겠잖아", "겠거든",
        "겠다", "겠어", "겠지", "겠네", "겠군", "겠음", "겠음요", "겠냐", "겠니",

        // Pre-final combinations: -았/었/였-
        "하였습니다", "되었습니다", "드렸습니다", "드렸어요",
        "했습니다", "했습니까", "됐습니다", "됐습니까",
        "았습니까", "었습니까", "였습니까",
        "았습니다", "었습니다", "였습니다",
        "았어요", "었어요", "였어요", "했어요", "되었어요", "됐어요",
        "았어용", "었어용", "였어용", "했어용", "됐어용",
        "았네용", "었네용", "였네용", "했네용", "됐네용",
        "았구요", "었구요", "였구요", "했구요", "됐구요",
        "았네요", "었네요", "였네요", "했네요", "됐네요",
        "았군요", "었군요", "였군요", "했군요", "됐군요",
        "았지요", "었지요", "였지요", "했지요", "됐지요",
        "았잖아", "었잖아", "였잖아", "했잖아", "됐잖아",
        "았거든", "었거든", "였거든", "했거든", "됐거든",
        "았었어", "었었어",
        "았음요", "었음요", "였음요", "했음요", "됐음요",
        "았음", "었음", "였음", "했음", "됐음",
        "았죠", "었죠", "였죠", "했죠", "됐죠",
        "았어", "었어", "였어", "했어", "됐어",
        "았지", "었지", "였지", "했지", "됐지",
        "았네", "었네", "였네", "했네", "됐네",
        "았군", "었군", "였군", "했군", "됐군",
        "았구나", "었구나", "였구나", "했구나", "됐구나",
        "았다", "었다", "였다", "했다", "됐다",
        "았냐", "었냐", "였냐", "했냐", "됐냐",
        "았니", "었니", "였니", "했니", "됐니",

        // Pre-final combinations: -더-
        "하더라고요", "하더군요", "하던데요", "하더라",
        "더라고요", "더군요", "던데요", "더라", "더군", "더냐", "던가요", "던가",

        // Standard formal & polite (격식체 / 해요체)
        "드립니다", "드릴게요", "드릴까요", "드려요",
        "합니다", "합니까", "하세요", "하지요", "하네요", "하나요", "하구요", "하군요",
        "입니다", "입니까", "이네요", "인가요", "이군요", "이라네", "이네용", "이에요",
        "됩니다", "됩니까", "되네요", "되나요", "되구요", "되군요",
        "습니다", "습니까", "십시오", "십시다", "시오", "소서",
        "할까요", "할까", "할게요", "할게",
        "을까요", "ㄹ까요", "을게요", "ㄹ게요",
        "거예요", "거에요",
        "거든요", "잖아요", "네용", "구요", "대요", "래요", "군요",
        "네요", "지요", "세요", "어요", "아요", "여요", "해요", "돼요", "예요",
        "게요", "나요", "가요", "어용", "아용",

        // Informal / Panmal (해체)
        "어봐요", "아봐요", "해봐요", "봐요",
        "을까", "ㄹ까", "을게", "ㄹ게", "어봐", "아봐", "해봐", "어라", "아라", "해라", "봐라",
        "구만", "구나", "는다", "란다", "단다", "거든", "잖아",
        "먹자", "보자", "가자", "오자", "하자",

        // Causal / Connective & Honorific endings
        "어서요", "아서요", "여서요", "해서요",
        "어서", "아서", "여서",
        "으니요", "으니",

        // Colloquial / Chat (구어/통신체)
        "음요", "ㅁ요", "임다",
        "ㅋㅋ", "ㅎㅎ", "ㅠㅠ", "ㅜㅜ"
    ).sortedByDescending { it.length }



    /**
     * Single-syllable endings requiring careful stem context to avoid noun false-positives.
     */
    private val MONOSYLLABIC_ENDINGS = listOf(
        "어", "아", "여", "해", "지", "네", "군", "자", "마", "다", "냐", "니", "죠", "봐", "워", "음", "임", "용"
    )

    /**
     * Nouns ending with letters that frequently collide with verbal endings.
     * Prevents false positives such as "사과", "회의", "나무", "후보자", etc.
     */
    private val NOUN_EXCLUSIONS = setOf(
        // Collisions with '자' / '보자'
        "후보자", "초보자", "보호자", "피보호자", "기자", "환자", "모자", "상자", "부자", "당사자",
        "관계자", "소비자", "사용자", "근로자", "노동자", "투자자", "동업자", "기술자", "보행자", "탑승자", "참가자",
        // Collisions with '과' / '의' / '무'
        "사과", "회의", "나무", "직무", "업무", "의무", "근무", "결과", "학과", "효과", "치과",
        // Collisions with '사'
        "의사", "교사", "변호사", "판사", "감사", "기사", "식사", "검사", "조사", "행사", "인사", "역사", "회사",
        // Collisions with '음' / 'ㅁ'
        "마음", "처음", "얼음", "젊음", "믿음", "죽음", "걸음", "웃음", "울음", "모임", "게임", "그림", "기름",
        "이름", "사람", "바람", "구름", "보람", "주름", "거름", "흐름", "점심", "모습",
        // Collisions with '지' / '네' / '어' / '다'
        "메시지", "이미지", "패키지", "소시지", "마사지",
        "편지", "잡지", "휴지", "가지", "바지", "돼지", "토지", "양지", "음지",
        "동네", "시내", "막내",
        "단어", "상어", "문어", "악어", "언어", "국어", "영어", "외국어",
        "바다", "소다", "대화"
    )

    /**
     * Normalizes the input eojeol by trimming trailing punctuation, whitespace, and brackets.
     */
    fun normalize(text: String): String {
        var end = text.length
        while (end > 0) {
            val c = text[end - 1]
            if (!c.isWhitespace() && c !in TRAILING_SYMBOLS) break
            end -= 1
        }
        return text.substring(0, end).trim()
    }

    /**
     * Known ungrammatical / corrupted irregular verbal conjugations (e.g. 걷어서, 돕아서, 짓어서, 흐러서, 하얗아서, 푸어서, 하어서).
     */
    private val INVALID_IRREGULAR_PATTERNS = listOf(
        // ㄷ 불규칙 비문 (걷다, 듣다, 묻다)
        Regex("""[가-힣]*(?:걷어서|듣으니|묻어서|긷어서|듣어서|걷으니)"""),
        // ㅂ 불규칙 비문 (돕다, 춥다, 아름답다, 덥다, 고맙다, 가볍다, 무겁다, 쉽다, 어렵다)
        Regex("""[가-힣]*(?:돕아서|돕어서|춥아서|춥어서|아름답아서|아름답어서|덥아서|덥어서|고맙아서|고맙어서|가볍아서|가볍어서|무겁아서|무겁어서|쉽아서|쉽어서|어렵아서|어렵어서)"""),
        // ㅅ 불규칙 비문 (짓다, 낫다, 붓다, 잇다, 젓다)
        Regex("""[가-힣]*(?:짓어서|낫아서|붓어서|잇어서|젓어서)"""),
        // 르 불규칙 비문 (흐르다, 빠르다, 구르다, 모르다, 오르다)
        Regex("""[가-힣]*(?:흐러서|빠라서|구러서|모라서|오라서)"""),
        // ㅎ 불규칙 비문 (하얗다, 파랗다, 노랗다, 까맣다, 빨갛다)
        Regex("""[가-힣]*(?:하얗아서|파랗아서|노랗아서|까맣아서|빨갛아서|하얗어서|파랗어서)"""),
        // 우 불규칙 비문 (푸다)
        Regex("""[가-힣]*(?:푸어서|푸었다)"""),
        // 여 불규칙 비문 (하다)
        Regex("""[가-힣]*(?:하어서|하었다)""")
    )

    /**
     * Checks if the word is an ungrammatical / corrupted irregular verbal form.
     */
    fun isInvalidIrregularConjugation(word: String): Boolean {
        val normalized = normalize(word)
        return INVALID_IRREGULAR_PATTERNS.any { it.containsMatchIn(normalized) }
    }

    /**
     * Explicit mappings for irregular verbal conjugations to their canonical morphological endings.
     */
    private val IRREGULAR_ENDING_MAP = mapOf(
        // ㅂ 불규칙
        "도와서요" to "아서요",
        "도와서" to "아서",
        "도와요" to "아요",
        "도와" to "아",
        "추워서요" to "어서요",
        "추워서" to "어서",
        "추워요" to "어요",
        "아름다워서요" to "어서요",
        "아름다워서" to "어서",
        "아름다워요" to "어요",
        "고마워서요" to "어서요",
        "고마워서" to "어서",
        "더워서요" to "어서요",
        "더워서" to "어서",
        "가벼워서요" to "어서요",
        "가벼워서" to "어서",
        "무거워서요" to "어서요",
        "무거워서" to "어서",

        // 르 불규칙
        "흘러서요" to "어서요",
        "흘러서" to "어서",
        "흘러요" to "어요",
        "빨라서요" to "아서요",
        "빨라서" to "아서",
        "빨라요" to "아요",
        "굴러서요" to "어서요",
        "굴러서" to "어서",
        "몰라서요" to "아서요",
        "몰라서" to "아서",
        "올라서요" to "아서요",
        "올라서" to "아서",

        // ㅎ 불규칙
        "하얘서요" to "아서요",
        "하얘서" to "아서",
        "하얘요" to "아요",
        "파래서요" to "아서요",
        "파래서" to "아서",
        "파래요" to "아요",
        "노래서요" to "아서요",
        "노래서" to "아서",
        "까매서요" to "아서요",
        "까매서" to "아서",
        "빨개서요" to "아서요",
        "빨개서" to "아서",

        // 우 불규칙
        "퍼서요" to "어서요",
        "퍼서" to "어서",
        "퍼요" to "어요",

        // 여 불규칙
        "해서요" to "여서요",
        "해서" to "여서",
        "하여서" to "여서",

        // ㄷ 불규칙
        "들으니요" to "으니요",
        "들으니" to "으니",
        "걸으니요" to "으니요",
        "걸으니" to "으니"
    )

    /**
     * Extracts canonical morphological endings from irregular verbal inflections (ㅂ, 르, ㅎ, 우, 여, ㄷ).
     */
    private fun extractIrregularEnding(normalized: String): String? {
        // Direct map lookup (longest match)
        IRREGULAR_ENDING_MAP[normalized]?.let { return it }

        // Suffix matches for longer compound verbs
        for ((surface, canonical) in IRREGULAR_ENDING_MAP) {
            if (normalized.endsWith(surface) && normalized.length > surface.length) {
                return canonical
            }
        }

        // Morphophonological heuristic suffix rules:
        // ㅂ 불규칙: ...워서요 -> "어서요", ...워서 -> "어서"
        if (normalized.endsWith("워서요") && normalized.length >= 3) return "어서요"
        if (normalized.endsWith("워서") && normalized.length >= 2) return "어서"

        // 르 불규칙: ...러서요 / ...러서 (when preceded by 'ㄹ' coda: 흘러서, 굴러서)
        if (normalized.endsWith("러서요") && normalized.length >= 4) {
            val stemChar = normalized[normalized.length - 4]
            if (hasBatchim(stemChar, FINAL_RIEUL)) return "어서요"
        }
        if (normalized.endsWith("러서") && normalized.length >= 3) {
            val stemChar = normalized[normalized.length - 3]
            if (hasBatchim(stemChar, FINAL_RIEUL)) return "어서"
        }
        // ...라서요 / ...라서 (when preceded by 'ㄹ' coda: 빨라서, 날라서)
        if (normalized.endsWith("라서요") && normalized.length >= 4) {
            val stemChar = normalized[normalized.length - 4]
            if (hasBatchim(stemChar, FINAL_RIEUL)) return "아서요"
        }
        if (normalized.endsWith("라서") && normalized.length >= 3) {
            val stemChar = normalized[normalized.length - 3]
            if (hasBatchim(stemChar, FINAL_RIEUL)) return "아서"
        }

        // ㅎ 불규칙: ...얘서요 -> "아서요", ...얘서 -> "아서", ...래서요 -> "아서요", ...래서 -> "아서"
        if (normalized.endsWith("얘서요")) return "아서요"
        if (normalized.endsWith("얘서")) return "아서"
        if (normalized.endsWith("래서요")) return "아서요"
        if (normalized.endsWith("래서")) return "아서"

        return null
    }

    /**
     * Extracts the terminal ending from a Korean eojeol or sentence fragment.
     * Returns null if the eojeol does not terminate with an identifiable verbal ending or chat marker.
     */
    fun extractEnding(eojeol: String): String? {
        val normalized = normalize(eojeol)
        if (normalized.isEmpty()) return null

        // 0. Ungrammatical irregular check
        if (isInvalidIrregularConjugation(normalized)) {
            return null
        }

        // 1. Pure colloquial emoticons
        if (normalized == "ㅋㅋ" || normalized == "ㅎㅎ" || normalized == "ㅠㅠ" || normalized == "ㅜㅜ" ||
            normalized.endsWith("ㅋㅋ") || normalized.endsWith("ㅎㅎ") ||
            normalized.endsWith("ㅠㅠ") || normalized.endsWith("ㅜㅜ")
        ) {
            return when {
                normalized.endsWith("ㅋㅋ") -> "ㅋㅋ"
                normalized.endsWith("ㅎㅎ") -> "ㅎㅎ"
                normalized.endsWith("ㅠㅠ") -> "ㅠㅠ"
                else -> "ㅜㅜ"
            }
        }

        // 2. Exact noun exclusions
        if (normalized in NOUN_EXCLUSIONS) {
            return null
        }

        // 2.5 Irregular verbal conjugation endings (ㅂ/르/ㅎ/우/여/ㄷ 불규칙 등)
        val irregularEnding = extractIrregularEnding(normalized)
        if (irregularEnding != null) {
            return irregularEnding
        }

        // 3. Phonological batchim-combined endings (-ㄹ게요, -ㄹ게, -ㄹ까요, -ㄹ까, -ㅂ니다, -ㅂ니까, -ㄴ다)
        val phonologicalEnding = extractBatchimPromotionEnding(normalized)
        if (phonologicalEnding != null) {
            return phonologicalEnding
        }

        // 4. Multi-syllable explicit endings (Longest Match)
        for (ending in COMPLEX_TERMINAL_ENDINGS) {
            if (normalized.endsWith(ending)) {
                if (ending == "보자" && NOUN_EXCLUSIONS.any { normalized.endsWith(it) }) {
                    continue
                }
                return ending
            }
        }

        // 5. Monosyllabic endings (-어, -아, -여, -해, -지, -네, -군, -자, -마, -다, -냐, -니, -죠, -봐, -워, -음, -임, -ㅁ)
        val batchimMorpheme = extractBatchimMorpheme(normalized)
        if (batchimMorpheme != null) {
            return batchimMorpheme
        }

        if (normalized.length >= 2) {
            val lastChar = normalized.last().toString()
            if (lastChar in MONOSYLLABIC_ENDINGS) {
                // Check if the whole normalized word or its suffix is an excluded noun
                if (NOUN_EXCLUSIONS.none { normalized.endsWith(it) }) {
                    return lastChar
                }
            }
        }

        return null
    }

    /**
     * Checks whether the given eojeol forms a sentence-terminal boundary.
     */
    fun isSentenceTerminal(eojeol: String): Boolean {
        return extractEnding(eojeol) != null
    }

    /**
     * Promotes batchim-combined verbal endings when preceded by a stem ending in the relevant batchim.
     */
    private fun extractBatchimPromotionEnding(normalized: String): String? {
        if (normalized.length < 2) return null

        // -ㄹ게요 / -ㄹ게 (exclude explicit -을게요, -할게요, -을게, -할게)
        if (normalized.endsWith("게요") && normalized.length >= 3) {
            if (!normalized.endsWith("을게요") && !normalized.endsWith("할게요") && !normalized.endsWith("드릴게요")) {
                val prevChar = normalized[normalized.length - 3]
                if (hasBatchim(prevChar, FINAL_RIEUL)) return "ㄹ게요"
            }
        }
        if (normalized.endsWith("게") && normalized.length >= 2) {
            if (!normalized.endsWith("을게") && !normalized.endsWith("할게") && !normalized.endsWith("드릴게")) {
                val prevChar = normalized[normalized.length - 2]
                if (hasBatchim(prevChar, FINAL_RIEUL)) return "ㄹ게"
            }
        }

        // -ㄹ까요 / -ㄹ까 (exclude explicit -을까요, -할까요, -을까, -할까)
        if (normalized.endsWith("까요") && normalized.length >= 3) {
            if (!normalized.endsWith("을까요") && !normalized.endsWith("할까요") && !normalized.endsWith("드릴까요")) {
                val prevChar = normalized[normalized.length - 3]
                if (hasBatchim(prevChar, FINAL_RIEUL)) return "ㄹ까요"
            }
        }
        if (normalized.endsWith("까") && normalized.length >= 2) {
            if (!normalized.endsWith("을까") && !normalized.endsWith("할까") && !normalized.endsWith("드릴까")) {
                val prevChar = normalized[normalized.length - 2]
                if (hasBatchim(prevChar, FINAL_RIEUL)) return "ㄹ까"
            }
        }

        // -ㅂ니다 / -ㅂ니까 (exclude explicit -합니다, -입니다, -됩니다, -드립니다, -습니다)
        if (normalized.endsWith("니다") && normalized.length >= 3) {
            if (!normalized.endsWith("합니다") && !normalized.endsWith("입니다") &&
                !normalized.endsWith("됩니다") && !normalized.endsWith("드립니다") &&
                !normalized.endsWith("습니다")
            ) {
                val prevChar = normalized[normalized.length - 3]
                if (hasBatchim(prevChar, FINAL_BIEUP)) return "ㅂ니다"
            }
        }
        if (normalized.endsWith("니까") && normalized.length >= 3) {
            if (!normalized.endsWith("합니까") && !normalized.endsWith("입니까") &&
                !normalized.endsWith("됩니까") && !normalized.endsWith("드립니다") &&
                !normalized.endsWith("습니까")
            ) {
                val prevChar = normalized[normalized.length - 3]
                if (hasBatchim(prevChar, FINAL_BIEUP)) return "ㅂ니까"
            }
        }

        // -ㄴ다 (exclude explicit -한다, -된다)
        if (normalized.endsWith("다") && normalized.length >= 2) {
            if (!normalized.endsWith("한다") && !normalized.endsWith("된다") && !normalized.endsWith("았다") &&
                !normalized.endsWith("었다") && !normalized.endsWith("였다") && !normalized.endsWith("겠다") &&
                !normalized.endsWith("는다") && !normalized.endsWith("란다") && !normalized.endsWith("단다")
            ) {
                val prevChar = normalized[normalized.length - 2]
                if (hasBatchim(prevChar, FINAL_NIEUN)) return "ㄴ다"
            }
        }

        return null
    }

    /**
     * Extracts -ㅁ / -ㅁ요 / -음 / -임 morphemes.
     */
    private fun extractBatchimMorpheme(normalized: String): String? {
        if (normalized.length < 2) return null
        val lastChar = normalized.last()

        if (normalized.endsWith("요") && normalized.length >= 2) {
            val prevChar = normalized[normalized.length - 2]
            if (hasBatchim(prevChar, FINAL_MIEUM) && NOUN_EXCLUSIONS.none { normalized.endsWith(it) }) {
                return "ㅁ요"
            }
        }

        if (hasBatchim(lastChar, FINAL_MIEUM)) {
            if (NOUN_EXCLUSIONS.none { normalized.endsWith(it) }) {
                return if (lastChar == '음') "음" else if (lastChar == '임') "임" else "ㅁ"
            }
        }

        return null
    }


    private fun isHangulSyllable(c: Char): Boolean {
        return c.code in HANGUL_BASE..HANGUL_END
    }

    private fun getFinalIndex(c: Char): Int {
        if (!isHangulSyllable(c)) return -1
        return (c.code - HANGUL_BASE) % FINAL_COUNT
    }

    private fun hasBatchim(c: Char, batchimIndex: Int): Boolean {
        return getFinalIndex(c) == batchimIndex
    }
}
