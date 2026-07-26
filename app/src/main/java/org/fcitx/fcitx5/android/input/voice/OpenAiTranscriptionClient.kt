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
import org.fcitx.fcitx5.android.input.ai.AiProviderProfile
import java.io.ByteArrayOutputStream
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
}

class VoiceTranscriptionException(message: String) : Exception(message)

/** Accuracy-first segment transcription. This is deliberately not labelled as Realtime. */
class OpenAiTranscriptionClient(
    private val profile: AiProviderProfile,
    private val model: String = VoiceTranscriptionModels.ACCURACY,
    private val transport: VoiceHttpTransport = UrlConnectionVoiceTransport()
) {
    suspend fun transcribe(wav: ByteArray): VoiceTranscriptionResult = withContext(Dispatchers.IO) {
        require(wav.size in MIN_WAV_BYTES..MAX_WAV_BYTES) { "Audio payload size is invalid" }
        val validated = profile.validate()
        val request = buildRequest(wav, model)
        try {
            val payload = transport.post(
                url = "${validated.baseUrl}/audio/transcriptions",
                authorization = "Bearer ${validated.apiKey}",
                request = request
            )
            parseResponse(payload, model)
        } finally {
            request.body.fill(0)
        }
    }

    companion object {
        private const val MIN_WAV_BYTES = 44
        private const val MAX_WAV_BYTES = 2 * 1024 * 1024
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
    override fun post(
        url: String,
        authorization: String,
        request: VoiceMultipartRequest
    ): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
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
            connection.outputStream.use { it.write(request.body) }
            val status = connection.responseCode
            if (status !in 200..299) {
                throw VoiceTranscriptionException("Transcription provider HTTP $status")
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
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 256 * 1024
        const val USER_AGENT =
            "Fcitx5Android-PrecisionDictation/0.1 (https://github.com/fcitx5-android/fcitx5-android)"
    }
}
