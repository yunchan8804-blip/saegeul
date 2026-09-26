/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAiSupport
import org.fcitx.fcitx5.android.utils.BackgroundProgressNotifier
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.util.Locale

/** Thrown mid-transfer when offline mode is turned on while a download is in flight; caught in [GemmaModelDownloadWorker] and reported as [GemmaInstallFailure.OFFLINE_MODE] rather than a generic network failure. */
private class GemmaOfflineModeInterruptedException : IOException("완전 오프라인 모드가 켜져 다운로드를 중단했습니다.")

/**
 * Resumable download of the fixed Gemma model into [GemmaModelFiles.partFile], one HTTP attempt per
 * `doWork()` invocation. A dropped connection or server error is reported via [Result.retry] (not
 * [Result.failure]): `WorkManager`'s own backoff/constraint re-evaluation re-invokes `doWork()`,
 * which resumes from the `.part` file's current length via a `Range` request - see
 * [rangeHeaderFor]/[decideResumeOutcome]. The `.part` file is only ever deleted here on a genuine
 * hash/size mismatch after a full transfer; every other failure path keeps it so the next attempt
 * (whether `WorkManager`'s own retry or a user-triggered [GemmaModelInstaller.start]) resumes rather
 * than restarting from zero.
 */
internal class GemmaModelDownloadWorker @JvmOverloads constructor(
    appContext: Context,
    parameters: WorkerParameters,
    private val client: GemmaDownloadClient = HttpsGemmaDownloadClient
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result = GemmaModelFiles.withTransferLock { runDownload() }

    private suspend fun runDownload(): Result {
        if (!OnDeviceAiSupport.isSupported) return Result.failure(failureData(GemmaInstallFailure.UNKNOWN))
        val model = GemmaModelFiles.modelFile(applicationContext)
        if (model.isFile && model.length() == GemmaModelFiles.MODEL_BYTES) return Result.success()
        if (AppPrefs.getInstance().advanced.offlineMode.getValue()) {
            return Result.failure(failureData(GemmaInstallFailure.OFFLINE_MODE))
        }
        val directory = requireNotNull(model.parentFile)
        if (!directory.exists() && !directory.mkdirs()) {
            return Result.failure(failureData(GemmaInstallFailure.UNKNOWN))
        }
        val part = GemmaModelFiles.partFile(applicationContext)
        val existingBytes = if (part.isFile) part.length() else 0L
        val available = StatFs(directory.absolutePath).availableBytes
        val stillNeeded = (GemmaModelFiles.REQUIRED_FREE_BYTES - existingBytes).coerceAtLeast(0L)
        if (!hasEnoughFreeSpace(available, stillNeeded)) {
            return Result.failure(failureData(GemmaInstallFailure.STORAGE_FULL))
        }

        val installStore = GemmaModelInstallStore.get(applicationContext)
        trySetForeground(existingBytes, 0L, installStore.allowMobileData)

        return try {
            transferOneAttempt(part, existingBytes, installStore)
            if (part.length() != GemmaModelFiles.MODEL_BYTES) {
                throw IOException("모델 다운로드가 완료되지 않았습니다: ${part.length()}/${GemmaModelFiles.MODEL_BYTES}")
            }
            setProgress(progressData(part.length(), 0L, verifying = true))
            GemmaModelFiles.verifyAndInstallPart(part, model) { isStopped }
            installStore.clear()
            BackgroundProgressNotifier.done(
                applicationContext,
                BackgroundProgressNotifier.ID_GEMMA_INSTALL,
                applicationContext.getString(R.string.gemma_install_notify_done_title),
                applicationContext.getString(R.string.gemma_install_notify_done_title)
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (offline: GemmaOfflineModeInterruptedException) {
            Result.failure(failureData(GemmaInstallFailure.OFFLINE_MODE))
        } catch (error: IOException) {
            when (classifyDownloadFailure(error.message)) {
                GemmaDownloadFailureClassification.VERIFY_FAILED -> {
                    part.delete()
                    Result.failure(failureData(GemmaInstallFailure.VERIFY_FAILED))
                }
                GemmaDownloadFailureClassification.RETRYABLE -> Result.retry()
            }
        } catch (error: Exception) {
            Result.failure(failureData(GemmaInstallFailure.UNKNOWN))
        }
    }

    private suspend fun transferOneAttempt(
        part: File,
        existingBytes: Long,
        installStore: GemmaModelInstallStore
    ) {
        val requestRange = existingBytes > 0L
        val response = client.open(
            GemmaModelFiles.MODEL_URL,
            existingBytes.takeIf { requestRange },
            if (requestRange) installStore.lastEtag else null
        )
        try {
            if (response.responseCode != HttpURLConnection.HTTP_OK &&
                response.responseCode != HttpURLConnection.HTTP_PARTIAL
            ) {
                throw IOException("모델 다운로드 HTTP ${response.responseCode}")
            }
            val outcome = decideResumeOutcome(requestRange, response.responseCode)
            val startBytes = if (outcome == GemmaResumeOutcome.KEEP_EXISTING_BYTES) {
                existingBytes
            } else {
                if (part.isFile && !part.delete()) throw IOException("이전 모델 부분 파일을 지울 수 없습니다.")
                0L
            }
            installStore.lastEtag = response.etag
            val speedTracker = GemmaDownloadSpeedTracker()
            FileOutputStream(part, startBytes > 0L).use { output ->
                val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
                var received = startBytes
                var lastProgressAt = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (AppPrefs.getInstance().advanced.offlineMode.getValue()) {
                        throw GemmaOfflineModeInterruptedException()
                    }
                    val count = response.inputStream.read(buffer)
                    if (count < 0) break
                    received += count
                    if (received > GemmaModelFiles.MODEL_BYTES) {
                        throw IOException("모델 파일이 허용된 고정 크기를 초과했습니다.")
                    }
                    output.write(buffer, 0, count)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                        lastProgressAt = now
                        val bps = speedTracker.sample(now, received)
                        setProgress(progressData(received, bps, verifying = false))
                        trySetForeground(received, bps, installStore.allowMobileData)
                    }
                }
                output.fd.sync()
            }
        } finally {
            response.disconnect()
        }
    }

    private suspend fun trySetForeground(downloadedBytes: Long, bytesPerSecond: Long, allowMobileData: Boolean) {
        try {
            setForeground(foregroundInfoFor(downloadedBytes, bytesPerSecond, allowMobileData))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Some OEM/OS combinations refuse foreground promotion; the download keeps running as an
            // ordinary background job rather than failing outright.
        }
    }

    private fun foregroundInfoFor(downloadedBytes: Long, bytesPerSecond: Long, allowMobileData: Boolean): ForegroundInfo {
        val total = GemmaModelFiles.MODEL_BYTES
        val text = if (bytesPerSecond > 0L) {
            applicationContext.getString(
                R.string.gemma_install_notify_progress_text_with_eta,
                formatGigabytes(downloadedBytes),
                formatGigabytes(total),
                formatEta((total - downloadedBytes).coerceAtLeast(0L) / bytesPerSecond)
            )
        } else {
            applicationContext.getString(
                R.string.gemma_install_notify_progress_text_no_eta,
                formatGigabytes(downloadedBytes),
                formatGigabytes(total)
            )
        }
        val pauseAction = NotificationCompat.Action(
            0,
            applicationContext.getString(R.string.gemma_install_action_pause),
            WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        )
        val progressCurrent = if (total <= 0L) 0 else ((downloadedBytes * 100L) / total).toInt()
        val notification = BackgroundProgressNotifier.buildProgressNotification(
            applicationContext,
            applicationContext.getString(R.string.gemma_install_notify_progress_title),
            text,
            progressCurrent,
            100,
            pauseAction
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(BackgroundProgressNotifier.ID_GEMMA_INSTALL, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(BackgroundProgressNotifier.ID_GEMMA_INSTALL, notification)
        }
    }

    private fun progressData(downloadedBytes: Long, bytesPerSecond: Long, verifying: Boolean) = workDataOf(
        KEY_DOWNLOADED_BYTES to downloadedBytes,
        KEY_BYTES_PER_SECOND to bytesPerSecond,
        KEY_VERIFYING to verifying
    )

    private fun failureData(reason: GemmaInstallFailure) = workDataOf(KEY_FAILURE_REASON to reason.name)

    private fun formatGigabytes(bytes: Long): String = String.format(Locale.US, "%.1fGB", bytes / 1_000_000_000.0)

    private fun formatEta(seconds: Long): String = if (seconds >= 60L) {
        applicationContext.getString(R.string.gemma_install_eta_minutes, ((seconds + 30L) / 60L).toInt())
    } else {
        applicationContext.getString(R.string.gemma_install_eta_seconds, seconds.toInt())
    }

    companion object {
        const val WORK_NAME = "gemma-model-download"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_BYTES_PER_SECOND = "bytes_per_second"
        const val KEY_VERIFYING = "verifying"
        const val KEY_FAILURE_REASON = "failure_reason"
        private const val TRANSFER_BUFFER_BYTES = 256 * 1024
        private const val PROGRESS_INTERVAL_MS = 900L
    }
}
