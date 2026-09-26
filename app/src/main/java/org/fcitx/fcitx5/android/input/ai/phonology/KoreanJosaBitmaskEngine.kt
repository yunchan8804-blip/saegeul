/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.phonology

/**
 * Phonological Korean Josa (Particle) Bitmask Engine.
 *
 * Implements deterministic O(1) phonological selection and logit/token filtering based on
 * the Hangul Unicode syllable decomposition formula:
 *
 *   S = C - 0xAC00
 *   T = S % 28 (Jongseong / Batchim index in [0, 27])
 *
 * Phonological Rules:
 * 1. T == 0: No batchim (ends in vowel) -> selects [는, 가, 를, 와, 로, 야, 며, 나, 란, 랑, 든]
 * 2. T != 0: Has batchim (ends in consonant):
 *    - T == 8 (Jongseong 'ㄹ'): selects '로', forbids '으로'. Selects [은, 이, 을, 과, 아, 며, 이나, 이란, 이랑, 이든]
 *    - T != 8 (Other consonants): selects '으로', forbids '로'. Selects [은, 이, 을, 과, 아, 이며, 이나, 이란, 이랑, 이든]
 */
object KoreanJosaBitmaskEngine {

    const val FLAG_NONE = 0
    const val FLAG_NO_BATCHIM = 1 shl 0           // T == 0 (모음 종결)
    const val FLAG_HAS_BATCHIM = 1 shl 1          // T != 0 (자음 종결)
    const val FLAG_RIEUL_BATCHIM = 1 shl 2        // T == 8 ('ㄹ' 받침)
    const val FLAG_NON_RIEUL_BATCHIM = 1 shl 3    // T != 0 && T != 8 ('ㄹ' 제외 자음 받침)

    const val HANGUL_SYLLABLE_START = 0xAC00
    const val HANGUL_SYLLABLE_END = 0xD7A3

    enum class JosaKind(
        val withBatchim: String,
        val withoutBatchim: String,
        val rieulVariant: String? = null
    ) {
        EUN_NEUN("은", "는"),
        I_GA("이", "가"),
        EUL_REUL("을", "를"),
        GWA_WA("과", "와"),
        EURO_RO("으로", "로", rieulVariant = "로"),
        A_YA("아", "야"),
        IMYEO_MYEO("이며", "며", rieulVariant = "며"),
        INA_NA("이나", "나"),
        IRAN_RAN("이란", "란"),
        IRANG_RANG("이랑", "랑"),
        IDEUN_DEUN("이든", "든"),
        EUROSEO_ROSEO("으로서", "로서", rieulVariant = "로서"),
        EUROSSEO_ROSSEO("으로써", "로써", rieulVariant = "로써"),
        EUROBUTEOR_ROBUTEOR("으로부터", "로부터", rieulVariant = "로부터"),
        EUROWI_ROWI("으로의", "로의", rieulVariant = "로의");

        companion object {
            private val LOOKUP_MAP: Map<String, JosaKind> = buildMap {
                for (kind in values()) {
                    put(kind.withBatchim, kind)
                    put(kind.withoutBatchim, kind)
                    kind.rieulVariant?.let { put(it, kind) }
                }
            }

            fun find(josa: String): JosaKind? = LOOKUP_MAP[josa]
        }
    }

