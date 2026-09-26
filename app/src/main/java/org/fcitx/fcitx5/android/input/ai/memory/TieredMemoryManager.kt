/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.memory

/**
 * An episode record stored in L3 episodic context.
 */
data class Episode(
    val topic: String,
    val summary: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 4-Tier Mobile Memory Architecture Manager (EAI-12).
 *
 * Tiers:
 * - L1: Active Buffer (real-time typing string)
 * - L2: Session Utterances (last 5 editor sentences, FIFO)
 * - L3: Episodic Context (up to 20 daily episodic notes, LRU)
 * - L4: Semantic Persona Profile (persistent key-value preferences)
 *
 * Enforces memory footprint safety strictly below 35 MB.
 */
class TieredMemoryManager {

    companion object {
        const val MAX_L2_UTTERANCES = 5
        const val MAX_L3_EPISODES = 20
        const val MAX_MEMORY_LIMIT_BYTES = 35L * 1024 * 1024 // 35 MB
        private const val STRING_OBJECT_OVERHEAD_BYTES = 24L
        private const val CHAR_BYTE_SIZE = 2L
    }

    private val lock = Any()

    // L1: Active typing buffer
    private var l1ActiveBuffer: String = ""

    // L2: Session utterances (FIFO, max 5)
    private val l2SessionUtterances = ArrayDeque<String>()

    // L3: Episodic context (LRU cache, max 20)
    private val l3Episodes = object : LinkedHashMap<String, Episode>(MAX_L3_EPISODES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Episode>?): Boolean {
            return size > MAX_L3_EPISODES
        }
    }

    // L4: Semantic / persistent profile key-values
    private val l4PersonaProfile = LinkedHashMap<String, String>()

    /* =========================================================================
     * L1: Active Buffer
     * ========================================================================= */

    fun updateL1Buffer(text: String) = synchronized(lock) {
        l1ActiveBuffer = text
        pruneIfMemoryExceeded()
    }

    fun getL1Buffer(): String = synchronized(lock) {
        l1ActiveBuffer
    }

    fun clearL1Buffer() = synchronized(lock) {
        l1ActiveBuffer = ""
    }

    /* =========================================================================
     * L2: Session Utterances (Max 5)
     * ========================================================================= */

    fun addSessionUtterance(text: String) = synchronized(lock) {
        if (text.isBlank()) return@synchronized
        while (l2SessionUtterances.size >= MAX_L2_UTTERANCES) {
            l2SessionUtterances.removeFirst()
        }
        l2SessionUtterances.addLast(text)
        pruneIfMemoryExceeded()
    }

    fun getSessionUtterances(): List<String> = synchronized(lock) {
        l2SessionUtterances.toList()
    }

    fun clearSession() = synchronized(lock) {
        l2SessionUtterances.clear()
        l1ActiveBuffer = ""
    }

    /* =========================================================================
     * L3: Episodic Context (LRU, Max 20)
     * ========================================================================= */

    fun addEpisode(topic: String, summary: String, timestamp: Long = System.currentTimeMillis()) = synchronized(lock) {
        val episodeKey = "${topic}_$timestamp"
        l3Episodes[episodeKey] = Episode(topic = topic, summary = summary, timestamp = timestamp)
        pruneIfMemoryExceeded()
    }

    fun getRecentEpisodes(): List<Episode> = synchronized(lock) {
        // Return most recent first
        l3Episodes.values.reversed()
    }

    fun clearEpisodes() = synchronized(lock) {
        l3Episodes.clear()
    }

    /* =========================================================================
     * L4: Semantic Persona Profile
     * ========================================================================= */

    fun updateL4Profile(key: String, value: String) = synchronized(lock) {
        l4PersonaProfile[key] = value
        pruneIfMemoryExceeded()
    }

    fun getL4Profile(): Map<String, String> = synchronized(lock) {
        HashMap(l4PersonaProfile)
    }

    fun clearL4Profile() = synchronized(lock) {
        l4PersonaProfile.clear()
    }

    fun clearAll() = synchronized(lock) {
        clearL1Buffer()
        clearSession()
        clearEpisodes()
        clearL4Profile()
    }

    /**
     * Reclaims memory across tiers according to Android onTrimMemory levels (ComponentCallbacks2).
     *
     * @param level Memory trim level, e.g. ComponentCallbacks2.TRIM_MEMORY_COMPLETE (80)
     */
    fun trimMemory(level: Int) = synchronized(lock) {
        when {
            level >= 80 -> { // TRIM_MEMORY_COMPLETE (Critical)
                l1ActiveBuffer = ""
                l2SessionUtterances.clear()
                while (l3Episodes.size > 1) {
                    val eldestKey = l3Episodes.keys.firstOrNull() ?: break
                    l3Episodes.remove(eldestKey)
                }
            }
            level >= 40 -> { // TRIM_MEMORY_BACKGROUND / MODERATE
                l1ActiveBuffer = ""
                while (l2SessionUtterances.size > 2) {
                    l2SessionUtterances.removeFirst()
                }
                while (l3Episodes.size > 5) {
                    val eldestKey = l3Episodes.keys.firstOrNull() ?: break
                    l3Episodes.remove(eldestKey)
                }
            }
            level >= 15 -> { // TRIM_MEMORY_RUNNING_CRITICAL
                l1ActiveBuffer = ""
                while (l2SessionUtterances.size > 3) {
                    l2SessionUtterances.removeFirst()
                }
                while (l3Episodes.size > 10) {
                    val eldestKey = l3Episodes.keys.firstOrNull() ?: break
                    l3Episodes.remove(eldestKey)
                }
            }
        }
    }

    /* =========================================================================
     * Memory Footprint & Safety (< 35MB)
     * ========================================================================= */

    /**
     * Estimates current memory consumption in bytes across L1-L4 tiers.
     */
    fun getEstimatedMemoryBytes(): Long = synchronized(lock) {
        var totalBytes = 0L

        // L1
        totalBytes += estimateStringBytes(l1ActiveBuffer)

        // L2
        for (item in l2SessionUtterances) {
            totalBytes += estimateStringBytes(item) + 16L // Deque node overhead
        }

        // L3
        for (entry in l3Episodes.entries) {
            totalBytes += estimateStringBytes(entry.key)
            totalBytes += estimateStringBytes(entry.value.topic)
            totalBytes += estimateStringBytes(entry.value.summary)
            totalBytes += 40L // Map entry + Episode object overhead
        }

        // L4
        for (entry in l4PersonaProfile.entries) {
            totalBytes += estimateStringBytes(entry.key)
            totalBytes += estimateStringBytes(entry.value)
            totalBytes += 32L // Map entry overhead
        }

        totalBytes
    }

    private fun estimateStringBytes(str: String): Long {
        return STRING_OBJECT_OVERHEAD_BYTES + (str.length.toLong() * CHAR_BYTE_SIZE)
    }

    private fun pruneIfMemoryExceeded() {
        // If memory somehow approaches the 35MB limit, prune L3 eldest entries first, then L2
        while (getEstimatedMemoryBytes() >= MAX_MEMORY_LIMIT_BYTES && l3Episodes.isNotEmpty()) {
            val eldestKey = l3Episodes.keys.firstOrNull() ?: break
            l3Episodes.remove(eldestKey)
        }
        while (getEstimatedMemoryBytes() >= MAX_MEMORY_LIMIT_BYTES && l2SessionUtterances.isNotEmpty()) {
            l2SessionUtterances.removeFirst()
        }
    }
}
