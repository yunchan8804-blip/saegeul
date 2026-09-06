/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import kotlin.math.min

/**
 * Intelligent Realtime Korean Typo Correction Engine for Saegeul Keyboard.
 * Resolves 2-Beolsik adjacent-key typos, jaso-level edit distance errors,
 * colloquial endings, and frequent Korean spelling confusions with sub-millisecond latency.
 */
class KoreanTypoCorrectionEngine(
    private val extraLexicon: List<String> = emptyList()
) {

    private val commonTypoDictionary = mapOf(
        "오눌" to "오늘",
        "오뉼" to "오늘",
        "오놀" to "오늘",
        "내올" to "내일",
        "판고" to "판교",
        "판규" to "판교",
        "안되요" to "안돼요",
        "되요" to "돼요",
        "시프지" to "싶은지",
        "시프니까" to "싶으니까",
        "시프면" to "싶으면",
        "시퍼서" to "싶어서",
        "시펐어" to "싶었어",
        "시펐어요" to "싶었어요",
        "시퍼" to "싶어",
        "시퍼요" to "싶어요",
        "시프다" to "싶다",
        "시픈" to "싶은",
        "시픈데" to "싶은데",
        "시플" to "싶을",
        "하구" to "하고",
        "머하구" to "뭐하고",
        "머하고" to "뭐하고",
        "머해" to "뭐해",
        "머야" to "뭐야",
        "머선" to "뭐선",
        "마싯어" to "맛있어",
        "마싯" to "맛있",
        "재밋어" to "재밌어",
        "재밋" to "재밌",
        "어뜨케" to "어떻게",
        "어떠케" to "어떻게",
        "어케" to "어떻게",
        "글구" to "그리고",
        "근뎅" to "그런데",
        "인제" to "이제",
        "갈께" to "갈게",
        "할께" to "할게",
        "잇어" to "있어",
        "잇어요" to "있어요",
        "잇슴" to "있음",
        "잇는" to "있는",
        "업서" to "없어",
        "업서요" to "없어요",
        "없슴" to "없음",
        "갓어" to "갔어",
        "봣어" to "봤어",
        "됫" to "됐",
        "됫어" to "됐어",
        "됫어요" to "됐어요",
        "안되" to "안돼",
        "되" to "돼",
        "됬" to "됐",
        "안대" to "안 돼",
        "안대요" to "안 돼요",
        "뵈요" to "봬요",
        "바램" to "바람",
        "일부려" to "일부러",
        "알겟어" to "알겠어",
        "알겟습니다" to "알겠습니다",
        "모르겟어" to "모르겠어",
        "마자" to "맞아",
        "마자요" to "맞아요",
        "감사합니더" to "감사합니다",
        "반갑습니더" to "반갑습니다"
    )

    // Suffix rules for auxiliary verb '-고 싶다' (phonetic typos like 시프-, 시퍼-, 시픈-, 시플-, 시펐-)
    // Sorted by descending length so longer suffixes match first (e.g. "시프니까" before "시프")
    private val sipSuffixRules = listOf(
        "시프니까" to "싶으니까",
        "시프니" to "싶으니",
        "시프면요" to "싶으면요",
        "시프면" to "싶으면",
        "시픈데요" to "싶은데요",
        "시픈데" to "싶은데",
        "시프지" to "싶은지",
        "시프지만" to "싶지만",
        "시픈가" to "싶은가",
        "시픈걸" to "싶은걸",
        "시프므로" to "싶으므로",
        "시플까" to "싶을까",
        "시플텐데" to "싶을텐데",
        "시플때" to "싶을 때",
        "시펐어요" to "싶었어요",
        "시펐는데" to "싶었는데",
        "시펐어" to "싶었어",
        "시펐다" to "싶었다",
        "시퍼요" to "싶어요",
        "시퍼서" to "싶어서",
        "시퍼" to "싶어",
        "시프다" to "싶다",
        "시픈" to "싶은",
        "시플" to "싶을",
        "시픔" to "싶음"
    )

    private val standardDictionary: Set<String> by lazy {
        (listOf(
            "안녕하세요", "감사합니다", "고맙습니다", "반갑습니다", "오늘", "내일", "모레", "어제",
            "판교", "강남", "홍대", "성수", "여의도", "종로", "신촌", "잠실", "광화문",
            "회의", "미팅", "배포", "일정", "약속", "시간", "확인했습니다", "부탁드립니다",
            "지금", "맛있어", "뭐해", "안돼요", "어떻게", "도착했습니다", "수고하셨습니다",
            "축하드립니다", "알겠습니다", "죄송합니다", "연락드리겠습니다", "진행하겠습니다",
            "내용", "자료", "정리", "공유", "검토", "보고", "작업", "문서", "코드", "확인",
            "생각", "말씀", "의견", "질문", "답변", "도움", "식사", "점심", "저녁", "커피",
            "싶으니까", "싶은지", "싶어요", "싶어", "싶다", "싶으면", "싶은데", "싶어서", "싶었어"
        ) + extraLexicon).toSet()
    }

    // 2-Beolsik Adjacent Jaso Graph
    private val adjacentJaso = mapOf(
        'ㅂ' to setOf('ㅈ', 'ㅁ'),
        'ㅈ' to setOf('ㅂ', 'ㄷ', 'ㄴ'),
        'ㄷ' to setOf('ㅈ', 'ㄱ', 'ㅇ'),
        'ㄱ' to setOf('ㄷ', 'ㅅ', 'ㄹ'),
        'ㅅ' to setOf('ㄱ', 'ㅎ', 'ㅛ'),
        'ㅛ' to setOf('ㅅ', 'ㅕ', 'ㅗ'),
        'ㅕ' to setOf('ㅛ', 'ㅑ', 'ㅓ'),
        'ㅑ' to setOf('ㅕ', 'ㅐ', 'ㅏ'),
        'ㅐ' to setOf('ㅑ', 'ㅔ', 'ㅣ'),
        'ㅔ' to setOf('ㅐ'),
        'ㅁ' to setOf('ㅂ', 'ㄴ', 'ㅋ'),
        'ㄴ' to setOf('ㅁ', 'ㅇ', 'ㅌ', 'ㅈ'),
        'ㅇ' to setOf('ㄴ', 'ㄹ', 'ㅊ', 'ㄷ'),
        'ㄹ' to setOf('ㅇ', 'ㅎ', 'ㅍ', 'ㄱ'),
        'ㅎ' to setOf('ㄹ', 'ㅗ', 'ㅠ', 'ㅅ'),
        'ㅗ' to setOf('ㅎ', 'ㅓ', 'ㅛ', 'ㅜ'),
        'ㅓ' to setOf('ㅗ', 'ㅏ', 'ㅕ', 'ㅡ'),
        'ㅏ' to setOf('ㅓ', 'ㅣ', 'ㅑ'),
        'ㅣ' to setOf('ㅏ', 'ㅐ'),
        'ㅋ' to setOf('ㅁ', 'ㅌ'),
        'ㅌ' to setOf('ㅋ', 'ㅊ', 'ㄴ'),
        'ㅊ' to setOf('ㅌ', 'ㅍ', 'ㅇ'),
        'ㅍ' to setOf('ㅊ', 'ㅠ', 'ㄹ'),
        'ㅠ' to setOf('ㅍ', 'ㅜ', 'ㅎ'),
        'ㅜ' to setOf('ㅠ', 'ㅡ', 'ㅗ'),
        'ㅡ' to setOf('ㅜ', 'ㅓ')
    )

    fun hasExplicitTypo(rawWord: String): Boolean {
        val word = rawWord.trim()
        return commonTypoDictionary.containsKey(word) ||
            normalizeEnding(word) != null ||
            normalizeMorphologicalTypo(word) != null
    }

    fun correct(rawWord: String, limit: Int = 3): List<String> {
        val word = rawWord.trim()
        if (word.length < 2) return emptyList()

        // Fast-path: If word is already a valid standard word and not a known typo, it needs no correction
        if (standardDictionary.contains(word) && !hasExplicitTypo(word)) {
            return emptyList()
        }

        // 0. QWERTY-to-Hangul conversion for English mistypes (e.g. 'dhsnf' -> '오눌' -> '오늘')
        val isAscii = word.all { (it in 'a'..'z') || (it in 'A'..'Z') }
        if (isAscii) {
            val hangul = qwertyToHangul(word)
            if (hangul.isNotEmpty() && hangul != word) {
                val hangulCorrections = correct(hangul, limit)
                val results = LinkedHashSet<String>()
                results.addAll(hangulCorrections)
                if (standardDictionary.contains(hangul)) {
                    results.add(hangul)
                }
                if (results.isNotEmpty()) {
                    return results.take(limit)
                }
            }
        }

        val results = LinkedHashSet<String>()

        // 1. Direct typo dictionary mapping
        commonTypoDictionary[word]?.let { results.add(it) }

        // 2. Morphological typo normalization (sipSuffix, ㄹ께 -> ㄹ게, 됬 -> 됐, etc.)
        normalizeMorphologicalTypo(word)?.let { results.add(it) }

        // 3. Colloquial / Incomplete Verb/Adjective Ending Normalization
        normalizeEnding(word)?.let { results.add(it) }

        // 3. Jaso-Level Fuzzy / Adjacent Distance Matching against Standard Lexicon
        val inputJaso = decomposeToJaso(word)

        val bestMatches = standardDictionary.mapNotNull { targetWord ->
            if (targetWord == word) return@mapNotNull null
            // Optimization: skip words with large length differences
            if (kotlin.math.abs(targetWord.length - word.length) > 1) return@mapNotNull null

            val targetJaso = decomposeToJaso(targetWord)
            val dist = jasoEditDistance(inputJaso, targetJaso)
            if (dist <= 2.2f) {
                targetWord to dist
            } else {
                null
            }
        }.sortedBy { it.second }

        bestMatches.forEach { results.add(it.first) }

        return results.take(limit)
    }

    private fun jasoEditDistance(s1: String, s2: String): Float {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { FloatArray(n + 1) }

        for (i in 0..m) dp[i][0] = i * 1.0f
        for (j in 0..n) dp[0][j] = j * 1.0f

        for (i in 1..m) {
            val c1 = s1[i - 1]
            for (j in 1..n) {
                val c2 = s2[j - 1]
                val cost = if (c1 == c2) {
                    0f
                } else if (isAdjacent(c1, c2)) {
                    0.5f // Adjacent keyboard keys have a much lower penalty
                } else {
                    1.0f
                }
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1.0f, dp[i][j - 1] + 1.0f),
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[m][n]
    }

    private fun isAdjacent(c1: Char, c2: Char): Boolean {
        return adjacentJaso[c1]?.contains(c2) == true || adjacentJaso[c2]?.contains(c1) == true
    }

    fun normalizeEnding(word: String): String? {
        return when {
            word.endsWith("세여") -> word.dropLast(2) + "세요"
            word.endsWith("네여") -> word.dropLast(2) + "네요"
            word.endsWith("게여") -> word.dropLast(2) + "게요"
            word.endsWith("데여") -> word.dropLast(2) + "데요"
            word.endsWith("합닏") -> word.dropLast(2) + "합니다"
            word.endsWith("습닏") -> word.dropLast(2) + "습니다"
            word.endsWith("임닏") -> word.dropLast(2) + "입니다"
            word.endsWith("함니다") -> word.dropLast(3) + "합니다"
            word.endsWith("임니다") -> word.dropLast(3) + "입니다"
            else -> null
        }
    }

    /**
     * Systematic morphological, phonological, and ending typo normalizer.
     * Handles auxiliary verbs (-고 싶다 conjugation typos like 시프니까, 시프면, 시퍼서, 시펐어),
     * promise/future endings (-ㄹ께 -> -ㄹ게), and non-standard jaso syllables (됬 -> 됐, 됫 -> 됐, 됀 -> 된).
     */
    fun normalizeMorphologicalTypo(rawWord: String): String? {
        val word = rawWord.trim()
        if (word.length < 2) return null

        // 1. -고 싶다 phonetic suffix rules
        for ((typoSuffix, targetSuffix) in sipSuffixRules) {
            if (word == typoSuffix) {
                return targetSuffix
            }
            if (word.endsWith(typoSuffix)) {
                val prefix = word.dropLast(typoSuffix.length)
                return if (prefix.endsWith("고")) {
                    "$prefix $targetSuffix"
                } else {
                    "$prefix$targetSuffix"
                }
            }
        }

        // 2. 종성 ㄹ + 께 / 께요 -> 게 / 게요 (한글 맞춤법 제53항: -(으)ㄹ게)
        if (word.endsWith("께요") && word.length >= 3) {
            val prevChar = word[word.length - 3]
            if (prevChar in '\uAC00'..'\uD7A3' && (prevChar.code - 0xAC00) % 28 == 8) {
                return word.dropLast(2) + "게요"
            }
        } else if (word.endsWith("께") && word.length >= 2) {
            val prevChar = word[word.length - 2]
            if (prevChar in '\uAC00'..'\uD7A3' && (prevChar.code - 0xAC00) % 28 == 8) {
                return word.dropLast(1) + "게"
            }
        }

        // 3. 됬 -> 됐 (비표준 음절 교정)
        if (word.contains("됬")) {
            return word.replace("됬", "됐")
        }

        // 4. 됫 -> 됐
        if (word.contains("됫")) {
            return word.replace("됫", "됐")
        }

        // 5. 됀 -> 된
        if (word.contains("됀")) {
            return word.replace("됀", "된")
        }

        // 6. 않되 / 않돼 -> 안 돼
        if (word.startsWith("않되") || word.startsWith("않돼")) {
            val rest = word.drop(2)
            val normalizedRest = if (rest.startsWith("되")) "돼" + rest.drop(1) else rest
            return if (normalizedRest.isEmpty()) "안 돼" else "안 $normalizedRest"
        }

        return null
    }

    /**
     * Finds pairs of (typoWord to correctedWord) for words within [rawSentence].
     */
    fun findTypoCorrectionsInSentence(rawSentence: String): List<Pair<String, String>> {
        val trimmed = rawSentence.trim()
        if (trimmed.isEmpty()) return emptyList()

        val tokens = trimmed.split(Regex("\\s+"))
        val pairs = mutableListOf<Pair<String, String>>()

        tokens.forEach { token ->
            val trailingPunctuation = token.takeLastWhile { it in ".,!?:;~" }
            val coreWord = if (trailingPunctuation.isNotEmpty()) token.dropLast(trailingPunctuation.length) else token

            val correction = commonTypoDictionary[coreWord]
                ?: normalizeMorphologicalTypo(coreWord)
                ?: normalizeEnding(coreWord)
            if (correction != null && correction != coreWord) {
                pairs.add(coreWord to correction)
            }
        }
        return pairs
    }

    /**
     * Scans all words in [rawSentence] and returns a corrected sentence if any typos were replaced.
     * Returns null if no typos were found/replaced.
     */
    fun correctSentence(rawSentence: String): String? {
        val trimmed = rawSentence.trim()
        if (trimmed.isEmpty()) return null

        val tokens = trimmed.split(Regex("\\s+"))
        var replacedAny = false

        val correctedTokens = tokens.map { token ->
            val trailingPunctuation = token.takeLastWhile { it in ".,!?:;~" }
            val coreWord = if (trailingPunctuation.isNotEmpty()) token.dropLast(trailingPunctuation.length) else token

            val correction = commonTypoDictionary[coreWord]
                ?: normalizeMorphologicalTypo(coreWord)
                ?: normalizeEnding(coreWord)
            if (correction != null && correction != coreWord) {
                replacedAny = true
                correction + trailingPunctuation
            } else {
                token
            }
        }

        return if (replacedAny) {
            correctedTokens.joinToString(" ")
        } else {
            null
        }
    }

    fun calculateReplacementOverlap(beforeCursor: String, candidate: String): Int {
        if (beforeCursor.isEmpty() || candidate.isEmpty()) return 0

        val trimmedBefore = beforeCursor.trimEnd()
        val trailingSpaces = beforeCursor.length - trimmedBefore.length
        if (trimmedBefore.isEmpty()) return 0

        // 0. Full sentence or clause typo correction match
        val correctedBefore = correctSentence(trimmedBefore)
        if (correctedBefore != null && (candidate == correctedBefore || candidate.startsWith(correctedBefore))) {
            return trimmedBefore.length + trailingSpaces
        }

        val lastClause = trimmedBefore
            .substringAfterLast('.')
            .substringAfterLast('?')
            .substringAfterLast('!')
            .substringAfterLast('\n')
            .trim()
        if (lastClause.isNotEmpty() && lastClause != trimmedBefore) {
            val correctedClause = correctSentence(lastClause)
            if (correctedClause != null && (candidate == correctedClause || candidate.startsWith(correctedClause))) {
                return lastClause.length + trailingSpaces
            }
        }

        val lastWord = trimmedBefore
            .substringAfterLast(' ')
            .substringAfterLast('\n')
            .substringAfterLast('\t')
            .substringAfterLast('\r')
            .ifEmpty { trimmedBefore }
        val isCandidateSingleWord = !candidate.contains(" ")

        // 1. Check if lastWord is a typo of candidate (or candidate is a typo correction for lastWord)
        if (isCandidateSingleWord && lastWord.isNotBlank() && lastWord != candidate) {
            val corrections = correct(lastWord)
            if (corrections.contains(candidate)) {
                return lastWord.length + trailingSpaces
            }
        }

        // 1-B. Multi-word or sentence candidate completion for context containing a typo in lastWord
        // e.g. beforeCursor = "뭘 하고 시프지", candidate = "뭘 하고 싶은지 모르겠어."
        if (!isCandidateSingleWord && lastWord.isNotBlank()) {
            val corrections = correct(lastWord)
            for (corr in corrections) {
                val normalizedBefore = trimmedBefore.dropLast(lastWord.length) + corr
                if (candidate.startsWith(normalizedBefore)) {
                    return trimmedBefore.length + trailingSpaces
                }
                if (lastClause.isNotEmpty() && lastClause != trimmedBefore) {
                    val normalizedClause = lastClause.dropLast(lastWord.length) + corr
                    if (candidate.startsWith(normalizedClause)) {
                        return lastClause.length + trailingSpaces
                    }
                }
            }
        }

        // 2. Standard longest suffix/prefix overlap match
        for (i in candidate.length downTo 1) {
            val prefix = candidate.substring(0, i).trimEnd()
            if (prefix.isNotEmpty() && trimmedBefore.endsWith(prefix)) {
                return prefix.length + trailingSpaces
            }
        }

        return 0
    }

    companion object {
        private const val HANGUL_BASE = 0xAC00
        private const val HANGUL_END = 0xD7A3

        private val CHOSEONG = charArrayOf(
            'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
            'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
        )
        private val JUNGSEONG = charArrayOf(
            'ㅏ', 'ㅐ', 'ㅑ', 'ㅒ', 'ㅓ', 'ㅔ', 'ㅕ', 'ㅖ', 'ㅗ', 'ㅘ',
            'ㅙ', 'ㅚ', 'ㅛ', 'ㅜ', 'ㅝ', 'ㅞ', 'ㅟ', 'ㅠ', 'ㅡ', 'ㅢ', 'ㅣ'
        )
        private val JONGSEONG = charArrayOf(
            '\u0000', 'ㄱ', 'ㄲ', 'ㄳ', 'ㄴ', 'ㄵ', 'ㄶ', 'ㄷ', 'ㄹ', 'ㄺ',
            'ㄻ', 'ㄼ', 'ㄽ', 'ㄾ', 'ㄿ', 'ㅀ', 'ㅁ', 'ㅂ', 'ㅄ', 'ㅅ',
            'ㅆ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
        )

        private val qwertyToJamoMap = mapOf(
            'r' to 'ㄱ', 'R' to 'ㄲ', 's' to 'ㄴ', 'e' to 'ㄷ', 'E' to 'ㄸ',
            'f' to 'ㄹ', 'a' to 'ㅁ', 'q' to 'ㅂ', 'Q' to 'ㅃ', 't' to 'ㅅ',
            'T' to 'ㅆ', 'd' to 'ㅇ', 'w' to 'ㅈ', 'W' to 'ㅉ', 'c' to 'ㅊ',
            'z' to 'ㅋ', 'x' to 'ㅌ', 'v' to 'ㅍ', 'g' to 'ㅎ',
            'k' to 'ㅏ', 'o' to 'ㅐ', 'i' to 'ㅑ', 'O' to 'ㅒ', 'j' to 'ㅓ',
            'p' to 'ㅔ', 'u' to 'ㅕ', 'P' to 'ㅖ', 'h' to 'ㅗ', 'y' to 'ㅛ',
            'n' to 'ㅜ', 'b' to 'ㅠ', 'm' to 'ㅡ', 'l' to 'ㅣ'
        )

        private val doubleVowelMap = mapOf(
            ('ㅗ' to 'ㅏ') to 'ㅘ', ('ㅗ' to 'ㅐ') to 'ㅙ', ('ㅗ' to 'ㅣ') to 'ㅚ',
            ('ㅜ' to 'ㅓ') to 'ㅝ', ('ㅜ' to 'ㅔ') to 'ㅞ', ('ㅜ' to 'ㅣ') to 'ㅟ',
            ('ㅡ' to 'ㅣ') to 'ㅢ'
        )

        private val doubleJongseongMap = mapOf(
            ('ㄱ' to 'ㅅ') to 'ㄳ', ('ㄴ' to 'ㅈ') to 'ㄵ', ('ㄴ' to 'ㅎ') to 'ㄶ',
            ('ㄹ' to 'ㄱ') to 'ㄺ', ('ㄹ' to 'ㅁ') to 'ㄻ', ('ㄹ' to 'ㅂ') to 'ㄼ',
            ('ㄹ' to 'ㅅ') to 'ㄽ', ('ㄹ' to 'ㅌ') to 'ㄾ', ('ㄹ' to 'ㅍ') to 'ㄿ',
            ('ㄹ' to 'ㅎ') to 'ㅀ', ('ㅂ' to 'ㅅ') to 'ㅄ'
        )

        private val splitJongseongMap = mapOf(
            'ㄳ' to ('ㄱ' to 'ㅅ'), 'ㄵ' to ('ㄴ' to 'ㅈ'), 'ㄶ' to ('ㄴ' to 'ㅎ'),
            'ㄺ' to ('ㄹ' to 'ㄱ'), 'ㄻ' to ('ㄹ' to 'ㅁ'), 'ㄼ' to ('ㄹ' to 'ㅂ'),
            'ㄽ' to ('ㄹ' to 'ㅅ'), 'ㄾ' to ('ㄹ' to 'ㅌ'), 'ㄿ' to ('ㄹ' to 'ㅍ'),
            'ㅀ' to ('ㄹ' to 'ㅎ'), 'ㅄ' to ('ㅂ' to 'ㅅ')
        )

        fun qwertyToHangul(qwerty: String): String {
            val sb = StringBuilder()
            var cho: Char? = null
            var jung: Char? = null
            var jong: Char? = null

            fun flush() {
                if (cho != null && jung != null) {
                    val cIdx = CHOSEONG.indexOf(cho!!)
                    val uIdx = JUNGSEONG.indexOf(jung!!)
                    val jIdx = if (jong != null) JONGSEONG.indexOf(jong!!) else 0
                    if (cIdx >= 0 && uIdx >= 0 && jIdx >= 0) {
                        sb.append((HANGUL_BASE + (cIdx * 21 + uIdx) * 28 + jIdx).toChar())
                    } else {
                        sb.append(cho).append(jung)
                        if (jong != null) sb.append(jong)
                    }
                } else {
                    if (cho != null) sb.append(cho)
                    if (jung != null) sb.append(jung)
                    if (jong != null) sb.append(jong)
                }
                cho = null
                jung = null
                jong = null
            }

            for (ch in qwerty) {
                val jamo = qwertyToJamoMap[ch]
                if (jamo == null) {
                    flush()
                    sb.append(ch)
                    continue
                }

                val isVowel = jamo in 'ㅏ'..'ㅣ'
                if (!isVowel) {
                    if (jung == null) {
                        if (cho == null) {
                            cho = jamo
                        } else {
                            flush()
                            cho = jamo
                        }
                    } else {
                        if (jong == null) {
                            if (JONGSEONG.contains(jamo)) {
                                jong = jamo
                            } else {
                                flush()
                                cho = jamo
                            }
                        } else {
                            val combined = doubleJongseongMap[jong!! to jamo]
                            if (combined != null) {
                                jong = combined
                            } else {
                                flush()
                                cho = jamo
                            }
                        }
                    }
                } else {
                    if (cho == null) {
                        if (jung == null) {
                            jung = jamo
                        } else {
                            val combined = doubleVowelMap[jung!! to jamo]
                            if (combined != null) {
                                jung = combined
                            } else {
                                flush()
                                jung = jamo
                            }
                        }
                    } else if (jung == null) {
                        jung = jamo
                    } else if (jong != null) {
                        val split = splitJongseongMap[jong!!]
                        if (split != null) {
                            jong = split.first
                            val nextCho = split.second
                            flush()
                            cho = nextCho
                            jung = jamo
                        } else {
                            val nextCho = jong
                            jong = null
                            flush()
                            cho = nextCho
                            jung = jamo
                        }
                    } else {
                        val combined = doubleVowelMap[jung!! to jamo]
                        if (combined != null) {
                            jung = combined
                        } else {
                            flush()
                            jung = jamo
                        }
                    }
                }
            }
            flush()
            return sb.toString()
        }

        fun decomposeToJaso(text: String): String {
            val sb = StringBuilder()
            for (ch in text) {
                val code = ch.code
                if (code in HANGUL_BASE..HANGUL_END) {
                    val syllableIndex = code - HANGUL_BASE
                    val cho = syllableIndex / (21 * 28)
                    val jung = (syllableIndex % (21 * 28)) / 28
                    val jong = syllableIndex % 28

                    sb.append(CHOSEONG[cho])
                    sb.append(JUNGSEONG[jung])
                    if (jong > 0) {
                        sb.append(JONGSEONG[jong])
                    }
                } else {
                    sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}
