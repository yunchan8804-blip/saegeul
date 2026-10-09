/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeBitmapsTest {

    @Test
    fun imageWithinTheLimitIsNotSubsampled() {
        assertEquals(1, ThemeBitmaps.sampleSizeFor(1080, 2400))
        assertEquals(1, ThemeBitmaps.sampleSizeFor(4096, 4096))
    }

    @Test
    fun imageOverTheLimitIsSubsampledByPowersOfTwo() {
        assertEquals(2, ThemeBitmaps.sampleSizeFor(4097, 100))
        assertEquals(2, ThemeBitmaps.sampleSizeFor(100, 8192))
        assertEquals(4, ThemeBitmaps.sampleSizeFor(8193, 100))
        assertEquals(4, ThemeBitmaps.sampleSizeFor(16384, 16384))
    }

    @Test
    fun subsampledLongSideNeverExceedsTheLimit() {
        for (longSide in listOf(1, 4095, 4096, 4097, 6000, 9000, 16384)) {
            val sample = ThemeBitmaps.sampleSizeFor(longSide, 10)
            assertTrue("$longSide", (longSide + sample - 1) / sample <= ThemeBitmaps.MAX_DECODE_LONG_SIDE)
        }
    }

    @Test
    fun customLimitIsHonoured() {
        assertEquals(4, ThemeBitmaps.sampleSizeFor(4000, 3000, 1080))
    }

    @Test
    fun nonPositiveLimitIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ThemeBitmaps.sampleSizeFor(100, 100, 0)
        }
    }

    @Test
    fun importLimitIs16384Pixels() {
        assertTrue(ThemeBitmaps.isImportableSize(16384, 16384))
        assertTrue(ThemeBitmaps.isImportableSize(1, 1))
        assertFalse(ThemeBitmaps.isImportableSize(16385, 100))
        assertFalse(ThemeBitmaps.isImportableSize(100, 16385))
        assertFalse(ThemeBitmaps.isImportableSize(0, 100))
        assertFalse(ThemeBitmaps.isImportableSize(100, -1))
    }
}
