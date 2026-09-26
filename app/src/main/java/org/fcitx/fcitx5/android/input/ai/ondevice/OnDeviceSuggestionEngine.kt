/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelFiles
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only automatic-context native host.
 *
 * [suggest] is serialized and returns only bounded response text and timing; it never logs prompt
 * or response text. Failures use [OnDeviceSuggestionException.code]: `BUSY`, `MODEL_MISSING`,
 * `RESOURCE_*`, `CANCELLED`, `OUTPUT_TOO_LONG`, `NATIVE_STOP_TIMEOUT`, or `NATIVE_CLOSE_FAILED`.
 * A warm Engine retains no Conversation: every request has a new Conversation and closes it before
 * the next request. The Engine itself belongs to [OnDeviceSharedEngine]: this class only holds a use of
 * it while warm. A cancelled or failed request releases that use and the lease; only an engine-level
 * failure or a sustained resource violation also asks the shared engine to close.
 */
class OnDeviceSuggestionEngine(
    context: Context,
    private val backend: BackendSelection = BackendSelection.Cpu()
) {

    sealed interface BackendSelection {
        data class Cpu(val threadCount: Int? = null) : BackendSelection
        data object Gpu : BackendSelection
    }

    data class SuggestionResult(
        val text: String,
        val verificationMs: Long,
        val initializationMs: Long,
        val firstTextMs: Long?,
        val totalMs: Long,
        val engineReused: Boolean
    ) {
        override fun toString(): String =
            "SuggestionResult(text=<redacted>, verificationMs=$verificationMs, " +
                "initializationMs=$initializationMs, firstTextMs=$firstTextMs, " +
                "totalMs=$totalMs, engineReused=$engineReused)"
    }

    data class PreparationResult(
        val verificationMs: Long,
        val initializationMs: Long,
        val totalMs: Long,
        val engineReused: Boolean
    )

    class OnDeviceSuggestionException(val code: String) : IllegalStateException(code)

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private val stateLock = Any()
    private val nativeLock = Any()

    private var warm: WarmEngine? = null
    private var lease: OnDeviceGenerationControl.Lease? = null
    private var unclosedConversation: Conversation? = null
    private var activeRequest: Request? = null
    private var preparingRequest: Request? = null
    private var resourceMonitor: Job? = null
    private var idleClose: Job? = null
    private var terminalFailure: String? = null
    private var closeRequested = false

    val isRunning: Boolean
        get() = synchronized(stateLock) { activeRequest != null }

    val isPreparing: Boolean
        get() = synchronized(stateLock) { preparingRequest != null }

    val isInferring: Boolean
        get() {
            val request = synchronized(stateLock) { activeRequest } ?: return false
            return synchronized(nativeLock) { request.conversation != null }
        }

    val isWarm: Boolean
        get() = synchronized(stateLock) { warm != null }

    val terminalFailureCode: String?
        get() = synchronized(stateLock) { terminalFailure }

    suspend fun suggest(prompt: String): SuggestionResult {
        require(prompt.isNotBlank()) { "Gemma prompt는 비어 있을 수 없습니다." }
        require(prompt.toByteArray(Charsets.UTF_8).size <= MAX_PROMPT_UTF8_BYTES) {
            "Gemma prompt는 UTF-8 기준 8192바이트 이하여야 합니다."
        }
        val request = Request(SystemClock.elapsedRealtime())
        val worker = scope.async { execute(request, prompt) }
        return try {
            worker.await()
        } catch (error: CancellationException) {
            request.cancelled.set(true)
            val active = synchronized(stateLock) { activeRequest === request }
            if (!active) {
                worker.cancel()
                worker.join()
                throw error
            }
            requestNativeCancellationAsync(request)
            try {
                withContext(NonCancellable) {
                    withTimeout(NATIVE_STOP_BUDGET_MS) { worker.join() }
                }
            } catch (_: TimeoutCancellationException) {
                latchTerminalFailure("NATIVE_STOP_TIMEOUT")
                waitForLateCleanup()
                throw OnDeviceSuggestionException("NATIVE_STOP_TIMEOUT")
            }
            throw error
        }
    }

    suspend fun prepare(): PreparationResult {
        val request = Request(SystemClock.elapsedRealtime())
        val worker = scope.async { prepare(request) }
        return try {
            withTimeout(GENERATION_BUDGET_MS) { worker.await() }
        } catch (_: TimeoutCancellationException) {
            request.cancelled.set(true)
            try {
                withContext(NonCancellable) {
                    withTimeout(NATIVE_STOP_BUDGET_MS) { worker.join() }
                }
            } catch (_: TimeoutCancellationException) {
                latchTerminalFailure("NATIVE_STOP_TIMEOUT")
                waitForLateCleanup()
                throw OnDeviceSuggestionException("NATIVE_STOP_TIMEOUT")
            }
            throw OnDeviceSuggestionException("PREPARATION_TIMEOUT")
        } catch (error: CancellationException) {
            request.cancelled.set(true)
            try {
                withContext(NonCancellable) {
                    withTimeout(NATIVE_STOP_BUDGET_MS) { worker.join() }
                }
            } catch (_: TimeoutCancellationException) {
                latchTerminalFailure("NATIVE_STOP_TIMEOUT")
                waitForLateCleanup()
                throw OnDeviceSuggestionException("NATIVE_STOP_TIMEOUT")
            }
            throw error
        }
    }

    /** Requests cancellation of the current Conversation only; cleanup remains owned by its worker. */
    fun cancel() {
        synchronized(stateLock) { activeRequest }?.let { request ->
            request.cancelled.set(true)
            requestNativeCancellationAsync(request)
        }
    }

    /** Closes the current request and any idle warm Engine. A timeout keeps the native lease owned. */
    suspend fun close() {
        markCloseAndCancel()
        val worker = scope.async {
            operationMutex.withLock {
                closeWarmLocked()
                synchronized(stateLock) { closeRequested = false }
            }
        }
        try {
            withContext(NonCancellable) {
                withTimeout(NATIVE_STOP_BUDGET_MS) {
                    worker.await()
                }
            }
        } catch (_: TimeoutCancellationException) {
            latchTerminalFailure("NATIVE_STOP_TIMEOUT")
            waitForLateCleanup()
            throw OnDeviceSuggestionException("NATIVE_STOP_TIMEOUT")
        }
    }

    private suspend fun execute(request: Request, prompt: String): SuggestionResult = operationMutex.withLock {
        withTimeout(GENERATION_BUDGET_MS) {
            executeLocked(request, prompt)
        }
    }

    private suspend fun prepare(request: Request): PreparationResult = operationMutex.withLock {
        prepareLocked(request)
    }

    private suspend fun prepareLocked(request: Request): PreparationResult {
        checkNotCancelled(request)
        synchronized(stateLock) {
            terminalFailure?.let { throw OnDeviceSuggestionException(it) }
            if (closeRequested) throw OnDeviceSuggestionException("BUSY")
            idleClose?.cancel()
            idleClose = null
            preparingRequest = request
        }
        var failure: Throwable? = null
        var result: PreparationResult? = null
        try {
            requireEligibleResources()
            checkNotClosing(request)
            val established = ensureWarm(request)
            checkNotClosing(request)
            requireEligibleResources()
            checkNotClosing(request)
            result = PreparationResult(
                verificationMs = established.verificationMs,
                initializationMs = established.initializationMs,
                totalMs = SystemClock.elapsedRealtime() - request.startedAt,
                engineReused = established.reused
            )
        } catch (error: Throwable) {
            failure = error
        } finally {
            synchronized(stateLock) {
                if (preparingRequest === request) preparingRequest = null
            }
            if (failure != null || request.cancelled.get() || isCloseRequested()) {
                try {
                    closeWarmLocked(discardSharedEngine = failure?.let(::isEngineFault) == true)
                } catch (error: Throwable) {
                    if (failure == null) failure = error else failure?.addSuppressed(error)
                }
            } else {
                scheduleIdleClose()
            }
        }
        if ((request.cancelled.get() || isCloseRequested()) && failure == null) {
            failure = OnDeviceSuggestionException("CANCELLED")
            try {
                closeWarmLocked()
            } catch (error: Throwable) {
                failure?.addSuppressed(error)
            }
        }
        failure?.let { throw normalizeFailure(it, request) }
        return checkNotNull(result).copy(totalMs = SystemClock.elapsedRealtime() - request.startedAt)
    }

    private suspend fun executeLocked(request: Request, prompt: String): SuggestionResult {
        checkNotCancelled(request)
        synchronized(stateLock) {
            terminalFailure?.let { throw OnDeviceSuggestionException(it) }
            if (closeRequested) throw OnDeviceSuggestionException("BUSY")
            idleClose?.cancel()
            idleClose = null
            activeRequest = request
        }
        var conversation: Conversation? = null
        var verificationMs = 0L
        var initializationMs = 0L
        var engineWasReused = false
        var failure: Throwable? = null
        var result: SuggestionResult? = null
        try {
            requireEligibleResources()
            val established = ensureWarm(request)
            verificationMs = established.verificationMs
            initializationMs = established.initializationMs
            engineWasReused = established.reused
            checkNotCancelled(request)

            val created = established.engine.createConversation(CONVERSATION_CONFIG)
            conversation = created
            synchronized(nativeLock) {
                if (request.cancelled.get()) throw CancellationException()
                request.conversation = created
            }
            val response = StringBuilder()
            var firstTextMs: Long? = null
            created.sendMessageAsync(prompt).collect { message ->
                checkNotCancelled(request)
                message.contents.contents.filterIsInstance<Content.Text>().forEach { chunk ->
                    if (chunk.text.isNotEmpty() && firstTextMs == null) {
                        firstTextMs = SystemClock.elapsedRealtime() - request.startedAt
                    }
                    response.append(chunk.text)
                    if (response.length > MAX_RESPONSE_CHARS) {
                        request.cancelled.set(true)
                        cancelRequest(request)
                        throw OnDeviceSuggestionException("OUTPUT_TOO_LONG")
                    }
                }
            }
            checkNotCancelled(request)
            if (response.isBlank()) throw OnDeviceSuggestionException("EMPTY_RESPONSE")
            result = SuggestionResult(
                text = response.toString(),
                verificationMs = verificationMs,
                initializationMs = initializationMs,
                firstTextMs = firstTextMs,
                totalMs = SystemClock.elapsedRealtime() - request.startedAt,
                engineReused = engineWasReused
            )
        } catch (error: Throwable) {
            failure = error
        } finally {
            if (failure != null) {
                request.cancelled.set(true)
                cancelRequest(request)
            }
            val conversationClosed = closeConversation(request, conversation)
            synchronized(stateLock) {
                if (activeRequest === request) activeRequest = null
            }
            if (request.cancellationFailure != null && failure == null) {
                failure = OnDeviceSuggestionException("NATIVE_CANCEL_FAILED")
            }
            if (!conversationClosed && failure == null) {
                failure = OnDeviceSuggestionException("NATIVE_CLOSE_FAILED")
            }
            if (failure != null || request.cancelled.get() || closeRequested) {
                try {
                    closeWarmLocked(
                        discardSharedEngine = request.cancellationFailure != null ||
                            failure?.let(::isEngineFault) == true
                    )
                } catch (error: Throwable) {
                    if (failure == null) failure = error else failure?.addSuppressed(error)
                }
            } else {
                scheduleIdleClose()
            }
        }
        if (request.cancelled.get() && failure == null) {
            failure = OnDeviceSuggestionException("CANCELLED")
            try {
                closeWarmLocked()
            } catch (error: Throwable) {
                failure?.addSuppressed(error)
            }
        }
        failure?.let { throw normalizeFailure(it, request) }
        return checkNotNull(result).copy(totalMs = SystemClock.elapsedRealtime() - request.startedAt)
    }

    private suspend fun ensureWarm(request: Request): EstablishedEngine {
        checkNotClosing(request)
        val model = GemmaModelFiles.modelFile(appContext).canonicalFile
        val currentFingerprint = fingerprint(model)
        val existing = synchronized(stateLock) { warm }
        if (existing != null && existing.fingerprint == currentFingerprint) {
            return EstablishedEngine(existing.use.resource, 0L, 0L, true)
        }
        if (existing != null) closeWarmLocked()
        requireEligibleResources()
        checkNotClosing(request)
        val verificationStartedAt = SystemClock.elapsedRealtime()
        val verified = GemmaModelFiles.requireVerifiedModel(appContext, request.cancelled::get)
        val verificationMs = SystemClock.elapsedRealtime() - verificationStartedAt
        checkNotClosing(request)
        requireEligibleResources()
        val newLease = OnDeviceGenerationControl.tryBegin(
            purpose = OnDeviceGenerationControl.Purpose.AUTO_CONTEXT,
            cancel = ::requestClose
        ) ?: throw OnDeviceSuggestionException("BUSY")
        var acquired: OnDeviceSharedEngine.Use? = null
        try {
            synchronized(stateLock) { lease = newLease }
            checkNotClosing(request)
            val use = try {
                OnDeviceSharedEngine.acquire(verified, backend.toFlavor())
            } catch (_: OnDeviceSharedEngine.InitializationFailedException) {
                throw OnDeviceSuggestionException("ENGINE_INITIALIZATION_FAILED")
            }
            acquired = use
            requireEligibleResources()
            checkNotClosing(request)
            val createdWarm = WarmEngine(use, fingerprint(verified))
            synchronized(stateLock) {
                if (request.cancelled.get() || closeRequested) throw CancellationException()
                warm = createdWarm
            }
            checkNotClosing(request)
            startResourceMonitor(createdWarm)
            checkNotClosing(request)
            return EstablishedEngine(use.resource, verificationMs, use.initializationMs, use.reused)
        } catch (error: Throwable) {
            val use = acquired
            if (use != null) {
                try {
                    synchronized(stateLock) {
                        if (warm?.use !== use) {
                            warm = WarmEngine(use, fingerprint(verified))
                        }
                    }
                    closeWarmLocked(discardSharedEngine = isEngineFault(error))
                } catch (closeError: Throwable) {
                    latchTerminalFailure("NATIVE_CLOSE_FAILED")
                    error.addSuppressed(closeError)
                    throw OnDeviceSuggestionException("NATIVE_CLOSE_FAILED")
                }
            } else {
                synchronized(stateLock) {
                    if (lease === newLease) lease = null
                }
                releaseLease(newLease)
            }
            throw error
        }
    }

    private fun startResourceMonitor(expected: WarmEngine) {
        resourceMonitor?.cancel()
        resourceMonitor = scope.launch {
            try {
                var violationSinceMs: Long? = null
                while (isActive) {
                    delay(RESOURCE_CHECK_INTERVAL_MS)
                    val stillExpected = synchronized(stateLock) { warm === expected }
                    if (!stillExpected) return@launch
                    if (hasEligibleResources()) {
                        violationSinceMs = null
                        continue
                    }
                    // A brief thermal or memory blip must not tear down a warm engine; only a
                    // sustained violation closes it.
                    val now = SystemClock.elapsedRealtime()
                    val since = violationSinceMs ?: now.also { violationSinceMs = it }
                    if (now - since >= RESOURCE_VIOLATION_GRACE_MS) {
                        OnDeviceSharedEngine.requestClose("RESOURCE")
                        requestClose()
                        return@launch
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                latchTerminalFailure("RESOURCE_MONITOR_${error.javaClass.simpleName}")
                requestClose()
            }
        }
    }

    private fun scheduleIdleClose() {
        idleClose?.cancel()
        idleClose = scope.launch {
            delay(IDLE_CLOSE_MS)
            try {
                close()
            } catch (_: OnDeviceSuggestionException) {
                // The terminal latch is the observable failure state; no prompt or response is retained here.
            }
        }
    }

    private fun requestClose() {
        markCloseAndCancel()
        scope.launch {
            try {
                close()
            } catch (_: OnDeviceSuggestionException) {
                // close() records timeout in the terminal state and retains ownership until late cleanup.
            }
        }
    }

    private fun markCloseAndCancel() {
        val requests = synchronized(stateLock) {
            closeRequested = true
            activeRequest to preparingRequest
        }
        val active = requests.first
        active?.cancelled?.set(true)
        requests.second?.cancelled?.set(true)
        active?.let(::requestNativeCancellationAsync)
    }

    private fun requestNativeCancellationAsync(request: Request) {
        scope.launch { cancelRequest(request) }
    }

    private fun cancelRequest(request: Request) {
        synchronized(nativeLock) {
            if (request.conversation == null) return
            try {
                request.conversation?.cancelProcess()
            } catch (error: Throwable) {
                request.cancellationFailure = error
            }
        }
    }

    private fun closeConversation(request: Request, fallback: Conversation?): Boolean = try {
        synchronized(nativeLock) {
            val target = request.conversation ?: fallback
            if (target != null) {
                unclosedConversation = target
                target.close()
                if (unclosedConversation === target) unclosedConversation = null
            }
            request.conversation = null
        }
        true
    } catch (error: Throwable) {
        request.cancellationFailure = error
        false
    }

    /**
     * Releases this engine's use of the shared Engine and its lease. The shared Engine stays warm for
     * the next purpose unless [discardSharedEngine] is set after an engine-level failure.
     */
    private fun closeWarmLocked(discardSharedEngine: Boolean = false) {
        val pending = synchronized(nativeLock) { unclosedConversation }
        if (pending != null) {
            try {
                synchronized(nativeLock) {
                    pending.close()
                    if (unclosedConversation === pending) unclosedConversation = null
                }
            } catch (error: Throwable) {
                latchTerminalFailure("NATIVE_CLOSE_FAILED")
                throw OnDeviceSuggestionException("NATIVE_CLOSE_FAILED")
            }
        }
        val closing = synchronized(stateLock) { warm }
        val closingLease = synchronized(stateLock) { lease }
        closing?.use?.release()
        if (discardSharedEngine) {
            OnDeviceSharedEngine.requestClose("AUTO_CONTEXT_ENGINE_FAULT")
        }
        synchronized(stateLock) {
            if (warm === closing) warm = null
            if (lease === closingLease) lease = null
            resourceMonitor?.cancel()
            resourceMonitor = null
            idleClose?.cancel()
            idleClose = null
        }
        closingLease?.let(::releaseLease)
    }

    /**
     * A lease that [OnDeviceGenerationControl.end] reports as already released is not a failure:
     * another purpose preempted it and already holds a newer lease in its place. This is the
     * normal outcome of preemption, not a terminal condition.
     */
    private fun releaseLease(target: OnDeviceGenerationControl.Lease) {
        if (!OnDeviceGenerationControl.end(target)) {
            Timber.i("Generation lease already released (preempted)")
        }
    }

    private fun waitForLateCleanup() {
        scope.launch {
            operationMutex.withLock {
                closeWarmLocked(discardSharedEngine = true)
                synchronized(stateLock) { closeRequested = false }
            }
        }
    }

    private fun latchTerminalFailure(code: String) {
        synchronized(stateLock) {
            terminalFailure = code
        }
    }

    private fun checkNotCancelled(request: Request) {
        if (request.cancelled.get()) throw CancellationException()
    }

    private fun checkNotClosing(request: Request) {
        checkNotCancelled(request)
        if (isCloseRequested()) throw CancellationException()
    }

    private fun isCloseRequested(): Boolean = synchronized(stateLock) { closeRequested }

    private fun normalizeFailure(error: Throwable, request: Request): OnDeviceSuggestionException = when {
        error is OnDeviceSuggestionException -> error
        error is TimeoutCancellationException -> OnDeviceSuggestionException("GENERATION_TIMEOUT")
        request.cancellationFailure != null -> OnDeviceSuggestionException("NATIVE_CANCEL_FAILED")
        request.cancelled.get() -> OnDeviceSuggestionException("CANCELLED")
        error is IOException && !GemmaModelFiles.modelFile(appContext).isFile -> {
            OnDeviceSuggestionException("MODEL_MISSING")
        }
        else -> OnDeviceSuggestionException("ENGINE_FAILED_${error.javaClass.simpleName}")
    }

    /** 엔진 자체가 망가졌을 수 있는 실패인지. 취소·출력 초과·빈 응답·자원 부족은 엔진을 버리지 않는다. */
    private fun isEngineFault(error: Throwable): Boolean = when (error) {
        is TimeoutCancellationException -> true
        is CancellationException -> false
        is OnDeviceSuggestionException -> error.code.startsWith("NATIVE_") || error.code.startsWith("ENGINE_") ||
            error.code == "GENERATION_TIMEOUT"
        else -> true
    }

    private fun fingerprint(model: File): ModelFingerprint = ModelFingerprint(
        path = model.canonicalPath,
        length = model.length(),
        modifiedAt = model.lastModified()
    )

    private fun hasEligibleResources(): Boolean = try {
        requireEligibleResources()
        true
    } catch (_: OnDeviceSuggestionException) {
        false
    }

    private fun requireEligibleResources() {
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (level >= 0 && scale > 0 && level <= scale) {
            (level.toLong() * 100L / scale).toInt()
        } else {
            null
        }
        val batteryStatus = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val batteryPlugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
            batteryStatus == BatteryManager.BATTERY_STATUS_FULL ||
            batteryPlugged != 0
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        val activityManager = appContext.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.currentThermalStatus
        } else {
            null
        }
        when {
            batteryPercent == null || (batteryPercent < MINIMUM_BATTERY_PERCENT && !charging) -> {
                throw OnDeviceSuggestionException("RESOURCE_BATTERY")
            }
            powerManager.isPowerSaveMode && !charging -> throw OnDeviceSuggestionException("RESOURCE_POWER_SAVE")
            memoryInfo.lowMemory -> throw OnDeviceSuggestionException("RESOURCE_LOW_MEMORY")
            // Light and moderate throttling are normal while a GPU model runs; only severe or
            // worse stops on-device generation. Devices without a thermal API are not blocked.
            thermalStatus != null && thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> {
                throw OnDeviceSuggestionException("RESOURCE_THERMAL")
            }
        }
    }

    private fun BackendSelection.toFlavor(): OnDeviceSharedEngine.Flavor = when (this) {
        is BackendSelection.Cpu -> OnDeviceSharedEngine.Flavor.Cpu(threadCount)
        BackendSelection.Gpu -> OnDeviceSharedEngine.Flavor.Gpu
    }

    private data class Request(val startedAt: Long) {
        val cancelled = AtomicBoolean(false)
        @Volatile
        var conversation: Conversation? = null
        @Volatile
        var cancellationFailure: Throwable? = null
    }

    private data class WarmEngine(
        val use: OnDeviceSharedEngine.Use,
        val fingerprint: ModelFingerprint
    )

    private data class EstablishedEngine(
        val engine: Engine,
        val verificationMs: Long,
        val initializationMs: Long,
        val reused: Boolean
    )

    private data class ModelFingerprint(
        val path: String,
        val length: Long,
        val modifiedAt: Long
    )

    private companion object {
        const val MAX_PROMPT_UTF8_BYTES = 8192
        const val MAX_RESPONSE_CHARS = 400
        const val MINIMUM_BATTERY_PERCENT = 20
        const val RESOURCE_CHECK_INTERVAL_MS = 500L
        const val RESOURCE_VIOLATION_GRACE_MS = 3_000L
        const val GENERATION_BUDGET_MS = 120_000L
        const val NATIVE_STOP_BUDGET_MS = 30_000L
        const val IDLE_CLOSE_MS = 600_000L

        val CONVERSATION_CONFIG = ConversationConfig(
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            extraContext = mapOf("enable_thinking" to false)
        )
    }
}
