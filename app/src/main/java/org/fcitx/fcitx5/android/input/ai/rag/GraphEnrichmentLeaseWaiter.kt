/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Retries a lease-gated [attempt] (on-device Gemma generation, which fails immediately when the
 * keyboard or another on-device generation purpose already holds
 * [org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl]'s single lease) instead of
 * treating that contention as a hard failure. On real device traces, the previous "fail fast on
 * busy" behavior meant the personal-graph worker gave up as soon as it lost a lease race against
 * material generation, even though the other side typically finishes within seconds to a couple of
 * minutes.
 *
 * No I/O of its own; [nowMs], [sleep] and [attempt] are injected so this is deterministically unit
 * testable without real sleeps.
 */
object GraphEnrichmentLeaseWaiter {

    const val RETRY_INTERVAL_MS = 3_000L
    const val MANUAL_MAX_WAIT_MS = 20L * 60 * 1000
    const val AUTOMATIC_MAX_WAIT_MS = 5L * 60 * 1000

    fun maxWaitMs(manual: Boolean): Long = if (manual) MANUAL_MAX_WAIT_MS else AUTOMATIC_MAX_WAIT_MS

    sealed interface Result<out T> {
        data class Acquired<T>(val value: T) : Result<T>

        /** The wait exceeded [maxWaitMs] without the lease ever becoming free. */
        data object GaveUp : Result<Nothing>

        /** [shouldAbort] returned true before the lease became free (worker stopping, conditions broke). */
        data object Aborted : Result<Nothing>
    }

    /**
     * Calls [attempt] once; if it throws and [isBusy] classifies that throwable as lease
     * contention, waits [retryIntervalMs] and retries, checking [shouldAbort] before every attempt
     * (including the first) and reporting the transition into/out of a waiting state via
     * [onWaitingChanged]. A non-busy throwable from [attempt] (a real failure) propagates
     * immediately, uncaught. Gives up once [nowMs] has advanced past start-time + [maxWaitMs].
     */
    suspend fun <T> waitForLease(
        manual: Boolean,
        shouldAbort: suspend () -> Boolean,
        isBusy: (Throwable) -> Boolean,
        attempt: suspend () -> T,
        nowMs: () -> Long = System::currentTimeMillis,
        sleep: suspend (Long) -> Unit = { delay(it) },
        retryIntervalMs: Long = RETRY_INTERVAL_MS,
        onWaitingChanged: suspend (Boolean) -> Unit = {}
    ): Result<T> {
        val deadline = nowMs() + maxWaitMs(manual)
        var waiting = false
        try {
            while (true) {
                if (shouldAbort()) return Result.Aborted
                try {
                    return Result.Acquired(attempt())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (!isBusy(error)) throw error
                    if (nowMs() >= deadline) return Result.GaveUp
                }
                if (!waiting) {
                    waiting = true
                    onWaitingChanged(true)
                }
                sleep(retryIntervalMs)
            }
        } finally {
            if (waiting) onWaitingChanged(false)
        }
    }
}
