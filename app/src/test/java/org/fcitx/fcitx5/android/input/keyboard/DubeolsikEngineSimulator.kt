/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * A minimal but real Dubeolsik automaton for tests, shared across this package's Mobile Hangul
 * composer tests. Unlike a flat key-string buffer, it actually composes/decomposes syllables:
 * choseong+jungseong(+jongseong), vowel-combining (ㅗ+ㅏ→ㅘ), compound finals (ㄱ+ㅅ→ㄳ), the
 * "도깨비불" batchim move when a vowel follows a syllable that already has one, and commits a
 * syllable to the editor text the moment a key can no longer attach to it. Backspace undoes
 * exactly one still-open component, or one already-committed character if nothing is open.
 *
 * [RedTeamHangulEngineAutomataTest]'s VirtualDubeolsikBuffer is a different, simpler stand-in
 * used by that red-team suite and is left untouched; this class is for the rest of this package.
 */
class DubeolsikEngineSimulator {
    private val committed = StringBuilder()
    private var choseongKey: Char? = null
    private val jungseongKeys = mutableListOf<Char>()
    private val jongseongKeys = mutableListOf<Char>()

    fun apply(outputs: List<MobileHangulComposer.Output>) {
        outputs.forEach { output ->
            when (output) {
                MobileHangulComposer.Output.Backspace -> backspace()
                MobileHangulComposer.Output.Space -> {
                    flush()
                    committed.append(' ')
                }
                is MobileHangulComposer.Output.Keys -> output.value.forEach(::pressRawKey)
            }
        }
    }

    /** Committed editor text, followed by whatever syllable is still open (being composed). */
    fun content(): String = committed.toString() + openText()

    /** Editor text that is already finalized and can no longer change. */
    fun committedText(): String = committed.toString()

    /**
     * Flattens [text] into its underlying sequence of choseong/jungseong/jongseong compatibility
     * jamo, breaking every compound vowel or batchim down into its base components (e.g. ㅘ ->
     * ㅗ,ㅏ; ㄶ -> ㄴ,ㅎ). A character outside the precomposed Hangul syllable block — a space, a
     * punctuation mark, or a still-open bare compatibility jamo — passes through unchanged as its
     * own single element.
     */
    fun flattenJamo(text: String): List<Char> = text.flatMap(::decomposeChar)

    private fun decomposeChar(ch: Char): List<Char> {
        val sIndex = ch.code - 0xAC00
        if (sIndex < 0 || sIndex >= HANGUL_SYLLABLE_COUNT) return listOf(ch)
        val cho = CHOSEONG_LIST[sIndex / (21 * 28)]
        val jung = JUNGSEONG_LIST[(sIndex % (21 * 28)) / 28]
        val jongIdx = sIndex % 28
        return buildList {
            add(cho)
            addAll(decomposeCombined(jung, JUNGSEONG_COMBINE))
            if (jongIdx != 0) addAll(decomposeCombined(JONGSEONG_LIST[jongIdx - 1], JONGSEONG_COMBINE))
        }
    }

    private fun decomposeCombined(jamo: Char, combine: Map<Pair<Char, Char>, Char>): List<Char> {
        val pair = combine.entries.firstOrNull { it.value == jamo }?.key ?: return listOf(jamo)
        return decomposeCombined(pair.first, combine) + pair.second
    }

