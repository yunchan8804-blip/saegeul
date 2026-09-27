/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/** Which vowel-combination table a mobile Hangul surface's directional/dot primitives use. */
enum class MobileHangulFamily {
    /** Builds ㅠ from ㆍ, so a following ㅣ combines into ㅝ (Chunjiin, ChunjiinPlus). */
    Chunjiin,

    /** Reaches ㅠ some other way, so a following ㅣ is always a new, separate vowel. */
    Other
}

/** Converts Samsung-style mobile Hangul key semantics into Dubeolsik engine actions. */
class MobileHangulComposer(private val family: MobileHangulFamily = MobileHangulFamily.Chunjiin) {
    sealed interface Output {
        data object Backspace : Output
        data object Space : Output
        data class Keys(val value: String) : Output
    }

    sealed interface Token {
        data class Cycle(
            val id: String,
            val jamo: List<Char>,
            val timeoutMillis: Long = PHONEPAD_MULTITAP_TIMEOUT_MS,
            val naratgulVowelPair: Boolean = false
        ) : Token

        /** A punctuation key that cycles through [symbols] on repeated taps, like [Cycle]. */
        data class SymbolCycle(
            val id: String,
            val symbols: List<Char>,
            val timeoutMillis: Long = PHONEPAD_MULTITAP_TIMEOUT_MS
        ) : Token

        data class Jamo(val value: Char) : Token
        data object VowelI : Token
        data object VowelDot : Token
        data object VowelEu : Token
        data object AddStroke : Token
        data object DoubleConsonant : Token
        data object Boundary : Token
    }

    private var lastCycleId: String? = null
    private var lastCycleTimeout = 0L
    private var cycleIndex = 0
    private var lastCycleAt = 0L
    private var cyclePreviousVowel: String? = null

    /** Dubeolsik keys currently visible in the backend for the active cycle's replaceable tail. */
    private var cycleTailKeys = ""

    /** Once true, [cyclePreviousVowel] has been folded into [cycleTailKeys] for this cycle. */
    private var cycleTailIncludesPrevious = false

    /** Whether the last jamo [pressCycle] selected was a vowel, for the space-swallow rule. */
    private var lastCycleSelectedVowel = false

    // Symbol cycles keep their own multitap bookkeeping so a punctuation key never shares a
    // replace streak with a jamo cycle, and never closes on Boundary like one (K4).
    private var lastSymbolCycleId: String? = null
    private var lastSymbolCycleAt = 0L
    private var symbolCycleIndex = 0

    private var currentVowel: String? = null
    private var pendingDots = 0
    private var lastJamo: Char? = null

    fun reset() {
        lastCycleId = null
        lastCycleTimeout = 0L
        cycleIndex = 0
        lastCycleAt = 0L
        cyclePreviousVowel = null
        cycleTailKeys = ""
        cycleTailIncludesPrevious = false
        lastCycleSelectedVowel = false
        lastSymbolCycleId = null
        lastSymbolCycleAt = 0L
        symbolCycleIndex = 0
        currentVowel = null
        pendingDots = 0
        lastJamo = null
    }

    fun pendingDotCount(): Int = pendingDots

    /**
     * Absorbs one accidental ㆍ tap locally so Backspace does not reach the Dubeolsik backend
     * and delete the preceding completed glyph. Returns false when there is nothing pending, so
     * the caller should send a normal Backspace instead.
     */
    fun cancelPendingDot(): Boolean {
        if (pendingDots == 0) return false
        pendingDots--
        return true
    }

    fun press(token: Token, nowMillis: Long = System.currentTimeMillis()): List<Output> = when (token) {
        is Token.Cycle -> pressCycle(token, nowMillis)
        is Token.SymbolCycle -> pressSymbolCycle(token, nowMillis)
        is Token.Jamo -> emitJamo(token.value)
        Token.VowelI -> pressChunjiinVowel('ㅣ')
        Token.VowelDot -> pressDot()
        Token.VowelEu -> pressChunjiinVowel('ㅡ')
        Token.AddStroke -> transformLast(strokeAdditions)
        Token.DoubleConsonant -> transformLast(doubleConsonants)
        Token.Boundary -> pressBoundary(nowMillis)
    }

