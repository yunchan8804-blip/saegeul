/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for [GemmaGraphGenerationSession.collectGenerated]: an engine error is a lease loss
 * (resumable pause) only when this session was itself cancelled - the preemption signal - and stays
 * a plain failure otherwise.
 */
class GemmaGraphGenerationSessionTest {

    private class EngineError(message: String) : RuntimeException(message)

    private fun failingResponses(error: Throwable): Flow<String> = flow {
        emit("{")
        throw error
    }

    @Test
    fun collectsAllPiecesWhenNothingWentWrong() {
        val text = runBlocking {
            GemmaGraphGenerationSession().collectGenerated(flow { emit("{\"a\":"); emit("1}") })
        }

        assertEquals("{\"a\":1}", text)
    }

    @Test
    fun engineErrorAfterPreemptionBecomesALeaseLoss() {
        val session = GemmaGraphGenerationSession()
        val engineError = EngineError("native cancelled")
        session.cancel()

        val lost = assertThrows(GemmaGraphGenerationSession.LeaseLostException::class.java) {
            runBlocking { session.collectGenerated(failingResponses(engineError)) }
        }

        assertSame(engineError, lost.cause)
    }

    @Test
    fun engineErrorWithoutPreemptionStaysAFailure() {
        val session = GemmaGraphGenerationSession()
        val engineError = EngineError("Input token ids are too long")

        val thrown = assertThrows(EngineError::class.java) {
            runBlocking { session.collectGenerated(failingResponses(engineError)) }
        }

        assertSame(engineError, thrown)
    }

    @Test
    fun cancellationAfterPreemptionBecomesALeaseLoss() {
        val session = GemmaGraphGenerationSession()
        session.cancel()

        assertThrows(GemmaGraphGenerationSession.LeaseLostException::class.java) {
            runBlocking { session.collectGenerated(failingResponses(CancellationException("native"))) }
        }
    }

    @Test
    fun cancellationWithoutPreemptionPropagatesAsCancellation() {
        val session = GemmaGraphGenerationSession()

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { session.collectGenerated(failingResponses(CancellationException("worker stopped"))) }
        }

        assertEquals("worker stopped", thrown.message)
    }

    @Test
    fun finishingNormallyAfterPreemptionIsStillALeaseLoss() {
        val session = GemmaGraphGenerationSession()
        session.cancel()

        assertThrows(GemmaGraphGenerationSession.LeaseLostException::class.java) {
            runBlocking { session.collectGenerated(flow { emit("{}") }) }
        }
    }
}
