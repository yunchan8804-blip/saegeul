/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.fcitx.fcitx5.android.input.typo.BaseKoreanVocabulary
import org.fcitx.fcitx5.android.input.typo.KeyboardAwareTypoCorrector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringReader

class KeyboardTypoJudgeTest {

    private val words = listOf("오늘" to 100, "날씨" to 90, "좋네요" to 80, "안녕하세요" to 70, "회의" to 60)

    private fun judge(personalCount: (String) -> Float = { 0f }): KeyboardTypoJudge {
        val vocabulary = BaseKoreanVocabulary { StringReader(words.joinToString("\n") { (word, count) -> "$word\t$count" }) }
        vocabulary.load()
        val corrector = KeyboardAwareTypoCorrector()
        vocabulary.forEachWord { word, prior -> corrector.addWord(word, prior) }
        return KeyboardTypoJudge({ corrector }, { vocabulary }, personalCount)
    }

    @Test
    fun `a word of the base vocabulary is not a typo`() {
        assertNull(judge().correctionFor("날씨"))
    }

    @Test
    fun `a word the base vocabulary lacks gets the correction the corrector finds`() {
        assertEquals("좋네요", judge().correctionFor("좋네여"))
        assertEquals("안녕하세요", judge().correctionFor("안녕하세여"))
    }

    @Test
    fun `punctuation around the word is judged apart and put back on the correction`() {
        assertEquals("좋네요?", judge().correctionFor("좋네여?"))
        assertEquals("(좋네요!", judge().correctionFor("(좋네여!"))
    }

    @Test
    fun `a personal word the user typed twice or more is not a typo`() {
        assertNull(judge { word -> if (word == "좋네여") 2f else 0f }.correctionFor("좋네여"))
        assertEquals("좋네요", judge { word -> if (word == "좋네여") 1f else 0f }.correctionFor("좋네여"))
    }

    @Test
    fun `tokens that are not plain Hangul words are not judged`() {
        val judge = judge()

        assertNull(judge.correctionFor("3시에"))
        assertNull(judge.correctionFor("hello"))
        assertNull(judge.correctionFor("ㅋㅋㅋ"))
        assertNull(judge.correctionFor("여"))
        assertNull(judge.correctionFor("?"))
    }

    @Test
    fun `a word nothing in the vocabulary is near gets no correction`() {
        assertNull(judge().correctionFor("공유드릴게"))
    }

    @Test
    fun `a word that starts a dictionary word is a word prefix`() {
        val judge = judge()

        assertTrue(judge.isWordPrefix("안녕하"))
        assertTrue(judge.isWordPrefix("안녕하세요"))
        assertFalse(judge.isWordPrefix("안녕하세여"))
        assertFalse(judge.isWordPrefix("3시"))
    }

    @Test
    fun `with the bundled vocabulary a known word is not a typo and a keyboard slip is`() {
        val vocabulary = BaseKoreanVocabulary { bundledVocabularyFile().reader() }
        vocabulary.load()
        val corrector = KeyboardAwareTypoCorrector()
        vocabulary.forEachWord(BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) { word, prior -> corrector.addWord(word, prior) }
        val judge = KeyboardTypoJudge({ corrector }, { vocabulary }, { 0f })

        assertNull(judge.correctionFor("몇시에"))
        assertNull(judge.correctionFor("날씨"))
        assertNull(judge.correctionFor("정리해서"))
        assertNull(judge.correctionFor("공유드릴게"))
        assertEquals("만나요", judge.correctionFor("만나여"))
        assertEquals("좋네요", judge.correctionFor("좋네여"))
        assertEquals("안녕하세요", judge.correctionFor("안녕하세여"))
        assertTrue(judge.isWordPrefix("안녕하세"))
    }

    private fun bundledVocabularyFile(): File =
        listOf("app/src/main/assets/ko_base_vocab.tsv", "src/main/assets/ko_base_vocab.tsv")
            .map(::File)
            .firstOrNull(File::exists)
            ?: throw AssertionError("ko_base_vocab.tsv를 찾을 수 없습니다. cwd=${File(".").absolutePath}")
}
