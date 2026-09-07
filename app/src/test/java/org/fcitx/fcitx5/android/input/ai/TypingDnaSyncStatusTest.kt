/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class TypingDnaSyncStatusTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `never synced or enriched returns NEVER`() {
        assertEquals(TypingDnaSyncLevel.NEVER, TypingDnaSyncStatus.level(0L, 0L))
    }

    @Test
    fun `synced without enrichment returns SYNC_ONLY`() {
        assertEquals(TypingDnaSyncLevel.SYNC_ONLY, TypingDnaSyncStatus.level(1_000L, 0L))
    }

    @Test
    fun `enrichment after sync returns ENRICHED`() {
        assertEquals(TypingDnaSyncLevel.ENRICHED, TypingDnaSyncStatus.level(1_000L, 1_001L))
    }

    @Test
    fun `enrichment older than sync returns SYNC_ONLY`() {
        assertEquals(TypingDnaSyncLevel.SYNC_ONLY, TypingDnaSyncStatus.level(1_000L, 0L))
        assertEquals(TypingDnaSyncLevel.SYNC_ONLY, TypingDnaSyncStatus.level(2_000L, 1_000L))
    }

    @Test
    fun `enrichment without recorded sync still returns ENRICHED`() {
        assertEquals(TypingDnaSyncLevel.ENRICHED, TypingDnaSyncStatus.level(0L, 1_000L))
    }

    @Test
    fun `formatTime uses HH mm when same day`() {
        // target 2026-01-05 03:30:00 UTC, now 2026-01-05 09:00:00 UTC
        val target = 1767583800000L
        val now = 1767603600000L
        assertEquals("03:30", TypingDnaSyncStatus.formatTime(target, now, utc))
    }

    @Test
    fun `formatTime uses M d HH mm when different day`() {
        // target 2026-01-04 23:00:00 UTC, now 2026-01-05 09:00:00 UTC
        val target = 1767567600000L
        val now = 1767603600000L
        assertEquals("1/4 23:00", TypingDnaSyncStatus.formatTime(target, now, utc))
    }
}
