/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.media

import java.util.LinkedList

/**
 * Exponential backoff retry queue manager for asynchronous media queries.
 * Prevents network storming, caps memory capacity, and deduplicates identical requests.
 */
class MediaRetryQueue(
    private val maxQueueSize: Int = 50,
    private val maxRetryAttempts: Int = 3,
    private val baseDelayMs: Long = 1000L
) {

    private val queue = LinkedList<MediaSearchRequest>()
    private val idSet = HashSet<String>()
    private var exhaustedCounter = 0

    fun size(): Int = queue.size

    fun contains(id: String): Boolean = idSet.contains(id)

    fun peek(): MediaSearchRequest? = queue.peekFirst()

    fun getExhaustedCount(): Int = exhaustedCounter

    fun enqueue(request: MediaSearchRequest): Boolean {
        if (idSet.contains(request.id)) {
            return false // Deduplicated
        }

        while (queue.size >= maxQueueSize) {
            val evicted = queue.removeFirst()
            idSet.remove(evicted.id)
        }

        queue.addLast(request)
        idSet.add(request.id)
        return true
    }

    fun calculateNextRetryDelay(attempt: Int): Long {
        val multiplier = 1L shl attempt.coerceIn(0, 10)
        return baseDelayMs * multiplier
    }

    fun pollNextReady(currentTime: Long): MediaSearchRequest? {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.nextRetryTime <= currentTime) {
                return item
            }
        }
        return queue.peekFirst()
    }

    fun getRetryCount(id: String): Int {
        return queue.find { it.id == id }?.retryCount ?: 0
    }

    fun recordFailure(id: String, errorMessage: String, failureTime: Long): Boolean {
        val item = queue.find { it.id == id } ?: return false
        item.retryCount++
        item.lastFailureMessage = errorMessage

        if (item.retryCount >= maxRetryAttempts) {
            // Exhausted: remove from active queue
            queue.remove(item)
            idSet.remove(item.id)
            exhaustedCounter++
            return true
        } else {
            val delay = calculateNextRetryDelay(item.retryCount)
            item.nextRetryTime = failureTime + delay
            return false
        }
    }

    fun recordSuccess(id: String) {
        val item = queue.find { it.id == id }
        if (item != null) {
            queue.remove(item)
            idSet.remove(item.id)
        }
    }

    fun clear() {
        queue.clear()
        idSet.clear()
        exhaustedCounter = 0
    }
}