    /**
     * Whether this state is still a plausible waypoint toward eventually typing [target].
     *
     * The already-committed text is checked as *characters*, not jamo: `committed` can never
     * shrink again (Backspace is not among the search's own moves), so once a syllable is flushed
     * its boundary is permanently frozen — decomposing it into jamo and comparing in flattened
     * jamo space would silently let a *wrongly split* commit through whenever its jamo sequence
     * happens to coincide with a *different* split of target's jamo. Concretely: typing "많다" can
     * flush "만" (ㅁㅏㄴ) the moment ㅅ fails to combine with jong ㄴ, then leave a bare open ㅎ;
     * flattened, "만"+ㅎ is ㅁㅏㄴㅎ — identical to "많"'s own flattening — even though "만" is
     * already the wrong, permanently-committed syllable and can never become "많" again. Comparing
     * the committed portion as characters against target's corresponding substring catches exactly
     * this, since "만" (as committed text) is simply not a prefix of "많다".
     *
     * The still-open syllable's choseong and jungseong are allowed to not yet match target there,
     * for as long as they could still be rewritten outright rather than merely grown: several
     * primitives (the Chunjiin ㅣ/ㆍ/ㅡ keys, a [MobileHangulComposer.Token.Cycle]'s multitap
     * replace, [MobileHangulComposer.Token.AddStroke]) send Backspace then a fresh, unrelated key
     * instead of combining onto what's already open (choseong ㄴ on the way to ㄹ, say). A
     * choseong locks in the moment its jungseong starts (any further consonant becomes a jongseong
     * instead); a jungseong locks in the moment *its* jongseong starts. The jongseong itself is
     * never checked against target at all, at any point: a consonant that does not belong on this
     * syllable is not a dead end, because it is exactly a "tentative batchim" — the instant the
     * *next* syllable's vowel is pressed, 도깨비불 moves it off to become that syllable's
     * choseong, which is how a syllable with no batchim of its own is normally typed right before
     * a following consonant-initial syllable. Reaching [target] exactly is decided separately, by
     * plain string equality on [content].
     */
    fun isOnTrackTo(target: String): Boolean {
        val committedSoFar = committed.toString()
        if (committedSoFar.length > target.length || !target.startsWith(committedSoFar)) return false
        val openJung = jungseongValue()
        if (choseongKey == null && openJung == null) return true
        if (committedSoFar.length >= target.length) return false
        val sIndex = target[committedSoFar.length].code - 0xAC00
        if (sIndex < 0 || sIndex >= HANGUL_SYLLABLE_COUNT) return false
        val choT = CHOSEONG_LIST[sIndex / (21 * 28)]
        val jungT = JUNGSEONG_LIST[(sIndex % (21 * 28)) / 28]
        if (choseongKey == null) return false
        if (openJung == null) return true
        if (choseongKey != choT) return false
        if (jongseongKeys.isEmpty()) return true
        return openJung == jungT
    }

    private fun pressRawKey(rawKey: Char) {
        val jamo = ATOMIC_KEYS[rawKey]
        if (jamo == null) {
            // Not a Dubeolsik ASCII key: this is a literal character (e.g. punctuation from a
            // [MobileHangulComposer.Token.SymbolCycle], which emits the raw symbol itself rather
            // than an encoded jamo key). Typing it forces the open syllable to commit first, then
            // appends the literal character on its own, exactly like a raw Fcitx key action would.
            flush()
            committed.append(rawKey)
            return
        }
        if (jamo in CHOSEONG_LIST) pressConsonant(jamo) else pressVowel(jamo)
    }

    private fun pressConsonant(c: Char) {
        when {
            jungseongKeys.isEmpty() -> {
                // No open vowel yet: a bare, un-followed consonant just sits there on its own; a
                // second one commits it as its own bare jamo letter and starts fresh with this one.
                if (choseongKey != null) committed.append(choseongKey)
                choseongKey = c
            }
            jongseongKeys.isEmpty() -> jongseongKeys.add(c)
            else -> {
                val combinable = JONGSEONG_COMBINE.containsKey(jongseongKeys.last() to c)
                if (combinable) {
                    jongseongKeys.add(c)
                } else {
                    flush()
                    choseongKey = c
                }
            }
        }
    }

    private fun pressVowel(v: Char) {
        if (jongseongKeys.isNotEmpty()) {
            // 도깨비불: the jongseong's last consonant moves to become the next syllable's onset.
            val moved = jongseongKeys.removeLast()
            flush()
            choseongKey = moved
            jungseongKeys.add(v)
            return
        }
        if (jungseongKeys.isEmpty()) {
            jungseongKeys.add(v)
            return
        }
        val combinable = JUNGSEONG_COMBINE.containsKey(jungseongValue()!! to v)
        if (combinable) {
            jungseongKeys.add(v)
        } else {
            flush()
            jungseongKeys.add(v)
        }
    }

    private fun backspace() {
        when {
            jongseongKeys.isNotEmpty() -> jongseongKeys.removeAt(jongseongKeys.lastIndex)
            jungseongKeys.isNotEmpty() -> jungseongKeys.removeAt(jungseongKeys.lastIndex)
            choseongKey != null -> choseongKey = null
            committed.isNotEmpty() -> committed.deleteCharAt(committed.length - 1)
        }
    }

    private fun flush() {
        if (choseongKey != null || jungseongKeys.isNotEmpty()) {
            committed.append(openText())
        }
        choseongKey = null
        jungseongKeys.clear()
        jongseongKeys.clear()
    }

    private fun openText(): String {
        val jung = jungseongValue()
        val jong = jongseongValue()
        return when {
            choseongKey == null && jung == null -> ""
            jung == null -> choseongKey.toString()
            // A standalone vowel with no onset and no jongseong yet shows as its bare
            // compatibility jamo (e.g. "ㅡ", "ㅢ"), matching real libhangul/Dubeolsik behavior;
            // the filler ㅇ onset is only synthesized once a jongseong forces a full syllable
            // block (e.g. ㅡ + ㄱ -> 윽).
            choseongKey == null && jong == null -> jung.toString()
            else -> composeSyllable(choseongKey ?: FILLER_IEUNG, jung, jong)
        }
    }

