/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import android.content.Context
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.points.PointLedger
import org.fcitx.fcitx5.android.input.ai.KoreanSuggestionSurface
import org.fcitx.fcitx5.android.input.ai.TypingDnaInstantSync
import org.fcitx.fcitx5.android.input.ai.TypingDnaStats
import org.fcitx.fcitx5.android.input.ai.VaultHabitState
import org.fcitx.fcitx5.android.input.ai.VaultHabitStore
import org.fcitx.fcitx5.android.input.ai.TypingDnaSyncStatusStore
import org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsStore
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentPhase
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentFailure
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentRunner
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentStatusStore
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentStatus
import org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphEnrichmentStagingStore
import org.fcitx.fcitx5.android.input.ai.CollectionDiagnostics
import java.io.File

internal data class DashboardSnapshot(
    val typingStats: TypingDnaStats,
    val habit: VaultHabitState,
    val pointBalance: Int,
    val ngramUnigrams: Int,
    val ngramBigrams: Int,
    val ngramLastLearnedMs: Long,
    val pendingSentences: Int,
    val vaultSentences: Int,
    val metrics: PredictionMetricsStore.Summary,
    val frequentWords: List<String>,
    /** Registry persona id -> pending (not-yet-compiled) sentence count in the vault. */
    val categoryPending: Map<String, Int>,
    /** Sentences needed per category before a batch compiles; [org.fcitx.fcitx5.android.input.ai.TypingDnaVault.thresholdPerCategory]. */
    val categoryPendingThreshold: Int,
    val security: DashboardSecurity,
    val lastSyncMs: Long,
    val enrichment: DashboardEnrichmentSnapshot,
    val collectionToday: CollectionDiagnostics.DailyCounters
)

internal data class DashboardSecurity(
    val cipherId: String,
    val isStrongBoxBacked: Boolean,
    val isHardwareBacked: Boolean
)

internal data class DashboardEnrichmentSnapshot(
    val phase: GraphEnrichmentPhase,
    val failure: GraphEnrichmentFailure,
    val failureDetail: String? = null,
    val lastAppliedMs: Long,
    val graphNodes: Int,
    val graphEdges: Int,
    val graphTopics: Int,
    /** The in-flight cycle's checkpoint, non-null while one exists regardless of [phase] (RUNNING mid-cycle, or INTERRUPTED/paused with progress to resume). */
    val staging: PersonalGraphEnrichmentStagingStore.State?
)

/** Reads the language-vault dashboard state away from the UI thread. */
internal class DashboardSnapshotReader(context: Context) {
    private val appContext = context.applicationContext
    private val syncStatusStore = TypingDnaSyncStatusStore(appContext)
    private val enrichmentStatusStore = GraphEnrichmentStatusStore(appContext)

    fun read(): DashboardSnapshot {
        val app = FcitxApplication.getInstance()
        val typingStats = app.typingDnaRepository.getStats()
        val ngramStats = app.personalNgramModel.stats()
        val frequentWordCounts = mutableListOf<Pair<String, Float>>()
        app.personalNgramModel.forEachUnigram { word, count ->
            if (word.length >= 2 && word != "<s>" && KoreanSuggestionSurface.isDisplayable(word)) {
                frequentWordCounts += word to count
            }
        }
        val frequentWords = frequentWordCounts.sortedByDescending { it.second }.take(12).map { it.first }
        val cipher = app.vaultCipher

        return DashboardSnapshot(
            typingStats = typingStats,
            habit = VaultHabitStore(appContext).state(),
            pointBalance = PointLedger(appContext).balance(),
            ngramUnigrams = ngramStats.unigrams,
            ngramBigrams = ngramStats.bigrams,
            ngramLastLearnedMs = ngramStats.lastLearnedMs,
            pendingSentences = app.typingDnaVault.totalBufferedCount(),
            vaultSentences = app.personalSentenceVault.stats().sentences,
            metrics = app.predictionMetricsStore.summary(),
            frequentWords = frequentWords,
            categoryPending = app.typingDnaVault.pendingByCategory(),
            categoryPendingThreshold = app.typingDnaVault.thresholdPerCategory,
            security = DashboardSecurity(
                cipherId = cipher.id,
                isStrongBoxBacked = cipher.isStrongBoxBacked,
                isHardwareBacked = cipher.isHardwareBacked
            ),
            lastSyncMs = syncStatusStore.lastSyncMs(),
            enrichment = readEnrichment(app),
            collectionToday = app.collectionDiagnostics.today()
        )
    }

    fun readEnrichment(): DashboardEnrichmentSnapshot = readEnrichment(FcitxApplication.getInstance())

