/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import java.util.concurrent.Executor

/**
 * 만들기 비싼 자원([T]) 하나를 여러 사용처가 번갈아 쓰도록 따뜻하게 유지한다.
 *
 * - [acquire]는 같은 [K]의 자원이 이미 있으면 재사용하고, 없으면 [create]로 만든다. 생성·종료는
 *   [initLock]으로 직렬화되며 블로킹이므로 메인 스레드에서 부르지 않는다.
 * - 사용처는 자원을 쓰는 동안 [Use]를 쥐고, 다 쓰면 [Use.release]한다. 사용 중에는 절대 닫지 않는다.
 * - [requestClose]는 블로킹하지 않는다. 쓰는 곳이 없으면 [closeExecutor]에서 바로 닫고, 있으면 마지막
 *   [Use.release] 뒤에 닫는다.
 * - 원하는 형태([preferred])와 다른 자원이 있어도 재사용한다. 단 [shouldReplace]가 참이고 아무도 쓰지
 *   않으면 새로 만든다.
 */
class SharedWarmResource<K : Any, T : Any, F : Any>(
    private val create: (K, F) -> T,
    private val close: (T) -> Unit,
    private val shouldReplace: (current: F, preferred: F) -> Boolean,
    private val closeExecutor: Executor
) {

    class Use<T : Any, F : Any> internal constructor(
        val resource: T,
        val flavor: F,
        val reused: Boolean,
        val initializationMs: Long,
        private val onRelease: (Use<T, F>) -> Unit
    ) {
        private var released = false

        fun release() {
            val first = synchronized(this) {
                if (released) false else true.also { released = true }
            }
            if (first) onRelease(this)
        }
    }

    private class Held<K : Any, T : Any, F : Any>(val key: K, val resource: T, val flavor: F)

    private val initLock = Any()
    private val stateLock = Any()

    @Volatile
    private var held: Held<K, T, F>? = null
    private var uses = 0
    private var pendingCloseReason: String? = null

    /** 지금 따뜻한 자원이 있는지. 메인 스레드에서 불러도 막히지 않는다. */
    val isWarm: Boolean
        get() = held != null

    val warmFlavor: F?
        get() = held?.flavor

    fun isWarmFor(key: K): Boolean = held?.key == key

    fun acquire(key: K, preferred: F, clock: () -> Long): Use<T, F> = synchronized(initLock) {
        val current = held
        if (current != null) {
            val reusable = synchronized(stateLock) {
                val inUse = uses > 0
                val sameKey = current.key == key
                when {
                    // 쓰는 곳이 있으면 형태가 달라도, 닫기가 예약돼 있어도 지금 자원을 함께 쓴다.
                    inUse && sameKey -> true
                    inUse -> throw IllegalStateException(BUSY)
                    pendingCloseReason != null || !sameKey -> false
                    else -> !shouldReplace(current.flavor, preferred)
                }.also { if (it) uses += 1 }
            }
            if (reusable) {
                return@synchronized Use(current.resource, current.flavor, true, 0L, ::release)
            }
            closeHeldLocked()
        }
        val startedAt = clock()
        val created = create(key, preferred)
        val elapsed = clock() - startedAt
        synchronized(stateLock) {
            held = Held(key, created, preferred)
            uses += 1
            pendingCloseReason = null
        }
        Use(created, preferred, false, elapsed, ::release)
    }

    /** 자원을 닫아 달라고 요청한다. 쓰는 곳이 남아 있으면 마지막 사용이 끝난 뒤에 닫는다. */
    fun requestClose(reason: String) {
        val closeNow = synchronized(stateLock) {
            if (held == null) return
            pendingCloseReason = reason
            uses == 0
        }
        if (closeNow) closeExecutor.execute(::closeIfIdle)
    }

    private fun release(@Suppress("UNUSED_PARAMETER") use: Use<T, F>) {
        val closeNow = synchronized(stateLock) {
            uses = (uses - 1).coerceAtLeast(0)
            uses == 0 && pendingCloseReason != null
        }
        if (closeNow) closeExecutor.execute(::closeIfIdle)
    }

    private fun closeIfIdle() {
        synchronized(initLock) {
            val shouldClose = synchronized(stateLock) { uses == 0 && pendingCloseReason != null }
            if (shouldClose) closeHeldLocked()
        }
    }

    private fun closeHeldLocked() {
        val closing = synchronized(stateLock) {
            held.also {
                held = null
                pendingCloseReason = null
            }
        } ?: return
        close(closing.resource)
    }

    companion object {
        /** 다른 모델의 자원을 아직 누가 쓰고 있어 바꿀 수 없을 때 [acquire]가 던지는 메시지. */
        const val BUSY = "SHARED_RESOURCE_BUSY"
    }
}