    /**
     * Required bitmask for each particle.
     * The preceding syllable's flags MUST satisfy `(precedingFlags and requiredMask) != 0`.
     */
    val JOSA_REQUIRED_MASK: Map<String, Int> = mapOf(
        // 은 / 는
        "은" to FLAG_HAS_BATCHIM,
        "는" to FLAG_NO_BATCHIM,
        // 이 / 가
        "이" to FLAG_HAS_BATCHIM,
        "가" to FLAG_NO_BATCHIM,
        // 을 / 를
        "을" to FLAG_HAS_BATCHIM,
        "를" to FLAG_NO_BATCHIM,
        // 과 / 와
        "과" to FLAG_HAS_BATCHIM,
        "와" to FLAG_NO_BATCHIM,
        // 으로 / 로
        "으로" to FLAG_NON_RIEUL_BATCHIM,
        "로" to (FLAG_NO_BATCHIM or FLAG_RIEUL_BATCHIM),
        // 아 / 야
        "아" to FLAG_HAS_BATCHIM,
        "야" to FLAG_NO_BATCHIM,
        // 이며 / 며
        "이며" to FLAG_NON_RIEUL_BATCHIM,
        "며" to (FLAG_NO_BATCHIM or FLAG_RIEUL_BATCHIM),
        // 이나 / 나
        "이나" to FLAG_HAS_BATCHIM,
        "나" to FLAG_NO_BATCHIM,
        // 이란 / 란
        "이란" to FLAG_HAS_BATCHIM,
        "란" to FLAG_NO_BATCHIM,
        // 이랑 / 랑
        "이랑" to FLAG_HAS_BATCHIM,
        "랑" to FLAG_NO_BATCHIM,
        // 이든 / 든
        "이든" to FLAG_HAS_BATCHIM,
        "든" to FLAG_NO_BATCHIM,
        // 으로서 / 로서
        "으로서" to FLAG_NON_RIEUL_BATCHIM,
        "로서" to (FLAG_NO_BATCHIM or FLAG_RIEUL_BATCHIM),
        // 으로써 / 로써
        "으로써" to FLAG_NON_RIEUL_BATCHIM,
        "로써" to (FLAG_NO_BATCHIM or FLAG_RIEUL_BATCHIM),
        // 으로부터 / 로부터
        "으로부터" to FLAG_NON_RIEUL_BATCHIM,
        "로부터" to (FLAG_NO_BATCHIM or FLAG_RIEUL_BATCHIM),
        // 으로의 / 로의
        "으로의" to FLAG_NON_RIEUL_BATCHIM,
        "로의" to (FLAG_NO_BATCHIM or FLAG_RIEUL_BATCHIM)
    )

    data class PrecomputedJosa(
        val josa: String,
        val nfdJosa: String,
        val length: Int,
        val nfdLength: Int
    )

    val PRECOMPUTED_JOSAS: List<PrecomputedJosa> by lazy {
        JOSA_REQUIRED_MASK.keys
            .sortedByDescending { it.length }
            .map { josa ->
                val nfd = java.text.Normalizer.normalize(josa, java.text.Normalizer.Form.NFD)
                PrecomputedJosa(josa, nfd, josa.length, nfd.length)
            }
    }

    /**
     * Returns Unicode Jongseong index:
     * -1 if not a Hangul syllable,
     * 0 if no jongseong (vowel),
     * 1..27 for jongseong (8 is 'ㄹ').
     */
    fun getBatchimCode(c: Char): Int {
        val code = c.code
        if (code !in HANGUL_SYLLABLE_START..HANGUL_SYLLABLE_END) return -1
        return (code - HANGUL_SYLLABLE_START) % 28
    }

    fun hasBatchim(c: Char): Boolean {
        val batchim = getBatchimCode(c)
        if (batchim >= 0) return batchim != 0
        return when (c) {
            '0', '1', '3', '6', '7', '8' -> true
            '2', '4', '5', '9' -> false
            else -> false
        }
    }

    fun isRieulBatchim(c: Char): Boolean {
        val batchim = getBatchimCode(c)
        if (batchim >= 0) return batchim == 8
        return c == '1' || c == '7' || c == '8'
    }

