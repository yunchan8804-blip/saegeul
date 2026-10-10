/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentFailure
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentRunner
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentStatusStore
import org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphEnrichmentCycle
import java.util.concurrent.TimeUnit

/**
 * Pure decision for what [GemmaGraphEnrichmentScheduler.requestManual] should do with an existing
 * manual work item, given its current [WorkInfo.State]s (an empty list when none exists) and
 * whether a manual run is actually executing in this process right now
 * ([GraphEnrichmentRunner.isRunning], read by the caller). [workerActuallyRunning] always wins over
 * the `WorkManager`-reported states and forces [ManualGraphEnrichmentAction.ALREADY_RUNNING], never
 * `REPLACE`: on-device, `WorkManager` briefly reported a live run as `ENQUEUED` right after the OS
 * called `onStopJob` on it (the worker's coroutine was still finishing), and replacing a work item
 * whose worker is still actually running kills that live run and starts a fresh one - which is how
 * a single tap turned into a 12-minute gap with no progress. Short of that, an `ENQUEUED`/`BLOCKED`
 * item - including one waiting out a retry backoff - is replaced so the user's tap takes effect
 * immediately instead of waiting for whatever backoff delay was already in flight (a separate,
 * earlier on-device trace: a manual run stuck in exponential backoff was silently re-recognized as
 * "already pending" and the button did nothing for 43 minutes).
 */
internal enum class ManualGraphEnrichmentAction { ALREADY_RUNNING, REPLACE, ENQUEUE_NEW }

internal fun decideManualGraphEnrichmentAction(
    existingStates: List<WorkInfo.State>,
    workerActuallyRunning: Boolean
): ManualGraphEnrichmentAction = when {
    workerActuallyRunning -> ManualGraphEnrichmentAction.ALREADY_RUNNING
    existingStates.any { it == WorkInfo.State.RUNNING } -> ManualGraphEnrichmentAction.ALREADY_RUNNING
    existingStates.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } -> ManualGraphEnrichmentAction.REPLACE
    else -> ManualGraphEnrichmentAction.ENQUEUE_NEW
}

/**
 * Pure decision for whether a finished automatic run should register a one-shot follow-up run: only
 * for an automatic run ([manual] false) while the automatic switch is on ([automaticEnabled]), and
 * then either when the run's chunk budget ran out before the cycle ended ([cycleFinished] false) or
 * when the cycle ended with at least [PersonalGraphEnrichmentCycle.MIN_NEW_SENTENCES]
 * ([remainingSentences]) sentences still past the enrichment cursor.
 */
internal fun shouldScheduleGraphFollowUp(
    manual: Boolean,
    cycleFinished: Boolean,
    remainingSentences: Int,
    automaticEnabled: Boolean
): Boolean = when {
    manual || !automaticEnabled -> false
    !cycleFinished -> true
    else -> remainingSentences >= PersonalGraphEnrichmentCycle.MIN_NEW_SENTENCES
}

/**
 * Schedules the on-device [GemmaGraphEnrichmentWorker]: a periodic automatic run (paired 1:1 with
 * material-generation's own automatic toggle, since both share the dashboard's single "automatic"
 * switch) and a manual one-shot run triggered from the dashboard button. Neither run requires
 * charging or the screen to be off; the worker checks [GemmaGenerationEligibility] when it runs.
 */
object GemmaGraphEnrichmentScheduler {

    const val KEY_MANUAL = "manual"

