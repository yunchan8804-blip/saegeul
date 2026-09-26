/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.grammar

/**
 * EAI-10: Grammar-Constrained Decoding Engine.
 *
 * Implements FSM-based token validity checking and real-time Korean coda (받침)
 * particle masking (< 30µs per token overhead).
 */
class GrammarConstrainedEngine {

    enum class SchemaType {
        FREE_TEXT,
        JSON_OBJECT,
        ACTION_CHIP
    }

    companion object {
        // Free text coda states
        const val STATE_FREE_INITIAL = 0
        const val STATE_FREE_NO_CODA = 1       // 받침 없음 (open syllable)
        const val STATE_FREE_CODA_NON_RIEUL = 2 // 받침 있음 ('ㄹ' 제외)
        const val STATE_FREE_CODA_RIEUL = 3     // 'ㄹ' 받침
        const val STATE_FREE_OTHER = 4          // 공백, 특수문자, 비한글

        // JSON FSM states
        const val STATE_JSON_START = 100       // Initial, expecting '{'
        const val STATE_JSON_OPEN = 101        // After '{', expecting '}' or '"' (key)
        const val STATE_JSON_KEY = 102         // Inside key string
        const val STATE_JSON_AFTER_KEY = 103   // After closing key '"', expecting ':'
        const val STATE_JSON_COLON = 104       // After ':', expecting value
        const val STATE_JSON_VAL_STR = 105     // Inside string value
        const val STATE_JSON_VAL_OTHER = 106   // Inside number/bool/null value
        const val STATE_JSON_AFTER_VAL = 107   // After value, expecting ',' or '}'
        const val STATE_JSON_COMMA = 108       // After ',', expecting '"' (next key)
        const val STATE_JSON_CLOSED = 109      // After closing '}', finished

        // ACTION_CHIP FSM states
        // Pattern 1: "[동작] <내용>"
        const val STATE_CHIP_START = 200
        const val STATE_CHIP_P1_ACTION = 201   // Inside '[동작'
        const val STATE_CHIP_P1_AFTER_ACTION = 202 // After ']'
        const val STATE_CHIP_P1_SPACE = 203    // After '] '
        const val STATE_CHIP_P1_CONTENT = 204  // Inside '<내용>'

        // Pattern 2: "<내용> (동작)"
        const val STATE_CHIP_P2_CONTENT = 210  // Inside '<내용>'
        const val STATE_CHIP_P2_SPACE = 211    // Space before '('
        const val STATE_CHIP_P2_ACTION = 212   // Inside '(동작'
        const val STATE_CHIP_P2_CLOSED = 213   // After ')'

        // Particle sets based on preceding coda requirement
        private val DISALLOWED_AFTER_NO_CODA = setOf(
            "은", "이", "을", "과", "으로", "으로서", "으로써", "이나", "이란", "이랑", "이에요", "이며", "이면서", "이든", "이든지"
        )
        private val DISALLOWED_AFTER_NON_RIEUL_CODA = setOf(
            "는", "가", "를", "와", "로", "로서", "로써", "나", "란", "랑", "예요", "며", "면서", "든", "든지"
        )
        private val DISALLOWED_AFTER_RIEUL_CODA = setOf(
            "는", "가", "를", "와", "으로", "으로서", "으로써", "나", "란", "랑", "예요"
        )

        /**
         * Returns coda index for a Hangul syllable:
         * 0 = No coda
         * 8 = 'ㄹ' coda
         * 1..27 (except 8) = Other codas
         * -1 = Not a Hangul syllable
         */
        @JvmStatic
        fun getCoda(ch: Char): Int {
            val code = ch.code
            if (code !in 0xAC00..0xD7A3) return -1
            return (code - 0xAC00) % 28
        }

        @JvmStatic
        fun hasCoda(ch: Char): Boolean {
            val coda = getCoda(ch)
            return coda > 0
        }
    }

