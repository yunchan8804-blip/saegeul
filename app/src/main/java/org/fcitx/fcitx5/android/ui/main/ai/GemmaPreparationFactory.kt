/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAiSupport
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationScheduler
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationState
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationStore
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGenerationEligibility
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGenerationMode
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGraphEnrichmentScheduler
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelFiles
import timber.log.Timber
import java.util.concurrent.TimeUnit

object GemmaPreparationFactory {
    fun create(context: Context): GemmaPreparationController? {
        if (!OnDeviceAiSupport.isSupported) return null
        val applicationContext = context.applicationContext
        // There is no dedicated app-start hook this file can reach (FcitxApplication.kt is outside
        // this change's scope), so this is the earliest reliable, already-scoped place to self-heal
        // a user who had material-generation's automatic toggle on before this graph periodic work
        // existed: it is otherwise only (re-)registered when that toggle is flipped. Idempotent
        // (KEEP) and cheap; safe to run every time the dashboard is opened.
        FcitxApplication.getInstance().applicationScope.launch {
            try {
                GemmaGraphEnrichmentScheduler.reconcileAutomaticIfEnabled(applicationContext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "Failed to reconcile graph-enrichment automatic schedule")
            }
        }
        return GemmaPreparationControllerImpl(applicationContext)
    }
}

private class GemmaPreparationControllerImpl(
    private val applicationContext: Context
) : GemmaPreparationController {

    override suspend fun snapshot(): GemmaPreparationSnapshot = withContext(Dispatchers.IO) {
        val state = GemmaAccumulationStore.get(applicationContext).load()
        val workManager = WorkManager.getInstance(applicationContext)
        val immediateWork = workManager.getWorkInfosForUniqueWork(ONE_TIME_WORK_NAME)
            .get(WORK_INFO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val periodicWork = workManager.getWorkInfosForUniqueWork(PERIODIC_WORK_NAME)
            .get(WORK_INFO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val allWork = immediateWork + periodicWork
        val nativeGenerating = OnDeviceGenerationControl.isGenerating
        val running = nativeGenerating || allWork.any { it.state == WorkInfo.State.RUNNING }
        val queued = allWork.any(::isQueued)
        val activeWork = allWork.any(::isActive)
        val modelReady = GemmaModelFiles.modelFile(applicationContext).let { file ->
            file.isFile && file.length() == GemmaModelFiles.MODEL_BYTES
        }
        val canAttempt = state.enabled || state.manualRequested
        val eligibilityWaitReason = if (canAttempt) {
            GemmaGenerationEligibility.evaluate(
                GemmaGenerationEligibility.snapshot(applicationContext),
                if (state.manualRequested) {
                    GemmaGenerationMode.MANUAL
                } else {
                    GemmaGenerationMode.AUTOMATIC
                }
            )
        } else {
            null
        }
        val error = state.error
        val status = when {
            error != null -> applicationContext.getString(R.string.gemma_vault_status_error)
            !modelReady -> applicationContext.getString(R.string.gemma_vault_status_model_needed)
            running -> applicationContext.getString(R.string.gemma_vault_status_running)
            eligibilityWaitReason != null -> eligibilityWaitReason.message
            state.status == GemmaAccumulationState.STATUS_ATTEMPTS_EXHAUSTED ->
                applicationContext.getString(R.string.gemma_vault_status_attempts_exhausted)
            canAttempt && queued -> applicationContext.getString(R.string.gemma_vault_status_queued)
            state.manualRequested && !activeWork -> applicationContext.getString(R.string.gemma_vault_status_manual_waiting)
            !state.enabled -> applicationContext.getString(R.string.gemma_vault_status_automatic_off)
            state.status == GemmaAccumulationState.STATUS_RUNNING ->
                applicationContext.getString(R.string.gemma_vault_status_interrupted)
            else -> state.status
        }
        GemmaPreparationSnapshot(
            automaticEnabled = state.enabled,
            manualRequested = state.manualRequested,
            modelReady = modelReady,
            running = running,
            queued = queued,
            stored = state.stored,
            added = state.added,
            lastRunEpochMs = state.lastRunEpochMs,
            status = status,
            error = error
        )
    }

    override suspend fun setAutomaticEnabled(enabled: Boolean) {
        // The dashboard toggle is always a user action; setEnabled's byUser=true default records
        // whether the user just opted out, so a later model install knows not to re-enable this.
        GemmaAccumulationScheduler.setEnabled(applicationContext, enabled, byUser = true)
        GemmaGraphEnrichmentScheduler.setEnabled(applicationContext, enabled)
    }

    override suspend fun requestManual() {
        val modelReady = withContext(Dispatchers.IO) {
            GemmaModelFiles.modelFile(applicationContext).let { file ->
                file.isFile && file.length() == GemmaModelFiles.MODEL_BYTES
            }
        }
        check(modelReady) { "Gemma 모델을 준비한 뒤 수동 보충을 요청할 수 있습니다." }
        GemmaAccumulationScheduler.requestManual(applicationContext)
    }

    override suspend fun requestGraphEnrichment(): Boolean =
        GemmaGraphEnrichmentScheduler.requestManual(applicationContext)

    override suspend fun stopManualGraphEnrichment() {
        GemmaGraphEnrichmentScheduler.cancelManual(applicationContext)
    }

    override suspend fun prioritizeGraphOverMaterial() {
        // GemmaAccumulationScheduler's only cancellation entry point (setEnabled(false)) would also
        // disable the user's automatic material-generation setting, which this button must not do.
        // GemmaAccumulationRuntime (an internal, module-visible object declared alongside
        // GemmaAccumulationWorker, not part of its class body) is the narrower existing mechanism
        // that only cancels the currently active generation attempt, letting that worker's own
        // unmodified retry/backoff handle the aftermath exactly as it already does whenever it loses
        // a lease race today.
        withContext(Dispatchers.IO) {
            org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationRuntime.cancelActiveGenerator()
        }
    }

    override fun openModelManagement(context: Context) {
        GemmaModelManagementLauncher.open(context)
    }

    override fun isModelReady(context: Context): Boolean =
        GemmaModelFiles.modelFile(context).isFile

    private fun isQueued(workInfo: WorkInfo): Boolean =
        workInfo.state == WorkInfo.State.ENQUEUED || workInfo.state == WorkInfo.State.BLOCKED

    private fun isActive(workInfo: WorkInfo): Boolean =
        isQueued(workInfo) || workInfo.state == WorkInfo.State.RUNNING

    private companion object {
        const val PERIODIC_WORK_NAME = "gemma-accumulation-periodic"
        const val ONE_TIME_WORK_NAME = "gemma-accumulation-now"
        const val WORK_INFO_TIMEOUT_SECONDS = 5L
    }
}