    /**
     * Replays a multitap cycle so the backend always ends up holding exactly what "the syllable
     * before this cycle" + "the selected jamo combined with it" would be: [cycleTailKeys] is
     * everything we ourselves put in the backend for this cycle, and [cyclePreviousVowel] (`P`)
     * is only ever erased once, the first time a selection needs it gone; after that it either
     * stays folded into a combined vowel or gets retyped alongside a non-combining selection.
     */
    private fun pressCycle(token: Token.Cycle, nowMillis: Long): List<Output> {
        require(token.jamo.isNotEmpty()) { "A multitap key needs at least one jamo" }
        pendingDots = 0
        lastSymbolCycleId = null
        val replacing = lastCycleId == token.id && nowMillis - lastCycleAt <= token.timeoutMillis
        cycleIndex = if (replacing) (cycleIndex + 1) % token.jamo.size else 0
        val selected = token.jamo[cycleIndex]

        if (!replacing) {
            cyclePreviousVowel = currentVowel.takeIf { selected.isVowel() }
            cycleTailKeys = ""
            cycleTailIncludesPrevious = false
        }
        lastCycleId = token.id
        lastCycleTimeout = token.timeoutMillis
        lastCycleAt = nowMillis

        val previous = cyclePreviousVowel
        val combined = previous
            ?.let { combineVowels(it, selected, token.naratgulVowelPair) }
            ?.takeIf { selected.isVowel() }

        val eraseUnfoldedPrevious = combined != null && !cycleTailIncludesPrevious
        val backspaceCount = cycleTailKeys.length + when {
            eraseUnfoldedPrevious && previous != null -> encode(previous).length
            else -> 0
        }

        val target = when {
            combined != null -> encode(combined)
            !cycleTailIncludesPrevious || previous == null -> encode(selected.toString())
            else -> encode(previous) + encode(selected.toString())
        }
        if (combined != null) cycleTailIncludesPrevious = true

        currentVowel = if (selected.isVowel()) (combined ?: selected.toString()) else null
        lastJamo = if (selected.isVowel()) currentVowel?.singleOrNull() else selected
        lastCycleSelectedVowel = selected.isVowel()
        cycleTailKeys = target

        return List(backspaceCount) { Output.Backspace } + Output.Keys(target)
    }

    private fun pressSymbolCycle(token: Token.SymbolCycle, nowMillis: Long): List<Output> {
        require(token.symbols.isNotEmpty()) { "A symbol cycle key needs at least one symbol" }
        val replacing =
            lastSymbolCycleId == token.id && nowMillis - lastSymbolCycleAt <= token.timeoutMillis
        symbolCycleIndex = if (replacing) (symbolCycleIndex + 1) % token.symbols.size else 0
        val selected = token.symbols[symbolCycleIndex]
        clearCycle()
        pendingDots = 0
        currentVowel = null
        lastJamo = null
        lastSymbolCycleId = token.id
        lastSymbolCycleAt = nowMillis
        return buildList {
            if (replacing) add(Output.Backspace)
            add(Output.Keys(selected.toString()))
        }
    }

    private fun emitJamo(jamo: Char): List<Output> {
        clearCycle()
        pendingDots = 0
        currentVowel = jamo.toString().takeIf { jamo.isVowel() }
        lastJamo = jamo
        return listOf(Output.Keys(encode(jamo.toString())))
    }

