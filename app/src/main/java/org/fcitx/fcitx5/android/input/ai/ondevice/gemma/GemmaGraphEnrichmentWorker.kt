/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.fcitx.fcitx5.android.input.ai.rag.ChunkEnrichResult
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentChunkOutcome
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentFailure
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentLeaseWaiter
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentPauseReason
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentPhase
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentRunner
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentStatusStore
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentUiState
import org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphEnrichmentCycle
import org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphEnrichmentStagingStore
import org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphEnricher
import org.fcitx.fcitx5.android.input.ai.rag.detail
import org.fcitx.fcitx5.android.input.ai.rag.title
import org.fcitx.fcitx5.android.utils.BackgroundProgressNotifier
import timber.log.Timber
import java.io.File

/** What [GemmaGraphEnrichmentWorker.runChunk] does after a mid-generation lease loss (see [decideLeaseLossOutcome]). */
internal enum class LeaseLossOutcome {
    /** Loop back to [GemmaGraphGenerationSession.ensureReady] and resume the *same* chunk once the lease is available again. */
    RETRY_SAME_CHUNK,
    /** Stop this cycle with the given [GraphEnrichmentPauseReason.LEASE_WAIT_TIMEOUT]-independent reason; the worker records it and returns retry/success. */
    PAUSE
}

/**
 * What a manual/automatic run does when [GemmaGraphGenerationSession.generate] throws
 * [GemmaGraphGenerationSession.LeaseLostException] mid-chunk (the keyboard preempted the lease, or
 * the watchdog reacted to a device condition breaking): only a *manual* run interrupted by *only*
 * the keyboard retries the same chunk once it can re-acquire the lease (the keyboard closing is the
 * one interruption that resolves on its own within the same session); every other case pauses the
 * cycle with the concrete reason instead - see the on-device trace in the class doc this was written
 * to fix (a raw `CancellationException` escaping the worker made every keyboard tap during a manual
 * run hard-cancel the whole job with no way to resume and no recorded reason).
 */
internal fun decideLeaseLossOutcome(waitReason: GemmaGenerationWaitReason?, manual: Boolean): LeaseLossOutcome = when {
    waitReason == GemmaGenerationWaitReason.KEYBOARD_ACTIVE && manual -> LeaseLossOutcome.RETRY_SAME_CHUNK
    waitReason != null -> LeaseLossOutcome.PAUSE
    // A transient race - the lease was lost but by the time we re-checked, nothing is blocking any
    // more - so retry immediately rather than guessing a reason that is no longer true.
    else -> LeaseLossOutcome.RETRY_SAME_CHUNK
}