    /**
     * Verifies if [nextToken] can follow [currentState] according to [schemaType].
     * Returns Pair(isValid, nextState).
     */
    fun isValidToken(
        currentState: Int,
        nextToken: String,
        schemaType: SchemaType
    ): Pair<Boolean, Int> {
        if (nextToken.isEmpty()) return Pair(true, currentState)

        return when (schemaType) {
            SchemaType.FREE_TEXT -> validateFreeTextToken(currentState, nextToken)
            SchemaType.JSON_OBJECT -> validateJsonToken(currentState, nextToken)
            SchemaType.ACTION_CHIP -> validateActionChipToken(currentState, nextToken)
        }
    }

    /**
     * Filters candidate tokens that are grammatically and syntactically allowed
     * when appended to [prefix].
     */
    fun filterAllowedTokens(
        prefix: String,
        candidates: List<String>,
        schemaType: SchemaType
    ): List<String> {
        if (candidates.isEmpty()) return emptyList()

        val currentState = deriveCurrentState(prefix, schemaType)
        val lastChar = prefix.lastOrNull()

        return candidates.filter { candidate ->
            val (isValid, _) = isValidToken(currentState, candidate, schemaType)
            if (!isValid) return@filter false

            // Additional Korean particle compatibility check when directly attached
            if (lastChar != null && !prefix.endsWith(" ")) {
                if (!isParticleCompatibleWithChar(lastChar, candidate)) {
                    return@filter false
                }
            }
            true
        }
    }

    /**
     * Derives the state resulting from scanning [text] under [schemaType].
     */
    fun deriveCurrentState(text: String, schemaType: SchemaType): Int {
        if (text.isEmpty()) {
            return when (schemaType) {
                SchemaType.FREE_TEXT -> STATE_FREE_INITIAL
                SchemaType.JSON_OBJECT -> STATE_JSON_START
                SchemaType.ACTION_CHIP -> STATE_CHIP_START
            }
        }

        var state = when (schemaType) {
            SchemaType.FREE_TEXT -> STATE_FREE_INITIAL
            SchemaType.JSON_OBJECT -> STATE_JSON_START
            SchemaType.ACTION_CHIP -> STATE_CHIP_START
        }

        // Fast state derivation
        when (schemaType) {
            SchemaType.FREE_TEXT -> {
                val lastHangul = text.trimEnd().lastOrNull { it.code in 0xAC00..0xD7A3 }
                return if (lastHangul != null) {
                    when (val coda = getCoda(lastHangul)) {
                        0 -> STATE_FREE_NO_CODA
                        8 -> STATE_FREE_CODA_RIEUL
                        in 1..27 -> STATE_FREE_CODA_NON_RIEUL
                        else -> STATE_FREE_OTHER
                    }
                } else {
                    STATE_FREE_OTHER
                }
            }
            SchemaType.JSON_OBJECT -> {
                for (i in text.indices) {
                    val chStr = text[i].toString()
                    val (valid, next) = validateJsonToken(state, chStr)
                    if (!valid) return -1
                    state = next
                }
                return state
            }
            SchemaType.ACTION_CHIP -> {
                for (i in text.indices) {
                    val chStr = text[i].toString()
                    val (valid, next) = validateActionChipToken(state, chStr)
                    if (!valid) return -1
                    state = next
                }
                return state
            }
        }
    }

    // =========================================================================
    // Free Text & Korean Coda (받침) Validation
    // =========================================================================

