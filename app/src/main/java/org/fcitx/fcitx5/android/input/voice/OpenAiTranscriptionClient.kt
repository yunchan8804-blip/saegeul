/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.input.ai.AiHttpStatusException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

data class VoiceTranscriptionResult(
    val text: String,
    val model: String
)

data class VoiceMultipartRequest(
    val contentType: String,
    val body: ByteArray
)

fun interface VoiceHttpTransport {
    fun post(url: String, authorization: String, request: VoiceMultipartRequest): String
    fun cancel() = Unit
}

class VoiceTranscriptionException(message: String) : Exception(message)
class VoiceAuthenticationException(message: String) : Exception(message)

/** Accuracy-first segment transcription. This is deliberately not labelled as Realtime. */
class OpenAiTranscriptionClient(
    private val profile: VoiceProviderProfile,
    private val model: String = profile.transcriptionModel,
    private val transport: VoiceHttpTransport = UrlConnectionVoiceTransport()
) {
    suspend fun transcribe(wav: ByteArray): VoiceTranscriptionResult = withContext(Dispatchers.IO) {
        require(wav.size in MIN_WAV_BYTES..MAX_WAV_BYTES) { "Audio payload size is invalid" }
        val validated = profile.validate()
        val request = buildRequest(wav, model)
        try {
            val authorization = "Bearer ${validated.apiKey}"
            val payload = try {
                transport.post(
                    url = validated.endpoint,
                    authorization = authorization,
                    request = request
                )
            } catch (exception: AiHttpStatusException) {
                if (exception.status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    throw VoiceAuthenticationException("OpenAI rejected the STT API key")
                }
                throw VoiceTranscriptionException(
                    exception.message ?: "Transcription provider request failed"
                )
            }
            parseResponse(payload, model)
        } finally {
            request.body.fill(0)
        }
    }

    fun cancel() {
        transport.cancel()
    }

    companion object {
        private const val MIN_WAV_BYTES = 44
        private const val MAX_WAV_BYTES = 12 * 1024 * 1024
        private val JSON = Json { ignoreUnknownKeys = true }

        internal fun buildRequest(wav: ByteArray, model: String): VoiceMultipartRequest {
            val boundary = "FcitxVoice${UUID.randomUUID().toString().replace("-", "")}"
            val output = ByteArrayOutputStream(wav.size + 1_024)
            fun line(value: String = "") {
                output.write(value.toByteArray(Charsets.UTF_8))
                output.write(CRLF)
            }
            fun textPart(name: String, value: String) {
                line("--$boundary")
                line("Content-Disposition: form-data; name=\"$name\"")
                line()
                line(value)
            }
            textPart("model", model)
            textPart("language", "ko")
            textPart("response_format", "json")
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"")
            line("Content-Type: audio/wav")
            line()
            output.write(wav)
            line()
            line("--$boundary--")
            return VoiceMultipartRequest(
                contentType = "multipart/form-data; boundary=$boundary",
                body = output.toByteArray()
            )
        }

        internal fun parseResponse(payload: String, requestedModel: String): VoiceTranscriptionResult {
            val root = runCatching { JSON.parseToJsonElement(payload).jsonObject }
                .getOrElse { throw VoiceTranscriptionException("Transcription provider returned invalid JSON") }
            val text = VoiceTranscriptPolicy.normalize(root.string("text"))
                ?: throw VoiceTranscriptionException("Transcription provider returned no text")
            return VoiceTranscriptionResult(
                text = text,
                model = root.string("model").ifBlank { requestedModel }
            )
        }

        private fun JsonObject.string(name: String): String =
            (get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()

        private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
    }
}

class UrlConnectionVoiceTransport : VoiceHttpTransport {
    @Volatile
    private var activeConnection: HttpURLConnection? = null
    @Volatile
    private var cancelled = false

    override fun post(
        url: String,
        authorization: String,
        request: VoiceMultipartRequest
    ): String {
        if (cancelled) throw IOException("Voice transcription request was cancelled")
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        activeConnection = connection
        try {
            if (cancelled) throw IOException("Voice transcription request was cancelled")
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 90_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(request.body.size)
            connection.setRequestProperty("Authorization", authorization)
            connection.setRequestProperty("Content-Type", request.contentType)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            try {
                connection.outputStream.use { it.write(request.body) }
            } catch (exception: IOException) {
                throwHttpStatusOrOriginal(
                    exception = exception,
                    providerLabel = "Transcription provider",
                    responseCode = { connection.responseCode }
                )
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                throw AiHttpStatusException(status, "Transcription provider HTTP $status")
            }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                try {
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (output.size() + read > MAX_RESPONSE_BYTES) {
                            throw VoiceTranscriptionException("Transcription response is too large")
                        }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                } finally {
                    buffer.fill(0)
                }
            }
            return try {
                bytes.toString(Charsets.UTF_8)
            } finally {
                bytes.fill(0)
            }
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection.disconnect()
        }
    }

    override fun cancel() {
        cancelled = true
        activeConnection?.disconnect()
        activeConnection = null
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 256 * 1024
        const val USER_AGENT =
            "Saegeul-PrecisionDictation/0.1 (https://github.com/yunchan8804-blip/saegeul)"
    }
}

internal fun throwHttpStatusOrOriginal(
    exception: IOException,
    providerLabel: String,
    responseCode: () -> Int
): Nothing {
    val status = runCatching(responseCode).getOrNull()
    if (status != null && status !in 200..299) {
        throw AiHttpStatusException(status, "$providerLabel HTTP $status")
    }
    throw exception
}
