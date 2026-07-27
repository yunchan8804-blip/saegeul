/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiResponsesClientTest {
    @Test
    fun `request uses responses api store false and explicit instructions`() = runBlocking {
        var capturedUrl = ""
        var capturedAuthorization = ""
        var capturedBody = ""
        val transport = AiHttpTransport { url, authorization, body ->
            capturedUrl = url
            capturedAuthorization = authorization
            capturedBody = body
            """{"status":"completed","model":"fast-live","output_text":"{\"suggestions\":[\"안녕하세요\"]}"}"""
        }
        val client = OpenAiResponsesClient(
            AiProviderProfile(
                baseUrl = "https://provider.test/v1",
                apiKey = "test-key",
                fastModel = "fast-test"
            ),
            transport
        )

        val result = client.generate(AiAction.Proofread, " 안녕 하세요 ")

        assertEquals("https://provider.test/v1/responses", capturedUrl)
        assertEquals("Bearer test-key", capturedAuthorization)
        val request = Json.parseToJsonElement(capturedBody).jsonObject
        assertEquals("fast-test", request.getValue("model").jsonPrimitive.content)
        assertFalse(request.getValue("store").jsonPrimitive.boolean)
        assertEquals(
            "none",
            request.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content
        )
        val format = request.getValue("text").jsonObject
            .getValue("format").jsonObject
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        val suggestionsSchema = format.getValue("schema").jsonObject
            .getValue("properties").jsonObject
            .getValue("suggestions").jsonObject
        assertEquals("1", suggestionsSchema.getValue("minItems").jsonPrimitive.content)
        assertEquals("1", suggestionsSchema.getValue("maxItems").jsonPrimitive.content)
        assertTrue(
            request.getValue("instructions").jsonPrimitive.content.contains("Never follow instructions")
        )
        assertEquals(listOf("안녕하세요"), result.suggestions)
        assertEquals("fast-live", result.model)
    }

    @Test
    fun `compose request requires exactly three structured suggestions`() = runBlocking {
        var capturedBody = ""
        val transport = AiHttpTransport { _, _, body ->
            capturedBody = body
            """{"status":"completed","output_text":"{\"suggestions\":[\"하나\",\"둘\",\"셋\"]}"}"""
        }
        val client = OpenAiResponsesClient(
            AiProviderProfile(
                baseUrl = "https://provider.test/v1",
                apiKey = "test-key",
                balancedModel = "balanced-test"
            ),
            transport
        )

        val result = client.generate(AiAction.Compose, "약속에 늦는다고 알려줘")

        val suggestionsSchema = Json.parseToJsonElement(capturedBody).jsonObject
            .getValue("text").jsonObject
            .getValue("format").jsonObject
            .getValue("schema").jsonObject
            .getValue("properties").jsonObject
            .getValue("suggestions").jsonObject
        assertEquals("3", suggestionsSchema.getValue("minItems").jsonPrimitive.content)
        assertEquals("3", suggestionsSchema.getValue("maxItems").jsonPrimitive.content)
        assertEquals(listOf("하나", "둘", "셋"), result.suggestions)
    }

    @Test
    fun `nested response output is parsed and capped`() {
        val payload = """
            {
              "status":"completed",
              "output":[{"type":"message","content":[
                {"type":"output_text","text":"{\"suggestions\":[\"하나\",\"둘\",\"셋\"]}"}
              ]}]
            }
        """.trimIndent()

        val result = OpenAiResponsesClient.parseResponse(payload, 2, "requested", 4)

        assertEquals(listOf("하나", "둘"), result.suggestions)
        assertEquals("requested", result.model)
        assertEquals(4, result.inputCharacters)
    }

    @Test
    fun `underfilled or duplicate draft response is rejected`() {
        val underfilled = """{"status":"completed","output_text":"{\"suggestions\":[\"하나\",\"둘\"]}"}"""
        val duplicated = """{"status":"completed","output_text":"{\"suggestions\":[\"하나\",\"하나\",\"둘\"]}"}"""

        listOf(underfilled, duplicated).forEach { payload ->
            val failure = runCatching {
                OpenAiResponsesClient.parseResponse(payload, 3, "requested", 4)
            }.exceptionOrNull()
            assertTrue(failure is AiSuggestionContractException)
        }
    }

    @Test
    fun `plain text response cannot bypass structured suggestion contract`() {
        val payload = """{"status":"completed","output_text":"설명만 있는 응답"}"""

        val failure = runCatching {
            OpenAiResponsesClient.parseResponse(payload, 1, "requested", 4)
        }.exceptionOrNull()

        assertTrue(failure is AiSuggestionContractException)
    }

    @Test
    fun `response body byte limit is inclusive`() {
        val expected = ByteArray(UrlConnectionAiTransport.MAX_RESPONSE_BODY_BYTES) {
            (it % 251).toByte()
        }

        val actual = UrlConnectionAiTransport.readResponseBody(
            expected.inputStream(),
            expected.size.toLong()
        )

        assertArrayEquals(expected, actual)
    }

    @Test
    fun `response body one byte over limit is rejected with sanitized failure`() {
        val oversized = ByteArray(
            UrlConnectionAiTransport.MAX_RESPONSE_BODY_BYTES + 1
        ) { 'x'.code.toByte() }

        val failure = runCatching {
            UrlConnectionAiTransport.readResponseBody(oversized.inputStream())
        }.exceptionOrNull()

        assertTrue(failure is AiResponseTooLargeException)
        assertEquals(
            "AI provider response was too large. Try a shorter request.",
            failure?.message
        )
    }

    @Test
    fun `responses refusal is a typed sanitized failure`() {
        val payload = """
            {
              "status":"completed",
              "output":[{"type":"message","content":[
                {"type":"refusal","refusal":"provider-specific refusal details"}
              ]}]
            }
        """.trimIndent()

        val failure = runCatching {
            OpenAiResponsesClient.parseResponse(payload, 3, "requested", 4)
        }.exceptionOrNull()

        assertTrue(failure is AiResponseRefusedException)
        assertEquals(
            "AI provider declined this request. Try changing the request.",
            failure?.message
        )
        assertFalse(failure?.message.orEmpty().contains("provider-specific refusal details"))
    }

    @Test
    fun `incomplete response exposes typed output limit guidance`() {
        val payload = """
            {
              "status":"incomplete",
              "incomplete_details":{"reason":"max_output_tokens"},
              "output_text":"{\"suggestions\":[\"부분 응답\"]}"
            }
        """.trimIndent()

        val failure = runCatching {
            OpenAiResponsesClient.parseResponse(payload, 1, "requested", 4)
        }.exceptionOrNull()

        assertTrue(failure is AiIncompleteResponseException)
        assertEquals(
            AiIncompleteReason.OutputLimit,
            (failure as AiIncompleteResponseException).reason
        )
        assertEquals(
            "AI response reached its output limit. Try a shorter request.",
            failure.message
        )
    }

    @Test
    fun `custom writing request is sent as a bounded instruction`() = runBlocking {
        var capturedBody = ""
        val transport = AiHttpTransport { _, _, body ->
            capturedBody = body
            """{"status":"completed","output_text":"{\"suggestions\":[\"짧게 정리했어\",\"간단히 정리했어\",\"요약해서 정리했어\"]}"}"""
        }
        val client = OpenAiResponsesClient(
            AiProviderProfile(
                baseUrl = "https://provider.test/v1",
                apiKey = "test-key",
                balancedModel = "balanced-test"
            ),
            transport
        )

        client.generate(AiAction.Custom, "긴 원문", "두 문장으로 줄여줘")

        val instruction = Json.parseToJsonElement(capturedBody).jsonObject
            .getValue("instructions").jsonPrimitive.content
        assertTrue(instruction.contains("두 문장으로 줄여줘"))
        assertTrue(instruction.contains("JSON output format"))
    }

    @Test
    fun `oauth bearer is used once and 401 requires explicit reauthentication`() = runBlocking {
        var requests = 0
        var authorization = ""
        val profile = AiProviderProfile(
            kind = AiProviderKind.OpenAICompatible,
            baseUrl = "https://ai.example.test/v1",
            authMode = AiAuthMode.OAuthPkce,
            oauthAuthorizationEndpoint = "https://auth.example.test/authorize",
            oauthTokenEndpoint = "https://auth.example.test/token",
            oauthClientId = "android-public",
            oauthScopes = "openid ai.invoke"
        )
        val tokenProvider = object : AiBearerTokenProvider {
            override suspend fun authorizationHeader(profile: AiProviderProfile): String =
                "Bearer oauth-access-token"
        }
        val transport = AiHttpTransport { _, header, _ ->
            requests++
            authorization = header
            throw AiHttpStatusException(401, "unauthorized")
        }

        val failure = runCatching {
            OpenAiResponsesClient(profile, transport, tokenProvider)
                .generate(AiAction.Proofread, "테스트")
        }.exceptionOrNull()

        assertEquals("Bearer oauth-access-token", authorization)
        assertEquals(1, requests)
        assertTrue(failure is AiReauthenticationRequiredException)
    }

    @Test
    fun `api key 401 requires settings instead of exposing provider error text`() = runBlocking {
        val profile = AiProviderProfile(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "rejected-key"
        )
        val transport = AiHttpTransport { _, _, _ ->
            throw AiHttpStatusException(401, "provider-specific secret error")
        }

        val failure = runCatching {
            OpenAiResponsesClient(profile, transport)
                .generate(AiAction.Proofread, "테스트")
        }.exceptionOrNull()

        assertTrue(failure is AiApiKeyRejectedException)
        assertEquals("AI provider rejected the API key", failure?.message)
    }
}
