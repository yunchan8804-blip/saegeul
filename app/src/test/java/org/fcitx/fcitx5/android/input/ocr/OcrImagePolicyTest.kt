/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrImagePolicyTest {
    @Test
    fun `only bounded bitmap formats are accepted`() {
        assertEquals(
            OcrImageMetadata("image/png", 1_000L),
            OcrImagePolicy.validateMetadata("image/png", 1_000L)
        )
        assertNull(OcrImagePolicy.validateMetadata("image/svg+xml", 1_000L))
        assertNull(OcrImagePolicy.validateMetadata(
            "image/jpeg",
            OcrImagePolicy.MAX_ENCODED_BYTES + 1L
        ))
    }

    @Test
    fun `decode sampling bounds dimensions and memory`() {
        assertEquals(1, OcrImagePolicy.sampleSize(1_000, 1_000))
        assertEquals(2, OcrImagePolicy.sampleSize(4_000, 3_000))
        assertNull(OcrImagePolicy.sampleSize(0, 1_000))
        assertNull(OcrImagePolicy.sampleSize(20_001, 1_000))
        assertNull(OcrImagePolicy.sampleSize(15_000, 15_000))
    }
}
