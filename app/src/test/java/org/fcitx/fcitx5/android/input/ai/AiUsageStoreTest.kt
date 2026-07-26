/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AiUsageStoreTest {
    @Test
    fun `state update aggregates success failure characters and actions`() {
        val success = updateAiUsageSnapshot(
            current = AiUsageSnapshot(),
            action = AiAction.Proofread,
            provider = AiProviderKind.OpenAI,
            model = "gpt-fast",
            status = AiUsageStatus.Success,
            inputCharacters = 12,
            outputCharacters = 15,
            occurredAtEpochMillis = 100
        )
        val failure = updateAiUsageSnapshot(
            current = success,
            action = AiAction.Compose,
            provider = AiProviderKind.OpenAICompatible,
            model = "local-model",
            status = AiUsageStatus.Failure,
            inputCharacters = 8,
            outputCharacters = 999,
            occurredAtEpochMillis = 200
        )

        assertEquals(2L, failure.totalRequests)
        assertEquals(1L, failure.successfulRequests)
        assertEquals(1L, failure.failedRequests)
        assertEquals(20L, failure.inputCharacters)
        assertEquals(15L, failure.outputCharacters)
        assertEquals(1L, failure.actionCounts[AiAction.Proofread])
        assertEquals(1L, failure.actionCounts[AiAction.Compose])
        assertEquals(AiProviderKind.OpenAICompatible, failure.lastProvider)
        assertEquals("local-model", failure.lastModel)
        assertEquals(AiAction.Compose, failure.lastAction)
        assertEquals(AiUsageStatus.Failure, failure.lastStatus)
        assertEquals(200L, failure.lastOccurredAtEpochMillis)
    }

    @Test
    fun `json round trip contains aggregates but no content or credentials`() {
        val snapshot = AiUsageSnapshot(
            totalRequests = 3,
            successfulRequests = 2,
            failedRequests = 1,
            inputCharacters = 45,
            outputCharacters = 67,
            actionCounts = mapOf(AiAction.Polite to 2, AiAction.Reply to 1),
            lastProvider = AiProviderKind.OpenAI,
            lastModel = "gpt-model",
            lastAction = AiAction.Reply,
            lastStatus = AiUsageStatus.Success,
            lastOccurredAtEpochMillis = 123_456
        )

        val encoded = encodeAiUsageSnapshot(snapshot)
        assertEquals(snapshot, decodeAiUsageSnapshot(encoded))
        val json = encoded.toString(Charsets.UTF_8)
        assertFalse(json.contains("prompt", ignoreCase = true))
        assertFalse(json.contains("result", ignoreCase = true))
        assertFalse(json.contains("apiKey", ignoreCase = true))
        assertFalse(json.contains("downloadUrl", ignoreCase = true))
    }

    @Test
    fun `malformed or unsupported json returns an empty snapshot`() {
        assertEquals(AiUsageSnapshot(), decodeAiUsageSnapshot("not json".toByteArray()))
        assertEquals(
            AiUsageSnapshot(),
            decodeAiUsageSnapshot("{\"version\":99,\"total\":5}".toByteArray())
        )
    }

    @Test
    fun `decoder ignores unknown actions and clamps invalid counters`() {
        val decoded = decodeAiUsageSnapshot(
            """{
                "version":1,
                "total":-5,
                "success":2,
                "failure":1,
                "inputCharacters":-1,
                "outputCharacters":7,
                "actions":{"Polite":2,"RemovedAction":99},
                "last":{"provider":"RemovedProvider","action":"RemovedAction","status":"Failure","time":-1}
            }""".trimIndent().toByteArray()
        )

        assertEquals(0L, decoded.totalRequests)
        assertEquals(2L, decoded.successfulRequests)
        assertEquals(1L, decoded.failedRequests)
        assertEquals(0L, decoded.inputCharacters)
        assertEquals(7L, decoded.outputCharacters)
        assertEquals(mapOf(AiAction.Polite to 2L), decoded.actionCounts)
        assertEquals(null, decoded.lastProvider)
        assertEquals(null, decoded.lastAction)
        assertEquals(AiUsageStatus.Failure, decoded.lastStatus)
        assertEquals(null, decoded.lastOccurredAtEpochMillis)
    }
}
