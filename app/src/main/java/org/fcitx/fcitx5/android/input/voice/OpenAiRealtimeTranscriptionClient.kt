/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.Closeable
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class VoiceRealtimeDelay(val id: String) {
    Minimal("minimal"),
    Low("low"),
    Medium("medium"),
    High("high"),
    ExtraHigh("xhigh")
}

internal sealed interface VoiceRealtimeEvent {
    data object SessionUpdated : VoiceRealtimeEvent
    data class TranscriptDelta(val itemId: String, val delta: String) : VoiceRealtimeEvent
    data class TranscriptCompleted(val itemId: String, val transcript: String) : VoiceRealtimeEvent
    data class Error(val message: String, val code: String) : VoiceRealtimeEvent
    data object Ignored : VoiceRealtimeEvent
}

internal object OpenAiRealtimeProtocol {
    private const val MAX_EVENT_CHARACTERS = 256 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    fun sessionUpdate(
        model: String,
        delay: VoiceRealtimeDelay = VoiceRealtimeDelay.Low
    ): String = buildJsonObject {
        put("type", "session.update")
        put("session", buildJsonObject {
            put("type", "transcription")
            put("audio", buildJsonObject {
                put("input", buildJsonObject {
                    put("format", buildJsonObject {
                        put("type", "audio/pcm")
                        put("rate", PcmStreamRecorder.SAMPLE_RATE)
                    })
                    put("transcription", buildJsonObject {
                        put("model", model)
                        put("language", "ko")
                        put("delay", delay.id)
                    })
                    put("turn_detection", null)
                })
            })
        })
    }.toString()

    fun audioAppend(pcm: ByteArray): String = buildJsonObject {
        put("type", "input_audio_buffer.append")
        put("audio", pcm.toByteString().base64())
    }.toString()

    const val COMMIT = "{\"type\":\"input_audio_buffer.commit\"}"

    fun parse(payload: String): VoiceRealtimeEvent {
        if (payload.length > MAX_EVENT_CHARACTERS) {
            return VoiceRealtimeEvent.Error("Realtime transcription event is too large", "event_too_large")
        }
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }
            .getOrElse {
                return VoiceRealtimeEvent.Error(
                    "Realtime transcription returned invalid JSON",
                    "invalid_json"
                )
            }
        return when (root.string("type")) {
            "session.updated" -> VoiceRealtimeEvent.SessionUpdated
            "conversation.item.input_audio_transcription.delta" ->
                VoiceRealtimeEvent.TranscriptDelta(
                    itemId = root.string("item_id"),
                    delta = root.string("delta")
                )
            "conversation.item.input_audio_transcription.completed" ->
                VoiceRealtimeEvent.TranscriptCompleted(
                    itemId = root.string("item_id"),
                    transcript = root.string("transcript")
                )
            "error" -> {
                val error = root["error"] as? JsonObject
                VoiceRealtimeEvent.Error(
                    message = error?.string("message").orEmpty()
                        .ifBlank { "Realtime transcription failed" },
                    code = error?.string("code").orEmpty()
                )
            }
            else -> VoiceRealtimeEvent.Ignored
        }
    }

    private fun JsonObject.string(name: String): String =
        (get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()
}

/** Keeps partial text bound to its server item; the completed transcript is authoritative. */
internal class RealtimeTranscriptAccumulator {
    private val partials = linkedMapOf<String, StringBuilder>()

    fun apply(event: VoiceRealtimeEvent): String? {
        return when (event) {
            is VoiceRealtimeEvent.TranscriptDelta -> {
                val itemId = event.itemId.takeIf(String::isNotBlank) ?: return null
                val target = partials.getOrPut(itemId, ::StringBuilder)
                val available = VoiceTranscriptPolicy.MAX_CHARACTERS - target.length
                if (available > 0) target.append(event.delta.take(available))
                target.toString().trim().takeIf(String::isNotBlank)
            }
            is VoiceRealtimeEvent.TranscriptCompleted -> {
                partials.remove(event.itemId)
                VoiceTranscriptPolicy.normalize(event.transcript)
            }
            else -> null
        }
    }
}

