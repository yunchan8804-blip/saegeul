/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

enum class AiUsageStatus {
    Success,
    Failure
}

/**
 * Privacy-preserving aggregate AI usage. It deliberately contains no input, output, credential,
 * endpoint, or media URL fields.
 */
data class AiUsageSnapshot(
    val totalRequests: Long = 0,
    val successfulRequests: Long = 0,
    val failedRequests: Long = 0,
    val inputCharacters: Long = 0,
    val outputCharacters: Long = 0,
    val actionCounts: Map<AiAction, Long> = emptyMap(),
    val lastProvider: AiProviderKind? = null,
    val lastModel: String? = null,
    val lastAction: AiAction? = null,
    val lastStatus: AiUsageStatus? = null,
    val lastOccurredAtEpochMillis: Long? = null
)

/** Stores only aggregate counters in no-backup app storage. */
class AiUsageStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "ai/usage.json")
    private val atomicFile = AtomicFile(file)

    @Synchronized
    fun recordSuccess(
        action: AiAction,
        provider: AiProviderKind,
        model: String,
        inputCharacters: Int,
        outputCharacters: Int,
        occurredAtEpochMillis: Long = System.currentTimeMillis()
    ): AiUsageSnapshot = record(
        action = action,
        provider = provider,
        model = model,
        status = AiUsageStatus.Success,
        inputCharacters = inputCharacters,
        outputCharacters = outputCharacters,
        occurredAtEpochMillis = occurredAtEpochMillis
    )

    @Synchronized
    fun recordFailure(
        action: AiAction,
        provider: AiProviderKind,
        model: String,
        inputCharacters: Int,
        occurredAtEpochMillis: Long = System.currentTimeMillis()
    ): AiUsageSnapshot = record(
        action = action,
        provider = provider,
        model = model,
        status = AiUsageStatus.Failure,
        inputCharacters = inputCharacters,
        outputCharacters = 0,
        occurredAtEpochMillis = occurredAtEpochMillis
    )

    @Synchronized
    fun snapshot(): AiUsageSnapshot = readSnapshot()

    @Synchronized
    fun clear() = atomicFile.delete()

    private fun record(
        action: AiAction,
        provider: AiProviderKind,
        model: String,
        status: AiUsageStatus,
        inputCharacters: Int,
        outputCharacters: Int,
        occurredAtEpochMillis: Long
    ): AiUsageSnapshot {
        val updated = updateAiUsageSnapshot(
            current = readSnapshot(),
            action = action,
            provider = provider,
            model = model,
            status = status,
            inputCharacters = inputCharacters,
            outputCharacters = outputCharacters,
            occurredAtEpochMillis = occurredAtEpochMillis
        )
        writeSnapshot(updated)
        return updated
    }

    private fun readSnapshot(): AiUsageSnapshot {
        if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES) return AiUsageSnapshot()
        return decodeAiUsageSnapshot(runCatching(atomicFile::readFully).getOrNull() ?: return AiUsageSnapshot())
    }

    private fun writeSnapshot(snapshot: AiUsageSnapshot) {
        file.parentFile?.mkdirs()
        val stream = atomicFile.startWrite()
        try {
            stream.write(encodeAiUsageSnapshot(snapshot))
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 64 * 1024L
    }
}

internal fun updateAiUsageSnapshot(
    current: AiUsageSnapshot,
    action: AiAction,
    provider: AiProviderKind,
    model: String,
    status: AiUsageStatus,
    inputCharacters: Int,
    outputCharacters: Int,
    occurredAtEpochMillis: Long
): AiUsageSnapshot {
    val successful = status == AiUsageStatus.Success
    val normalizedModel = model.trim().take(MAX_MODEL_LENGTH).takeIf(String::isNotEmpty)
    val actionCounts = current.actionCounts.toMutableMap().apply {
        this[action] = safeAdd(get(action) ?: 0, 1)
    }
    return current.copy(
        totalRequests = safeAdd(current.totalRequests, 1),
        successfulRequests = safeAdd(current.successfulRequests, if (successful) 1 else 0),
        failedRequests = safeAdd(current.failedRequests, if (successful) 0 else 1),
        inputCharacters = safeAdd(current.inputCharacters, inputCharacters.coerceAtLeast(0).toLong()),
        outputCharacters = safeAdd(
            current.outputCharacters,
            if (successful) outputCharacters.coerceAtLeast(0).toLong() else 0
        ),
        actionCounts = actionCounts,
        lastProvider = provider,
        lastModel = normalizedModel,
        lastAction = action,
        lastStatus = status,
        lastOccurredAtEpochMillis = occurredAtEpochMillis.coerceAtLeast(0)
    )
}