    private fun pressDot(): List<Output> {
        clearCycle()
        lastJamo = null
        val old = currentVowel
        val next = when (old) {
            "ㅣ" -> "ㅏ"
            "ㅏ" -> "ㅑ"
            "ㅡ" -> "ㅜ"
            "ㅜ" -> "ㅠ"
            "ㅚ" -> "ㅘ"
            else -> null
        }
        if (next == null) {
            currentVowel = null
            pendingDots = if (pendingDots == 2) 1 else pendingDots + 1
            return emptyList()
        }
        pendingDots = 0
        currentVowel = next
        lastJamo = next.single()
        return replaceVowel(requireNotNull(old), next)
    }

    private fun pressChunjiinVowel(primitive: Char): List<Output> {
        clearCycle()
        lastJamo = null
        val old = currentVowel
        val combined = when {
            primitive == 'ㅣ' && pendingDots == 1 -> "ㅓ"
            primitive == 'ㅣ' && pendingDots == 2 -> "ㅕ"
            primitive == 'ㅡ' && pendingDots == 1 -> "ㅗ"
            primitive == 'ㅡ' && pendingDots == 2 -> "ㅛ"
            primitive == 'ㅣ' -> vowelICombination(old)
            else -> null
        }
        val next = combined ?: primitive.toString()
        pendingDots = 0
        currentVowel = next
        lastJamo = next.singleOrNull()
        return if (old == null || combined == null) {
            listOf(Output.Keys(encode(next)))
        } else {
            replaceVowel(old, next)
        }
    }

    /** ㅠ + ㅣ → ㅝ only on the Chunjiin family, which is the only one that builds ㅠ from ㆍ. */
    private fun vowelICombination(previous: String?): String? =
        chunjiinICombinations[previous]?.takeIf { family == MobileHangulFamily.Chunjiin || previous != "ㅠ" }

    /**
     * A timely space only closes a still-open Phonepad-style *consonant* multitap (K4); a vowel
     * cycle, and every 300ms single-vowel cycle, always produces a real space.
     */
    private fun pressBoundary(nowMillis: Long): List<Output> {
        val closesMultitap = lastCycleId != null &&
            nowMillis - lastCycleAt <= lastCycleTimeout &&
            lastCycleTimeout >= PHONEPAD_MULTITAP_TIMEOUT_MS &&
            !lastCycleSelectedVowel
        reset()
        return if (closesMultitap) emptyList() else listOf(Output.Space)
    }

    private fun replaceVowel(previous: String, next: String) =
        backspacesFor(previous) + Output.Keys(encode(next))

    /** libhangul Backspace removes one Dubeolsik input key, not one composed vowel. */
    private fun backspacesFor(jamo: String?) =
        List(jamo?.let(::encode)?.length ?: 0) { Output.Backspace }

    private fun encode(jamo: String) = jamo.map { dubeolsik.getValue(it) }.joinToString("")

    private fun transformLast(mapping: Map<Char, Char>): List<Output> {
        val next = lastJamo?.let(mapping::get) ?: return emptyList()
        lastJamo = next
        clearCycle()
        pendingDots = 0
        currentVowel = next.toString().takeIf { next.isVowel() }
        return listOf(Output.Backspace, Output.Keys(encode(next.toString())))
    }

    private fun clearCycle() {
        lastCycleId = null
        lastCycleTimeout = 0L
        cyclePreviousVowel = null
        cycleTailKeys = ""
        cycleTailIncludesPrevious = false
        lastCycleSelectedVowel = false
        lastSymbolCycleId = null
    }

    private fun combineVowels(
        previous: String?,
        next: Char,
        naratgulVowelPair: Boolean
    ): String? {
        // Samsung Naratgul intentionally interprets ㅜ + the ㅏ/ㅓ key's first tap as ㅝ.
        if (naratgulVowelPair && previous == "ㅜ" && next == 'ㅏ') return "ㅝ"
        return vowelCombinations[previous to next]
    }

    private fun Char.isVowel() = this in 'ㅏ'..'ㅣ'

