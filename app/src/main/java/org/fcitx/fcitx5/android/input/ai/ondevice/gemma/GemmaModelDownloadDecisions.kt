/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import androidx.work.WorkInfo
import java.net.HttpURLConnection

/** The `Range` header value [GemmaModelDownloadWorker] should send for an existing `.part` file, or null for a plain GET from byte 0. */
internal fun rangeHeaderFor(existingPartBytes: Long): String? =
    if (existingPartBytes > 0L) "bytes=$existingPartBytes-" else null

/** What a download response means for an on-disk `.part` file that already had [requestedRange] bytes on it. */
internal enum class GemmaResumeOutcome {
    /** The server honored the `Range` request (`206 Partial Content`): keep the existing bytes and append the response body. */
    KEEP_EXISTING_BYTES,

    /** No range was requested, or the server ignored it and sent `200 OK` from the start: discard whatever is on disk and start over. */
    DISCARD_EXISTING_BYTES
}

internal fun decideResumeOutcome(requestedRange: Boolean, responseCode: Int): GemmaResumeOutcome =
    if (requestedRange && responseCode == HttpURLConnection.HTTP_PARTIAL) {
        GemmaResumeOutcome.KEEP_EXISTING_BYTES
    } else {
        GemmaResumeOutcome.DISCARD_EXISTING_BYTES
    }

/** What [GemmaModelDownloadWorker] does with the `.part` file it finds on disk before it opens any connection. */
internal enum class GemmaPartDisposition {
    /** Empty, absent or shorter than the model: request the missing bytes (a `Range` request when there are some). */
    TRANSFER,

    /** Exactly the model size: nothing is left to download, and a `Range` request past the end would be answered with `416`. Go straight to verification. */
    VERIFY_COMPLETE,

    /** Longer than the model can ever be, so it cannot be a prefix of it: delete it and download from byte 0. */
    DISCARD_OVERSIZED
}

internal fun decidePartDisposition(partBytes: Long, modelBytes: Long): GemmaPartDisposition = when {
    partBytes > modelBytes -> GemmaPartDisposition.DISCARD_OVERSIZED
    partBytes == modelBytes -> GemmaPartDisposition.VERIFY_COMPLETE
    else -> GemmaPartDisposition.TRANSFER
}

/** What a `416 Range Not Satisfiable` answer to a resume request means for the `.part` file. */
internal enum class GemmaRangeRejection {
    /** The `.part` file already holds the whole model, which is why the server has nothing left to send: verify it. */
    VERIFY_PART,

    /** The `.part` file does not match what the server can serve: delete it and retry from byte 0. */
    DISCARD_PART_AND_RETRY
}

internal fun decideRangeRejection(partBytes: Long, modelBytes: Long): GemmaRangeRejection =
    if (partBytes == modelBytes) GemmaRangeRejection.VERIFY_PART else GemmaRangeRejection.DISCARD_PART_AND_RETRY

/** Whether [availableBytes] of free space is enough to hold the fixed-size model (plus headroom), independent of how [StatFs] measures it. */
internal fun hasEnoughFreeSpace(availableBytes: Long, requiredBytes: Long): Boolean =
    availableBytes >= requiredBytes

/**
 * Which downloaded-byte count to show: the worker's own [GemmaModelDownloadWorker.setProgress] value
 * while it is actually [WorkInfo.State.RUNNING] (pushed roughly once a second by the worker itself,
 * not polled), falling back to the `.part` file's length for every other state - including a `null`
 * [partFileBytes], meaning the `.part` file does not exist at all, which always wins regardless of any
 * stale progress data left over from a previous run of the same (or a different) work generation.
 */
internal fun resolveDownloadedBytes(
    workState: WorkInfo.State?,
    progressDownloadedBytes: Long?,
    partFileBytes: Long?
): Long? = if (partFileBytes != null && workState == WorkInfo.State.RUNNING && progressDownloadedBytes != null) {
    progressDownloadedBytes
} else {
    partFileBytes
}

/** What a completed-but-failed transfer attempt means for the `.part` file: a genuine hash/size mismatch after a full transfer is not retryable and must not keep corrupt bytes around; every other failure (a dropped connection, a 5xx, …) keeps the `.part` file for `WorkManager`'s own retry to resume. */
internal enum class GemmaDownloadFailureClassification { VERIFY_FAILED, RETRYABLE }

internal fun classifyDownloadFailure(errorMessage: String?): GemmaDownloadFailureClassification =
    if (errorMessage?.contains("SHA-256") == true || errorMessage?.contains("크기가 고정 크기와 다릅니다") == true) {
        GemmaDownloadFailureClassification.VERIFY_FAILED
    } else {
        GemmaDownloadFailureClassification.RETRYABLE
    }

/**
 * Restores [GemmaInstallState] from on-disk/`WorkManager` facts alone, so the installer's state
 * survives a process restart without re-hashing the (multi-gigabyte) model file on every read.
 * [workState] is the current `gemma-model-download` unique work's state, or null when none is known
 * to `WorkManager` (never scheduled this install, or pruned after finishing). [partFileBytes] is the
 * `.part` file's length, or null when it does not exist.
 */
internal fun deriveInstallState(
    modelInstalled: Boolean,
    partFileBytes: Long?,
    workState: WorkInfo.State?,
    verifying: Boolean,
    bytesPerSecond: Long,
    allowMobileData: Boolean,
    failureReason: GemmaInstallFailure?,
    totalBytes: Long
): GemmaInstallState {
    if (modelInstalled) return GemmaInstallState.Installed
    if (partFileBytes == null) {
        return if (workState == WorkInfo.State.FAILED) {
            GemmaInstallState.Failed(failureReason ?: GemmaInstallFailure.UNKNOWN, 0L, totalBytes)
        } else {
            GemmaInstallState.NotInstalled
        }
    }
    return when (workState) {
        WorkInfo.State.RUNNING -> if (verifying) {
            GemmaInstallState.Verifying
        } else {
            GemmaInstallState.Downloading(partFileBytes, totalBytes, bytesPerSecond, allowMobileData)
        }
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
            GemmaInstallState.WaitingForNetwork(partFileBytes, totalBytes, wifiOnly = !allowMobileData)
        WorkInfo.State.FAILED ->
            GemmaInstallState.Failed(failureReason ?: GemmaInstallFailure.UNKNOWN, partFileBytes, totalBytes)
        // CANCELLED (paused by the user, or via cancelAndDelete before the .part is actually removed),
        // SUCCEEDED with the model file somehow still missing, or no WorkInfo at all (a leftover
        // `.part` from a previous process with nothing currently scheduled): all read as paused, since
        // GemmaModelInstaller.start resumes any of them identically.
        else -> GemmaInstallState.Paused(partFileBytes, totalBytes)
    }
}

/** A moving-average download speed over the trailing [windowMs], fed by [sample] calls carrying (elapsed-realtime, cumulative bytes) pairs. Pure/testable: the caller supplies "now" instead of this reading the clock itself. */
internal class GemmaDownloadSpeedTracker(private val windowMs: Long = 5_000L) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun sample(nowMs: Long, cumulativeBytes: Long): Long {
        samples.addLast(nowMs to cumulativeBytes)
        while (samples.size > 1 && nowMs - samples.first().first > windowMs) samples.removeFirst()
        val (oldestAt, oldestBytes) = samples.first()
        val elapsedMs = nowMs - oldestAt
        if (elapsedMs <= 0L) return 0L
        return (cumulativeBytes - oldestBytes) * 1000L / elapsedMs
    }
}