internal fun encodeAiUsageSnapshot(snapshot: AiUsageSnapshot): ByteArray {
    val actionCounts = buildJsonObject {
        AiAction.entries.forEach { action ->
            val count = snapshot.actionCounts[action]?.coerceAtLeast(0) ?: 0
            if (count > 0) put(action.name, count)
        }
    }
    val last = buildJsonObject {
        snapshot.lastProvider?.let { put("provider", it.name) }
        snapshot.lastModel?.trim()?.take(MAX_MODEL_LENGTH)?.takeIf(String::isNotEmpty)?.let {
            put("model", it)
        }
        snapshot.lastAction?.let { put("action", it.name) }
        snapshot.lastStatus?.let { put("status", it.name) }
        snapshot.lastOccurredAtEpochMillis?.takeIf { it >= 0 }?.let { put("time", it) }
    }
    return buildJsonObject {
        put("version", FORMAT_VERSION)
        put("total", snapshot.totalRequests.coerceAtLeast(0))
        put("success", snapshot.successfulRequests.coerceAtLeast(0))
        put("failure", snapshot.failedRequests.coerceAtLeast(0))
        put("inputCharacters", snapshot.inputCharacters.coerceAtLeast(0))
        put("outputCharacters", snapshot.outputCharacters.coerceAtLeast(0))
        put("actions", actionCounts)
        put("last", last)
    }.toString().toByteArray(Charsets.UTF_8)
}

internal fun decodeAiUsageSnapshot(bytes: ByteArray): AiUsageSnapshot = runCatching {
    require(bytes.size in 1..MAX_JSON_BYTES)
    val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    require(root.long("version") == FORMAT_VERSION.toLong())
    val actions = root["actions"]?.jsonObject.orEmpty().mapNotNull { (name, value) ->
        val action = enumValueOrNull<AiAction>(name) ?: return@mapNotNull null
        val count = value.jsonPrimitive.longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
        action to count
    }.toMap()
    val last = root["last"]?.jsonObject ?: JsonObject(emptyMap())
    AiUsageSnapshot(
        totalRequests = root.nonNegativeLong("total"),
        successfulRequests = root.nonNegativeLong("success"),
        failedRequests = root.nonNegativeLong("failure"),
        inputCharacters = root.nonNegativeLong("inputCharacters"),
        outputCharacters = root.nonNegativeLong("outputCharacters"),
        actionCounts = actions,
        lastProvider = last.enumOrNull("provider"),
        lastModel = last.stringOrNull("model")?.take(MAX_MODEL_LENGTH),
        lastAction = last.enumOrNull("action"),
        lastStatus = last.enumOrNull("status"),
        lastOccurredAtEpochMillis = last.long("time")?.takeIf { it >= 0 }
    )
}.getOrDefault(AiUsageSnapshot())

private fun JsonObject.nonNegativeLong(name: String): Long = long(name)?.coerceAtLeast(0) ?: 0

private fun JsonObject.long(name: String): Long? =
    get(name)?.jsonPrimitive?.longOrNull

private fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

private inline fun <reified T : Enum<T>> JsonObject.enumOrNull(name: String): T? =
    stringOrNull(name)?.let(::enumValueOrNull)

private inline fun <reified T : Enum<T>> enumValueOrNull(name: String): T? =
    enumValues<T>().firstOrNull { it.name == name }

private fun safeAdd(left: Long, right: Long): Long {
    val safeLeft = left.coerceAtLeast(0)
    val safeRight = right.coerceAtLeast(0)
    return if (Long.MAX_VALUE - safeLeft < safeRight) Long.MAX_VALUE else safeLeft + safeRight
}

private const val FORMAT_VERSION = 1
private const val MAX_JSON_BYTES = 64 * 1024
private const val MAX_MODEL_LENGTH = 120
