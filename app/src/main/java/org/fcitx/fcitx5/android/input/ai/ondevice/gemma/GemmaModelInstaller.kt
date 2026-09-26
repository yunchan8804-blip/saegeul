/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAiSupport
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The single public entry point for installing/managing the on-device Gemma model - the frozen
 * contract the onboarding, vault, settings and keyboard screens are built on (see [GemmaInstallState]
 * for the state shape). [state] is one app-wide `StateFlow`, restored from the `.part` file and
 * `WorkManager`'s own persisted `WorkInfo` on first access after a process restart rather than kept
 * only in memory - see [deriveInstallState].
 */
object GemmaModelInstaller {

    private val installerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<GemmaInstallState>(GemmaInstallState.NotInstalled)

    @Volatile
    private var collectorStarted = false
    private val collectorLock = Any()

    fun state(context: Context): StateFlow<GemmaInstallState> {
        ensureCollector(context.applicationContext)
        return mutableState
    }

    fun isOnUnmeteredNetwork(context: Context): Boolean {
        val connectivityManager =
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Starts (or resumes) the download. A no-op when the model is already installed or the device is
     * unsupported. When a download is already enqueued/running, this replaces it with one carrying
     * the new [allowMobileData] constraint - `WorkManager` constraints are fixed at enqueue time, so
     * "just update the constraint" means restarting the work item; the `.part` file and its `ETag`
     * are untouched, so the new attempt resumes within one HTTP request instead of restarting the
     * transfer.
     */
    fun start(context: Context, allowMobileData: Boolean) {
        val applicationContext = context.applicationContext
        if (!OnDeviceAiSupport.isSupported) return
        val model = GemmaModelFiles.modelFile(applicationContext)
        if (model.isFile && model.length() == GemmaModelFiles.MODEL_BYTES) return
        GemmaModelInstallStore.get(applicationContext).allowMobileData = allowMobileData
        val request = OneTimeWorkRequestBuilder<GemmaModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(GemmaModelDownloadWorker.WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        ensureCollector(applicationContext)
        refreshStateAsync(applicationContext)
    }

    /** Cancels the running/enqueued download, keeping the `.part` file so [start] resumes it. */
    fun pause(context: Context) {
        val applicationContext = context.applicationContext
        WorkManager.getInstance(applicationContext).cancelUniqueWork(GemmaModelDownloadWorker.WORK_NAME)
        refreshStateAsync(applicationContext)
    }

    /** Cancels the download and deletes the `.part` file - unlike [pause], this cannot be resumed. */
    fun cancelAndDelete(context: Context) {
        val applicationContext = context.applicationContext
        WorkManager.getInstance(applicationContext).cancelUniqueWork(GemmaModelDownloadWorker.WORK_NAME)
        installerScope.launch {
            GemmaModelFiles.withTransferLock {
                withContext(Dispatchers.IO) {
                    partFile(applicationContext).takeIf { it.isFile }?.delete()
                }
            }
            GemmaModelInstallStore.get(applicationContext).clear()
            refreshStateNow(applicationContext)
        }
    }

    /**
     * Deletes an installed model. Cancels any in-flight on-device generation first (material
     * accumulation and personal-graph enrichment each hold their own lease on the Gemma engine; both
     * would otherwise fail mid-generation against a model file that just disappeared).
     */
    fun deleteModel(context: Context) {
        val applicationContext = context.applicationContext
        installerScope.launch {
            GemmaAccumulationRuntime.cancelActiveGenerator()
            GemmaGraphEnrichmentRuntime.cancelActiveGenerator()
            GemmaModelFiles.deleteModel(applicationContext)
            refreshStateNow(applicationContext)
        }
    }

    suspend fun importFrom(context: Context, uri: Uri): Result<Unit> {
        val applicationContext = context.applicationContext
        return try {
            GemmaModelFiles.importFrom(applicationContext, uri) { _, _ -> }
            onInstallSucceeded(applicationContext)
            refreshStateNow(applicationContext)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            refreshStateNow(applicationContext)
            Result.failure(error)
        }
    }

    /** Pure calculation from a [GemmaInstallState.Downloading] snapshot; null while the speed sample is still 0 (just started, or genuinely stalled). */
    fun estimatedSecondsRemaining(state: GemmaInstallState.Downloading): Long? {
        if (state.bytesPerSecond <= 0L) return null
        val remaining = (state.totalBytes - state.downloadedBytes).coerceAtLeast(0L)
        return remaining / state.bytesPerSecond
    }

    /**
     * Called once a model install (download or import) finishes: turns "자동으로 배우기" (material
     * accumulation + personal-graph enrichment) on by default, unless the user had explicitly turned
     * it off before - see [GemmaAutomaticLearningPolicy.shouldAutoEnableAfterInstall].
     */
    internal suspend fun onInstallSucceeded(context: Context) {
        val store = GemmaAccumulationStore.get(context)
        val state = store.load()
        if (GemmaAutomaticLearningPolicy.shouldAutoEnableAfterInstall(state.enabled, state.automaticLearningUserOptOut)) {
            GemmaAccumulationScheduler.setEnabled(context, enabled = true, byUser = false)
            GemmaGraphEnrichmentScheduler.setEnabled(context, enabled = true)
        }
    }

    /**
     * Called on app start: forces "자동으로 배우기" back off when the model it depends on is not on
     * disk - the model lives under `noBackupFilesDir` and is never restored by a cloud/device-transfer
     * backup, while the `enabled` flag itself (an ordinary `SharedPreferences` entry) can be. See
     * [GemmaAutomaticLearningPolicy.shouldForceDisableAutomaticLearning].
     */
    suspend fun enforceAutomaticLearningRequiresModel(context: Context) {
        val model = GemmaModelFiles.modelFile(context)
        val modelInstalled = model.isFile && model.length() == GemmaModelFiles.MODEL_BYTES
        val state = GemmaAccumulationStore.get(context).load()
        if (GemmaAutomaticLearningPolicy.shouldForceDisableAutomaticLearning(modelInstalled, state.enabled)) {
            GemmaAccumulationScheduler.setEnabled(context, enabled = false, byUser = false)
            GemmaGraphEnrichmentScheduler.setEnabled(context, enabled = false)
        }
    }

    /**
     * Subscribes once to `WorkManager`'s own push-based `getWorkInfosForUniqueWorkFlow` (backed by a
     * Room observer over the `WorkSpec` table, not a timer): it emits the current `WorkInfo` list the
     * instant collection starts, and again only when that row actually changes - i.e. on every
     * [GemmaModelDownloadWorker.setProgress] call while downloading (roughly once a second, driven by
     * the worker's own cadence, not a separately scheduled poll) and on every state transition.
     * Nothing runs, and nothing wakes this process (including the keyboard/IME process this code also
     * runs in), while no `gemma-model-download` work exists or it is idle - unlike a fixed-interval
     * poll, which would keep firing forever regardless of whether there is anything to observe.
     * Started at most once per process (guarded by [collectorStarted]) and kept for the process's
     * lifetime, mirroring [installerScope] itself.
     */
    private fun ensureCollector(context: Context) {
        if (collectorStarted) return
        synchronized(collectorLock) {
            if (collectorStarted) return
            collectorStarted = true
            installerScope.launch {
                WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWorkFlow(GemmaModelDownloadWorker.WORK_NAME)
                    .collect { workInfos -> applyWorkInfos(context, workInfos) }
            }
        }
    }

    private fun refreshStateAsync(context: Context) {
        installerScope.launch { refreshStateNow(context) }
    }

    /** A one-off `WorkManager` query for callers that just mutated the work item and need [mutableState] to reflect that immediately, without waiting for [ensureCollector]'s subscription to catch up. */
    private fun refreshStateNow(context: Context) {
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(GemmaModelDownloadWorker.WORK_NAME)
            .get(WORK_INFO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        applyWorkInfos(context, workInfos)
    }

    private fun applyWorkInfos(context: Context, workInfos: List<WorkInfo>) {
        if (!OnDeviceAiSupport.isSupported) {
            mutableState.value = GemmaInstallState.Unsupported
            return
        }
        val model = GemmaModelFiles.modelFile(context)
        val modelInstalled = model.isFile && model.length() == GemmaModelFiles.MODEL_BYTES
        val part = partFile(context)
        val partBytes = if (part.isFile) part.length() else null
        val current = workInfos.minByOrNull { STATE_PRIORITY[it.state] ?: Int.MAX_VALUE }
        val progress = current?.progress
        val progressDownloadedBytes = progress?.getLong(GemmaModelDownloadWorker.KEY_DOWNLOADED_BYTES, -1L)
            ?.takeIf { it >= 0L }
        val bytesPerSecond = progress?.getLong(GemmaModelDownloadWorker.KEY_BYTES_PER_SECOND, 0L) ?: 0L
        val verifying = progress?.getBoolean(GemmaModelDownloadWorker.KEY_VERIFYING, false) ?: false
        val failureReason = current?.outputData
            ?.getString(GemmaModelDownloadWorker.KEY_FAILURE_REASON)
            ?.let { name -> runCatching { GemmaInstallFailure.valueOf(name) }.getOrNull() }
        val allowMobileData = GemmaModelInstallStore.get(context).allowMobileData
        mutableState.value = deriveInstallState(
            modelInstalled = modelInstalled,
            partFileBytes = resolveDownloadedBytes(current?.state, progressDownloadedBytes, partBytes),
            workState = current?.state,
            verifying = verifying,
            bytesPerSecond = bytesPerSecond,
            allowMobileData = allowMobileData,
            failureReason = failureReason,
            totalBytes = GemmaModelFiles.MODEL_BYTES
        )
    }

    private fun partFile(context: Context): File = GemmaModelFiles.partFile(context)

    private val STATE_PRIORITY = mapOf(
        WorkInfo.State.RUNNING to 0,
        WorkInfo.State.ENQUEUED to 1,
        WorkInfo.State.BLOCKED to 1,
        WorkInfo.State.FAILED to 2,
        WorkInfo.State.CANCELLED to 3,
        WorkInfo.State.SUCCEEDED to 3
    )

    private const val WORK_INFO_TIMEOUT_SECONDS = 10L
    private const val BACKOFF_DELAY_SECONDS = 30L
}