/**
 * On-device personal-graph enrichment. Chunks the user's own sentences (locally, never leaving the
 * device), sends each chunk through the on-device Gemma model, parses the response, and merges the
 * parsed fragment directly into [org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphStore] as soon
 * as that one chunk finishes - not batched until the cycle ends - so the user sees the effect after
 * the first batch (roughly a minute or two) instead of waiting for the whole cycle. The staging
 * checkpoint ([PersonalGraphEnrichmentStagingStore]) keeps only the cursor, the fixed chunk list,
 * and per-chunk timing, so a run broken off partway through resumes from
 * [PersonalGraphEnrichmentStagingStore.State.nextChunkIndex] with the exact same chunks.
 *
 * When a graph already exists, a new cycle only exports sentences new since it was last built
 * ([org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault.exportSince], capped at
 * [MAX_INCREMENTAL_SENTENCES]) instead of re-scanning the whole vault - see [startNewCycleOrNull].
 *
 * [GemmaGraphGenerationSession] keeps one Gemma engine open across the whole cycle (GPU first, CPU
 * fallback) instead of paying a fresh engine initialization per chunk, the majority of each chunk's
 * measured 90-130s wall time.
 *
 * Automatic runs (periodic, [GemmaGenerationMode.AUTOMATIC]) additionally require the screen to be
 * off, on top of [GemmaGenerationEligibility]'s battery/thermal/memory/keyboard gate and the
 * `WorkManager` charging + battery-not-low constraints the periodic request itself carries. Manual
 * runs (dashboard button, [GemmaGenerationMode.MANUAL]) have neither the charging/screen
 * requirement, but still wait for [GemmaGenerationEligibility] and for the keyboard to be hidden;
 * they also run as a foreground service ([trySetForeground]) since a plain background job can be
 * stopped by the system well before a multi-minute Gemma run finishes.
 *
 * When the on-device generation lease is held by another purpose (material generation, or the
 * keyboard), this worker no longer fails immediately: [GraphEnrichmentLeaseWaiter] retries every
 * few seconds up to a per-mode cap before giving up (see [runChunk]). Every point where a run stops
 * without reaching a terminal result (success/no_data/parse_failed) records why in the staging
 * checkpoint's [PersonalGraphEnrichmentStagingStore.State.pauseReason], so the dashboard can show a
 * plain-language reason instead of a generic "interrupted" message.
 *
 * A manual run's completion (success, failure, or pause alike) requests material generation's own
 * existing manual run ([GemmaAccumulationScheduler.requestManual]) so the two do not race for the
 * same on-device generation lease the way pressing "generate now" used to: the graph run goes
 * first, and material starts only once this worker's execution actually ends.
 */
