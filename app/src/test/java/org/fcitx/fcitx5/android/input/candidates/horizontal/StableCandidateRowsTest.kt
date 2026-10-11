/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.input.candidates.horizontal.StableCandidateRows.AutomaticChip
import org.fcitx.fcitx5.android.input.candidates.horizontal.StableCandidateRows.AutomaticKind
import org.fcitx.fcitx5.android.input.candidates.horizontal.StableCandidateRows.EditorInput
import org.fcitx.fcitx5.android.input.candidates.horizontal.StableCandidateRows.Rows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins that a chip shown for one input never moves or disappears when slower sources deliver:
 * the fast row (native and contextual chips) and the AI row (automatic suggestions) only grow at
 * their end while the input stays the same.
 */
class StableCandidateRowsTest {

    private val editor = EditorInput(sessionEpoch = 1, stateRevision = 10, textInspectionAllowed = true, addressField = false)

    private fun word(text: String, comment: String = "") = CandidateWord("", text, comment)

    private fun automatic(text: String, kind: AutomaticKind) = AutomaticChip(word(text, "자동"), kind)

    private fun List<StableCandidateRows.Chip>.texts() = map { it.word.text }

    private fun StableCandidateRows.render(
        editor: EditorInput = this@StableCandidateRowsTest.editor,
        native: List<CandidateWord> = emptyList(),
        words: List<CandidateWord> = emptyList(),
        sentences: List<CandidateWord> = emptyList(),
        automatic: List<AutomaticChip> = emptyList()
    ): Rows = update(editor, native, words, sentences, automatic)

    @Test
    fun `the first render shows the merge order of native and contextual chips`() {
        val rows = StableCandidateRows()

        val first = rows.render(
            native = listOf(word("A"), word("B")),
            words = listOf(word("W"), word("T", comment = "✏️")),
            sentences = listOf(word("S"))
        )

        assertEquals(listOf("A", "T", "B", "W", "S"), first.fast.texts())
        assertEquals(emptyList<String>(), first.ai.texts())
    }

    @Test
    fun `contextual chips arriving later are appended to the fast row and shown ones stay`() {
        val rows = StableCandidateRows()

        val first = rows.render()
        val arrived = rows.render(
            words = listOf(word("T", comment = "✏️"), word("W1")),
            sentences = listOf(word("S1"))
        )
        val changed = rows.render(words = listOf(word("W2"), word("W1")))

        assertEquals(emptyList<String>(), first.fast.texts())
        assertEquals(listOf("T", "W1", "S1"), arrived.fast.texts())
        assertEquals(listOf("T", "W1", "S1", "W2"), changed.fast.texts())
        assertEquals(emptyList<String>(), changed.ai.texts())
    }

    @Test
    fun `a batch arriving together is ordered by the merge rule and appended after the shown chips`() {
        val rows = StableCandidateRows()
        rows.render(native = listOf(word("A")), words = listOf(word("X")))

        val later = rows.render(
            native = listOf(word("A")),
            words = listOf(word("X"), word("W3"), word("T2", comment = "✏️")),
            sentences = listOf(word("S3"))
        )

        assertEquals(listOf("A", "X", "T2", "W3", "S3"), later.fast.texts())
    }

    @Test
    fun `a contextual chip its source stopped offering stays in the fast row`() {
        val rows = StableCandidateRows()
        rows.render(words = listOf(word("X"), word("Y")))

        val gone = rows.render(words = listOf(word("Z")))

        assertEquals(listOf("X", "Y", "Z"), gone.fast.texts())
    }

    @Test
    fun `only the contextual order changing leaves the fast row alone`() {
        val rows = StableCandidateRows()
        val native = listOf(word("A"))
        val first = rows.render(native = native, words = listOf(word("X"), word("Y")))

        val reranked = rows.render(native = native, words = listOf(word("Y"), word("X")))

        assertEquals(listOf("A", "X", "Y"), first.fast.texts())
        assertEquals(listOf("A", "X", "Y"), reranked.fast.texts())
    }

    @Test
    fun `the AI row holds only automatic chips, replacements and sentences before words`() {
        val rows = StableCandidateRows()

        val rendered = rows.render(
            words = listOf(word("cw")),
            sentences = listOf(word("cs")),
            automatic = listOf(
                automatic("W1", AutomaticKind.WORD),
                automatic("S1", AutomaticKind.SENTENCE),
                automatic("R1", AutomaticKind.REPLACEMENT)
            )
        )

        assertEquals(listOf("cw", "cs"), rendered.fast.texts())
        assertEquals(listOf("S1", "R1", "W1"), rendered.ai.texts())
    }