    /**
     * Resolves phonological batchim flags for English words based on Korean loanword
     * phonological rules and conventions.
     */
    fun getEnglishPhonologicalFlags(word: String): Int {
        val trimmed = word.trim().filter { it.isLetter() }
        if (trimmed.isEmpty()) return FLAG_NONE

        // 1. All-caps Acronym (e.g. AI, URL, ML, LLM, API, GPT, CPU, DNA, OS, TV, PC, IT)
        val isAcronym = trimmed.length in 1..5 && trimmed.all { it.isUpperCase() }
        if (isAcronym) {
            return when (trimmed.last()) {
                'L', 'R' -> FLAG_HAS_BATCHIM or FLAG_RIEUL_BATCHIM
                'M', 'N' -> FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM
                else -> FLAG_NO_BATCHIM // 'I', 'P', 'T', 'S', 'A', 'K', 'C', 'D', 'O', etc.
            }
        }

        val lower = trimmed.lowercase()

        // 2. Silent 'e' endings with preceding consonant sound:
        // -one (phone, iphone, zone, clone, tone), -ine (line, online, airline, shine, wine), -ane (plane, lane) -> 'ㄴ' 받침
        if (lower.endsWith("one") || lower.endsWith("ine") || lower.endsWith("ane")) {
            return FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM
        }
        // -ame (game, name, frame), -ime (time, prime), -ome (home, chrome) -> 'ㅁ' 받침
        if (lower.endsWith("ame") || lower.endsWith("ime") || lower.endsWith("ome")) {
            return FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM
        }
        // -le (google, apple, table, file, cycle, profile, title, mobile, sale, scale) -> 'ㄹ' 받침
        if (lower.endsWith("le")) {
            return FLAG_HAS_BATCHIM or FLAG_RIEUL_BATCHIM
        }
        // Other silent 'e' like -be (tube), -de (code, node), -ge (page, image), -pe (pipe, tape, type),
        // -se (case, base, house), -te (site, date, suite), -ze (size), -ce (voice, peace):
        // Korean pronunciation appends vowel [으/이] -> no batchim
        if (lower.endsWith("e")) {
            return FLAG_NO_BATCHIM
        }

        // 3. Consonants acting as Korean final batchim:
        // -k, -ck (macbook, book, pack, check, talk, park, work, track, desk, click, pink) -> [ㄱ] 받침
        if (lower.endsWith("k") || lower.endsWith("ck")) {
            return FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM
        }
        // -p, -pp (app, cup, shop, map, laptop, step, trip, webapp) -> [ㅂ] 받침
        if (lower.endsWith("p") || lower.endsWith("pp")) {
            return FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM
        }
        // -b, -bb (web, club, job, lab, pub) -> [ㅂ] 받침
        if (lower.endsWith("b") || lower.endsWith("bb")) {
            return FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM
        }
        // -m, -mm (team, room, program, spam, system, stream, zoom, album) -> [ㅁ] 받침
        if (lower.endsWith("m") || lower.endsWith("mm")) {
            return FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM
        }
        // -n, -nn (fan, man, scan, clean, green, screen, icon, dragon, sign, run) -> [ㄴ] 받침
        if (lower.endsWith("n") || lower.endsWith("nn")) {
            return FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM
        }
        // -l, -ll (call, ball, cell, email, model, pixel, channel, tool, url, scroll, wall) -> [ㄹ] 받침
        if (lower.endsWith("l") || lower.endsWith("ll")) {
            return FLAG_HAS_BATCHIM or FLAG_RIEUL_BATCHIM
        }
        // -ng (song, king, ring, ping, spring, warning) -> [ㅇ] 받침
        if (lower.endsWith("ng")) {
            return FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM
        }

        // 4. Pure vowels (-a, -i, -o, -u, -y) -> no batchim
        if (lower.endsWith("a") || lower.endsWith("i") || lower.endsWith("o") || lower.endsWith("u") || lower.endsWith("y")) {
            return FLAG_NO_BATCHIM
        }

        // 5. Plural -s or consonants pronounced with vowel epenthesis (-t, -d, -s, -x, -z, -sh, -ch) -> no batchim
        return FLAG_NO_BATCHIM
    }