    private fun validateFreeTextToken(
        currentState: Int,
        nextToken: String
    ): Pair<Boolean, Int> {
        // Check if nextToken violates coda rules based on currentState
        if (currentState in STATE_FREE_NO_CODA..STATE_FREE_CODA_RIEUL) {
            val particle = extractLeadingParticle(nextToken)
            if (particle != null) {
                when (currentState) {
                    STATE_FREE_NO_CODA -> {
                        if (DISALLOWED_AFTER_NO_CODA.contains(particle)) return Pair(false, currentState)
                    }
                    STATE_FREE_CODA_NON_RIEUL -> {
                        if (DISALLOWED_AFTER_NON_RIEUL_CODA.contains(particle)) return Pair(false, currentState)
                    }
                    STATE_FREE_CODA_RIEUL -> {
                        if (DISALLOWED_AFTER_RIEUL_CODA.contains(particle)) return Pair(false, currentState)
                    }
                }
            }
        }

        // Determine next state from the last character of nextToken
        val lastChar = nextToken.last()
        val coda = getCoda(lastChar)
        val nextState = when {
            coda == 0 -> STATE_FREE_NO_CODA
            coda == 8 -> STATE_FREE_CODA_RIEUL
            coda > 0 -> STATE_FREE_CODA_NON_RIEUL
            else -> STATE_FREE_OTHER
        }

        return Pair(true, nextState)
    }

    /**
     * Checks if [candidate] as a particle or particle-prefixed phrase is compatible
     * with the preceding character [prevChar].
     */
    fun isParticleCompatibleWithChar(prevChar: Char, candidate: String): Boolean {
        val coda = getCoda(prevChar)
        if (coda < 0) return true // Non-hangul preceding character: pass

        val particle = extractLeadingParticle(candidate) ?: return true

        return when {
            coda == 0 -> !DISALLOWED_AFTER_NO_CODA.contains(particle)
            coda == 8 -> !DISALLOWED_AFTER_RIEUL_CODA.contains(particle)
            else -> !DISALLOWED_AFTER_NON_RIEUL_CODA.contains(particle)
        }
    }

    /**
     * Extracts leading Korean particle from token (e.g. "를", "을", "은", "는", "으로", "로").
     */
    private fun extractLeadingParticle(token: String): String? {
        val trimmed = token.trimStart()
        if (trimmed.isEmpty()) return null

        // Try 2-char particles first
        if (trimmed.length >= 2) {
            val twoChar = trimmed.substring(0, 2)
            if (twoChar in setOf("으로", "이나", "이란", "이랑", "로서", "로써", "에서", "에게", "보다", "부터", "까지", "마저", "조차", "처럼", "하고")) {
                return twoChar
            }
        }
        // 1-char particles
        val oneChar = trimmed.substring(0, 1)
        if (oneChar in setOf("은", "는", "이", "가", "을", "를", "과", "와", "로", "의", "도", "만", "나", "란", "랑", "며", "든")) {
            return oneChar
        }

        return null
    }

    // =========================================================================
    // JSON Schema FSM Validation
    // =========================================================================

    private fun validateJsonToken(
        currentState: Int,
        nextToken: String
    ): Pair<Boolean, Int> {
        var state = currentState

        for (ch in nextToken) {
            when (state) {
                STATE_JSON_START -> {
                    if (ch.isWhitespace()) continue
                    if (ch == '{') state = STATE_JSON_OPEN
                    else return Pair(false, state)
                }
                STATE_JSON_OPEN -> {
                    if (ch.isWhitespace()) continue
                    if (ch == '}') state = STATE_JSON_CLOSED
                    else if (ch == '"') state = STATE_JSON_KEY
                    else return Pair(false, state)
                }
                STATE_JSON_KEY -> {
                    if (ch == '"') state = STATE_JSON_AFTER_KEY
                    // allow key characters
                }
                STATE_JSON_AFTER_KEY -> {
                    if (ch.isWhitespace()) continue
                    if (ch == ':') state = STATE_JSON_COLON
                    else return Pair(false, state)
                }
                STATE_JSON_COLON -> {
                    if (ch.isWhitespace()) continue
                    state = when (ch) {
                        '"' -> STATE_JSON_VAL_STR
                        '{' -> STATE_JSON_OPEN
                        '[', in '0'..'9', '-', 't', 'f', 'n' -> STATE_JSON_VAL_OTHER
                        else -> return Pair(false, state)
                    }
                }
                STATE_JSON_VAL_STR -> {
                    if (ch == '"') state = STATE_JSON_AFTER_VAL
                }
                STATE_JSON_VAL_OTHER -> {
                    if (ch == ',') state = STATE_JSON_COMMA
                    else if (ch == '}') state = STATE_JSON_CLOSED
                    else if (ch == ']') state = STATE_JSON_AFTER_VAL
                    else if (ch.isWhitespace()) state = STATE_JSON_AFTER_VAL
                    // otherwise continuing primitive
                }
                STATE_JSON_AFTER_VAL -> {
                    if (ch.isWhitespace()) continue
                    if (ch == ',') state = STATE_JSON_COMMA
                    else if (ch == '}') state = STATE_JSON_CLOSED
                    else return Pair(false, state)
                }
                STATE_JSON_COMMA -> {
                    if (ch.isWhitespace()) continue
                    if (ch == '"') state = STATE_JSON_KEY
                    else return Pair(false, state)
                }
                STATE_JSON_CLOSED -> {
                    if (ch.isWhitespace()) continue
                    return Pair(false, state) // No characters allowed after JSON close
                }
                else -> return Pair(false, state)
            }
        }

        return Pair(true, state)
    }