class GemmaGraphEnrichmentWorker(
    appContext: Context,
    parameters: WorkerParameters
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(GemmaGraphEnrichmentScheduler.KEY_MANUAL, false)
        val mode = if (manual) GemmaGenerationMode.MANUAL else GemmaGenerationMode.AUTOMATIC
        val statusStore = GraphEnrichmentStatusStore(applicationContext)
        val stagingStore = stagingStore()
        // Whether this execution reached a genuine terminal result (finishCycle ran, or a
        // model-missing/unhandled-exception failure that nothing here will resume on its own).
        // Defaults true; the resumable early-exits below (and every pause() inside runCycle, via
        // markResumable) flip it false so material generation is not requested for those - see the
        // class doc's "graph goes first" note and the keyboard-preemption fix this guards against.
        var cycleTerminal = true

        if (!processMutex.tryLock()) {
            return if (manual) Result.retry() else Result.success()
        }
        try {
            currentCoroutineContext().ensureActive()
            if (isStopped) {
                cycleTerminal = false
                return Result.success()
            }

            val startWait = GemmaGenerationEligibility.evaluate(
                GemmaGenerationEligibility.snapshot(applicationContext), mode
            )
            if (startWait != null) {
                cycleTerminal = false
                return if (manual) Result.retry() else Result.success()
            }

            val model = GemmaModelFiles.modelFile(applicationContext)
            if (!(model.isFile && model.length() == GemmaModelFiles.MODEL_BYTES)) {
                statusStore.recordFailure(System.currentTimeMillis(), failure = GraphEnrichmentFailure.MODEL_UNAVAILABLE)
                if (manual) GraphEnrichmentRunner.notifyCompletion(applicationContext, null, GraphEnrichmentFailure.MODEL_UNAVAILABLE)
                return Result.failure()
            }

            var state = stagingStore.load()
            if (state == null) {
                Timber.tag(GemmaGraphGenerationSession.LOG_TAG).i("cycle start manual=%s", manual)
                state = startNewCycleOrNull(stagingStore, statusStore, manual)
                    ?: return Result.success()
            } else {
                Timber.tag(GemmaGraphGenerationSession.LOG_TAG).i(
                    "cycle resume manual=%s chunk=%d/%d", manual, state.nextChunkIndex, state.chunks.size
                )
            }
            // Always RUNNING once real work begins - including a resumed cycle and the very first
            // lease wait - never left at QUEUED. A resumed cycle previously skipped this (it was
            // only called from startNewCycleOrNull), so the dashboard kept showing "starting soon"
            // for a run that was actually already going.
            statusStore.recordStarted(System.currentTimeMillis())
            GraphEnrichmentRunner.markRunning()
            if (manual) trySetForeground(state)

            return runCycle(state, model, mode, manual, statusStore, stagingStore) { cycleTerminal = false }
        } catch (cancelled: CancellationException) {
            if (isStopped) {
                // A real WorkManager stop - the one case this is allowed to propagate as-is.
                throw cancelled
            }
            // Defensive backstop: everything this round's fix knows about (the keyboard preempting
            // the lease, the watchdog reacting to a device condition mid-generation) is now caught
            // and turned into a pause/retry inside runChunk/runCycle - see
            // GemmaGraphGenerationSession.LeaseLostException's doc. If a CancellationException still
            // reaches here with isStopped == false, some other path let one escape uncaught; treat it
            // the same way rather than letting WorkManager hard-cancel the whole job with no recorded
            // reason (the exact on-device trace this whole fix responds to).
            cycleTerminal = false
            Timber.tag(GemmaGraphGenerationSession.LOG_TAG).i(
                "doWork caught a CancellationException while isStopped=false; treating as a pause, not a hard cancel"
            )
            return if (manual) Result.retry() else Result.success()
        } catch (error: Exception) {
            statusStore.recordFailure(System.currentTimeMillis(), failure = GraphEnrichmentFailure.UNKNOWN, detail = error.javaClass.simpleName)
            if (manual) GraphEnrichmentRunner.notifyCompletion(applicationContext, null, GraphEnrichmentFailure.UNKNOWN)
            return Result.failure()
        } finally {
            GraphEnrichmentRunner.markStopped()
            processMutex.unlock()
            if (manual && cycleTerminal) {
                // Graph goes first; material only starts once this execution has actually and
                // genuinely ended (success, no-data, model-unavailable, or an unhandled failure) -
                // never for a pause/retry exit, which would let material grab the lease the instant
                // it frees up, before the graph worker itself gets a chance to resume. NonCancellable:
                // this must still fire even when doWork() is exiting because WorkManager cancelled it.
                withContext(NonCancellable) {
                    runCatching { GemmaAccumulationScheduler.requestManual(applicationContext) }
                }
            }
        }
    }

    /**
     * Promotes a manual run to a foreground service so the system does not stop it as an ordinary
     * background job partway through a multi-minute Gemma generation (observed on-device: a plain
     * `WorkManager` job was stopped by `JobScheduler` well before a chunk finished). Android 14+
     * requires a declared foreground service type for this; below that, the plain two-argument
     * `ForegroundInfo` is used. If `setForeground` itself fails for any reason (for example
     * `ForegroundServiceStartNotAllowedException` on some OEM/OS combinations), this only logs and
     * lets the worker continue as an ordinary background task - the graph-enrichment button must
     * keep working even where foreground promotion is refused.
     */
    private suspend fun trySetForeground(state: PersonalGraphEnrichmentStagingStore.State) {
        try {
            setForeground(foregroundInfoFor(state))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(
                "Failed to promote graph enrichment to a foreground worker (%s); continuing in the background",
                error.javaClass.simpleName
            )
        }
    }

    /** Updates the manual run's foreground notification to match [state] (same text the dashboard shows). */
    private suspend fun updateManualForeground(state: PersonalGraphEnrichmentStagingStore.State) {
        try {
            setForeground(foregroundInfoFor(state))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(
                "Failed to update graph enrichment foreground notification (%s)",
                error.javaClass.simpleName
            )
        }
    }

    private fun foregroundInfoFor(state: PersonalGraphEnrichmentStagingStore.State): ForegroundInfo {
        val uiState = GraphEnrichmentUiState.from(
            phase = GraphEnrichmentPhase.RUNNING,
            failure = GraphEnrichmentFailure.NONE,
            staging = state,
            nowMs = System.currentTimeMillis(),
            automaticEnabled = false,
            lastAppliedMs = 0L,
            graphNodes = 0,
            graphEdges = 0,
            graphTopics = 0
        )
        val stopAction = NotificationCompat.Action(
            0,
            applicationContext.getString(R.string.enrichment_action_stop),
            WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        )
        val notification = BackgroundProgressNotifier.buildProgressNotification(
            applicationContext,
            BackgroundProgressNotifier.ProgressSpec(
                title = uiState.title(applicationContext),
                text = uiState.detail(applicationContext) ?: uiState.title(applicationContext),
                current = uiState.progressCurrent,
                total = uiState.progressTotal,
                action = stopAction
            )
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                BackgroundProgressNotifier.ID_GRAPH_ENRICH,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(BackgroundProgressNotifier.ID_GRAPH_ENRICH, notification)
        }
    }

    /**
     * Returns a freshly started cycle's state, or null when either [PersonalGraphEnrichmentCycle]
     * says it is not yet time for a new cycle (never true for [manual], which always starts one),
     * or the vault has nothing to enrich (recorded as `no_data` immediately, since there is nothing
     * a later run could add). When a graph already exists, only sentences new since it was last
     * built are exported ([MAX_INCREMENTAL_SENTENCES] cap); otherwise the first-ever full export
     * ([MAX_SENTENCES] cap) runs, matching [PersonalGraphEnrichmentCycle]'s own existing-graph check.
     */
    private suspend fun startNewCycleOrNull(
        stagingStore: PersonalGraphEnrichmentStagingStore,
        statusStore: GraphEnrichmentStatusStore,
        manual: Boolean
    ): PersonalGraphEnrichmentStagingStore.State? {
        val app = FcitxApplication.getInstance()
        val graphStats = app.personalGraphStore.stats()
        val vaultStats = app.personalSentenceVault.stats()
        val hasExistingGraph = graphStats.builtMs > 0L
        val newSentences = (vaultStats.sentences - graphStats.sourceSentenceCount).coerceAtLeast(0)
        val msSinceBuild = System.currentTimeMillis() - graphStats.builtMs
        if (!PersonalGraphEnrichmentCycle.shouldStart(hasExistingGraph, newSentences, msSinceBuild, manual)) {
            return null
        }
        val sentences = if (hasExistingGraph) {
            app.personalSentenceVault.exportSince(graphStats.builtMs, MAX_INCREMENTAL_SENTENCES)
        } else {
            app.personalSentenceVault.exportForEnrichment(MAX_SENTENCES)
        }
        if (sentences.isEmpty()) {
            val result = PersonalGraphEnricher.EnrichResult(false, "no_data", 0, 0, 0)
            statusStore.recordResult(result, System.currentTimeMillis())
            if (manual) GraphEnrichmentRunner.notifyCompletion(applicationContext, result, GraphEnrichmentFailure.NONE)
            return null
        }
        val chunks = PersonalGraphEnricher.chunksFor(sentences, MAX_CHARS_PER_CHUNK)
        val state = PersonalGraphEnrichmentStagingStore.State(
            cycleStartedMs = System.currentTimeMillis(),
            sourceSentenceCountAtStart = vaultStats.sentences,
            chunks = chunks,
            nextChunkIndex = 0,
            successCount = 0,
            failCount = 0,
            manual = manual,
            isIncremental = hasExistingGraph,
            processedSentenceCount = sentences.size
        )
        stagingStore.save(state)
        return state
    }

    private suspend fun runCycle(
        initialState: PersonalGraphEnrichmentStagingStore.State,
        model: File,
        mode: GemmaGenerationMode,
        manual: Boolean,
        statusStore: GraphEnrichmentStatusStore,
        stagingStore: PersonalGraphEnrichmentStagingStore,
        markResumable: () -> Unit
    ): Result {
        var current = initialState
        var processedThisRun = 0
        // Cumulative lease-wait time across the whole cycle, used only to isolate how much of each
        // individual chunk's wall time (below) was waiting rather than actually generating - see
        // PersonalGraphEnrichmentStagingStore.State.lastWaitMs's doc.
        var totalWaitMs = 0L
        val session = GemmaGraphGenerationSession()
        // Persists the waiting/not-waiting transition into the staging checkpoint so the dashboard
        // (which reads the staging file for its progress text) can show a distinct "waiting for
        // another on-device task" message instead of looking stuck mid-chunk, and updates the
        // manual run's foreground notification to match. The keyboard being visible OR still within
        // its post-hide grace (OnDeviceGenerationControl.isKeyboardActive - PERSONAL_GRAPH's own
        // lease rule waits out PERSONAL_GRAPH_KEYBOARD_GRACE_MS of that grace, not the input view's
        // instant-off signal) is what tells this wait apart from material generation holding the
        // lease - see PersonalGraphEnrichmentStagingStore.State.waitingReason's doc.
        val onWaitingChanged: suspend (Boolean) -> Unit = { waiting ->
            if (!waiting && current.waitingOnLease) {
                totalWaitMs += (System.currentTimeMillis() - current.waitingOnLeaseSinceMs).coerceAtLeast(0L)
            }
            val reason = if (waiting &&
                (OnDeviceGenerationControl.isInputViewVisible || OnDeviceGenerationControl.isKeyboardActive)
            ) {
                GraphEnrichmentPauseReason.KEYBOARD_ACTIVE
            } else {
                GraphEnrichmentPauseReason.NONE
            }
            current = current.copy(
                waitingOnLease = waiting,
                waitingOnLeaseSinceMs = if (waiting) System.currentTimeMillis() else 0L,
                waitingReason = if (waiting) reason else GraphEnrichmentPauseReason.NONE
            )
            stagingStore.save(current)
            if (manual) updateManualForeground(current)
        }

        suspend fun pause(reason: GraphEnrichmentPauseReason): Result {
            current = current.copy(pauseReason = reason)
            stagingStore.save(current)
            if (manual) GraphEnrichmentRunner.notifyCancelled(applicationContext)
            markResumable()
            Timber.tag(GemmaGraphGenerationSession.LOG_TAG).i("paused reason=%s chunk=%d/%d", reason, current.nextChunkIndex, current.chunks.size)
            return if (manual) Result.retry() else Result.success()
        }

        GemmaGraphEnrichmentRuntime.register(session)
        try {
            // Manual runs process every remaining chunk to completion (no per-run cap); automatic
            // runs stop after MAX_CHUNKS_PER_AUTO_RUN and resume on a later periodic tick.
            while (current.nextChunkIndex < current.chunks.size &&
                (manual || processedThisRun < MAX_CHUNKS_PER_AUTO_RUN)
            ) {
                currentCoroutineContext().ensureActive()
                if (isStopped) return pause(GraphEnrichmentPauseReason.USER_STOPPED)
                val waitReason = GemmaGenerationEligibility.evaluate(
                    GemmaGenerationEligibility.snapshot(applicationContext), mode
                )
                if (waitReason != null) {
                    return pause(pauseReasonFor(waitReason))
                }

                val chunkText = current.chunks[current.nextChunkIndex]
                val chunkStartedAtMs = System.currentTimeMillis()
                val waitBeforeChunkMs = totalWaitMs
                when (val outcome = runChunk(session, model, chunkText, mode, manual, onWaitingChanged)) {
                    is ChunkRunOutcome.Paused -> return pause(outcome.reason)
                    ChunkRunOutcome.LeaseTimedOut -> return pause(GraphEnrichmentPauseReason.LEASE_WAIT_TIMEOUT)
                    is ChunkRunOutcome.Completed -> {
                        val rawElapsedMs = (System.currentTimeMillis() - chunkStartedAtMs).coerceAtLeast(0L)
                        // Excludes any lease wait(s) this chunk hit (e.g. re-acquiring after the
                        // keyboard preempted mid-generation) so completedChunkDurationMsSum/the ETA
                        // and this log line both reflect actual generation time only.
                        val waitMs = (totalWaitMs - waitBeforeChunkMs).coerceAtLeast(0L)
                        val elapsedMs = (rawElapsedMs - waitMs).coerceAtLeast(0L)
                        current = applyChunkResultIncrementally(current, outcome.result, elapsedMs, waitMs, statusStore)
                        stagingStore.save(current)
                        processedThisRun++
                        if (manual) updateManualForeground(current)
                        Timber.tag(GemmaGraphGenerationSession.LOG_TAG).i(
                            "chunk %d/%d done ms=%d backend=%s",
                            current.nextChunkIndex,
                            current.chunks.size,
                            elapsedMs,
                            if (session.usedGpu) "GPU" else "CPU"
                        )
                    }
                }
            }
        } finally {
            GemmaGraphEnrichmentRuntime.unregister(session)
            session.close()
        }

        if (current.nextChunkIndex < current.chunks.size) {
            // Ran out of this run's chunk budget; resumes on the next scheduled/manual run.
            markResumable()
            return Result.success()
        }
        return finishCycle(current, manual, statusStore, stagingStore)
    }

    private fun pauseReasonFor(waitReason: GemmaGenerationWaitReason): GraphEnrichmentPauseReason = when (waitReason) {
        GemmaGenerationWaitReason.BATTERY_LEVEL_UNKNOWN -> GraphEnrichmentPauseReason.BATTERY_LEVEL_UNKNOWN
        GemmaGenerationWaitReason.BATTERY_BELOW_MINIMUM,
        GemmaGenerationWaitReason.MANUAL_BATTERY_BELOW_MINIMUM -> GraphEnrichmentPauseReason.BATTERY_LOW
        GemmaGenerationWaitReason.POWER_SAVE_MODE -> GraphEnrichmentPauseReason.POWER_SAVE
        GemmaGenerationWaitReason.THERMAL_LIMITED -> GraphEnrichmentPauseReason.THERMAL
        GemmaGenerationWaitReason.LOW_MEMORY -> GraphEnrichmentPauseReason.LOW_MEMORY
        GemmaGenerationWaitReason.KEYBOARD_ACTIVE -> GraphEnrichmentPauseReason.KEYBOARD_ACTIVE
    }

    /**
     * Merges a completed chunk's fragment directly into the main
     * [org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphStore] (all-or-nothing per cycle is gone:
     * every chunk's result lands as soon as it is parsed) and records the new totals via
     * [GraphEnrichmentStatusStore.recordIncrementalApply] so the dashboard's "last applied" line and
     * counts move forward with it. The staging [state] itself only advances the cursor/timing/counts.
     * [elapsedMs] is generation time only (lease wait already excluded by the caller); [waitMs] is
     * that excluded wait, stored separately as [PersonalGraphEnrichmentStagingStore.State.lastWaitMs].
     */
    private fun applyChunkResultIncrementally(
        state: PersonalGraphEnrichmentStagingStore.State,
        chunkResult: ChunkEnrichResult,
        elapsedMs: Long,
        waitMs: Long,
        statusStore: GraphEnrichmentStatusStore
    ): PersonalGraphEnrichmentStagingStore.State {
        val succeeded = chunkResult.outcome == GraphEnrichmentChunkOutcome.VALID
        if (succeeded && (chunkResult.nodes.isNotEmpty() || chunkResult.edges.isNotEmpty() || chunkResult.topics.isNotEmpty())) {
            val app = FcitxApplication.getInstance()
            val (baseNodes, baseEdges, baseTopics) = app.personalGraphStore.snapshot()
            val (mergedNodes, mergedEdges, mergedTopics) = PersonalGraphEnricher.mergeGraphs(
                baseNodes, baseEdges, baseTopics,
                chunkResult.nodes, chunkResult.edges, chunkResult.topics
            )
            app.personalGraphStore.replaceGraph(
                mergedNodes, mergedEdges, mergedTopics,
                System.currentTimeMillis(),
                sourceSentenceCount = app.personalSentenceVault.stats().sentences
            )
            app.personalGraphStore.save()
            val stats = app.personalGraphStore.stats()
            statusStore.recordIncrementalApply(System.currentTimeMillis(), stats.nodes, stats.edges, stats.topics)
        }
        return state.copy(
            nextChunkIndex = state.nextChunkIndex + 1,
            successCount = state.successCount + if (succeeded) 1 else 0,
            failCount = state.failCount + if (succeeded) 0 else 1,
            completedChunkDurationMsSum = state.completedChunkDurationMsSum + elapsedMs,
            lastWaitMs = waitMs,
            pauseReason = GraphEnrichmentPauseReason.NONE
        )
    }

    private fun finishCycle(
        state: PersonalGraphEnrichmentStagingStore.State,
        manual: Boolean,
        statusStore: GraphEnrichmentStatusStore,
        stagingStore: PersonalGraphEnrichmentStagingStore
    ): Result {
        stagingStore.clear()
        if (state.successCount == 0) {
            val result = PersonalGraphEnricher.EnrichResult(false, "parse_failed", 0, 0, 0)
            statusStore.recordResult(result, System.currentTimeMillis())
            if (manual) GraphEnrichmentRunner.notifyCompletion(applicationContext, result, GraphEnrichmentFailure.INVALID_RESPONSE)
            Timber.tag(GemmaGraphGenerationSession.LOG_TAG).i("cycle result=parse_failed")
            return Result.success()
        }
        val stats = FcitxApplication.getInstance().personalGraphStore.stats()
        val reason = if (state.failCount == 0) "ok" else "partial"
        val result = PersonalGraphEnricher.EnrichResult(true, reason, stats.nodes, stats.edges, stats.topics)
        statusStore.recordResult(result, System.currentTimeMillis())
        if (manual) GraphEnrichmentRunner.notifyCompletion(applicationContext, result, GraphEnrichmentFailure.NONE)
        Timber.tag(GemmaGraphGenerationSession.LOG_TAG).i("cycle result=%s nodes=%d edges=%d topics=%d", reason, stats.nodes, stats.edges, stats.topics)
        return Result.success()
    }

    /** [runChunk]'s outcome: either a parsed (possibly empty/invalid) chunk, or why generation never ran. */
    private sealed interface ChunkRunOutcome {
        data class Completed(val result: ChunkEnrichResult) : ChunkRunOutcome
        data object LeaseTimedOut : ChunkRunOutcome
        data class Paused(val reason: GraphEnrichmentPauseReason) : ChunkRunOutcome
    }

    /**
     * Generates and parses one chunk on [session]. [GemmaGraphGenerationSession.ensureReady] only
     * actually waits/opens an engine the first time (or after the lease was lost); once ready,
     * later chunks generate directly on the already-open engine. [onWaitingChanged] mirrors the
     * waiting state into the staging checkpoint for the dashboard.
     *
     * A [GemmaGraphGenerationSession.LeaseLostException] mid-generation (the watchdog below reacting
     * to the keyboard appearing or a device condition breaking) is not itself a pause: for a manual
     * run interrupted only by the keyboard, this closes the session and loops back through
     * [GemmaGraphGenerationSession.ensureReady] - which waits for the keyboard to close via
     * [GraphEnrichmentLeaseWaiter] the same way the very first chunk would - and resumes the *same*
     * chunk once the lease is available again, rather than treating the whole run as broken. Every
     * other interruption (an automatic run, or any non-keyboard reason) becomes a [ChunkRunOutcome.Paused]
     * with the concrete reason instead.
     */
    private suspend fun runChunk(
        session: GemmaGraphGenerationSession,
        model: File,
        chunkText: String,
        mode: GemmaGenerationMode,
        manual: Boolean,
        onWaitingChanged: suspend (Boolean) -> Unit
    ): ChunkRunOutcome = coroutineScope {
        val watchdog = launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                val wait = GemmaGenerationEligibility.evaluate(GemmaGenerationEligibility.snapshot(applicationContext), mode)
                if (isStopped || wait != null) {
                    session.cancel()
                    return@launch
                }
            }
        }
        try {
            var leaseTimedOut = false
            var pausedReason: GraphEnrichmentPauseReason? = null
            val enriched = PersonalGraphEnricher.enrichChunk(chunkText) { instruction, input ->
                val prompt = buildPrompt(instruction, input)
                var text: String? = null
                while (text == null && !leaseTimedOut && pausedReason == null) {
                    val ready = session.ensureReady(
                        modelFile = model,
                        manual = manual,
                        shouldAbort = { isStopped },
                        onWaitingChanged = onWaitingChanged
                    )
                    when (ready) {
                        is GraphEnrichmentLeaseWaiter.Result.Acquired -> {
                            try {
                                text = session.generate(prompt)
                            } catch (lost: GemmaGraphGenerationSession.LeaseLostException) {
                                // lost 자체는 신호일 뿐이다: 무엇이 임차권을 뺏었는지는 아래에서 다시 평가해 재시도/일시정지를 결정한다.
                                if (isStopped) throw CancellationException("WorkManager stopped the graph enrichment worker")
                                session.close()
                                val waitReason = GemmaGenerationEligibility.evaluate(
                                    GemmaGenerationEligibility.snapshot(applicationContext), mode
                                )
                                when (decideLeaseLossOutcome(waitReason, manual)) {
                                    LeaseLossOutcome.RETRY_SAME_CHUNK -> Unit
                                    LeaseLossOutcome.PAUSE -> pausedReason = waitReason?.let(::pauseReasonFor)
                                        ?: GraphEnrichmentPauseReason.LEASE_WAIT_TIMEOUT
                                }
                            }
                        }
                        GraphEnrichmentLeaseWaiter.Result.GaveUp -> leaseTimedOut = true
                        GraphEnrichmentLeaseWaiter.Result.Aborted -> {
                            pausedReason = if (isStopped) GraphEnrichmentPauseReason.USER_STOPPED else GraphEnrichmentPauseReason.SCREEN_ON
                        }
                    }
                }
                if (text != null) listOf(text) else emptyList()
            }
            val finalPausedReason = pausedReason
            when {
                finalPausedReason != null -> ChunkRunOutcome.Paused(finalPausedReason)
                leaseTimedOut -> ChunkRunOutcome.LeaseTimedOut
                else -> ChunkRunOutcome.Completed(enriched)
            }
        } finally {
            watchdog.cancelAndJoin()
        }
    }

    private fun buildPrompt(instruction: String, chunk: String): String = """
        $instruction
        JSON 객체 하나만 출력하고, 설명이나 코드펜스는 절대 포함하지 마라.
        노드는 최대 20개, 엣지는 최대 25개, 토픽은 최대 3개까지만 만들어라.
        ---BEGIN SENTENCES---
        $chunk
        ---END SENTENCES---
    """.trimIndent()

    private fun stagingStore(): PersonalGraphEnrichmentStagingStore = PersonalGraphEnrichmentStagingStore(
        File(applicationContext.filesDir, "personal_graph_staging.json"),
        cipher = FcitxApplication.getInstance().vaultCipher
    )

    private companion object {
        val processMutex = Mutex()
        const val WATCHDOG_INTERVAL_MS = 250L
        const val MAX_SENTENCES = 400
        const val MAX_INCREMENTAL_SENTENCES = 120
        const val MAX_CHARS_PER_CHUNK = 700
        const val MAX_CHUNKS_PER_AUTO_RUN = 4
    }
}
