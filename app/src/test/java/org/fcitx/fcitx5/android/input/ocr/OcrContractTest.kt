/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrContractTest {
    @Test
    fun `editor binding requires one known cursor`() {
        assertEquals(
            OcrEditorTarget("chat.app", 4, 1, 12),
            OcrTextContract.bindEditor("chat.app", 4, 1, 12, 12)
        )
        assertNull(OcrTextContract.bindEditor("chat.app", 4, 1, 12, 13))
        assertNull(OcrTextContract.bindEditor("", 4, 1, 12, 12))
        assertNull(OcrTextContract.bindEditor("chat.app", 4, 1, -1, -1))
    }

    @Test
    fun `OCR lines are bounded and only explicit selections are formatted`() {
        val blocks = OcrTextContract.parse("  첫 줄\r\n\r\n 둘째 줄  ")

        assertEquals(listOf("첫 줄", "둘째 줄"), blocks?.map(OcrTextBlock::text))
        assertNull(OcrTextContract.format(blocks.orEmpty(), emptySet()))
        assertEquals(
            "둘째 줄",
            OcrTextContract.format(blocks.orEmpty(), setOf("line:1"))
        )
        assertNull(OcrTextContract.format(blocks.orEmpty(), setOf("missing")))
        assertNull(OcrTextContract.parse("가".repeat(OcrTextContract.MAX_BLOCK_CHARACTERS + 1)))
    }

    @Test
    fun `one reviewed OCR result inserts at most once`() {
        val gate = OcrCommitGate()

        assertTrue(gate.claim())
        assertFalse(gate.claim())
        gate.resetForReview()
        assertTrue(gate.claim())
    }
}
