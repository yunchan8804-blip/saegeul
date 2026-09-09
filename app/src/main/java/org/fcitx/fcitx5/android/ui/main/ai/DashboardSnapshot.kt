/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import android.content.Context
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.ai.AiAuthMode
import org.fcitx.fcitx5.android.input.ai.AiOAuthSessionStore
import org.fcitx.fcitx5.android.input.ai.AiProviderCredentialStore
import org.fcitx.fcitx5.android.input.ai.AiProviderProfile
import org.fcitx.fcitx5.android.input.ai.KoreanSuggestionSurface
import org.fcitx.fcitx5.android.input.ai.TypingDnaInstantSync
import org.fcitx.fcitx5.android.input.ai.TypingDnaStats
import org.fcitx.fcitx5.android.input.ai.TypingDnaSyncStatusStore
import org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsStore
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentPhase
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentFailure
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentRunner
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentStatusStore
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentStatus
import org.fcitx.fcitx5.android.input.ai.TypingDnaVault
import java.io.File

internal data class DashboardSnapshot(
    val typingStats: TypingDnaStats,
    val ngramUnigrams: Int,
    val ngramBigrams: Int,
    val ngramLastLearnedMs: Long,
    val pendingSentences: Int,
    val vaultSentences: Int,
    val metrics: PredictionMetricsStore.Summary,
    val frequentWords: List<String>,
    val messengerRatio: Float,
    val workRatio: Float,
    val generalRatio: Float,
    val security: DashboardSecurity,
    val lastSyncMs: Long,
    val enrichment: DashboardEnrichmentSnapshot
)

internal data class DashboardSecurity(
    val cipherId: String,
    val isStrongBoxBacked: Boolean,
    val isHardwareBacked: Boolean
)

internal data class DashboardEnrichmentSnapshot(
    val phase: GraphEnrichmentPhase,
    val failure: GraphEnrichmentFailure,
    val lastAppliedMs: Long,
    val graphBuiltMs: Long,
    val graphNodes: Int,
    val graphEdges: Int,
    val graphTopics: Int,
    val offlineMode: Boolean,
    val profile: AiProviderProfile?,
    val oauthNeedsLogin: Boolean
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
        val categoryCounts = app.personalNgramModel.categoryCounts()
        val messengerCount = categoryCounts[TypingDnaVault.CATEGORY_MESSENGER] ?: 0f
        val workCount = categoryCounts[TypingDnaVault.CATEGORY_WORK] ?: 0f
        val generalCount = categoryCounts[TypingDnaVault.CATEGORY_GENERAL] ?: 0f
        val categoryTotal = messengerCount + workCount + generalCount
        val cipher = app.vaultCipher

        return DashboardSnapshot(
            typingStats = typingStats,
            ngramUnigrams = ngramStats.unigrams,
            ngramBigrams = ngramStats.bigrams,
            ngramLastLearnedMs = ngramStats.lastLearnedMs,
            pendingSentences = app.typingDnaVault.totalBufferedCount(),
            vaultSentences = app.personalSentenceVault.stats().sentences,
            metrics = app.predictionMetricsStore.summary(),
            frequentWords = frequentWords,
            messengerRatio = if (categoryTotal > 0f) messengerCount / categoryTotal else 0f,
            workRatio = if (categoryTotal > 0f) workCount / categoryTotal else 0f,
            generalRatio = if (categoryTotal > 0f) generalCount / categoryTotal else 0f,
            security = DashboardSecurity(
                cipherId = cipher.id,
                isStrongBoxBacked = cipher.isStrongBoxBacked,
                isHardwareBacked = cipher.isHardwareBacked
            ),
            lastSyncMs = syncStatusStore.lastSyncMs(),
            enrichment = readEnrichment(app)
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
        return read()
    }

    private fun readEnrichment(app: FcitxApplication): DashboardEnrichmentSnapshot {
        val graphStats = app.personalGraphStore.stats()
        val status = enrichmentStatusStore.snapshot()
        val effectiveStatus = status.copy(phase = effectivePhase(status))
        val offlineMode = AppPrefs.getInstance().advanced.offlineMode.getValue()
        val profile = AiProviderCredentialStore(appContext).load()
        val oauthNeedsLogin = profile?.let {
            it.authMode == AiAuthMode.OAuthPkce && !AiOAuthSessionStore(appContext).hasSession(it)
        } == true
        return DashboardEnrichmentSnapshot(
            phase = effectiveStatus.phase,
            failure = effectiveStatus.failure,
            lastAppliedMs = effectiveStatus.lastAppliedMs,
            graphBuiltMs = graphStats.builtMs,
            graphNodes = graphStats.nodes,
            graphEdges = graphStats.edges,
            graphTopics = graphStats.topics,
            offlineMode = offlineMode,
            profile = profile,
            oauthNeedsLogin = oauthNeedsLogin
        )
    }

    private fun effectivePhase(status: GraphEnrichmentStatus): GraphEnrichmentPhase =
        if (status.phase == GraphEnrichmentPhase.RUNNING && !GraphEnrichmentRunner.isRunning()) {
            GraphEnrichmentPhase.INTERRUPTED
        } else {
            status.phase
        }
}