    fun readAnalyzedSentenceCount(): Int =
        FcitxApplication.getInstance().typingDnaRepository.getStats().totalSentences

    fun persistWithoutIme() {
        val app = FcitxApplication.getInstance()
        TypingDnaInstantSync.persistOnly(
            app.typingDnaVault,
            app.typingDnaRepository,
            sentenceStoreFile = File(appContext.filesDir, "personalized_sentences.json"),
            cipher = app.vaultCipher
        )
    }

    fun readAfterManualSync(): DashboardSnapshot {
        syncStatusStore.recordSync(System.currentTimeMillis())
        return read()
    }

    fun clearAll(): DashboardSnapshot {
        val app = FcitxApplication.getInstance()
        app.typingDnaRepository.clear()
        app.typingDnaVault.purge()
        app.personalNgramModel.clear()
        app.correctionPatternStore.clear()
        app.predictionMetricsStore.clear()
        app.personalSentenceVault.clear()
        app.personalGraphStore.clear()
        PersonalGraphEnrichmentStagingStore(
            File(appContext.filesDir, "personal_graph_staging.json"),
            cipher = app.vaultCipher
        ).clear()
        GraphEnrichmentStatusStore(appContext).clear()
        org.fcitx.fcitx5.android.input.FcitxInputMethodService.activeInstance?.recentSentSentences?.clear()
        return read()
    }

    private fun readEnrichment(app: FcitxApplication): DashboardEnrichmentSnapshot {
        val graphStats = app.personalGraphStore.stats()
        val status = enrichmentStatusStore.snapshot()
        val effectiveStatus = status.copy(
            phase = effectiveEnrichmentPhase(status, GraphEnrichmentRunner.isRunning())
        )
        // Read regardless of phase: a paused (INTERRUPTED) cycle's progress/pause reason lives here
        // too, not just a live RUNNING one.
        val staging = PersonalGraphEnrichmentStagingStore(
            File(appContext.filesDir, "personal_graph_staging.json"),
            cipher = app.vaultCipher
        ).load()
        return DashboardEnrichmentSnapshot(
            phase = effectiveStatus.phase,
            failure = effectiveStatus.failure,
            failureDetail = effectiveStatus.failureDetail,
            lastAppliedMs = effectiveStatus.lastAppliedMs,
            graphNodes = graphStats.nodes,
            graphEdges = graphStats.edges,
            graphTopics = graphStats.topics,
            staging = staging
        )
    }

}

/** Terminal phases [effectiveEnrichmentPhase] refuses to show while a worker is actually running. */
private val TERMINAL_ENRICHMENT_PHASES = setOf(
    GraphEnrichmentPhase.SUCCEEDED,
    GraphEnrichmentPhase.PARTIAL,
    GraphEnrichmentPhase.NO_DATA,
    GraphEnrichmentPhase.FAILED
)

/**
 * [GraphEnrichmentPhase.RUNNING] with no live worker in this process means the process died
 * mid-cycle: [GraphEnrichmentPhase.INTERRUPTED]. Conversely, a live worker with a *terminal* phase
 * still on record ([TERMINAL_ENRICHMENT_PHASES]) means a new cycle has already started but has not
 * reached its own next incremental apply yet - shown as [GraphEnrichmentPhase.RUNNING] rather than
 * whatever the previous cycle finished as, so the dashboard never reads "done"/"failed" while a
 * cycle is genuinely in progress (only the terminal write from [GemmaGraphEnrichmentWorker.finishCycle]
 * should ever be visible, never a stale one a new cycle has already superseded).
 * [GraphEnrichmentPhase.QUEUED] (a manual
 * request just scheduled the worker) is left untouched by every correction here - it is not yet
 * [GraphEnrichmentPhase.RUNNING], so a worker that has not started is expected, not "interrupted",
 * and [GemmaGraphEnrichmentWorker] itself overwrites it with RUNNING the moment it actually starts.
 *
 * Pure (no I/O, no singletons read internally) so it is unit testable on its own; [workerIsRunning]
 * is [GraphEnrichmentRunner.isRunning] at the call site.
 */
internal fun effectiveEnrichmentPhase(status: GraphEnrichmentStatus, workerIsRunning: Boolean): GraphEnrichmentPhase = when {
    status.phase == GraphEnrichmentPhase.RUNNING && !workerIsRunning -> GraphEnrichmentPhase.INTERRUPTED
    workerIsRunning && status.phase in TERMINAL_ENRICHMENT_PHASES -> GraphEnrichmentPhase.RUNNING
    else -> status.phase
}
