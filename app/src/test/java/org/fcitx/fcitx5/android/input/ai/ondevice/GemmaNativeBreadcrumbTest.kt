/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GemmaNativeBreadcrumbTest {

    private data class Write(val purpose: String?, val sinceMs: Long)

    private val writes = mutableListOf<Write>()
    private var now = 1_000L

    @Before
    fun setUp() {
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        OnDeviceGenerationControl.clockMs = { now }
        OnDeviceGenerationControl.configureBreadcrumb { purpose, sinceMs -> writes += Write(purpose, sinceMs) }
    }

    @After
    fun tearDown() {
        OnDeviceGenerationControl.configureBreadcrumb(null)
        OnDeviceGenerationControl.clockMs = System::currentTimeMillis
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
    }

    @Test
    fun `lease begin and end write the holder then an empty value`() {
        val lease = requireNotNull(
            OnDeviceGenerationControl.tryBegin(OnDeviceGenerationControl.Purpose.EXPLICIT_CONTEXT) {}
        )
        now = 2_000L
        OnDeviceGenerationControl.end(lease)

        assertEquals(listOf(Write("EXPLICIT_CONTEXT", 1_000L), Write(null, 2_000L)), writes)
    }

    @Test
    fun `a denied begin and a stale end write nothing`() {
        assertNull(OnDeviceGenerationControl.tryBegin(OnDeviceGenerationControl.Purpose.AUTO_CONTEXT) {})
        val lease = requireNotNull(
            OnDeviceGenerationControl.tryBegin(OnDeviceGenerationControl.Purpose.EXPLICIT_CONTEXT) {}
        )
        OnDeviceGenerationControl.end(lease)
        writes.clear()

        assertEquals(false, OnDeviceGenerationControl.end(lease))

        assertTrue(writes.isEmpty())
    }

    @Test
    fun `preemption writes the new holder and ending the preempted lease writes nothing`() {
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
        val auto = requireNotNull(
            OnDeviceGenerationControl.tryBegin(OnDeviceGenerationControl.Purpose.AUTO_CONTEXT) {}
        )
        now = 3_000L
        val explicit = requireNotNull(
            OnDeviceGenerationControl.tryBegin(OnDeviceGenerationControl.Purpose.EXPLICIT_CONTEXT) {}
        )
        assertEquals(false, OnDeviceGenerationControl.end(auto))
        now = 4_000L
        OnDeviceGenerationControl.end(explicit)

        assertEquals(
            listOf(Write("AUTO_CONTEXT", 1_000L), Write("EXPLICIT_CONTEXT", 3_000L), Write(null, 4_000L)),
            writes
        )
    }

    @Test
    fun `store keeps the holder with its process pid and a new process drops the old one`() {
        val prefs = InMemorySharedPreferences()
        val dying = GemmaNativeBreadcrumbStore(prefs, { 42 }, Unit)
        dying.onHolderChanged("AUTO_CONTEXT", 1_000L)

        assertEquals(NativeCrashAttribution.Breadcrumb("AUTO_CONTEXT", 1_000L, 42, "AUTO_CONTEXT", 0L), dying.read())

        val restarted = GemmaNativeBreadcrumbStore(prefs, { 77 }, Unit)
        assertEquals(NativeCrashAttribution.Breadcrumb("AUTO_CONTEXT", 1_000L, 42, "AUTO_CONTEXT", 0L), restarted.read())
        restarted.onHolderChanged("PERSONAL_GRAPH", 2_000L)
        restarted.clearOtherProcess()
        assertEquals(NativeCrashAttribution.Breadcrumb("PERSONAL_GRAPH", 2_000L, 77, "PERSONAL_GRAPH", 0L), restarted.read())

        val third = GemmaNativeBreadcrumbStore(prefs, { 90 }, Unit)
        third.clearOtherProcess()
        assertEquals(NativeCrashAttribution.Breadcrumb("", 0L, 0), third.read())
    }

    @Test
    fun `release empties the purpose and keeps the last purpose with the release time`() {
        val store = GemmaNativeBreadcrumbStore(InMemorySharedPreferences(), { 42 }, Unit)
        store.onHolderChanged("AUTO_CONTEXT", 1_000L)

        store.onHolderChanged(null, 2_000L)

        assertEquals(NativeCrashAttribution.Breadcrumb("", 0L, 42, "AUTO_CONTEXT", 2_000L), store.read())
    }

    @Test
    fun `acquiring again after a release clears the release time`() {
        val store = GemmaNativeBreadcrumbStore(InMemorySharedPreferences(), { 42 }, Unit)
        store.onHolderChanged("AUTO_CONTEXT", 1_000L)
        store.onHolderChanged(null, 2_000L)

        store.onHolderChanged("PERSONAL_GRAPH", 3_000L)

        assertEquals(NativeCrashAttribution.Breadcrumb("PERSONAL_GRAPH", 3_000L, 42, "PERSONAL_GRAPH", 0L), store.read())
    }
}
