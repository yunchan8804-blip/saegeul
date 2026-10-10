/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import org.fcitx.fcitx5.android.input.ai.vault.PlainVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultFile
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Encrypted-at-rest progress for an in-flight on-device graph-enrichment cycle: the cursor, the
 * fixed chunk list, and per-chunk timing. The chunked sentence snapshot is fixed once at cycle
 * start ([State.chunks]) and persisted here (never in plaintext) so that a worker run interrupted
 * mid-cycle by a broken device condition resumes from [State.nextChunkIndex] with the exact same
 * chunks, instead of re-exporting a possibly different sentence snapshot. Each chunk's parsed graph
 * fragment is merged directly into [PersonalGraphStore] as soon as it completes (not staged here),
 * so the graph itself already reflects everything the cycle has done so far even before this
 * checkpoint is cleared. Cleared once the cycle finishes (all chunks attempted) or is abandoned.
 */
class PersonalGraphEnrichmentStagingStore(
    storeFile: File?,
    private val cipher: VaultCipher = PlainVaultCipher
) {

    data class State(
        val cycleStartedMs: Long,
        val sourceSentenceCountAtStart: Int,
        val chunks: List<String>,
        val nextChunkIndex: Int,
        val successCount: Int,
        val failCount: Int,
        /** True while the worker is waiting for [GraphEnrichmentLeaseWaiter] to free the on-device generation lease, rather than actively generating a chunk. */
        val waitingOnLease: Boolean = false,
        /** Epoch millis the current lease wait began; 0 when [waitingOnLease] is false. */
        val waitingOnLeaseSinceMs: Long = 0L,
        /**
         * Why the worker is waiting, while [waitingOnLease] is true; [GraphEnrichmentPauseReason.NONE]
         * (the default, meaning "another on-device task holds the lease") otherwise. Only
         * [GraphEnrichmentPauseReason.KEYBOARD_ACTIVE] currently gets a distinct value here - it
         * shows a different, button-less message ("will resume once the keyboard closes") from the
         * generic material-generation-is-busy waiting state.
         */
        val waitingReason: GraphEnrichmentPauseReason = GraphEnrichmentPauseReason.NONE,
        /** Sum of wall-clock milliseconds spent actually generating on each completed chunk attempt (success or parse failure alike), excluding any lease wait - see [lastWaitMs] - for estimating remaining time. */
        val completedChunkDurationMsSum: Long = 0L,
        /** Milliseconds the most recently completed chunk spent waiting for the on-device generation lease before it could start (0 if it never had to wait); not included in [completedChunkDurationMsSum]. */
        val lastWaitMs: Long = 0L,
        /** Why the worker most recently stopped without reaching a terminal result; [GraphEnrichmentPauseReason.NONE] otherwise. */
        val pauseReason: GraphEnrichmentPauseReason = GraphEnrichmentPauseReason.NONE,
        /** True when this cycle was started by the dashboard's manual request rather than the periodic automatic run - only a manual cycle is user-cancellable. */
        val manual: Boolean = false,
        /** True when this cycle only processed sentences new since the graph already stored was last built (an existing graph to add to), false for a first-ever full export. */
        val isIncremental: Boolean = false,
        /** How many sentences this cycle's [chunks] were built from in total, for the incremental progress line. */
        val processedSentenceCount: Int = 0,
        /** Sentences skipped because the engine rejected even that one sentence alone as too long; counted across the cycle. */
        val skippedSentenceCount: Int = 0,
        /**
         * Newest `lastSeenMs` among the sentences [chunks] were built from . The enrichment cursor ([PersonalGraphStore.GraphStats.enrichedThroughMs])
         * moves to it when the cycle ends; 0 means none, which leaves the cursor where it is.
         */
        val throughMs: Long = 0L,
        /**
         * How many times the chunk at [nextChunkIndex] was started without finishing. Saved before the
         * engine runs, so a native crash that kills the process still counts; reset once the chunk
         * completes. Reaching the attempt limit skips the chunk instead of repeating it forever.
         */
        val currentChunkAttempts: Int = 0,
        /** Chunks given up on after repeated engine failures; counted across the cycle and included in [failCount]. */
        val skippedChunkCount: Int = 0
    ) {
        val completedChunkCount: Int get() = successCount + failCount
    }

    private val vaultFile: VaultFile? = storeFile?.let { VaultFile(it, cipher, VaultFile.aadFor(it.name)) }

    fun load(): State? {
        val vf = vaultFile ?: return null
        if (!vf.exists()) return null
        val raw = try {
            vf.readTextAndMigrate()
        } catch (exception: Exception) {
            // The file stays read-blocked, so save() cannot replace it with a fresh cycle.
            Timber.w(exception, "Graph enrichment staging could not be read")
            return null
        } ?: return null
        if (raw.isBlank()) return null
        return try {
            val root = JSONObject(raw)
            val chunksArr = root.optJSONArray("chunks") ?: JSONArray()
            val chunks = (0 until chunksArr.length()).map { chunksArr.optString(it, "") }
            State(
                cycleStartedMs = root.optLong("cycleStartedMs", 0L),
                sourceSentenceCountAtStart = root.optInt("sourceSentenceCountAtStart", 0),
                chunks = chunks,
                nextChunkIndex = root.optInt("nextChunkIndex", 0).coerceIn(0, chunks.size),
                successCount = root.optInt("successCount", 0),
                failCount = root.optInt("failCount", 0),
                waitingOnLease = root.optBoolean("waitingOnLease", false),
                waitingOnLeaseSinceMs = root.optLong("waitingOnLeaseSinceMs", 0L),
                waitingReason = root.optString("waitingReason", "")
                    .let { stored -> GraphEnrichmentPauseReason.entries.firstOrNull { it.name == stored } }
                    ?: GraphEnrichmentPauseReason.NONE,
                completedChunkDurationMsSum = root.optLong("completedChunkDurationMsSum", 0L),
                lastWaitMs = root.optLong("lastWaitMs", 0L),
                pauseReason = root.optString("pauseReason", "")
                    .let { stored -> GraphEnrichmentPauseReason.entries.firstOrNull { it.name == stored } }
                    ?: GraphEnrichmentPauseReason.NONE,
                manual = root.optBoolean("manual", false),
                isIncremental = root.optBoolean("isIncremental", false),
                processedSentenceCount = root.optInt("processedSentenceCount", 0),
                skippedSentenceCount = root.optInt("skippedSentenceCount", 0),
                throughMs = root.optLong("throughMs", 0L),
                currentChunkAttempts = root.optInt("currentChunkAttempts", 0),
                skippedChunkCount = root.optInt("skippedChunkCount", 0)
            )
        } catch (exception: Exception) {
            vf.markUnreadable()
            Timber.w("Graph enrichment staging could not be parsed (%s)", exception.javaClass.simpleName)
            null
        }
    }

    fun save(state: State) {
        val vf = vaultFile ?: return
        val root = JSONObject()
        root.put("v", 2)
        root.put("cycleStartedMs", state.cycleStartedMs)
        root.put("sourceSentenceCountAtStart", state.sourceSentenceCountAtStart)
        root.put("chunks", JSONArray().apply { state.chunks.forEach { put(it) } })
        root.put("nextChunkIndex", state.nextChunkIndex)
        root.put("successCount", state.successCount)
        root.put("failCount", state.failCount)
        root.put("waitingOnLease", state.waitingOnLease)
        root.put("waitingOnLeaseSinceMs", state.waitingOnLeaseSinceMs)
        root.put("waitingReason", state.waitingReason.name)
        root.put("completedChunkDurationMsSum", state.completedChunkDurationMsSum)
        root.put("lastWaitMs", state.lastWaitMs)
        root.put("pauseReason", state.pauseReason.name)
        root.put("manual", state.manual)
        root.put("isIncremental", state.isIncremental)
        root.put("processedSentenceCount", state.processedSentenceCount)
        root.put("skippedSentenceCount", state.skippedSentenceCount)
        root.put("throughMs", state.throughMs)
        root.put("currentChunkAttempts", state.currentChunkAttempts)
        root.put("skippedChunkCount", state.skippedChunkCount)
        vf.writeText(root.toString())
    }

    /** Removes the staged cycle at the user's request, even when its file cannot be read. */
    fun clear() {
        vaultFile?.deleteForUser()
    }

    /**
     * Drops the staged cycle once the worker has finished it. Unlike [clear] this respects a read
     * block, so a staging file that could not be read is never deleted automatically.
     */
    fun discard() {
        try {
            vaultFile?.delete()
        } catch (exception: IOException) {
            Timber.w(exception, "Graph enrichment staging could not be discarded")
        }
    }
}
