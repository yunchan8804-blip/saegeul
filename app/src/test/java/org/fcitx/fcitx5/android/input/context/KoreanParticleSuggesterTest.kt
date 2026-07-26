/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KoreanParticleSuggesterTest {
    @Test
    fun `vowel ending chooses vowel particles`() {
        assertEquals(
            listOf("는", "가", "를", "와", "로", "예요"),
            KoreanParticleSuggester.suggest("학교").map(KoreanParticleSuggestion::text)
        )
    }

    @Test
    fun `consonant ending chooses consonant particles`() {
        assertEquals(
            listOf("은", "이", "을", "과", "으로", "이에요"),
            KoreanParticleSuggester.suggest("집").map(KoreanParticleSuggestion::text)
        )
    }

    @Test
    fun `rieul batchim uses direction ro exception`() {
        val direction = KoreanParticleSuggester.suggest("서울")
            .single { it.kind == KoreanParticleKind.Direction }

        assertEquals("로", direction.text)
    }

    @Test
    fun `only the immediate Hangul word ending is eligible`() {
        assertTrue(KoreanParticleSuggester.suggest("hello").isEmpty())
        assertTrue(KoreanParticleSuggester.suggest("한글!").isEmpty())
        assertEquals("은", KoreanParticleSuggester.suggest("한글  ").first().text)
    }

    @Test
    fun `limit is deterministic and zero does no work`() {
        assertEquals(listOf("는", "가"), KoreanParticleSuggester.suggest("나", 2).map { it.text })
        assertTrue(KoreanParticleSuggester.suggest("나", 0).isEmpty())
    }

    @Test
    fun `reviewed particle can be claimed only once`() {
        val gate = KoreanParticleCommitGate()

        assertTrue(gate.claim())
        assertTrue(!gate.claim())
    }
}
