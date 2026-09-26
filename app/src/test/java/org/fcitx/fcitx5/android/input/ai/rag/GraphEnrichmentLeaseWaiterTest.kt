/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphEnrichmentLeaseWaiterTest {

    private class FakeBusyException : IllegalStateException("busy")

    private fun isBusy(error: Throwable): Boolean = error is FakeBusyException

    @Test
    fun succeedsImmediatelyWithoutWaitingWhenTheFirstAttemptSucceeds() = runBlocking {
        var sleeps = 0
        val waitingCalls = mutableListOf<Boolean>()

        val result = GraphEnrichmentLeaseWaiter.waitForLease(
            manual = false,
            shouldAbort = { false },
            isBusy = ::isBusy,
            attempt = { "value" },
            nowMs = { 0L },
            sleep = { sleeps++ },
            onWaitingChanged = { waitingCalls.add(it) }
        )

        assertEquals(GraphEnrichmentLeaseWaiter.Result.Acquired("value"), result)
        assertEquals(0, sleeps)
        assertTrue(waitingCalls.isEmpty())
    }

    @Test
    fun retriesOnBusyAndAcquiresOnceTheLeaseFreesUp() = runBlocking {
        var attempts = 0
        var clock = 0L
        val waitingCalls = mutableListOf<Boolean>()

        val result = GraphEnrichmentLeaseWaiter.waitForLease(
            manual = false,
            shouldAbort = { false },
            isBusy = ::isBusy,
            attempt = {
                attempts++
                if (attempts < 3) throw FakeBusyException()
                "acquired"
            },
            nowMs = { clock },
            sleep = { clock += it },
            onWaitingChanged = { waitingCalls.add(it) }
        )

        assertEquals(GraphEnrichmentLeaseWaiter.Result.Acquired("acquired"), result)
        assertEquals(3, attempts)
        assertEquals(listOf(true, false), waitingCalls)
    }

    @Test
    fun givesUpAfterExceedingTheManualMaxWaitWithoutAcquiring() = runBlocking {
        var clock = 0L
        val waitingCalls = mutableListOf<Boolean>()

        val result = GraphEnrichmentLeaseWaiter.waitForLease(
            manual = true,
            shouldAbort = { false },
            isBusy = ::isBusy,
            attempt = { throw FakeBusyException() },
            nowMs = { clock },
            sleep = { clock += GraphEnrichmentLeaseWaiter.RETRY_INTERVAL_MS },
            onWaitingChanged = { waitingCalls.add(it) }
        )

        assertEquals(GraphEnrichmentLeaseWaiter.Result.GaveUp, result)
        assertTrue(clock >= GraphEnrichmentLeaseWaiter.MANUAL_MAX_WAIT_MS)
        assertEquals(listOf(true, false), waitingCalls)
    }

    @Test
    fun automaticGivesUpMuchSoonerThanManual() = runBlocking {
        var clock = 0L
        var attempts = 0

        val result = GraphEnrichmentLeaseWaiter.waitForLease(
            manual = false,
            shouldAbort = { false },
            isBusy = ::isBusy,
            attempt = { attempts++; throw FakeBusyException() },
            nowMs = { clock },
            sleep = { clock += GraphEnrichmentLeaseWaiter.RETRY_INTERVAL_MS }
        )

        assertEquals(GraphEnrichmentLeaseWaiter.Result.GaveUp, result)
        assertTrue(clock < GraphEnrichmentLeaseWaiter.MANUAL_MAX_WAIT_MS)
        assertTrue(clock >= GraphEnrichmentLeaseWaiter.AUTOMATIC_MAX_WAIT_MS)
    }

    @Test
    fun abortsImmediatelyWhenShouldAbortIsAlreadyTrue() = runBlocking {
        var attempts = 0
        var sleeps = 0

        val result = GraphEnrichmentLeaseWaiter.waitForLease(
            manual = false,
            shouldAbort = { true },
            isBusy = ::isBusy,
            attempt = { attempts++; "unused" },
            sleep = { sleeps++ }
        )

        assertEquals(GraphEnrichmentLeaseWaiter.Result.Aborted, result)
        assertEquals(0, attempts)
        assertEquals(0, sleeps)
    }

    @Test
    fun abortsMidWaitWithoutReachingTheDeadline() = runBlocking {
        var attempts = 0
        val waitingCalls = mutableListOf<Boolean>()

        val result = GraphEnrichmentLeaseWaiter.waitForLease(
            manual = true,
            shouldAbort = { attempts >= 2 },
            isBusy = ::isBusy,
            attempt = { attempts++; throw FakeBusyException() },
            nowMs = { 0L },
            sleep = {},
            onWaitingChanged = { waitingCalls.add(it) }
        )

        assertEquals(GraphEnrichmentLeaseWaiter.Result.Aborted, result)
        assertEquals(2, attempts)
        assertEquals(listOf(true, false), waitingCalls)
    }

    @Test
    fun nonBusyFailuresPropagateImmediatelyWithoutRetrying() = runBlocking {
        var attempts = 0
        val error = IllegalStateException("real failure")

        try {
            GraphEnrichmentLeaseWaiter.waitForLease(
                manual = false,
                shouldAbort = { false },
                isBusy = { false },
                attempt = { attempts++; throw error }
            )
            org.junit.Assert.fail("expected the real failure to propagate")
        } catch (thrown: IllegalStateException) {
            assertEquals(error, thrown)
        }
        assertEquals(1, attempts)
    }

    @Test
    fun cancellationPropagatesInsteadOfBeingTreatedAsBusy() = runBlocking {
        try {
            GraphEnrichmentLeaseWaiter.waitForLease(
                manual = false,
                shouldAbort = { false },
                isBusy = { true },
                attempt = { throw CancellationException("cancelled") }
            )
            org.junit.Assert.fail("expected cancellation to propagate")
        } catch (_: CancellationException) {
        }
    }
}
