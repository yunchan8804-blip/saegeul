/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypoEvidenceTest {

    private val evidence = typoEvidenceOf("좋네여" to "좋네요", "안녕하세" to "안녕하세요", wordPrefixes = setOf("안녕하세"))

    @Test
    fun `a typo word gives its correction`() {
        assertEquals("좋네요", evidence.correctionFor("좋네여", stillTyping = true))
        assertEquals("좋네요", evidence.correctionFor("좋네여", stillTyping = false))
    }

    @Test
    fun `a word that is not a typo gives nothing`() {
        assertNull(evidence.correctionFor("날씨", stillTyping = false))
    }

    @Test
    fun `a word still being typed that starts a dictionary word is not a typo`() {
        assertNull(evidence.correctionFor("안녕하세", stillTyping = true))
        assertEquals("안녕하세요", evidence.correctionFor("안녕하세", stillTyping = false))
    }
}
