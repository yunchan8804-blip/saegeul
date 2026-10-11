/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import org.fcitx.fcitx5.android.core.CandidateWord
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Splits the horizontal candidate bar's chips into a fast row and an AI row so that a chip the user
 * has already seen for the current input never moves or disappears when slower sources deliver.
 *
 * The fast row holds the native candidates and every contextual prediction, immediate or late. It
 * only grows within an input: a chip stays where it was, as the very instance first shown, even
 * after its source stops offering it; chips whose text is new are appended at the end, in the order
 * [HorizontalCandidateMerger.merge] gives that batch. The AI row holds the on-device automatic
 * suggestions only. Chips already shown keep their relative order, new ones are appended at the
 * end, and ones the source dropped (expired or invalidated) disappear.
 *
 * Holds the per-input state and nothing of Android, so it can be unit tested on the JVM.
 * [HorizontalCandidateComponent] calls [update] once per render.
 */
internal class StableCandidateRows {

    /**
     * What identifies the editor input a render belongs to. It must change whenever the editor text,
     * composition or cursor changes, and must not change when an asynchronous result (on-device
     * suggestion, contextual prediction, learning) arrives for the same input.
     */
    data class EditorInput(
        val sessionEpoch: Long,
        val stateRevision: Long,
        val textInspectionAllowed: Boolean,
        val addressField: Boolean
    )

    enum class AutomaticKind {
        /** A word to append. */
        WORD,

        /** A sentence completion to append. */
        SENTENCE,

        /** A sentence that rewrites the text being typed, so it is never hidden by a same-text chip. */
        REPLACEMENT
    }

    class AutomaticChip(val word: CandidateWord, val kind: AutomaticKind)

    class Chip(val word: CandidateWord, val sentence: Boolean)

    class Rows(
        val fast: List<Chip>,
        val ai: List<Chip>,
        /** The sentence chip of the landscape single row, one of the chips in [fast] or [ai]. */
        val landscapeSentence: Chip?
    ) {
        /** The one-row bar shows the fast row followed by the AI row. */
        val oneRow: List<Chip>
            get() = fast + ai

        /** The landscape single row's word list: every chip but [landscapeSentence]. */
        val landscapeWords: List<Chip>
            get() = oneRow.filter { it !== landscapeSentence }
    }

    private data class InputKey(val editor: EditorInput, val nativeTexts: List<String>)

    /**
     * A fast row chip as it was first shown. Native chips are looked up again by index so they stay
     * the instances fcitx delivered last: the key guarantees the native texts are the same.
     */
    private class FrozenChip(val word: CandidateWord, val sentence: Boolean, val nativeIndex: Int) {
        fun resolve(nativeCandidates: List<CandidateWord>): Chip =
            Chip(if (nativeIndex >= 0) nativeCandidates[nativeIndex] else word, sentence)
    }

    private var key: InputKey? = null
    private val frozenFast = mutableListOf<FrozenChip>()
    private var aiTexts: List<String> = emptyList()
    private var landscapeSentenceText: String? = null

    fun reset() {
        key = null
        frozenFast.clear()
        aiTexts = emptyList()
        landscapeSentenceText = null
    }

    /** With [EditorInput.addressField] the fast row follows the address field rule of [HorizontalCandidateMerger.merge]. */
    fun update(
        editor: EditorInput,
        nativeCandidates: List<CandidateWord>,
        contextualWords: List<CandidateWord>,
        contextualSentences: List<CandidateWord>,
        automatic: List<AutomaticChip>
    ): Rows {
        val nextKey = InputKey(editor, nativeCandidates.map { it.text })
        if (nextKey != key) {
            reset()
            key = nextKey
        }
        appendToFastRow(editor.addressField, nativeCandidates, contextualWords, contextualSentences)
        val fast = frozenFast.map { it.resolve(nativeCandidates) }
        val ai = appendOnly(incomingAiChips(fast, automatic))
        return Rows(fast, ai, pickLandscapeSentence(fast, ai))
    }

    /** Adds the candidates whose text the fast row does not show yet, ordered as one merge batch. */
    private fun appendToFastRow(
        addressField: Boolean,
        nativeCandidates: List<CandidateWord>,
        contextualWords: List<CandidateWord>,
        contextualSentences: List<CandidateWord>
    ) {
        val known = frozenFast.mapTo(HashSet()) { it.word.text }
        val newSentences = contextualSentences.filter { it.text !in known }
        val sentenceInstances = Collections.newSetFromMap(IdentityHashMap<CandidateWord, Boolean>())
        sentenceInstances.addAll(newSentences)
        HorizontalCandidateMerger
            .merge(
                nativeCandidates.filter { it.text !in known },
                contextualWords.filter { it.text !in known },
                newSentences,
                addressField
            )
            .mapTo(frozenFast) { word ->
                FrozenChip(word, word in sentenceInstances, nativeCandidates.indexOfFirst { it === word })
            }
    }

    /**
     * What this render offers for the AI row: automatic replacements and sentences, then automatic
     * words. A chip is left out when the fast row shows its text, except automatic replacements and
     * chips the AI row already showed: that check only decides what enters the AI row.
     */
    private fun incomingAiChips(fast: List<Chip>, automatic: List<AutomaticChip>): List<Chip> {
        val fastTexts = fast.mapTo(HashSet()) { it.word.text }
        val offered = automatic.filter {
            it.kind == AutomaticKind.REPLACEMENT || it.word.text in aiTexts || it.word.text !in fastTexts
        }
        val sentences = offered.filter { it.kind != AutomaticKind.WORD }.map { Chip(it.word, true) }
        val words = offered.filter { it.kind == AutomaticKind.WORD }.map { Chip(it.word, false) }
        return (sentences + words).distinctBy { it.word.text }
    }

    /** Keeps [aiTexts] order for chips still [incoming], drops the gone ones, appends the new ones. */
    private fun appendOnly(incoming: List<Chip>): List<Chip> {
        val byText = incoming.associateBy { it.word.text }
        val kept = aiTexts.filter { it in byText }
        val keptSet = kept.toHashSet()
        aiTexts = kept + incoming.map { it.word.text }.filter { it !in keptSet }
        return aiTexts.map { byText.getValue(it) }
    }

    /**
     * The first sentence of the AI row, else of the fast row; once an input has one it stays the
     * landscape sentence chip while it is still offered, so a later arrival cannot swap it out.
     */
    private fun pickLandscapeSentence(fast: List<Chip>, ai: List<Chip>): Chip? {
        val kept = landscapeSentenceText?.let { text ->
            (fast + ai).firstOrNull { it.sentence && it.word.text == text }
        }
        val chip = kept ?: ai.firstOrNull { it.sentence } ?: fast.firstOrNull { it.sentence }
        landscapeSentenceText = chip?.word?.text
        return chip
    }
}