/** Carries a socket failure across the WebSocket, UI, and AudioRecord threads exactly once. */
internal class RealtimeTerminalFailure {
    private val error = AtomicReference<Exception?>(null)

    fun report(exception: Exception, stopCapture: () -> Unit) {
        if (error.compareAndSet(null, exception)) stopCapture()
    }

    fun throwIfPresent() {
        error.get()?.let { throw it }
    }
}

internal data class VoiceRealtimeSocketRequest(
    val url: String,
    val authorization: String
)

internal interface VoiceRealtimeSocket {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String): Boolean
    fun cancel()
}

internal interface VoiceRealtimeSocketListener {
    fun onOpen(socket: VoiceRealtimeSocket)
    fun onText(text: String)
    fun onClosed(code: Int, reason: String)
    fun onFailure(error: Throwable, httpStatus: Int?)
}

internal fun interface VoiceRealtimeConnector {
    fun open(
        request: VoiceRealtimeSocketRequest,
        listener: VoiceRealtimeSocketListener
    ): VoiceRealtimeSocket
}

/**
 * One push-to-talk Realtime transcription session.
 *
 * Transcript deltas are preview-only. [finish] returns one normalized final transcript, which still
 * has to pass the editor identity and explicit insertion gates in [VoiceTranscriptionWindow].
 */
