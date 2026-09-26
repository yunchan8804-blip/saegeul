/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class SharedWarmResourceTest {

    private class FakeEngine(val key: String, val flavor: String) {
        var closed = false
    }

    private val created = mutableListOf<FakeEngine>()
    private val pendingCloses = mutableListOf<Runnable>()
    private val deferredExecutor = Executor { pendingCloses += it }

    private fun resource(executor: Executor = deferredExecutor) = SharedWarmResource<String, FakeEngine, String>(
        create = { key, flavor -> FakeEngine(key, flavor).also { created += it } },
        close = { it.closed = true },
        shouldReplace = { current, preferred -> preferred == "gpu" && current != "gpu" },
        closeExecutor = executor
    )

    private fun runPendingCloses() {
        val batch = pendingCloses.toList()
        pendingCloses.clear()
        batch.forEach { it.run() }
    }

    private val clock = { 0L }

    @Test
    fun secondPurposeReusesTheWarmEngineWithoutInitializing() {
        val shared = resource()
        val first = shared.acquire("model", "gpu", clock)
        first.release()
        val second = shared.acquire("model", "cpu", clock)

        assertEquals(1, created.size)
        assertFalse(first.reused)
        assertTrue(second.reused)
        assertSame(first.resource, second.resource)
        assertEquals("gpu", second.flavor)
        assertFalse(created.single().closed)
    }

    @Test
    fun releasingEveryUseKeepsTheEngineWarm() {
        val shared = resource()
        shared.acquire("model", "gpu", clock).release()
        runPendingCloses()

        assertTrue(shared.isWarm)
        assertFalse(created.single().closed)
    }

    @Test
    fun closeIsDeferredUntilTheLastUseEnds() {
        val shared = resource()
        val use = shared.acquire("model", "gpu", clock)
        shared.requestClose("TRIM_MEMORY")
        runPendingCloses()
        assertFalse(created.single().closed)

        use.release()
        runPendingCloses()
        assertTrue(created.single().closed)
        assertFalse(shared.isWarm)
    }

    @Test
    fun idleEngineClosesOnRequest() {
        val shared = resource()
        shared.acquire("model", "gpu", clock).release()
        shared.requestClose("RESOURCE")
        runPendingCloses()

        assertTrue(created.single().closed)
    }

    @Test
    fun acquireAfterAPendingCloseBuildsAFreshEngine() {
        val shared = resource()
        shared.acquire("model", "gpu", clock).release()
        shared.requestClose("FAILURE")
        val next = shared.acquire("model", "gpu", clock)

        assertEquals(2, created.size)
        assertTrue(created[0].closed)
        assertFalse(next.reused)
    }

    @Test
    fun cpuEngineIsUpgradedToGpuOnlyWhenNobodyUsesIt() {
        val shared = resource()
        val cpuUse = shared.acquire("model", "cpu", clock)
        val whileBusy = shared.acquire("model", "gpu", clock)
        assertEquals(1, created.size)
        assertEquals("cpu", whileBusy.flavor)

        cpuUse.release()
        whileBusy.release()
        val upgraded = shared.acquire("model", "gpu", clock)
        assertEquals(2, created.size)
        assertTrue(created[0].closed)
        assertEquals("gpu", upgraded.flavor)
    }

    @Test
    fun changedModelReplacesAnIdleEngineButNeverOneInUse() {
        val shared = resource()
        val use = shared.acquire("model-a", "gpu", clock)
        val busy = runCatching { shared.acquire("model-b", "gpu", clock) }.exceptionOrNull()
        assertEquals(SharedWarmResource.BUSY, busy?.message)
        assertFalse(created.single().closed)

        use.release()
        val replaced = shared.acquire("model-b", "gpu", clock)
        assertTrue(created[0].closed)
        assertEquals("model-b", replaced.resource.key)
    }

    @Test
    fun doubleReleaseCountsOnce() {
        val shared = resource()
        val first = shared.acquire("model", "gpu", clock)
        val second = shared.acquire("model", "gpu", clock)
        first.release()
        first.release()
        shared.requestClose("TRIM_MEMORY")
        runPendingCloses()
        assertFalse(created.single().closed)

        second.release()
        runPendingCloses()
        assertTrue(created.single().closed)
    }
}
