/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

data class AiGenerationResult(
    val suggestions: List<String>,
    val model: String,
    val inputCharacters: Int,
    val outputCharacters: Int
)

fun interface AiHttpTransport {
    fun post(url: String, authorization: String, body: String): String
}

class OpenAiResponsesClient(
    private val profile: AiProviderProfile,
    private val transport: AiHttpTransport = UrlConnectionAiTransport(),
    private val authorizationProvider: AiBearerTokenProvider = ProfileAiBearerTokenProvider
) {
    suspend fun generate(
        action: AiAction,
        input: String,
        customInstruction: String? = null
    ): AiGenerationResult =
        withContext(Dispatchers.IO) {
            val cleanInput = input.trim()
            require(cleanInput.isNotEmpty() || action == AiAction.Custom) { "AI input is empty" }
            require(cleanInput.length <= MAX_INPUT_CHARACTERS) { "AI input is too long" }
            val requestInput = cleanInput.ifEmpty {
                "Create a new message from the explicit writing request."
            }
            val validated = profile.validate()
            val model = validated.model(action.tier)
            val request = buildJsonObject {
                put("model", model)
                put("instructions", action.developerInstruction(customInstruction))
                put("input", requestInput)
                put("store", false)
                put("max_output_tokens", MAX_OUTPUT_TOKENS)
                put("reasoning", buildJsonObject { put("effort", "none") })
                put("text", buildJsonObject {
                    put("verbosity", "low")
                    put("format", buildJsonObject {
                        put("type", "json_schema")
                        put("name", "fcitx_ai_suggestions")
                        put("strict", true)
                        put("schema", buildJsonObject {
                            put("type", "object")
                            put("additionalProperties", false)
                            put("properties", buildJsonObject {
                                put("suggestions", buildJsonObject {
                                    put("type", "array")
                                    put("minItems", action.maxSuggestions)
                                    put("maxItems", action.maxSuggestions)
                                    put("items", buildJsonObject { put("type", "string") })
                                })
                            })
                            put("required", buildJsonArray { add("suggestions") })
                        })
                    })
                })
            }
            val authorization = authorizationProvider.authorizationHeader(validated)
            val response = try {
                transport.post(validated.responsesEndpoint, authorization, request.toString())
            } catch (exception: AiHttpStatusException) {
                if (exception.status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    throw authorizationProvider.onUnauthorized(validated)
                }
                throw AiProviderException(exception.message ?: "AI provider request failed")
            }
            parseResponse(response, action.maxSuggestions, model, cleanInput.length)
        }

    companion object {
        const val MAX_INPUT_CHARACTERS = 4_000
        private const val MAX_OUTPUT_TOKENS = 1_024

        internal fun parseResponse(
            payload: String,
            maxSuggestions: Int,
            requestedModel: String,
            inputCharacters: Int
        ): AiGenerationResult {
            val root = runCatching { JSON.parseToJsonElement(payload).jsonObject }
                .getOrElse { throw AiProviderException("AI provider returned invalid JSON") }
            val status = root.string("status")
            if (status.isNotEmpty() && status != "completed") {
                throw AiProviderException("AI response did not complete")
            }
            val outputText = root.string("output_text").takeIf(String::isNotBlank)
                ?: extractOutputText(root["output"] as? JsonArray)
                ?: throw AiProviderException("AI response contained no text")
            val normalizedSuggestions = parseSuggestions(outputText)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
            if (normalizedSuggestions.size < maxSuggestions) {
                throw AiSuggestionContractException()
            }
            val suggestions = normalizedSuggestions.take(maxSuggestions)
            return AiGenerationResult(
                suggestions = suggestions,
                model = root.string("model").ifBlank { requestedModel },
                inputCharacters = inputCharacters,
                outputCharacters = suggestions.sumOf(String::length)
            )
        }

        private fun extractOutputText(output: JsonArray?): String? {
            if (output == null) return null
            val parts = mutableListOf<String>()
            output.forEach { item ->
                val content = (item as? JsonObject)?.get("content") as? JsonArray
                    ?: return@forEach
                content.forEach { element ->
                    val part = element as? JsonObject ?: return@forEach
                    if (part.string("type") == "output_text") {
                        part.string("text").takeIf(String::isNotBlank)?.let(parts::add)
                    }
                }
            }
            return parts.joinToString("\n").takeIf(String::isNotBlank)
        }

        private fun parseSuggestions(outputText: String): List<String> {
            val trimmed = outputText.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            return runCatching {
                val array = JSON.parseToJsonElement(trimmed).jsonObject["suggestions"] as JsonArray
                array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            }.getOrElse {
                throw AiSuggestionContractException()
            }
        }

        private fun JsonObject.string(name: String): String =
            (get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

class UrlConnectionAiTransport : AiHttpTransport {
    override fun post(url: String, authorization: String, body: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 90_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Authorization", authorization)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                ByteArrayOutputStream().use { output ->
                    input.copyTo(output)
                    output.toByteArray()
                }
            } ?: ByteArray(0)
            if (status !in 200..299) {
                val code = runCatching {
                    val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                    val error = root["error"] as? JsonObject
                    (error?.get("code") as? JsonPrimitive)?.contentOrNull
                }.getOrNull().orEmpty()
                throw AiHttpStatusException(
                    status,
                    if (code.isEmpty()) "AI provider HTTP $status" else "AI provider HTTP $status ($code)"
                )
            }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val USER_AGENT =
            "Fcitx5Android-AiInput/0.1 (https://github.com/fcitx5-android/fcitx5-android)"
    }
}

class AiProviderException(message: String) : Exception(message)

class AiSuggestionContractException : Exception("AI suggestion contract was not satisfied")