internal class OpenAiRealtimeTranscriptionSession(
    profile: VoiceProviderProfile,
    private val delay: VoiceRealtimeDelay = VoiceRealtimeDelay.Low,
    private val connector: VoiceRealtimeConnector = OkHttpVoiceRealtimeConnector(),
    private val onPartial: (String) -> Unit = {},
    private val onTerminalError: (Exception) -> Unit = {},
    private val connectTimeoutMillis: Long = CONNECT_TIMEOUT_MILLIS,
    private val transcriptionTimeoutMillis: Long = TRANSCRIPTION_TIMEOUT_MILLIS
) : Closeable {
    private val validated = profile.validate()
    private val ready = CompletableDeferred<Unit>()
    private val result = CompletableDeferred<VoiceTranscriptionResult>()
    private val accumulator = RealtimeTranscriptAccumulator()
    private val closed = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)
    private var socket: VoiceRealtimeSocket? = null

    suspend fun connect() {
        val listener = object : VoiceRealtimeSocketListener {
            override fun onOpen(socket: VoiceRealtimeSocket) {
                this@OpenAiRealtimeTranscriptionSession.socket = socket
                if (!socket.send(
                        OpenAiRealtimeProtocol.sessionUpdate(
                            validated.realtimeTranscriptionModel,
                            delay
                        )
                    )
                ) {
                    fail(VoiceTranscriptionException("Realtime transcription session setup failed"))
                }
            }

            override fun onText(text: String) {
                when (val event = OpenAiRealtimeProtocol.parse(text)) {
                    VoiceRealtimeEvent.SessionUpdated -> ready.complete(Unit)
                    is VoiceRealtimeEvent.TranscriptDelta -> {
                        accumulator.apply(event)?.let(onPartial)
                    }
                    is VoiceRealtimeEvent.TranscriptCompleted -> {
                        val transcript = accumulator.apply(event)
                        if (transcript == null) {
                            fail(VoiceTranscriptionException("Realtime transcription returned no text"))
                        } else if (terminal.compareAndSet(false, true)) {
                            result.complete(
                                VoiceTranscriptionResult(
                                    text = transcript,
                                    model = validated.realtimeTranscriptionModel
                                )
                            )
                        }
                    }
                    is VoiceRealtimeEvent.Error -> fail(
                        if (event.code == "invalid_api_key") {
                            VoiceAuthenticationException("OpenAI rejected the STT API key")
                        } else {
                            VoiceTranscriptionException(event.message)
                        }
                    )
                    VoiceRealtimeEvent.Ignored -> Unit
                }
            }

            override fun onClosed(code: Int, reason: String) {
                if (!closed.get() && !result.isCompleted) {
                    fail(VoiceTranscriptionException("Realtime transcription connection closed"))
                }
            }

            override fun onFailure(error: Throwable, httpStatus: Int?) {
                fail(
                    if (httpStatus == HttpURLConnection.HTTP_UNAUTHORIZED) {
                        VoiceAuthenticationException("OpenAI rejected the STT API key")
                    } else {
                        VoiceTranscriptionException("Realtime transcription connection failed")
                    }
                )
            }
        }
        socket = connector.open(
            VoiceRealtimeSocketRequest(
                url = validated.realtimeEndpoint,
                authorization = "Bearer ${validated.apiKey}"
            ),
            listener
        )
        try {
            withTimeout(connectTimeoutMillis) { ready.await() }
        } catch (_: TimeoutCancellationException) {
            val timeout = VoiceTranscriptionException("Realtime transcription connection timed out")
            fail(timeout)
            close()
            throw timeout
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun append(pcm: ByteArray) {
        check(ready.isCompleted && !ready.isCancelled) { "Realtime session is not ready" }
        if (closed.get() || socket?.send(OpenAiRealtimeProtocol.audioAppend(pcm)) != true) {
            val error = VoiceTranscriptionException("Realtime audio stream failed")
            fail(error)
            throw error
        }
    }

    suspend fun finish(): VoiceTranscriptionResult {
        if (closed.get() || socket?.send(OpenAiRealtimeProtocol.COMMIT) != true) {
            val error = VoiceTranscriptionException("Realtime audio commit failed")
            fail(error)
            throw error
        }
        return try {
            withTimeout(transcriptionTimeoutMillis) { result.await() }
        } catch (_: TimeoutCancellationException) {
            val timeout = VoiceTranscriptionException("Realtime transcription finalization timed out")
            fail(timeout)
            throw timeout
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket?.close(NORMAL_CLOSE_CODE, "dictation complete")
        socket = null
    }

    fun cancel() {
        if (!closed.compareAndSet(false, true)) return
        socket?.cancel()
        socket = null
    }

    private fun fail(error: Exception) {
        if (!terminal.compareAndSet(false, true)) return
        ready.completeExceptionally(error)
        result.completeExceptionally(error)
        onTerminalError(error)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 20_000L
        const val TRANSCRIPTION_TIMEOUT_MILLIS = 90_000L
        const val NORMAL_CLOSE_CODE = 1_000
    }
}

internal class OkHttpVoiceRealtimeConnector(
    private val client: OkHttpClient = sharedClient
) : VoiceRealtimeConnector {
    override fun open(
        request: VoiceRealtimeSocketRequest,
        listener: VoiceRealtimeSocketListener
    ): VoiceRealtimeSocket {
        val okhttpRequest = Request.Builder()
            .url(request.url)
            .header("Authorization", request.authorization)
            .header("User-Agent", USER_AGENT)
            .build()
        val webSocket = client.newWebSocket(okhttpRequest, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onOpen(OkHttpVoiceRealtimeSocket(webSocket))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onText(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onFailure(t, response?.code)
            }
        })
        return OkHttpVoiceRealtimeSocket(webSocket)
    }

    private class OkHttpVoiceRealtimeSocket(
        private val webSocket: WebSocket
    ) : VoiceRealtimeSocket {
        override fun send(text: String): Boolean = webSocket.send(text)
        override fun close(code: Int, reason: String): Boolean = webSocket.close(code, reason)
        override fun cancel() = webSocket.cancel()
    }

    private companion object {
        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }
        const val USER_AGENT =
            "Saegeul-RealtimeDictation/0.1 (https://github.com/yunchan8804/saegeul)"
    }
}
