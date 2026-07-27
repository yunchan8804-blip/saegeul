/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.input.ai.AiHttpStatusException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

data class DiarizationRequest(
    val source: MeetingAudioSource,
    val model: String = MODEL
) {
    companion object {
        const val MODEL = "gpt-4o-transcribe-diarize"
    }
}

interface DiarizationHttpTransport {
    fun post(url: String, authorization: String, request: DiarizationRequest): String
    fun cancel() = Unit
}

internal object DiarizationMultipartContract {
    fun textFields(request: DiarizationRequest): Map<String, String> = linkedMapOf(
        "model" to request.model,
        "language" to "ko",
        "response_format" to "diarized_json",
        "chunking_strategy" to "auto"
    )
}

class OpenAiDiarizationClient(
    private val profile: VoiceProviderProfile,
    private val transport: DiarizationHttpTransport = UrlConnectionDiarizationTransport()
) {
    suspend fun transcribe(source: MeetingAudioSource): MeetingDiarizationResult =
        withContext(Dispatchers.IO) {
            val validated = profile.validate()
            if (!MeetingDiarizationCapability.supports(validated)) {
                throw VoiceTranscriptionException("Provider does not declare OpenAI diarization support")
            }
            val authorization = "Bearer ${validated.apiKey}"
            val payload = try {
                transport.post(
                    url = validated.endpoint,
                    authorization = authorization,
                    request = DiarizationRequest(source, validated.diarizationModel)
                )
            } catch (exception: AiHttpStatusException) {
                if (exception.status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    throw VoiceAuthenticationException("OpenAI rejected the STT API key")
                }
                throw VoiceTranscriptionException(
                    exception.message ?: "Diarization provider request failed"
                )
            }
            parseResponse(payload, validated.diarizationModel)
        }

    fun cancel() {
        transport.cancel()
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        internal fun parseResponse(
            payload: String,
            requestedModel: String
        ): MeetingDiarizationResult {
            val root = runCatching { JSON.parseToJsonElement(payload).jsonObject }
                .getOrElse { throw VoiceTranscriptionException("Diarization provider returned invalid JSON") }
            val rawSegments = root["segments"] as? JsonArray
                ?: throw VoiceTranscriptionException("Diarization provider returned no segments")
            if (rawSegments.isEmpty() || rawSegments.size > MeetingTranscriptSelection.MAX_SEGMENTS) {
                throw VoiceTranscriptionException("Diarization segment count is invalid")
            }
            val segments = rawSegments.mapIndexed { index, element ->
                parseSegment(element as? JsonObject, index)
            }.sortedBy(MeetingSpeakerSegment::startSeconds)
            return MeetingDiarizationResult(
                segments = segments,
                model = root.string("model").ifBlank { requestedModel }
            )
        }

        private fun parseSegment(value: JsonObject?, index: Int): MeetingSpeakerSegment {
            val segment = value
                ?: throw VoiceTranscriptionException("Diarization segment is invalid")
            val start = segment.number("start")
            val end = segment.number("end")
            if (!start.isFinite() || !end.isFinite() || start < 0.0 || end < start) {
                throw VoiceTranscriptionException("Diarization timestamp is invalid")
            }
            val text = segment.string("text").trim()
            if (text.isEmpty() || text.length > MeetingTranscriptSelection.MAX_SEGMENT_CHARACTERS) {
                throw VoiceTranscriptionException("Diarization text is invalid")
            }
            val speaker = segment.string("speaker")
                .replace(Regex("[\\p{Cc}\\p{Cf}]"), "")
                .trim()
                .take(64)
            val rawId = segment.string("id").ifBlank { "segment" }
            return MeetingSpeakerSegment(
                id = "${rawId.take(80)}:$index",
                speaker = speaker,
                startSeconds = start,
                endSeconds = end,
                text = text
            )
        }

        private fun JsonObject.string(name: String): String =
            (get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()

        private fun JsonObject.number(name: String): Double =
            (get(name) as? JsonPrimitive)?.doubleOrNull
                ?: throw VoiceTranscriptionException("Diarization timestamp is missing")
    }
}

class UrlConnectionDiarizationTransport : DiarizationHttpTransport {
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    @Volatile
    private var activeInput: InputStream? = null

    override fun post(url: String, authorization: String, request: DiarizationRequest): String {
        val metadata = request.source.metadata
        if (metadata.declaredSizeBytes != null &&
            metadata.declaredSizeBytes > MeetingAudioPolicy.MAX_FILE_BYTES
        ) throw MeetingAudioException("Selected audio exceeds the upload limit")
        val boundary = "FcitxMeeting${UUID.randomUUID().toString().replace("-", "")}"
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        activeConnection = connection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 180_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setChunkedStreamingMode(STREAM_BUFFER_BYTES)
            connection.setRequestProperty("Authorization", authorization)
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.outputStream.use { output ->
                DiarizationMultipartContract.textFields(request).forEach { (name, value) ->
                    writeTextPart(output, boundary, name, value)
                }
                writeLine(output, "--$boundary")
                writeLine(
                    output,
                    "Content-Disposition: form-data; name=\"file\"; filename=\"${metadata.uploadFileName}\""
                )
                writeLine(output, "Content-Type: ${metadata.contentType}")
                writeLine(output)
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                var total = 0L
                val input = request.source.openStream()
                activeInput = input
                try {
                    input.use {
                        while (true) {
                            val read = it.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MeetingAudioPolicy.MAX_FILE_BYTES) {
                                throw MeetingAudioException("Selected audio exceeds the upload limit")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                } finally {
                    activeInput = null
                    buffer.fill(0)
                }
                writeLine(output)
                writeLine(output, "--$boundary--")
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                throw AiHttpStatusException(status, "Diarization provider HTTP $status")
            }
            return readBounded(connection.inputStream)
        } finally {
            activeInput = null
            activeConnection = null
            connection.disconnect()
        }
    }

    override fun cancel() {
        runCatching { activeInput?.close() }
        activeInput = null
        activeConnection?.disconnect()
        activeConnection = null
    }

    private fun readBounded(input: InputStream): String = input.use {
        val bytes = ByteArray(MAX_RESPONSE_BYTES)
        try {
            var total = 0
            while (total < bytes.size) {
                val read = it.read(bytes, total, bytes.size - total)
                if (read < 0) break
                total += read
            }
            if (total == bytes.size && it.read() >= 0) {
                throw VoiceTranscriptionException("Diarization response is too large")
            }
            bytes.decodeToString(0, total)
        } finally {
            bytes.fill(0)
        }
    }

    private fun writeTextPart(
        output: java.io.OutputStream,
        boundary: String,
        name: String,
        value: String
    ) {
        writeLine(output, "--$boundary")
        writeLine(output, "Content-Disposition: form-data; name=\"$name\"")
        writeLine(output)
        writeLine(output, value)
    }

    private fun writeLine(output: java.io.OutputStream, value: String = "") {
        output.write(value.toByteArray(Charsets.UTF_8))
        output.write(CRLF)
    }

    private companion object {
        const val STREAM_BUFFER_BYTES = 16 * 1024
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        const val USER_AGENT =
            "Fcitx5Android-MeetingDiarization/0.1 (https://github.com/fcitx5-android/fcitx5-android)"
        val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
    }
}
