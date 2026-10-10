/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.input.policy.EngineRestartEditorRehydrationGate
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the replay-free Fcitx event subscription and its generations. A subscription belongs to
 * exactly one engine instance; an engine restart or a failed prompt marker drops it, and a
 * replacement is made only once the new engine is READY and its editor has been rehydrated.
 */
internal class FcitxEventCollector(
    private val host: Host,
    private val rehydrationGate: EngineRestartEditorRehydrationGate
) {

    /** The service state and actions the collector reads or triggers. */
    data class Host(
        val fcitx: () -> FcitxConnection,
        val lifecycleScope: () -> CoroutineScope,
        val handleEvent: (FcitxEvent<*>) -> Unit,
        val postFcitxJob: (block: suspend FcitxAPI.() -> Unit) -> Job,
        val setEffectiveCapFlags: suspend FcitxAPI.(
            flags: CapabilityFlags,
            ime: InputMethodEntry,
            packageName: String?
        ) -> Unit,
        val discardLocalInputState: () -> Unit,
        val resetPromptForEngineRestart: () -> Unit
    )

    private var collectorJob: Job? = null
    private var restartJob: Job? = null
    private var collectorGeneration = 0L
    private var restartRequest = 0L
    private var readyCollectorGeneration = Long.MIN_VALUE
    private var observedEngineGeneration = Long.MIN_VALUE

    /** The engine generation the current subscription belongs to, or [Long.MIN_VALUE] when none. */
    var collectorEngineGeneration = Long.MIN_VALUE
        private set

    val isCollectorReady: Boolean
        get() = collectorJob?.isActive == true &&
            readyCollectorGeneration == collectorGeneration &&
            collectorEngineGeneration == host.fcitx().engineGeneration.value

    /** Begins collecting for the engine the service connected to and follows its restarts. */
    fun start() {
        val fcitx = host.fcitx()
        observedEngineGeneration = fcitx.engineGeneration.value
        restartWhenReady()
        host.lifecycleScope().launch {
            fcitx.engineGeneration.collect { generation ->
                if (generation != observedEngineGeneration) {
                    observedEngineGeneration = generation
                    // The monotonically increasing boundary survives StateFlow state conflation,
                    // so active, starting, and draining prompts all die with their old engine.
                    discardGenerationForPromptSafety(engineRestart = true)
                }
            }
        }
    }

    /** Starts a fresh, replay-free event subscription for the current Fcitx engine generation. */
    private fun startCollecting() {
        if (collectorJob?.isActive == true) return
        val generation = ++collectorGeneration
        val fcitx = host.fcitx()
        val engineGeneration = fcitx.engineGeneration.value
        collectorEngineGeneration = engineGeneration
        readyCollectorGeneration = Long.MIN_VALUE
        collectorJob = host.lifecycleScope().launch(start = CoroutineStart.UNDISPATCHED) {
            fcitx.runImmediately { eventFlow }.onSubscription {
                if (generation == collectorGeneration &&
                    engineGeneration == fcitx.engineGeneration.value
                ) {
                    readyCollectorGeneration = generation
                }
            }.collect {
                if (generation != collectorGeneration) return@collect
                if (engineGeneration != fcitx.engineGeneration.value) {
                    // A restart can move STOPPING -> STOPPED -> READY before StateFlow collectors
                    // run. Never route a new-engine event through an old prompt/collector epoch.
                    discardGenerationForPromptSafety(engineRestart = true)
                    return@collect
                }
                host.handleEvent(it)
            }
        }
    }

    /**
     * Subscribes only once the current engine has fully reached READY.
     *
     * The engine generation advances only after Fcitx.stop() returned from its native dispatcher.
     * Re-subscribing earlier could give an old native callback a new collector epoch.
     */
    private fun restartWhenReady() {
        val fcitx = host.fcitx()
        val request = ++restartRequest
        val engineGeneration = fcitx.engineGeneration.value
        restartJob?.cancel()
        restartJob = host.lifecycleScope().launch {
            fcitx.runOnReady { }
            if (request == restartRequest &&
                engineGeneration == fcitx.engineGeneration.value
            ) {
                val restored = restoreEditorAfterEngineRestart(
                    rehydrationGate.claimReady(engineGeneration)
                )
                if (restored && request == restartRequest &&
                    engineGeneration == fcitx.engineGeneration.value
                ) {
                    startCollecting()
                }
            }
        }
    }

    /**
     * A daemon-only engine restart creates a fresh native AndroidFrontend without another Android
     * bind/start callback. Recreate its InputContext before events or keys are allowed through.
     */
    private suspend fun restoreEditorAfterEngineRestart(
        plan: EngineRestartEditorRehydrationGate.Plan?
    ): Boolean {
        val fcitx = host.fcitx()
        val engineStillReady = {
            fcitx.runImmediately { isReady }
        }
        if (plan == null) return engineStillReady()
        if (plan.engineGeneration != fcitx.engineGeneration.value || !engineStillReady()) {
            return false
        }
        // A start-input-view callback may refine EditorInfo without issuing a second bind/start.
        // Resolve the claim again so its native restore targets that latest same-generation field.
        val currentPlan = rehydrationGate.refreshClaimedPlan(plan) ?: return true
        val failure = AtomicReference<Throwable?>()
        val appliedPlan = AtomicReference<EngineRestartEditorRehydrationGate.Plan?>()
        val restoreJob = host.postFcitxJob {
            // The Android editor may have changed while this job waited behind another operation.
            val latestPlan = rehydrationGate.refreshClaimedPlan(currentPlan)
                ?: return@postFcitxJob
            if (!isReady || latestPlan.engineGeneration != fcitx.engineGeneration.value
            ) return@postFcitxJob
            appliedPlan.set(latestPlan)
            activate(latestPlan.uid, latestPlan.packageName)
            latestPlan.inputMethodUniqueName?.takeIf { it.isNotBlank() }?.let { activateIme(it) }
            setCandidatePagingMode(if (latestPlan.isVirtualKeyboard) 0 else 1)
            host.setEffectiveCapFlags.invoke(
                this,
                latestPlan.capabilityFlags,
                inputMethodEntryCached,
                latestPlan.packageName
            )
            if (latestPlan.shouldFocus) focus(true)
        }
        restoreJob.invokeOnCompletion { cause -> failure.set(cause) }
        restoreJob.join()
        if (plan.engineGeneration != fcitx.engineGeneration.value || !engineStillReady()) {
            return false
        }
        val cause = failure.get()
        if (cause == null) return true
        Timber.w(cause, "Fcitx editor rehydration did not complete")
        if (rehydrationGate.retryAfterFailedRestore(appliedPlan.get() ?: currentPlan)) {
            restartWhenReady()
        }
        return false
    }

    /**
     * Drops the old event subscription before releasing a prompt gate.
     *
     * Once the collector is cancelled, any callback that predates a failed marker or a restart is
     * deliberately never routed through a later editor. Replacement collection waits for the
     * current engine's READY boundary, where its replay-free SharedFlow is safe to subscribe.
     */
    fun discardGenerationForPromptSafety(engineRestart: Boolean = false) {
        // Invalidate before cancellation: an in-flight collector callback must not write after
        // the prompt gate becomes idle.
        val engineGeneration = host.fcitx().engineGeneration.value
        observedEngineGeneration = engineGeneration
        if (engineRestart) {
            rehydrationGate.requestForEngineGeneration(engineGeneration)
            host.discardLocalInputState()
        }
        collectorGeneration += 1
        restartJob?.cancel()
        restartJob = null
        collectorEngineGeneration = Long.MIN_VALUE
        readyCollectorGeneration = Long.MIN_VALUE
        collectorJob?.cancel()
        collectorJob = null
        host.resetPromptForEngineRestart()
        restartWhenReady()
    }
}