    fun getPhonologicalFlags(c: Char): Int {
        val batchim = getBatchimCode(c)
        if (batchim >= 0) {
            return if (batchim == 0) {
                FLAG_NO_BATCHIM
            } else if (batchim == 8) {
                FLAG_HAS_BATCHIM or FLAG_RIEUL_BATCHIM
            } else {
                FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM
            }
        }
        // Korean Jamo (호환 자모 영역 U+3131..U+318E)
        val code = c.code
        if (code in 0x3131..0x318E) {
            return when (c) {
                'ㄹ' -> FLAG_HAS_BATCHIM or FLAG_RIEUL_BATCHIM
                'ㅋ', 'ㅎ', in 'ㅏ'..'ㅣ' -> FLAG_NO_BATCHIM
                'ㄱ', 'ㄴ', 'ㄷ', 'ㅁ', 'ㅂ', 'ㅅ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅌ', 'ㅍ',
                'ㄳ', 'ㄵ', 'ㄶ', 'ㄺ', 'ㄻ', 'ㄼ', 'ㄽ', 'ㄾ', 'ㄿ', 'ㅀ', 'ㅄ' -> FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM
                else -> FLAG_NO_BATCHIM
            }
        }
        // First-mid-last Hangul Jamo (첫가끝 자모 U+1100..U+11FF)
        if (code in 0x1100..0x11FF) {
            return when (code) {
                in 0x1160..0x11A7 -> FLAG_NO_BATCHIM // Jungseong (Vowels)
                0x11AF, in 0x11B0..0x11B6 -> FLAG_HAS_BATCHIM or FLAG_RIEUL_BATCHIM // Jongseong Rieul & clusters
                in 0x11A8..0x11FF -> FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM // Jongseong (Coda)
                0x1105 -> FLAG_HAS_BATCHIM or FLAG_RIEUL_BATCHIM // Choseong Rieul
                0x110F, 0x1112 -> FLAG_NO_BATCHIM // Choseong Kieuk, Hieuh
                else -> FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM // Choseong consonants
            }
        }
        if (c.isDigit()) {
            return when (c) {
                '0', '3', '6' -> FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM
                '1', '7', '8' -> FLAG_HAS_BATCHIM or FLAG_RIEUL_BATCHIM
                '2', '4', '5', '9' -> FLAG_NO_BATCHIM
                else -> FLAG_NONE
            }
        }
        if (c in 'A'..'Z' || c in 'a'..'z') {
            return when (c.uppercaseChar()) {
                'L', 'R' -> FLAG_HAS_BATCHIM or FLAG_RIEUL_BATCHIM
                'M', 'N' -> FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM
                else -> FLAG_NO_BATCHIM
            }
        }
        // Emoji & Symbols: Treat as vowel-ending (FLAG_NO_BATCHIM) for natural Korean particles
        if (Character.isSurrogate(c) || code in 0x2600..0x27BF || code in 0x1F300..0x1FAFF) {
            return FLAG_NO_BATCHIM
        }
        return FLAG_NONE
    }

    /**
     * Checks if a character is an ignorable zero-width or non-printing format control character.
     * Includes ZWSP (\u200B), ZWNJ (\u200C), ZWJ (\u200D), BOM (\uFEFF), LRM/RLM (\u200E, \u200F),
     * Word Joiner (\u2060), Variation Selectors (U+FE00..U+FE0F), and Bidi controls (U+202A..U+202E).
     */
    fun isIgnorableControlChar(c: Char): Boolean {
        val code = c.code
        return c == '\u200B' || // Zero-Width Space
            c == '\u200C' || // Zero-Width Non-Joiner (ZWNJ)
            c == '\u200D' || // Zero-Width Joiner (ZWJ)
            c == '\uFEFF' || // Zero-Width No-Break Space / BOM
            c == '\u200E' || c == '\u200F' || // LRM, RLM
            c == '\u2060' || // Word Joiner
            code in 0xFE00..0xFE0F || // Variation Selectors
            code in 0x202A..0x202E // Bidi controls
    }

    fun getPhonologicalFlags(word: String): Int {
        val trimmed = word.trimEnd()
        if (trimmed.isEmpty()) return FLAG_NONE
        var endIdx = trimmed.length - 1
        while (endIdx >= 0 && isIgnorableControlChar(trimmed[endIdx])) {
            endIdx--
        }
        if (endIdx < 0) return FLAG_NO_BATCHIM

        // Normalize substring to NFC without ignorable control characters for robust coda detection
        val cleanSub = trimmed.substring(0, endIdx + 1).filterNot { isIgnorableControlChar(it) }
        if (cleanSub.isEmpty()) return FLAG_NO_BATCHIM
        val nfc = java.text.Normalizer.normalize(cleanSub, java.text.Normalizer.Form.NFC)
        val targetChar = nfc.lastOrNull() ?: trimmed[endIdx]

        val batchim = getBatchimCode(targetChar)
        if (batchim >= 0) {
            return getPhonologicalFlags(targetChar)
        }
        if (targetChar.code in 0x3131..0x318E || targetChar.code in 0x1100..0x11FF) {
            return getPhonologicalFlags(targetChar)
        }
        if (targetChar.isDigit()) {
            return getPhonologicalFlags(targetChar)
        }
        if (targetChar in 'A'..'Z' || targetChar in 'a'..'z') {
            var start = nfc.length - 1
            while (start >= 0 && (nfc[start] in 'A'..'Z' || nfc[start] in 'a'..'z')) {
                start--
            }
            val engToken = nfc.substring(start + 1)
            return getEnglishPhonologicalFlags(engToken)
        }
        if (Character.isSurrogate(targetChar) || targetChar.code in 0x2600..0x27BF || targetChar.code in 0x1F300..0x1FAFF || targetChar.code in 0x2300..0x23FF || targetChar.code in 0x2B50..0x2B55) {
            return FLAG_NO_BATCHIM
        }
        return FLAG_NONE
    }

