/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCrashAttributionTest {

    private val native = ApplicationExitInfo.REASON_CRASH_NATIVE

    private fun exit(pid: Int, timestampMs: Long, reason: Int = native) =
        NativeCrashAttribution.ExitRecord(pid, timestampMs, reason)

    private fun breadcrumb(purpose: String, pid: Int) = NativeCrashAttribution.Breadcrumb(purpose, 10L, pid)

    @Test
    fun `matching pid names the purpose held at the crash`() {
        val result = NativeCrashAttribution.evaluate(
            listOf(exit(pid = 42, timestampMs = 1_000L)),
            breadcrumb("AUTO_CONTEXT", pid = 42),
            lastCheckedMs = 0L
        )

        assertEquals(listOf(NativeCrashAttribution.Crash(1_000L, "AUTO_CONTEXT")), result.crashes)
        assertEquals(1_000L, result.checkedMs)
    }

    @Test
    fun `different pid leaves the purpose unknown`() {
        val result = NativeCrashAttribution.evaluate(
            listOf(exit(pid = 42, timestampMs = 1_000L)),
            breadcrumb("AUTO_CONTEXT", pid = 41),
            lastCheckedMs = 0L
        )

        assertEquals(listOf(NativeCrashAttribution.Crash(1_000L, NativeCrashAttribution.PURPOSE_NONE)), result.crashes)
    }

    @Test
    fun `empty purpose with matching pid leaves the purpose unknown`() {
        val result = NativeCrashAttribution.evaluate(
            listOf(exit(pid = 42, timestampMs = 1_000L)),
            breadcrumb("", pid = 42),
            lastCheckedMs = 0L
        )

        assertEquals(listOf(NativeCrashAttribution.Crash(1_000L, NativeCrashAttribution.PURPOSE_NONE)), result.crashes)
    }

    @Test
    fun `crashes at or before the last checked time are ignored`() {
        val result = NativeCrashAttribution.evaluate(
            listOf(exit(pid = 42, timestampMs = 1_000L), exit(pid = 43, timestampMs = 900L)),
            breadcrumb("AUTO_CONTEXT", pid = 42),
            lastCheckedMs = 1_000L
        )

        assertTrue(result.crashes.isEmpty())
        assertEquals(1_000L, result.checkedMs)
    }

    @Test
    fun `exits that are not native crashes are ignored`() {
        val result = NativeCrashAttribution.evaluate(
            listOf(
                exit(pid = 42, timestampMs = 1_000L, reason = ApplicationExitInfo.REASON_CRASH),
                exit(pid = 43, timestampMs = 2_000L, reason = ApplicationExitInfo.REASON_USER_REQUESTED)
            ),
            breadcrumb("AUTO_CONTEXT", pid = 42),
            lastCheckedMs = 500L
        )

        assertTrue(result.crashes.isEmpty())
        assertEquals(500L, result.checkedMs)
    }

    @Test
    fun `several new crashes are ordered by time and only the matching pid gets the purpose`() {
        val result = NativeCrashAttribution.evaluate(
            listOf(exit(pid = 44, timestampMs = 3_000L), exit(pid = 43, timestampMs = 2_000L)),
            breadcrumb("PERSONAL_GRAPH", pid = 44),
            lastCheckedMs = 100L
        )

        assertEquals(
            listOf(
                NativeCrashAttribution.Crash(2_000L, NativeCrashAttribution.PURPOSE_NONE),
                NativeCrashAttribution.Crash(3_000L, "PERSONAL_GRAPH")
            ),
            result.crashes
        )
        assertEquals(3_000L, result.checkedMs)
    }

    private fun released(lastPurpose: String, releasedMs: Long, pid: Int) =
        NativeCrashAttribution.Breadcrumb("", 0L, pid, lastPurpose, releasedMs)

    @Test
    fun `crash within the window after a release is attributed to the last purpose`() {
        val window = NativeCrashAttribution.RELEASE_ATTRIBUTION_WINDOW_MS
        val result = NativeCrashAttribution.evaluate(
            listOf(exit(pid = 42, timestampMs = 5_000L + window)),
            released("AUTO_CONTEXT", releasedMs = 5_000L, pid = 42),
            lastCheckedMs = 0L
        )

        assertEquals(listOf(NativeCrashAttribution.Crash(5_000L + window, "AUTO_CONTEXT_AFTER_RELEASE")), result.crashes)
    }

    @Test
    fun `crash later than the window after a release stays unknown`() {
        val window = NativeCrashAttribution.RELEASE_ATTRIBUTION_WINDOW_MS
        val result = NativeCrashAttribution.evaluate(
            listOf(exit(pid = 42, timestampMs = 5_000L + window + 1)),
            released("AUTO_CONTEXT", releasedMs = 5_000L, pid = 42),
            lastCheckedMs = 0L
        )

        assertEquals(listOf(NativeCrashAttribution.Crash(5_000L + window + 1, NativeCrashAttribution.PURPOSE_NONE)), result.crashes)
    }

    @Test
    fun `crash before the release time stays unknown`() {
        val result = NativeCrashAttribution.evaluate(
            listOf(exit(pid = 42, timestampMs = 4_999L)),
            released("AUTO_CONTEXT", releasedMs = 5_000L, pid = 42),
            lastCheckedMs = 0L
        )

        assertEquals(listOf(NativeCrashAttribution.Crash(4_999L, NativeCrashAttribution.PURPOSE_NONE)), result.crashes)
    }

    @Test
    fun `release attribution needs the same pid`() {
        val result = NativeCrashAttribution.evaluate(
            listOf(exit(pid = 43, timestampMs = 6_000L)),
            released("AUTO_CONTEXT", releasedMs = 5_000L, pid = 42),
            lastCheckedMs = 0L
        )

        assertEquals(listOf(NativeCrashAttribution.Crash(6_000L, NativeCrashAttribution.PURPOSE_NONE)), result.crashes)
    }

    @Test
    fun `a held purpose wins over the last released purpose`() {
        val result = NativeCrashAttribution.evaluate(
            listOf(exit(pid = 42, timestampMs = 6_000L)),
            NativeCrashAttribution.Breadcrumb("PERSONAL_GRAPH", 5_500L, 42, "PERSONAL_GRAPH", 0L),
            lastCheckedMs = 0L
        )

        assertEquals(listOf(NativeCrashAttribution.Crash(6_000L, "PERSONAL_GRAPH")), result.crashes)
    }
}