    @Test
    fun `an automatic chip with the text of a fast row chip is left out`() {
        val rows = StableCandidateRows()
        val native = listOf(word("A"), word("B"))

        rows.render(native = native, words = listOf(word("W1")), sentences = listOf(word("감사합니다.")))
        val later = rows.render(
            native = native,
            words = listOf(word("W1")),
            sentences = listOf(word("감사합니다.")),
            automatic = listOf(
                automatic("B", AutomaticKind.WORD),
                automatic("W1", AutomaticKind.WORD),
                automatic("감사합니다.", AutomaticKind.SENTENCE),
                automatic("G", AutomaticKind.WORD)
            )
        )

        assertEquals(listOf("G"), later.ai.texts())
    }

    @Test
    fun `an AI row chip stays when the same text later arrives in the fast row`() {
        val rows = StableCandidateRows()

        rows.render(automatic = listOf(automatic("G", AutomaticKind.WORD)))
        val later = rows.render(
            words = listOf(word("G")),
            automatic = listOf(automatic("G", AutomaticKind.WORD), automatic("H", AutomaticKind.WORD))
        )

        assertEquals(listOf("G"), later.fast.texts())
        assertEquals(listOf("G", "H"), later.ai.texts())
    }

    @Test
    fun `an automatic replacement stays in the AI row even when the fast row shows the same text`() {
        val rows = StableCandidateRows()
        val native = listOf(word("안녕하세요."))

        rows.render(native = native)
        val later = rows.render(
            native = native,
            automatic = listOf(
                automatic("안녕하세요.", AutomaticKind.REPLACEMENT),
                automatic("네 알겠습니다.", AutomaticKind.SENTENCE)
            )
        )

        assertEquals(listOf("안녕하세요."), later.fast.texts())
        assertEquals(listOf("안녕하세요.", "네 알겠습니다."), later.ai.texts())
    }

    @Test
    fun `an AI row chip keeps its place, new chips are appended and dropped chips leave`() {
        val rows = StableCandidateRows()

        val first = rows.render(automatic = listOf(automatic("G1", AutomaticKind.WORD)))
        assertEquals(listOf("G1"), first.ai.texts())

        val withSentence = rows.render(
            automatic = listOf(automatic("S1", AutomaticKind.SENTENCE), automatic("G1", AutomaticKind.WORD))
        )
        assertEquals(listOf("G1", "S1"), withSentence.ai.texts())

        val withNew = rows.render(
            automatic = listOf(
                automatic("C", AutomaticKind.SENTENCE),
                automatic("S1", AutomaticKind.SENTENCE),
                automatic("G1", AutomaticKind.WORD)
            )
        )
        assertEquals(listOf("G1", "S1", "C"), withNew.ai.texts())

        val withoutG1 = rows.render(
            automatic = listOf(automatic("C", AutomaticKind.SENTENCE), automatic("S1", AutomaticKind.SENTENCE))
        )
        assertEquals(listOf("S1", "C"), withoutG1.ai.texts())
    }

    @Test
    fun `a new input rebuilds both rows`() {
        val rows = StableCandidateRows()
        rows.render(native = listOf(word("A")), words = listOf(word("X")), automatic = listOf(automatic("G", AutomaticKind.WORD)))

        val otherRevision = rows.render(
            editor = editor.copy(stateRevision = 11),
            native = listOf(word("A")),
            words = listOf(word("Y"))
        )
        assertEquals(listOf("A", "Y"), otherRevision.fast.texts())
        assertEquals(emptyList<String>(), otherRevision.ai.texts())

        rows.render(editor = editor.copy(stateRevision = 11), native = listOf(word("A")), automatic = listOf(automatic("G", AutomaticKind.WORD)))
        val otherNative = rows.render(
            editor = editor.copy(stateRevision = 11),
            native = listOf(word("A"), word("B")),
            words = listOf(word("Z")),
            automatic = listOf(automatic("G2", AutomaticKind.WORD))
        )
        assertEquals(listOf("A", "B", "Z"), otherNative.fast.texts())
        assertEquals(listOf("G2"), otherNative.ai.texts())

        val otherSession = rows.render(
            editor = editor.copy(sessionEpoch = 2, stateRevision = 11),
            native = listOf(word("A"), word("B")),
            words = listOf(word("V"))
        )
        assertEquals(listOf("A", "B", "V"), otherSession.fast.texts())
        assertEquals(emptyList<String>(), otherSession.ai.texts())
    }