    private fun isRieulVariantKind(kind: JosaKind): Boolean {
        return kind == JosaKind.EURO_RO || kind == JosaKind.IMYEO_MYEO ||
            kind == JosaKind.EUROSEO_ROSEO || kind == JosaKind.EUROSSEO_ROSSEO ||
            kind == JosaKind.EUROBUTEOR_ROBUTEOR || kind == JosaKind.EUROWI_ROWI
    }

    fun selectJosa(precedingChar: Char, kind: JosaKind): String {
        val flags = getPhonologicalFlags(precedingChar)
        if (flags == FLAG_NONE) return kind.withoutBatchim
        if (isRieulVariantKind(kind)) {
            return if ((flags and (FLAG_NO_BATCHIM or FLAG_RIEUL_BATCHIM)) != 0) {
                kind.withoutBatchim
            } else {
                kind.withBatchim
            }
        }
        return if ((flags and FLAG_HAS_BATCHIM) != 0) kind.withBatchim else kind.withoutBatchim
    }

    fun selectJosa(precedingWord: String, kind: JosaKind): String {
        val flags = getPhonologicalFlags(precedingWord)
        if (flags == FLAG_NONE) return kind.withoutBatchim
        if (isRieulVariantKind(kind)) {
            return if ((flags and (FLAG_NO_BATCHIM or FLAG_RIEUL_BATCHIM)) != 0) {
                kind.withoutBatchim
            } else {
                kind.withBatchim
            }
        }
        return if ((flags and FLAG_HAS_BATCHIM) != 0) kind.withBatchim else kind.withoutBatchim
    }

    fun attachJosa(precedingWord: String, kind: JosaKind): String {
        return precedingWord + selectJosa(precedingWord, kind)
    }

    fun isValidAttachment(precedingChar: Char, josa: String): Boolean {
        val reqMask = JOSA_REQUIRED_MASK[josa] ?: return true
        val flags = getPhonologicalFlags(precedingChar)
        if (flags == FLAG_NONE) return true
        return (flags and reqMask) != 0
    }

    fun isValidAttachment(precedingWord: String, josa: String): Boolean {
        val reqMask = JOSA_REQUIRED_MASK[josa] ?: return true
        val flags = getPhonologicalFlags(precedingWord)
        if (flags == FLAG_NONE) return true
        return (flags and reqMask) != 0
    }

    fun filterCandidates(prefix: String, candidates: List<String>): List<String> {
        val flags = getPhonologicalFlags(prefix)

        return candidates.filter { candidate ->
            val trimmed = candidate.trimStart()
            if (trimmed.isEmpty()) return@filter true

            if (flags != FLAG_NONE) {
                // Check if candidate starts with a particle token
                // Check longer josas first (e.g. "으로" before "로")
                val matchedJosa = JOSA_REQUIRED_MASK.keys
                    .sortedByDescending { it.length }
                    .firstOrNull { josa ->
                        if (trimmed.startsWith(josa)) {
                            val nextIdx = josa.length
                            nextIdx == trimmed.length || trimmed[nextIdx].isWhitespace() ||
                                trimmed[nextIdx] in ".,!?:;~()[]"
                        } else false
                    }
                if (matchedJosa != null) {
                    if (!isValidAttachment(prefix, matchedJosa)) {
                        return@filter false
                    }
                }
            }

            // Also reject candidate if it has internal josa mismatch
            !hasJosaMismatch(candidate)
        }
    }

    data class Mismatch(
        val index: Int,
        val precedingChar: Char,
        val wrongJosa: String,
        val correctJosa: String
    )