    companion object {
        const val PHONEPAD_MULTITAP_TIMEOUT_MS = 1_500L
        const val SINGLE_VOWEL_MULTITAP_TIMEOUT_MS = 300L

        private val chunjiinICombinations = mapOf(
            "ㅏ" to "ㅐ", "ㅑ" to "ㅒ", "ㅓ" to "ㅔ", "ㅕ" to "ㅖ",
            "ㅗ" to "ㅚ", "ㅜ" to "ㅟ", "ㅡ" to "ㅢ",
            "ㅠ" to "ㅝ", "ㅘ" to "ㅙ", "ㅝ" to "ㅞ"
        )

        private val vowelCombinations = mapOf(
            ("ㅏ" to 'ㅣ') to "ㅐ", ("ㅑ" to 'ㅣ') to "ㅒ",
            ("ㅓ" to 'ㅣ') to "ㅔ", ("ㅕ" to 'ㅣ') to "ㅖ",
            ("ㅗ" to 'ㅏ') to "ㅘ", ("ㅗ" to 'ㅐ') to "ㅙ", ("ㅗ" to 'ㅣ') to "ㅚ",
            ("ㅜ" to 'ㅓ') to "ㅝ", ("ㅜ" to 'ㅔ') to "ㅞ", ("ㅜ" to 'ㅣ') to "ㅟ",
            ("ㅡ" to 'ㅣ') to "ㅢ", ("ㅘ" to 'ㅣ') to "ㅙ", ("ㅝ" to 'ㅣ') to "ㅞ"
        )

        private val strokeAdditions = mapOf(
            'ㄱ' to 'ㅋ', 'ㄴ' to 'ㄷ', 'ㄷ' to 'ㅌ',
            'ㅁ' to 'ㅂ', 'ㅂ' to 'ㅍ',
            'ㅅ' to 'ㅈ', 'ㅈ' to 'ㅊ', 'ㅇ' to 'ㅎ',
            'ㅏ' to 'ㅑ', 'ㅓ' to 'ㅕ', 'ㅗ' to 'ㅛ', 'ㅜ' to 'ㅠ',
            'ㅐ' to 'ㅒ', 'ㅔ' to 'ㅖ'
        )

        private val doubleConsonants = mapOf(
            'ㄱ' to 'ㄲ', 'ㄲ' to 'ㄱ', 'ㄷ' to 'ㄸ', 'ㄸ' to 'ㄷ',
            'ㅂ' to 'ㅃ', 'ㅃ' to 'ㅂ', 'ㅅ' to 'ㅆ', 'ㅆ' to 'ㅅ',
            'ㅈ' to 'ㅉ', 'ㅉ' to 'ㅈ'
        )

        private val dubeolsik = mapOf(
            'ㄱ' to "r", 'ㄲ' to "R", 'ㄴ' to "s", 'ㄷ' to "e", 'ㄸ' to "E",
            'ㄹ' to "f", 'ㅁ' to "a", 'ㅂ' to "q", 'ㅃ' to "Q", 'ㅅ' to "t",
            'ㅆ' to "T", 'ㅇ' to "d", 'ㅈ' to "w", 'ㅉ' to "W", 'ㅊ' to "c",
            'ㅋ' to "z", 'ㅌ' to "x", 'ㅍ' to "v", 'ㅎ' to "g",
            'ㅏ' to "k", 'ㅐ' to "o", 'ㅑ' to "i", 'ㅒ' to "O", 'ㅓ' to "j",
            'ㅔ' to "p", 'ㅕ' to "u", 'ㅖ' to "P", 'ㅗ' to "h", 'ㅘ' to "hk",
            'ㅙ' to "ho", 'ㅚ' to "hl", 'ㅛ' to "y", 'ㅜ' to "n", 'ㅝ' to "nj",
            'ㅞ' to "np", 'ㅟ' to "nl", 'ㅠ' to "b", 'ㅡ' to "m", 'ㅢ' to "ml",
            'ㅣ' to "l"
        )
    }
}