    private fun jungseongValue(): Char? = jungseongKeys.fold(null as Char?) { acc, k ->
        if (acc == null) k else JUNGSEONG_COMBINE[acc to k] ?: k
    }

    private fun jongseongValue(): Char? = jongseongKeys.fold(null as Char?) { acc, k ->
        if (acc == null) k else JONGSEONG_COMBINE[acc to k] ?: k
    }

    private fun composeSyllable(cho: Char, jung: Char, jong: Char?): String {
        val choIdx = CHOSEONG_LIST.indexOf(cho)
        val jungIdx = JUNGSEONG_LIST.indexOf(jung)
        val jongIdx = if (jong == null) 0 else JONGSEONG_LIST.indexOf(jong) + 1
        if (choIdx < 0 || jungIdx < 0 || jongIdx < 0) return buildString {
            append(cho); append(jung); jong?.let(::append)
        }
        val codePoint = 0xAC00 + (choIdx * 21 + jungIdx) * 28 + jongIdx
        return codePoint.toChar().toString()
    }

    companion object {
        private const val FILLER_IEUNG = 'ㅇ'
        private const val HANGUL_SYLLABLE_COUNT = 19 * 21 * 28

        private const val CHOSEONG_LIST = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
        private const val JUNGSEONG_LIST = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"

        /** The 27 possible jongseong, index 0 (none) handled separately by [composeSyllable]. */
        private const val JONGSEONG_LIST = "ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

        private val JUNGSEONG_COMBINE = mapOf(
            ('ㅏ' to 'ㅣ') to 'ㅐ', ('ㅑ' to 'ㅣ') to 'ㅒ',
            ('ㅓ' to 'ㅣ') to 'ㅔ', ('ㅕ' to 'ㅣ') to 'ㅖ',
            ('ㅗ' to 'ㅏ') to 'ㅘ', ('ㅗ' to 'ㅐ') to 'ㅙ', ('ㅗ' to 'ㅣ') to 'ㅚ',
            ('ㅜ' to 'ㅓ') to 'ㅝ', ('ㅜ' to 'ㅔ') to 'ㅞ', ('ㅜ' to 'ㅣ') to 'ㅟ',
            ('ㅡ' to 'ㅣ') to 'ㅢ', ('ㅘ' to 'ㅣ') to 'ㅙ', ('ㅝ' to 'ㅣ') to 'ㅞ'
        )

        private val JONGSEONG_COMBINE = mapOf(
            ('ㄱ' to 'ㅅ') to 'ㄳ',
            ('ㄴ' to 'ㅈ') to 'ㄵ', ('ㄴ' to 'ㅎ') to 'ㄶ',
            ('ㄹ' to 'ㄱ') to 'ㄺ', ('ㄹ' to 'ㅁ') to 'ㄻ', ('ㄹ' to 'ㅂ') to 'ㄼ',
            ('ㄹ' to 'ㅅ') to 'ㄽ', ('ㄹ' to 'ㅌ') to 'ㄾ', ('ㄹ' to 'ㅍ') to 'ㄿ',
            ('ㄹ' to 'ㅎ') to 'ㅀ',
            ('ㅂ' to 'ㅅ') to 'ㅄ'
        )

        /** Reverse of [MobileHangulComposer]'s own dubeolsik key map: raw ASCII key -> jamo. */
        private val ATOMIC_KEYS: Map<Char, Char> = mapOf(
            'r' to 'ㄱ', 'R' to 'ㄲ', 's' to 'ㄴ', 'e' to 'ㄷ', 'E' to 'ㄸ',
            'f' to 'ㄹ', 'a' to 'ㅁ', 'q' to 'ㅂ', 'Q' to 'ㅃ', 't' to 'ㅅ',
            'T' to 'ㅆ', 'd' to 'ㅇ', 'w' to 'ㅈ', 'W' to 'ㅉ', 'c' to 'ㅊ',
            'z' to 'ㅋ', 'x' to 'ㅌ', 'v' to 'ㅍ', 'g' to 'ㅎ',
            'k' to 'ㅏ', 'o' to 'ㅐ', 'i' to 'ㅑ', 'O' to 'ㅒ', 'j' to 'ㅓ',
            'p' to 'ㅔ', 'u' to 'ㅕ', 'P' to 'ㅖ', 'h' to 'ㅗ', 'y' to 'ㅛ',
            'n' to 'ㅜ', 'b' to 'ㅠ', 'm' to 'ㅡ', 'l' to 'ㅣ'
        )
    }
}