    fun findMismatches(text: String): List<Mismatch> {
        if (text.length > 2000) return emptyList()
        val mismatches = mutableListOf<Mismatch>()

        val length = text.length
        var i = 0
        while (i < length - 1) {
            val c = text[i]
            if (isIgnorableControlChar(c)) {
                i++
                continue
            }
            var tokenEnd = i
            val flags: Int

            if (c in 'A'..'Z' || c in 'a'..'z') {
                var start = i
                while (start >= 0 && (text[start] in 'A'..'Z' || text[start] in 'a'..'z')) {
                    start--
                }
                val engToken = text.substring(start + 1, i + 1)
                flags = getEnglishPhonologicalFlags(engToken)
            } else if (c.code in 0x1100..0x11FF) {
                while (tokenEnd + 1 < length && text[tokenEnd + 1].code in 0x1100..0x11FF) {
                    tokenEnd++
                }
                val jamoToken = text.substring(i, tokenEnd + 1)
                val nfcToken = java.text.Normalizer.normalize(jamoToken, java.text.Normalizer.Form.NFC)
                flags = if (nfcToken.isNotEmpty()) getPhonologicalFlags(nfcToken.last()) else getPhonologicalFlags(text[tokenEnd])
            } else if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(text[i + 1])) {
                tokenEnd = i + 1
                while (tokenEnd + 1 < length && (text[tokenEnd + 1].code in 0xFE00..0xFE0F || text[tokenEnd + 1] == '\u200D')) {
                    tokenEnd++
                }
                flags = FLAG_NO_BATCHIM
            } else if (c.code in 0x2600..0x27BF || c.code in 0x2300..0x23FF || c.code in 0x2B50..0x2B55) {
                while (tokenEnd + 1 < length && (text[tokenEnd + 1].code in 0xFE00..0xFE0F || text[tokenEnd + 1] == '\u200D')) {
                    tokenEnd++
                }
                flags = FLAG_NO_BATCHIM
            } else {
                flags = getPhonologicalFlags(c)
            }

            if (flags != FLAG_NONE) {
                var josaStart = tokenEnd + 1
                // Skip ignorable control characters between noun and josa
                while (josaStart < length && isIgnorableControlChar(text[josaStart])) {
                    josaStart++
                }

                for (item in PRECOMPUTED_JOSAS) {
                    val josa = item.josa
                    val nfdJosa = item.nfdJosa
                    val matchedLen = if (text.startsWith(josa, josaStart)) {
                        item.length
                    } else if (text.startsWith(nfdJosa, josaStart)) {
                        item.nfdLength
                    } else {
                        0
                    }

                    if (matchedLen > 0 && josaStart + matchedLen <= length) {
                        val nextIdx = josaStart + matchedLen
                        val isBoundary = nextIdx == length || text[nextIdx].isWhitespace() || text[nextIdx] in ".,!?:;~()[]"
                        if (isBoundary) {
                            // Specific check: '나은' when preceded by '더 ' is adjective '낫다'
                            if (c == '나' && josa == "은" && (i >= 3 && text.substring(i - 3, i) == "더 ")) {
                                break
                            }
                            val reqMask = JOSA_REQUIRED_MASK[josa] ?: continue
                            if ((flags and reqMask) == 0) {
                                val kind = JosaKind.find(josa)
                                val correct = if (kind != null) {
                                    if (isRieulVariantKind(kind)) {
                                        if ((flags and (FLAG_NO_BATCHIM or FLAG_RIEUL_BATCHIM)) != 0) {
                                            kind.withoutBatchim
                                        } else {
                                            kind.withBatchim
                                        }
                                    } else {
                                        if ((flags and FLAG_HAS_BATCHIM) != 0) kind.withBatchim else kind.withoutBatchim
                                    }
                                } else josa
                                val finalCorrect = if (matchedLen == nfdJosa.length && nfdJosa != josa) {
                                    java.text.Normalizer.normalize(correct, java.text.Normalizer.Form.NFD)
                                } else {
                                    correct
                                }
                                val wrongSub = text.substring(josaStart, josaStart + matchedLen)
                                mismatches.add(Mismatch(josaStart, c, wrongSub, finalCorrect))
                            }
                            break
                        }
                    }
                }
            }
            i = tokenEnd + 1
        }
        return mismatches
    }

    fun hasJosaMismatch(text: String): Boolean {
        return findMismatches(text).isNotEmpty()
    }

    fun correctJosaMismatch(text: String): String {
        val mismatches = findMismatches(text)
        if (mismatches.isEmpty()) return text

        val sb = StringBuilder(text)
        for (m in mismatches.asReversed()) {
            sb.replace(m.index, m.index + m.wrongJosa.length, m.correctJosa)
        }
        return sb.toString()
    }
}
