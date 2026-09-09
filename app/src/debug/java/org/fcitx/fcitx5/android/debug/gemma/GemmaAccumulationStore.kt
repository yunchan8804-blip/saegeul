/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.debug.gemma

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedMaterialPolicy
import org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedSentenceBank
import org.fcitx.fcitx5.android.input.ai.ondevice.IngestionReport
import org.json.JSONObject

data class GemmaAccumulationState(
    val enabled: Boolean = false,
    val status: String = STATUS_IDLE,
    val stored: Int = 0,
    val covered: Int = 0,
    val totalPrefixes: Int = GeneratedMaterialPolicy.PREFIXES.size,
    val added: Int = 0,
    val rejected: Int = 0,
    val duplicates: Int = 0,
    val lastRunEpochMs: Long = 0L,
    val error: String? = null,
    val attempts: Map<String, Int> = emptyMap(),
    val cursor: Int = 0
) {
    companion object {
        const val STATUS_IDLE = "대기"
        const val STATUS_RUNNING = "실행 중"
        const val STATUS_TARGET_REACHED = "목표 충족"
        const val STATUS_ATTEMPTS_EXHAUSTED = "시도 소진"
    }
}

class GemmaAccumulationStore private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(GemmaAccumulationState())
    private var initialized = false
    private var bankLoaded = false

    val state: StateFlow<GemmaAccumulationState> = mutableState

    suspend fun load(): GemmaAccumulationState = onIo {
        mutex.withLock {
            try {
                loadLocked()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                publishLocked(mutableState.value.copy(error = errorMessage(error)))
                throw error
            }
            mutableState.value
        }
    }

    suspend fun setEnabled(enabled: Boolean): GemmaAccumulationState = onIo {
        mutex.withLock {
            try {
                loadLocked()
                persistAndPublishLocked(
                    mutableState.value.copy(
                        enabled = enabled,
                        status = if (enabled) GemmaAccumulationState.STATUS_IDLE else "사용 안 함",
                        error = null
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                publishLocked(mutableState.value.copy(error = errorMessage(error)))
                throw error
            }
            mutableState.value
        }
    }

    suspend fun resetAttempts(): GemmaAccumulationState = onIo {
        mutex.withLock {
            try {
                loadLocked()
                persistAndPublishLocked(
                    mutableState.value.copy(
                        attempts = emptyMap(),
                        status = GemmaAccumulationState.STATUS_IDLE,
                        error = null
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                publishLocked(mutableState.value.copy(error = errorMessage(error)))
                throw error
            }
            mutableState.value
        }
    }

    suspend fun nextPlan(): GemmaAccumulationPlan? = onIo {
        mutex.withLock {
            loadLocked()
            val current = mutableState.value
            if (!current.enabled) return@withLock null
            val counts = prefixCountsLocked()
            val plan = GemmaAccumulationPlanner.select(counts, current.attempts, current.cursor)
            if (plan == null) {
                val status = if (GemmaAccumulationPlanner.hasCoverageDeficit(counts)) {
                    GemmaAccumulationState.STATUS_ATTEMPTS_EXHAUSTED
                } else {
                    GemmaAccumulationState.STATUS_TARGET_REACHED
                }
                persistAndPublishLocked(current.copy(status = status, error = null))
            }
            plan
        }
    }

    suspend fun markAttemptStarted(plan: GemmaAccumulationPlan): Boolean = onIo {
        mutex.withLock {
            loadLocked()
            val current = mutableState.value
            if (!current.enabled) return@withLock false
            val attempts = current.attempts.toMutableMap()
            attempts[plan.prefix] = (attempts[plan.prefix] ?: 0) + 1
            persistAndPublishLocked(
                current.copy(
                    status = GemmaAccumulationState.STATUS_RUNNING,
                    attempts = attempts,
                    cursor = plan.nextCursor,
                    error = null
                )
            )
            true
        }
    }

    suspend fun rollbackAttempt(prefix: String): Unit = onIo {
        mutex.withLock {
            loadLocked()
            val current = mutableState.value
            val attempts = current.attempts.toMutableMap()
            val remaining = (attempts[prefix] ?: 0) - 1
            if (remaining > 0) attempts[prefix] = remaining else attempts.remove(prefix)
            persistAndPublishLocked(
                current.copy(
                    attempts = attempts,
                    status = if (current.enabled) GemmaAccumulationState.STATUS_IDLE else current.status
                )
            )
        }
    }

    suspend fun commitIfEnabled(
        prefix: String,
        operation: (GeneratedSentenceBank) -> IngestionReport
    ): IngestionReport? = onIo {
        mutex.withLock {
            require(prefix in GeneratedMaterialPolicy.PREFIXES) { "Unknown generated-material prefix" }
            loadLocked()
            val current = mutableState.value
            if (!current.enabled) return@withLock null
            val report = operation(bank())
            val counts = prefixCountsLocked()
            val next = current.copy(
                status = GemmaAccumulationState.STATUS_RUNNING,
                stored = bank().sentenceCount,
                covered = counts.values.count { it >= GeneratedMaterialPolicy.TARGET_PER_PREFIX },
                added = current.added + report.added,
                rejected = current.rejected + report.rejected,
                duplicates = current.duplicates + report.duplicate,
                lastRunEpochMs = System.currentTimeMillis(),
                error = null
            )
            try {
                persistAndPublishLocked(next)
            } catch (error: Exception) {
                publishLocked(next.copy(error = "은행 저장 뒤 상태 저장 실패: ${errorMessage(error)}"))
                throw IllegalStateException("은행 저장 뒤 상태 저장 실패", error)
            }
            report
        }
    }

    suspend fun recordBlocked(status: String): Unit = onIo {
        mutex.withLock {
            loadLocked()
            val current = mutableState.value
            if (!current.enabled) return@withLock
            persistAndPublishLocked(current.copy(status = status))
        }
    }

    suspend fun recordFailure(error: Throwable): Unit = onIo {
        mutex.withLock {
            try {
                loadLocked()
                val next = mutableState.value.copy(
                    status = "오류",
                    lastRunEpochMs = System.currentTimeMillis(),
                    error = errorMessage(error)
                )
                try {
                    persistAndPublishLocked(next)
                } catch (failure: Exception) {
                    publishLocked(next.copy(error = "상태 저장 실패: ${errorMessage(failure)}"))
                    throw failure
                }
            } catch (error: CancellationException) {
                throw error
            } catch (failure: Exception) {
                publishLocked(
                    mutableState.value.copy(error = "상태 저장 실패: ${errorMessage(failure)}")
                )
                throw failure
            }
        }
    }

    suspend fun finishRun(): Unit = onIo {
        mutex.withLock {
            loadLocked()
            val current = mutableState.value
            if (!current.enabled) return@withLock
            val counts = prefixCountsLocked()
            val status = when {
                !GemmaAccumulationPlanner.hasCoverageDeficit(counts) ->
                    GemmaAccumulationState.STATUS_TARGET_REACHED
                GemmaAccumulationPlanner.select(counts, current.attempts, current.cursor) == null ->
                    GemmaAccumulationState.STATUS_ATTEMPTS_EXHAUSTED
                else -> GemmaAccumulationState.STATUS_IDLE
            }
            persistAndPublishLocked(
                current.copy(
                    status = status,
                    stored = bank().sentenceCount,
                    covered = counts.values.count { it >= GeneratedMaterialPolicy.TARGET_PER_PREFIX },
                    lastRunEpochMs = System.currentTimeMillis(),
                    error = null
                )
            )
        }
    }

    private fun loadLocked() {
        if (!initialized) {
            val stored = preferences().readState()
            val restored = stored.copy(
                status = if (stored.status == GemmaAccumulationState.STATUS_RUNNING) {
                    GemmaAccumulationState.STATUS_IDLE
                } else {
                    stored.status
                }
            )
            if (restored != stored) check(preferences().writeState(restored)) {
                "Gemma 축적 상태를 저장할 수 없습니다."
            }
            mutableState.value = restored
        }
        val bank = bank()
        if (!bankLoaded) {
            bank.load()
            bankLoaded = true
        }
        val counts = prefixCountsLocked()
        publishLocked(
            mutableState.value.copy(
                stored = bank.sentenceCount,
                covered = counts.values.count { it >= GeneratedMaterialPolicy.TARGET_PER_PREFIX }
            )
        )
        initialized = true
    }

    private fun prefixCountsLocked(): Map<String, Int> =
        GeneratedMaterialPolicy.PREFIXES.associateWith { bank().exactPrefixCount(it) }

    private fun bank(): GeneratedSentenceBank = FcitxApplication.getInstance().generatedSentenceBank

    private fun persistAndPublishLocked(next: GemmaAccumulationState) {
        check(preferences().writeState(next)) { "Gemma 축적 상태를 저장할 수 없습니다." }
        publishLocked(next)
    }

    private fun publishLocked(next: GemmaAccumulationState) {
        mutableState.value = next
    }

    private fun preferences(): SharedPreferences =
        applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun SharedPreferences.readState(): GemmaAccumulationState {
        val objectValue = JSONObject(getString(KEY_ATTEMPTS, "{}"))
        val attempts = GeneratedMaterialPolicy.PREFIXES.mapNotNull { prefix ->
            objectValue.optInt(prefix, 0).takeIf { it > 0 }?.let { prefix to it }
        }.toMap()
        return GemmaAccumulationState(
            enabled = getBoolean(KEY_ENABLED, false),
            status = getString(KEY_STATUS, GemmaAccumulationState.STATUS_IDLE)
                ?: GemmaAccumulationState.STATUS_IDLE,
            stored = getInt(KEY_STORED, 0),
            covered = getInt(KEY_COVERED, 0),
            added = getInt(KEY_ADDED, 0),
            rejected = getInt(KEY_REJECTED, 0),
            duplicates = getInt(KEY_DUPLICATES, 0),
            lastRunEpochMs = getLong(KEY_LAST_RUN, 0L),
            error = getString(KEY_ERROR, null),
            attempts = attempts,
            cursor = getInt(KEY_CURSOR, 0)
        )
    }

    private fun SharedPreferences.writeState(state: GemmaAccumulationState): Boolean {
        val attempts = JSONObject().apply {
            state.attempts.forEach { (prefix, value) -> put(prefix, value) }
        }
        return edit()
            .putBoolean(KEY_ENABLED, state.enabled)
            .putString(KEY_STATUS, state.status)
            .putInt(KEY_STORED, state.stored)
            .putInt(KEY_COVERED, state.covered)
            .putInt(KEY_ADDED, state.added)
            .putInt(KEY_REJECTED, state.rejected)
            .putInt(KEY_DUPLICATES, state.duplicates)
            .putLong(KEY_LAST_RUN, state.lastRunEpochMs)
            .putString(KEY_ERROR, state.error)
            .putString(KEY_ATTEMPTS, attempts.toString())
            .putInt(KEY_CURSOR, state.cursor)
            .commit()
    }

    private fun errorMessage(error: Throwable): String =
        error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName

    private suspend fun <T> onIo(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { block() }

    companion object {
        const val PREFERENCES = "gemma_accumulation_state"
        const val KEY_ENABLED = "enabled"
        const val KEY_STATUS = "status"
        const val KEY_STORED = "stored"
        const val KEY_COVERED = "covered"
        const val KEY_ADDED = "added"
        const val KEY_REJECTED = "rejected"
        const val KEY_DUPLICATES = "duplicates"
        const val KEY_LAST_RUN = "last_run_epoch_ms"
        const val KEY_ERROR = "error"
        const val KEY_ATTEMPTS = "attempts"
        const val KEY_CURSOR = "cursor"

        @Volatile
        private var instance: GemmaAccumulationStore? = null

        fun get(context: Context): GemmaAccumulationStore =
            instance ?: synchronized(this) {
                instance ?: GemmaAccumulationStore(context.applicationContext).also { instance = it }
            }
    }
}