    /**
     * Toggles only the periodic automatic run. A manual run (and its active generation) is the
     * user's own explicit action, started from the graph card's own button, and must only be
     * stopped by that card's own [중지]/[cancelManual] action - never as a side effect of disabling
     * material generation's automatic switch or pressing material's own "stop" button, both of
     * which call this with `enabled = false`. (On-device trace: `setEnabled(false)` used to also
     * cancel [MANUAL_WORK_NAME] and the active generator, so turning off material's automatic
     * switch - or its stop button - killed a manual graph run the user had started moments before.)
     */
    suspend fun setEnabled(context: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val applicationContext = context.applicationContext
        val workManager = WorkManager.getInstance(applicationContext)
        if (enabled) {
            enqueuePeriodic(workManager, ExistingPeriodicWorkPolicy.UPDATE)
        } else {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME).result.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            workManager.cancelUniqueWork(FOLLOW_UP_WORK_NAME).result.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    /**
     * Registers a single automatic run [FOLLOW_UP_DELAY_MINUTES] minutes out when
     * [shouldScheduleGraphFollowUp] says so (reading the automatic switch from
     * [GemmaAccumulationStore]), so a backlog is not left waiting for the next hourly tick. The run
     * is the ordinary automatic one - same worker, same eligibility checks and `processMutex`.
     * [ExistingWorkPolicy.APPEND_OR_REPLACE] is used because a follow-up registers the next one while
     * it is itself still running, which KEEP would silently drop; the new one queues behind it instead.
     */
    suspend fun scheduleAutomaticFollowUp(
        context: Context,
        manual: Boolean,
        cycleFinished: Boolean,
        remainingSentences: Int
    ) = withContext(Dispatchers.IO) {
        val applicationContext = context.applicationContext
        val automaticEnabled = GemmaAccumulationStore.get(applicationContext).load().enabled
        if (!shouldScheduleGraphFollowUp(manual, cycleFinished, remainingSentences, automaticEnabled)) {
            return@withContext
        }
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            FOLLOW_UP_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<GemmaGraphEnrichmentWorker>()
                .setInputData(workDataOf(KEY_MANUAL to false))
                .setInitialDelay(FOLLOW_UP_DELAY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
        )
    }

    /**
     * Idempotently (re)registers the periodic automatic run when material-generation's own
     * automatic toggle ([GemmaAccumulationState.enabled]) is on, with
     * [ExistingPeriodicWorkPolicy.UPDATE] so an already-registered run picks up the current
     * constraints (an older build registered it with a charging requirement) while keeping its
     * schedule and any run already in progress. This exists because the periodic work is otherwise
     * only (re-)registered when the user flips the automatic switch: a user who had it on before
     * this feature shipped would otherwise never get the periodic work registered until they toggle
     * it off and back on. Safe to call repeatedly (e.g. every time the dashboard is opened); a
     * no-op when automatic is off.
     */
    suspend fun reconcileAutomaticIfEnabled(context: Context) = withContext(Dispatchers.IO) {
        val applicationContext = context.applicationContext
        val automaticEnabled = GemmaAccumulationStore.get(applicationContext).load().enabled
        if (!automaticEnabled) return@withContext
        enqueuePeriodic(WorkManager.getInstance(applicationContext), ExistingPeriodicWorkPolicy.UPDATE)
    }

    private fun enqueuePeriodic(workManager: WorkManager, policy: ExistingPeriodicWorkPolicy) {
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            policy,
            PeriodicWorkRequestBuilder<GemmaGraphEnrichmentWorker>(PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
        ).result.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * Schedules a single manual run. Returns false without scheduling anything when the on-device
     * model is not ready (recording [GraphEnrichmentFailure.MODEL_UNAVAILABLE] so the dashboard
     * shows why). Otherwise returns true: either an already-`RUNNING` manual run is left alone (it
     * is already doing what the button asked for), or a not-yet-running one (including one waiting
     * out a retry backoff) is replaced so it starts immediately - see
     * [decideManualGraphEnrichmentAction]. A successful (re)schedule immediately records
     * [org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentPhase.QUEUED], clearing any stale
     * failure from a previous run so the dashboard does not keep showing it after the user acted.
     */
    suspend fun requestManual(context: Context): Boolean = withContext(Dispatchers.IO) {
        val applicationContext = context.applicationContext
        val model = GemmaModelFiles.modelFile(applicationContext)
        val modelReady = model.isFile && model.length() == GemmaModelFiles.MODEL_BYTES
        if (!modelReady) {
            GraphEnrichmentStatusStore(applicationContext).recordFailure(
                System.currentTimeMillis(),
                failure = GraphEnrichmentFailure.MODEL_UNAVAILABLE
            )
            return@withContext false
        }
        val workManager = WorkManager.getInstance(applicationContext)
        val existing = workManager.getWorkInfosForUniqueWork(MANUAL_WORK_NAME)
            .get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val action = decideManualGraphEnrichmentAction(existing.map { it.state }, GraphEnrichmentRunner.isRunning())
        when (action) {
            ManualGraphEnrichmentAction.ALREADY_RUNNING -> return@withContext true
            ManualGraphEnrichmentAction.REPLACE -> enqueueManual(workManager, ExistingWorkPolicy.REPLACE)
            ManualGraphEnrichmentAction.ENQUEUE_NEW -> enqueueManual(workManager, ExistingWorkPolicy.KEEP)
        }
        GraphEnrichmentStatusStore(applicationContext).recordQueued()
        true
    }

    /** Cancels a pending or running manual run, leaving its staging checkpoint intact so it can be resumed. */
    suspend fun cancelManual(context: Context) = withContext(Dispatchers.IO) {
        // Kills the active generation immediately (a running worker's own watchdog would otherwise
        // take up to its own poll interval to notice `isStopped`), then cancels the work item so it
        // does not restart.
        GemmaGraphEnrichmentRuntime.cancelActiveGenerator()
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(MANUAL_WORK_NAME).result.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun enqueueManual(workManager: WorkManager, policy: ExistingWorkPolicy) {
        workManager.enqueueUniqueWork(
            MANUAL_WORK_NAME,
            policy,
            OneTimeWorkRequestBuilder<GemmaGraphEnrichmentWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true))
                .setBackoffCriteria(BackoffPolicy.LINEAR, MANUAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
        ).result.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private const val OPERATION_TIMEOUT_SECONDS = 30L
    private const val PERIODIC_INTERVAL_HOURS = 1L
    private const val MANUAL_BACKOFF_SECONDS = 60L
    private const val FOLLOW_UP_DELAY_MINUTES = 15L
    const val PERIODIC_WORK_NAME = "gemma-graph-enrichment-periodic"
    const val MANUAL_WORK_NAME = "gemma-graph-enrichment-manual"
    const val FOLLOW_UP_WORK_NAME = "gemma-graph-enrichment-followup"
}

/** Mirrors [GemmaAccumulationRuntime]: lets [GemmaGraphEnrichmentScheduler] cancel a live run. */
internal object GemmaGraphEnrichmentRuntime {
    private val lock = Any()
    private var activeSession: GemmaGraphGenerationSession? = null

    fun register(session: GemmaGraphGenerationSession) {
        synchronized(lock) { activeSession = session }
    }

    fun unregister(session: GemmaGraphGenerationSession) {
        synchronized(lock) {
            if (activeSession === session) activeSession = null
        }
    }

    fun cancelActiveGenerator() {
        synchronized(lock) { activeSession }?.cancel()
    }
}