    // =========================================================================
    // ACTION_CHIP FSM Validation: "[동작] <내용>" or "<내용> (동작)"
    // =========================================================================

    private fun validateActionChipToken(
        currentState: Int,
        nextToken: String
    ): Pair<Boolean, Int> {
        var state = currentState

        for (ch in nextToken) {
            when (state) {
                STATE_CHIP_START -> {
                    if (ch.isWhitespace()) continue
                    state = if (ch == '[') {
                        STATE_CHIP_P1_ACTION
                    } else if (ch != ']' && ch != '(' && ch != ')') {
                        STATE_CHIP_P2_CONTENT
                    } else {
                        return Pair(false, state)
                    }
                }
                // Pattern 1: "[동작] 내용"
                STATE_CHIP_P1_ACTION -> {
                    if (ch == ']') state = STATE_CHIP_P1_AFTER_ACTION
                    else if (ch == '[' || ch == '(' || ch == ')') return Pair(false, state)
                }
                STATE_CHIP_P1_AFTER_ACTION -> {
                    if (ch == ' ') state = STATE_CHIP_P1_SPACE
                    else return Pair(false, state)
                }
                STATE_CHIP_P1_SPACE -> {
                    if (ch != ' ' && ch != '[' && ch != ']' && ch != '(' && ch != ')') {
                        state = STATE_CHIP_P1_CONTENT
                    } else {
                        return Pair(false, state)
                    }
                }
                STATE_CHIP_P1_CONTENT -> {
                    if (ch == '[' || ch == ']' || ch == '(' || ch == ')') {
                        return Pair(false, state)
                    }
                }

                // Pattern 2: "내용 (동작)"
                STATE_CHIP_P2_CONTENT -> {
                    if (ch == ' ') state = STATE_CHIP_P2_SPACE
                    else if (ch == '(') state = STATE_CHIP_P2_ACTION
                    else if (ch == '[' || ch == ']' || ch == ')') return Pair(false, state)
                }
                STATE_CHIP_P2_SPACE -> {
                    if (ch == ' ') continue
                    if (ch == '(') state = STATE_CHIP_P2_ACTION
                    else if (ch != '[' && ch != ']' && ch != ')') state = STATE_CHIP_P2_CONTENT
                    else return Pair(false, state)
                }
                STATE_CHIP_P2_ACTION -> {
                    if (ch == ')') state = STATE_CHIP_P2_CLOSED
                    else if (ch == '(' || ch == '[' || ch == ']') return Pair(false, state)
                }
                STATE_CHIP_P2_CLOSED -> {
                    if (ch.isWhitespace()) continue
                    return Pair(false, state) // No tokens allowed after closed chip
                }
                else -> return Pair(false, state)
            }
        }

        return Pair(true, state)
    }
}