    @Test
    fun `reset forgets the fast row`() {
        val rows = StableCandidateRows()
        rows.render(native = listOf(word("A")), words = listOf(word("X")))

        rows.reset()
        val after = rows.render(native = listOf(word("A")), words = listOf(word("Y")))

        assertEquals(listOf("A", "Y"), after.fast.texts())
    }

    @Test
    fun `the one-row bar shows the fast row then the AI row`() {
        val rows = StableCandidateRows()
        val native = listOf(word("A"))
        rows.render(native = native, words = listOf(word("X")), sentences = listOf(word("S")))

        val later = rows.render(
            native = native,
            words = listOf(word("X")),
            sentences = listOf(word("S")),
            automatic = listOf(automatic("G", AutomaticKind.WORD), automatic("GS", AutomaticKind.SENTENCE))
        )

        assertEquals(listOf("A", "X", "S"), later.fast.texts())
        assertEquals(listOf("GS", "G"), later.ai.texts())
        assertEquals(listOf("A", "X", "S", "GS", "G"), later.oneRow.texts())
    }

    @Test
    fun `an address field keeps the address merge order and appends later chips after it`() {
        val address = editor.copy(addressField = true)
        val rows = StableCandidateRows()
        val native = listOf(word("gmail"), word("gmai"), word("gma"))

        val first = rows.render(
            editor = address,
            native = native,
            words = listOf(word("@gmail.com"), word(".com")),
            sentences = listOf(word("메일 주소를 확인해 주세요."))
        )
        val later = rows.render(
            editor = address,
            native = native,
            words = listOf(word(".com"), word("@gmail.com"), word("@naver.com")),
            automatic = listOf(automatic("G", AutomaticKind.WORD))
        )

        assertEquals(listOf("gmail", "@gmail.com", ".com", "gmai", "gma"), first.fast.texts())
        assertEquals(listOf("gmail", "@gmail.com", ".com", "gmai", "gma", "@naver.com"), later.fast.texts())
        assertEquals(listOf("G"), later.ai.texts())
        assertEquals(listOf("gmail", "@gmail.com", ".com", "gmai", "gma", "@naver.com", "G"), later.oneRow.texts())
    }

    @Test
    fun `the fast row keeps the first contextual instances and the latest native ones`() {
        val rows = StableCandidateRows()
        val contextual = word("X")
        rows.render(native = listOf(word("A"), word("B")), words = listOf(contextual))

        val laterNative = listOf(word("A"), word("B"))
        val later = rows.render(native = laterNative, words = listOf(word("X")))

        assertSame(laterNative[0], later.fast[0].word)
        assertSame(laterNative[1], later.fast[1].word)
        assertSame(contextual, later.fast[2].word)
    }

    @Test
    fun `the landscape sentence is the AI row's first sentence, else the fast row's, and then stays`() {
        val rows = StableCandidateRows()
        val native = listOf(word("A"))
        val fastSentence = word("FS")

        val first = rows.render(native = native, sentences = listOf(fastSentence))
        assertSame(fastSentence, first.landscapeSentence?.word)
        assertEquals(listOf("A"), first.landscapeWords.texts())

        val later = rows.render(
            native = native,
            sentences = listOf(fastSentence),
            automatic = listOf(automatic("GS", AutomaticKind.SENTENCE), automatic("G", AutomaticKind.WORD))
        )
        assertSame(fastSentence, later.landscapeSentence?.word)
        assertEquals(listOf("A", "GS", "G"), later.landscapeWords.texts())

        val fresh = StableCandidateRows().render(
            native = native,
            sentences = listOf(word("FS")),
            automatic = listOf(automatic("GS", AutomaticKind.SENTENCE))
        )
        assertEquals("GS", fresh.landscapeSentence?.word?.text)
    }

    @Test
    fun `the landscape sentence is the first AI sentence when the fast row has none`() {
        val rows = StableCandidateRows()
        val native = listOf(word("A"))
        val empty = rows.render(native = native)
        assertNull(empty.landscapeSentence)

        val later = rows.render(
            native = native,
            automatic = listOf(automatic("G", AutomaticKind.WORD), automatic("GS", AutomaticKind.SENTENCE))
        )

        assertEquals("GS", later.landscapeSentence?.word?.text)
        assertEquals(listOf("A", "G"), later.landscapeWords.texts())
    }
}
