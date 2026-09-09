/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 캡처한 provider wire replay 경계 계약을 검사하며 live model, HTTP, IME 작업은 수행하지 않는다. */
@RunWith(Parameterized::class)
class CapturedPrefixContinuationContractTest(
    private val replay: Replay
) {
    @Test
    fun `captured typed continuation preserves the prefix and expected editor text`() {
        val result = OpenAiResponsesClient.parseResponse(
            payload = replay.responseEnvelope.toString(),
            maxSuggestions = 3,
            requestedModel = replay.model,
            inputCharacters = replay.rawPrefix.length,
            continuationAbstention = true
        )

        assertEquals(replay.suggestions, result.suggestions)
        assertEquals(replay.model, result.model)
        assertTrue(replay.expectedEditorText.startsWith(replay.rawPrefix))
        assertNoModelSpecialToken(replay.rawPrefix)

        val continuation = PrefetchedContinuation.parse(result.suggestions, replay.rawPrefix).single()

        assertEquals(replay.expectedKind, continuation.kind)
        assertEquals(replay.payload, continuation.text)
        assertNoModelSpecialToken(continuation.text)

        val joinMode = when (continuation.kind) {
            PrefetchedContinuation.Kind.WORD,
            PrefetchedContinuation.Kind.CONTINUATION -> ContextualAppend.JoinMode.NEXT_WORD
            PrefetchedContinuation.Kind.CONTINUATION_ATTACH -> ContextualAppend.JoinMode.ATTACH
        }
        val insertion = ContextualAppend(
            expectedContext = replay.rawPrefix,
            suffix = continuation.text,
            joinMode = joinMode
        ).insertionFor(replay.rawPrefix)

        assertNotNull(insertion)
        val editorText = replay.rawPrefix + insertion
        assertNoModelSpecialToken(editorText)
        assertEquals(replay.expectedEditorText, editorText)
    }

    private fun assertNoModelSpecialToken(value: String) {
        assertFalse(
            "Captured model output must not contain a model special token: $value",
            value.contains("<|") || value.contains("|>") || value.contains("<s>") || value.contains("</s>")
        )
    }

    data class Replay(
        val rawPrefix: String,
        val responseEnvelope: JsonObject,
        val expectedEditorText: String,
        val expectedKind: PrefetchedContinuation.Kind,
        val payload: String,
        val suggestions: List<String>
    ) {
        val model: String = responseEnvelope.getValue("model").jsonPrimitive.content

        override fun toString(): String = rawPrefix.replace("\n", "\\n")
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0}")
        fun replayCases(): Collection<Array<Any>> {
            val classLoader = requireNotNull(CapturedPrefixContinuationContractTest::class.java.classLoader)
            val resource = requireNotNull(
                classLoader.getResourceAsStream("ai/kanana-prefix-wire-replay.json")
            )
            val root = resource.bufferedReader().use { reader ->
                Json.parseToJsonElement(reader.readText()).jsonObject
            }
            assertEquals(
                "Fixture must remain a captured replay rather than live inference",
                "captured_model_output_replay_not_live_inference",
                root.getValue("mode").jsonPrimitive.content
            )
            val records = root.getValue("records").jsonArray
            assertEquals("Fixture must contain every captured prefix", 12, records.size)
            return records.map { element ->
                arrayOf<Any>(parseReplay(element.jsonObject))
            }
        }

        private fun parseReplay(record: JsonObject): Replay {
            val diagnostic = record.getValue("adapter_diagnostic").jsonObject
            val suggestions = diagnostic.getValue("suggestions").jsonArray.stringValues()
            val wire = suggestions.single()
            return Replay(
                rawPrefix = record.getValue("raw_prefix").jsonPrimitive.content,
                responseEnvelope = record.getValue("response_envelope").jsonObject,
                expectedEditorText = record.getValue("expected_editor_text").jsonPrimitive.content,
                expectedKind = PrefetchedContinuation.Kind.valueOf(wire.substringBefore('\t')),
                payload = diagnostic.getValue("payload").jsonPrimitive.content,
                suggestions = suggestions
            )
        }

        private fun JsonArray.stringValues(): List<String> = map { it.jsonPrimitive.content }
    }
}
