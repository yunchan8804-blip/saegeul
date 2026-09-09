/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ads.TypingDnaInterstitialController
import org.fcitx.fcitx5.android.input.ai.AiSettingsNavigator
import org.fcitx.fcitx5.android.input.ai.TypingDnaPersistenceException
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentFailure
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentPhase
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentRunner
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentStatusStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Dedicated visual dashboard for Typing DNA linguistic evolution.
 * Displays real-time accumulation charts, tone & persona balances,
 * top bigram transitions, and on-device privacy guarantee metrics.
 */
class TypingDnaDashboardActivity : AppCompatActivity() {

    private lateinit var snapshotReader: DashboardSnapshotReader
    private lateinit var chartView: TypingDnaChartView

    private lateinit var tvLevelBadge: TextView
    private lateinit var tvLevelTitle: TextView
    private lateinit var tvLevelDesc: TextView
    private lateinit var progressLevel: LinearProgressIndicator
    private lateinit var tvLevelProgressText: TextView

    private lateinit var tvDashSentences: TextView
    private lateinit var tvDashBigrams: TextView
    private lateinit var tvDashEndings: TextView
    private lateinit var tvDashPhrases: TextView
    private lateinit var tvDashNgramStats: TextView
    private lateinit var tvDashRagStats: TextView
    private lateinit var tvDashGraphStats: TextView
    private lateinit var tvDashSyncLevel: TextView
    private lateinit var tvDashEnrichmentAvailability: TextView
    private lateinit var btnEnrichmentSetup: MaterialButton
    private lateinit var progressEnrichment: LinearProgressIndicator
    private lateinit var tvDashLastLearned: TextView
    private lateinit var tvSyncPill: TextView
    private lateinit var btnSyncNow: MaterialButton
    private lateinit var btnClearDna: MaterialButton
    private lateinit var enrichmentStatusStore: GraphEnrichmentStatusStore
    private lateinit var interstitial: TypingDnaInterstitialController
    private lateinit var dashboardContent: View
    private lateinit var dashboardLoading: View
    private lateinit var dashboardLoadingMessage: TextView
    private lateinit var dashboardSyncBusy: View
    private lateinit var dashboardSyncError: TextView

    private var refreshJob: Job? = null
    private var enrichmentRefreshJob: Job? = null
    private var syncJob: Job? = null
    private var clearJob: Job? = null
    private var hasRenderedSnapshot = false
    private var dashboardBusy = false
    private var enrichmentRunning = false
    private var snapshotGeneration = 0L
    private var enrichmentRefreshPending = false

    private val enrichmentStatusListener: () -> Unit = {
        runOnUiThread { requestEnrichmentRefresh() }
    }

    private lateinit var tvVaultHeroSubtitle: TextView
    private lateinit var tvVaultSecuritySubtitle: TextView
    private lateinit var vaultIntegrityGrid: LinearLayout
    private lateinit var tvVaultIntegrityEmpty: TextView
    private lateinit var tvVaultAcceptRate: TextView
    private lateinit var tvVaultAcceptRateDescription: TextView
    private lateinit var tvVaultPersonalHits: TextView
    private lateinit var tvVaultKeystrokesSaved: TextView
    private lateinit var tvVaultTyposFixed: TextView
    private lateinit var vaultTimelineView: VaultTimelineView
    private lateinit var tvVaultTimelineSummary: TextView
    private lateinit var tvVaultTimelineEmpty: TextView
    private lateinit var cardVaultWords: MaterialCardView
    private lateinit var chipGroupVaultWords: ChipGroup
    private lateinit var vaultCatMessengerFill: View
    private lateinit var vaultCatWorkFill: View
    private lateinit var vaultCatGeneralFill: View
    private lateinit var tvVaultCatMessengerPct: TextView
    private lateinit var tvVaultCatWorkPct: TextView
    private lateinit var tvVaultCatGeneralPct: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_typing_dna_dashboard)
        applySystemBarInsets()

        snapshotReader = DashboardSnapshotReader(applicationContext)
        interstitial = TypingDnaInterstitialController(this)
        interstitial.prepare()
        enrichmentStatusStore = GraphEnrichmentStatusStore(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        tvSyncPill = findViewById(R.id.tv_sync_pill)
        dashboardContent = findViewById(R.id.dashboard_content)
        dashboardLoading = findViewById(R.id.dashboard_loading)
        dashboardLoadingMessage = findViewById(R.id.dashboard_loading_message)
        dashboardSyncBusy = findViewById(R.id.dashboard_sync_busy)
        dashboardSyncError = findViewById(R.id.dashboard_sync_error)

        chartView = findViewById(R.id.chart_view)
        tvLevelBadge = findViewById(R.id.tv_level_badge)
        tvLevelTitle = findViewById(R.id.tv_level_title)
        tvLevelDesc = findViewById(R.id.tv_level_desc)
        progressLevel = findViewById(R.id.progress_level)
        tvLevelProgressText = findViewById(R.id.tv_level_progress_text)

        tvDashSentences = findViewById(R.id.tv_dash_sentences)
        tvDashBigrams = findViewById(R.id.tv_dash_bigrams)
        tvDashEndings = findViewById(R.id.tv_dash_endings)
        tvDashPhrases = findViewById(R.id.tv_dash_phrases)
        tvDashNgramStats = findViewById(R.id.tv_dash_ngram_stats)
        tvDashRagStats = findViewById(R.id.tv_dash_rag_stats)
        tvDashGraphStats = findViewById(R.id.tv_dash_graph_stats)
        tvDashSyncLevel = findViewById(R.id.tv_dash_sync_level)
        tvDashEnrichmentAvailability = findViewById(R.id.tv_dash_enrichment_availability)
        btnEnrichmentSetup = findViewById(R.id.btn_enrichment_setup)
        progressEnrichment = findViewById(R.id.progress_enrichment)
        tvDashLastLearned = findViewById(R.id.tv_dash_last_learned)

        tvVaultHeroSubtitle = findViewById(R.id.tv_vault_hero_subtitle)
        tvVaultSecuritySubtitle = findViewById(R.id.tv_vault_security_subtitle)
        vaultIntegrityGrid = findViewById(R.id.vault_integrity_grid)
        tvVaultIntegrityEmpty = findViewById(R.id.tv_vault_integrity_empty)
        tvVaultAcceptRate = findViewById(R.id.tv_vault_accept_rate)
        tvVaultAcceptRateDescription = findViewById(R.id.tv_vault_accept_rate_description)
        tvVaultPersonalHits = findViewById(R.id.tv_vault_personal_hits)
        tvVaultKeystrokesSaved = findViewById(R.id.tv_vault_keystrokes_saved)
        tvVaultTyposFixed = findViewById(R.id.tv_vault_typos_fixed)
        vaultTimelineView = findViewById(R.id.vault_timeline_view)
        tvVaultTimelineSummary = findViewById(R.id.tv_vault_timeline_summary)
        tvVaultTimelineEmpty = findViewById(R.id.tv_vault_timeline_empty)
        cardVaultWords = findViewById(R.id.card_vault_words)
        chipGroupVaultWords = findViewById(R.id.chip_group_vault_words)
        vaultCatMessengerFill = findViewById(R.id.vault_cat_messenger_fill)
        vaultCatWorkFill = findViewById(R.id.vault_cat_work_fill)
        vaultCatGeneralFill = findViewById(R.id.vault_cat_general_fill)
        tvVaultCatMessengerPct = findViewById(R.id.tv_vault_cat_messenger_pct)
        tvVaultCatWorkPct = findViewById(R.id.tv_vault_cat_work_pct)
        tvVaultCatGeneralPct = findViewById(R.id.tv_vault_cat_general_pct)

        btnSyncNow = findViewById(R.id.btn_sync_now)
        btnClearDna = findViewById(R.id.btn_clear_dna)
        btnEnrichmentSetup.setOnClickListener {
            AiSettingsNavigator.openWritingSetup(this)
        }
        findViewById<MaterialButton>(R.id.btn_dashboard_loading_retry).setOnClickListener {
            requestDashboardRefresh(animate = true)
        }

        btnSyncNow.setOnClickListener { startSync() }

        btnClearDna.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("언어 지문 전체 초기화")
                .setMessage("기기 내에 학습된 모든 말투, 종결 어미, 나만의 표현을 영구 삭제하시겠습니까?")
                .setPositiveButton(R.string.delete) { _, _ ->
                    startClear()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        requestDashboardRefresh(animate = true)
    }

    override fun onStart() {
        super.onStart()
        enrichmentStatusStore.addListener(enrichmentStatusListener)
    }

    override fun onStop() {
        enrichmentStatusStore.removeListener(enrichmentStatusListener)
        super.onStop()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) return
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS
        )
    }

    private fun applySystemBarInsets() {
        val appBar = findViewById<AppBarLayout>(R.id.dashboard_appbar)
        val scroll = findViewById<NestedScrollView>(R.id.dashboard_scroll)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.dashboard_root)) { _, windowInsets ->
            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            appBar.updatePadding(top = statusBars.top)
            scroll.updatePadding(
                left = navBars.left,
                right = navBars.right,
                bottom = navBars.bottom
            )
            windowInsets
        }
    }

    override fun onResume() {
        super.onResume()
        requestDashboardRefresh(animate = false)
    }

    private fun requestDashboardRefresh(animate: Boolean) {
        if (refreshJob?.isActive == true || syncJob?.isActive == true || clearJob?.isActive == true) return
        val generation = ++snapshotGeneration
        if (!hasRenderedSnapshot) showInitialLoading()
        refreshJob = lifecycleScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { snapshotReader.read() }
                if (generation != snapshotGeneration) return@launch
                hasRenderedSnapshot = true
                renderDashboard(snapshot, animate)
                showDashboardContent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                android.util.Log.w("SaegeulAI", "dashboard load failed: ${error.javaClass.simpleName}")
                if (!hasRenderedSnapshot) showInitialLoadFailure()
                else Toast.makeText(this@TypingDnaDashboardActivity, R.string.dashboard_loading_failed, Toast.LENGTH_SHORT).show()
            } finally {
                refreshJob = null
            }
        }
    }

    private fun requestEnrichmentRefresh() {
        if (refreshJob?.isActive == true || syncJob?.isActive == true || clearJob?.isActive == true) return
        if (enrichmentRefreshJob?.isActive == true) {
            enrichmentRefreshPending = true
            return
        }
        val generation = snapshotGeneration
        enrichmentRefreshJob = lifecycleScope.launch {
            try {
                val enrichment = withContext(Dispatchers.IO) { snapshotReader.readEnrichment() }
                if (generation != snapshotGeneration) return@launch
                renderEnrichment(enrichment)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                android.util.Log.w("SaegeulAI", "dashboard enrichment refresh failed: ${error.javaClass.simpleName}")
            } finally {
                enrichmentRefreshJob = null
                if (enrichmentRefreshPending) {
                    enrichmentRefreshPending = false
                    requestEnrichmentRefresh()
                }
            }
        }
    }

    private fun startSync() {
        if (syncJob?.isActive == true || clearJob?.isActive == true) return
        val ime = org.fcitx.fcitx5.android.input.FcitxInputMethodService.activeInstance
        snapshotGeneration++
        dashboardSyncError.visibility = View.GONE
        setDashboardBusy(true)
        syncJob = lifecycleScope.launch {
            var completedSnapshot: DashboardSnapshot? = null
            try {
                val before = withContext(Dispatchers.IO) { snapshotReader.readAnalyzedSentenceCount() }
                if (ime != null) {
                    ime.triggerInstantTypingDnaSyncAsync()
                } else {
                    withContext(Dispatchers.IO) { snapshotReader.persistWithoutIme() }
                }
                val snapshot = withContext(Dispatchers.IO) { snapshotReader.readAfterManualSync() }
                completedSnapshot = snapshot
                hasRenderedSnapshot = true
                renderDashboard(snapshot, animate = true)
                Toast.makeText(
                    this@TypingDnaDashboardActivity,
                    when {
                        snapshot.typingStats.totalSentences > before ->
                            "최신 언어 지문 분석 완료 (분석 문장: ${snapshot.typingStats.totalSentences}개)"
                        snapshot.typingStats.totalSentences > 0 ->
                            "대기 중인 새 문장이 없습니다 (분석 문장: ${snapshot.typingStats.totalSentences}개)"
                        ime == null ->
                            "저장된 언어 지문을 불러왔습니다 (분석 문장: ${snapshot.typingStats.totalSentences}개)"
                        else -> "대기 중인 새 문장이 없습니다 (분석 문장: ${snapshot.typingStats.totalSentences}개)"
                    },
                    Toast.LENGTH_SHORT
                ).show()
                interstitial.showAfterAction()
                continueWithEnrichment(snapshot.enrichment)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: TypingDnaPersistenceException) {
                android.util.Log.w("SaegeulAI", "dashboard sync failed: ${error.javaClass.simpleName}")
                showSyncFailure()
            } catch (error: Throwable) {
                android.util.Log.w("SaegeulAI", "dashboard sync failed: ${error.javaClass.simpleName}")
                showSyncFailure()
            } finally {
                syncJob = null
                if (!isFinishing && !isDestroyed) {
                    setDashboardBusy(false)
                    completedSnapshot?.let { renderEnrichment(it.enrichment) }
                    requestEnrichmentRefresh()
                }
            }
        }
    }

    private fun startClear() {
        if (syncJob?.isActive == true || clearJob?.isActive == true) return
        snapshotGeneration++
        setDashboardBusy(true)
        clearJob = lifecycleScope.launch {
            var snapshot: DashboardSnapshot? = null
            try {
                val clearedSnapshot = withContext(Dispatchers.IO) { snapshotReader.clearAll() }
                snapshot = clearedSnapshot
                hasRenderedSnapshot = true
                renderDashboard(clearedSnapshot, animate = true)
                Toast.makeText(this@TypingDnaDashboardActivity, "언어 지문이 안전하게 초기화되었습니다.", Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                android.util.Log.w("SaegeulAI", "dashboard clear failed: ${error.javaClass.simpleName}")
                Toast.makeText(this@TypingDnaDashboardActivity, R.string.typing_dna_persistence_failed, Toast.LENGTH_SHORT).show()
            } finally {
                clearJob = null
                if (!isFinishing && !isDestroyed) {
                    setDashboardBusy(false)
                    snapshot?.let { renderEnrichment(it.enrichment) } ?: requestEnrichmentRefresh()
                }
            }
        }
    }

    private fun continueWithEnrichment(enrichment: DashboardEnrichmentSnapshot) {
        val profile = enrichment.profile
        when {
            enrichment.offlineMode || profile == null -> AlertDialog.Builder(this)
                .setTitle(R.string.sync_done_title)
                .setMessage(
                    if (enrichment.offlineMode) getString(R.string.sync_offline_message)
                    else getString(R.string.sync_llm_missing_message)
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
            enrichment.oauthNeedsLogin -> AlertDialog.Builder(this)
                .setTitle(R.string.sync_done_title)
                .setMessage(R.string.ai_oauth_reauth_required)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.enrichment_setup_action) { _, _ ->
                    AiSettingsNavigator.openWritingSetup(this)
                }
                .show()
            else -> {
                ensureNotificationPermission()
                if (GraphEnrichmentRunner.start(this, profile, notify = true)) {
                    requestEnrichmentRefresh()
                    AlertDialog.Builder(this)
                        .setTitle(R.string.enrich_bg_title)
                        .setMessage(R.string.enrich_bg_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    requestEnrichmentRefresh()
                }
            }
        }
    }

    private fun renderDashboard(snapshot: DashboardSnapshot, animate: Boolean) {
        val stats = snapshot.typingStats
        tvLevelBadge.text = getString(R.string.vault_level_number, stats.level)
        tvLevelBadge.contentDescription = getString(R.string.vault_level_content_description, stats.level)
        tvLevelTitle.text = getString(R.string.vault_level_title, stats.level)
        tvLevelDesc.setText(R.string.vault_level_description)

        progressLevel.progress = stats.levelProgressPercent

        tvDashSentences.text = "${stats.totalSentences}"
        tvDashBigrams.text = "${stats.bigramsCount}"
        tvDashEndings.text = "${stats.endingsCount}"
        tvDashPhrases.text = "${stats.phrasesCount}"

        tvLevelProgressText.text = if (stats.level >= 5) {
            getString(R.string.vault_level_progress_max)
        } else {
            val remain = (stats.nextLevelTargetSentences - stats.totalSentences).coerceAtLeast(1)
            getString(
                R.string.vault_level_progress_next,
                stats.level + 1,
                remain,
                stats.levelProgressPercent
            )
        }

        chartView.setStats(stats, animate = animate)

        tvDashNgramStats.text = getString(
            R.string.typing_dna_ngram_stats_line,
            snapshot.ngramUnigrams,
            snapshot.ngramBigrams,
            snapshot.pendingSentences
        )
        tvDashRagStats.text = getString(
            R.string.personal_sentence_vault_stats_line,
            snapshot.vaultSentences
        )
        val nowMs = System.currentTimeMillis()
        if (snapshot.lastSyncMs > 0L) {
            tvSyncPill.visibility = View.VISIBLE
            tvSyncPill.text = getString(
                R.string.sync_pill_last,
                org.fcitx.fcitx5.android.input.ai.TypingDnaSyncStatus.formatTime(snapshot.lastSyncMs, nowMs)
            )
        } else {
            tvSyncPill.visibility = View.GONE
        }
        renderEnrichment(snapshot.enrichment)

        if (snapshot.ngramLastLearnedMs != 0L) {
            tvDashLastLearned.visibility = android.view.View.VISIBLE
            tvDashLastLearned.text = getString(
                R.string.typing_dna_last_learned,
                DateUtils.getRelativeTimeSpanString(
                    snapshot.ngramLastLearnedMs,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                )
            )
        } else {
            tvDashLastLearned.visibility = android.view.View.GONE
        }

        val metricsSummary = snapshot.metrics

        tvVaultHeroSubtitle.text = getString(
            R.string.vault_hero_subtitle,
            snapshot.ngramUnigrams,
            stats.totalSentences
        )

        tvVaultSecuritySubtitle.text = when {
            snapshot.security.isStrongBoxBacked -> getString(R.string.vault_security_strongbox)
            snapshot.security.isHardwareBacked -> getString(R.string.vault_security_tee)
            else -> getString(R.string.vault_security_software)
        }

        vaultIntegrityGrid.visibility = View.VISIBLE
        tvVaultAcceptRate.text = if (metricsSummary.totalShown > 0) {
            "${(metricsSummary.acceptRate * 100).roundToInt()}%"
        } else {
            getString(R.string.vault_metric_not_recorded)
        }
        tvVaultAcceptRateDescription.text = if (metricsSummary.totalShown > 0) {
            getString(
                R.string.vault_metric_accept_rate_counts,
                metricsSummary.totalShown,
                metricsSummary.totalAccepted
            )
        } else {
            getString(R.string.vault_metric_accept_rate_description)
        }
        tvVaultPersonalHits.text = if (metricsSummary.totalAccepted > 0) {
            "${(metricsSummary.personalShare * 100).roundToInt()}%"
        } else {
            getString(R.string.vault_metric_not_recorded)
        }
        tvVaultKeystrokesSaved.setText(R.string.vault_metric_measurement_pending)
        tvVaultTyposFixed.text = "${metricsSummary.typoCorrected}"
        tvVaultIntegrityEmpty.visibility = if (metricsSummary.totalShown == 0) {
            View.VISIBLE
        } else {
            View.GONE
        }

        vaultTimelineView.setSummary(metricsSummary)
        val timelineSentenceTotal = metricsSummary.recent.sumOf { it.learnedSentences }
        val timelineDailyMaximum = metricsSummary.recent.maxOfOrNull { it.learnedSentences } ?: 0
        tvVaultTimelineSummary.text = getString(
            R.string.vault_timeline_summary,
            timelineSentenceTotal,
            timelineDailyMaximum
        )
        tvVaultTimelineEmpty.visibility = if (metricsSummary.recent.none { it.learnedSentences > 0 }) {
            View.VISIBLE
        } else {
            View.GONE
        }

        chipGroupVaultWords.removeAllViews()
        if (snapshot.frequentWords.isEmpty()) {
            cardVaultWords.visibility = View.GONE
        } else {
            cardVaultWords.visibility = View.VISIBLE
            snapshot.frequentWords.forEach { word ->
                val chip = Chip(this)
                chip.text = word
                chip.isClickable = false
                chip.isCheckable = false
                chip.isFocusable = false
                chipGroupVaultWords.addView(chip)
            }
        }

        setCategoryBar(vaultCatMessengerFill, tvVaultCatMessengerPct, snapshot.messengerRatio)
        setCategoryBar(vaultCatWorkFill, tvVaultCatWorkPct, snapshot.workRatio)
        setCategoryBar(vaultCatGeneralFill, tvVaultCatGeneralPct, snapshot.generalRatio)
    }

    private fun renderEnrichment(enrichment: DashboardEnrichmentSnapshot) {
        val phase = enrichment.phase
        val nowMs = System.currentTimeMillis()

        tvDashSyncLevel.text = when (phase) {
            GraphEnrichmentPhase.NEVER -> if (enrichment.graphBuiltMs > 0L) {
                getString(R.string.enrichment_status_legacy)
            } else {
                getString(R.string.enrichment_status_never)
            }
            GraphEnrichmentPhase.RUNNING -> getString(R.string.enrichment_status_running)
            GraphEnrichmentPhase.SUCCEEDED -> getString(R.string.enrichment_status_succeeded)
            GraphEnrichmentPhase.PARTIAL -> getString(R.string.enrichment_status_partial)
            GraphEnrichmentPhase.NO_DATA -> getString(R.string.enrichment_status_no_data)
            GraphEnrichmentPhase.FAILED -> getString(
                when (enrichment.failure) {
                    GraphEnrichmentFailure.INVALID_RESPONSE -> R.string.graph_enrichment_failure_invalid_response
                    GraphEnrichmentFailure.REAUTH_REQUIRED -> R.string.graph_enrichment_failure_reauth_required
                    GraphEnrichmentFailure.PROVIDER_BUSY -> R.string.graph_enrichment_failure_provider_busy
                    GraphEnrichmentFailure.TIMEOUT -> R.string.graph_enrichment_failure_timeout
                    GraphEnrichmentFailure.NETWORK -> R.string.graph_enrichment_failure_network
                    GraphEnrichmentFailure.PROVIDER_ERROR -> R.string.graph_enrichment_failure_provider_error
                    GraphEnrichmentFailure.STORAGE -> R.string.graph_enrichment_failure_storage
                    GraphEnrichmentFailure.NONE,
                    GraphEnrichmentFailure.UNKNOWN -> R.string.enrichment_status_failed
                }
            )
            GraphEnrichmentPhase.INTERRUPTED -> getString(R.string.enrichment_status_interrupted)
        }
        tvDashGraphStats.text = when {
            enrichment.lastAppliedMs > 0L -> getString(
                R.string.enrichment_graph_stats_last_applied,
                org.fcitx.fcitx5.android.input.ai.TypingDnaSyncStatus.formatTime(enrichment.lastAppliedMs, nowMs),
                enrichment.graphNodes,
                enrichment.graphEdges,
                enrichment.graphTopics
            )
            phase == GraphEnrichmentPhase.NEVER && enrichment.graphBuiltMs > 0L -> getString(
                R.string.enrichment_graph_stats_legacy,
                org.fcitx.fcitx5.android.input.ai.TypingDnaSyncStatus.formatTime(enrichment.graphBuiltMs, nowMs),
                enrichment.graphNodes,
                enrichment.graphEdges,
                enrichment.graphTopics
            )
            else -> getString(
                R.string.dashboard_graph_stats_line,
                enrichment.graphNodes,
                enrichment.graphEdges,
                enrichment.graphTopics
            )
        }

        val isRunning = phase == GraphEnrichmentPhase.RUNNING
        enrichmentRunning = isRunning
        progressEnrichment.visibility = if (isRunning) View.VISIBLE else View.GONE
        progressEnrichment.isIndeterminate = true
        updateActionAvailability()

        when {
            enrichment.offlineMode -> {
                tvDashEnrichmentAvailability.visibility = View.VISIBLE
                tvDashEnrichmentAvailability.setText(R.string.enrichment_unavailable_offline)
                btnEnrichmentSetup.visibility = View.GONE
            }
            enrichment.profile == null -> {
                tvDashEnrichmentAvailability.visibility = View.VISIBLE
                tvDashEnrichmentAvailability.setText(R.string.enrichment_unavailable_provider)
                btnEnrichmentSetup.visibility = View.VISIBLE
            }
            enrichment.oauthNeedsLogin || enrichment.failure == GraphEnrichmentFailure.REAUTH_REQUIRED -> {
                tvDashEnrichmentAvailability.visibility = View.VISIBLE
                tvDashEnrichmentAvailability.setText(R.string.enrichment_unavailable_oauth)
                btnEnrichmentSetup.visibility = View.VISIBLE
            }
            else -> {
                tvDashEnrichmentAvailability.visibility = View.GONE
                btnEnrichmentSetup.visibility = View.GONE
            }
        }
    }

    private fun setDashboardBusy(busy: Boolean) {
        dashboardBusy = busy
        dashboardSyncBusy.visibility = if (busy) View.VISIBLE else View.GONE
        updateActionAvailability()
    }

    private fun updateActionAvailability() {
        btnSyncNow.isEnabled = !dashboardBusy && !enrichmentRunning
        btnClearDna.isEnabled = !dashboardBusy
    }

    private fun showSyncFailure() {
        if (isFinishing || isDestroyed) return
        dashboardSyncError.visibility = View.VISIBLE
        Toast.makeText(this, R.string.typing_dna_persistence_failed, Toast.LENGTH_SHORT).show()
    }

    private fun showInitialLoading() {
        dashboardContent.visibility = View.GONE
        dashboardLoading.visibility = View.VISIBLE
        dashboardLoadingMessage.setText(R.string.dashboard_loading_message)
        findViewById<View>(R.id.dashboard_loading_error).visibility = View.GONE
        findViewById<View>(R.id.btn_dashboard_loading_retry).visibility = View.GONE
        findViewById<View>(R.id.dashboard_loading_progress).visibility = View.VISIBLE
    }

    private fun showInitialLoadFailure() {
        dashboardContent.visibility = View.GONE
        dashboardLoading.visibility = View.VISIBLE
        findViewById<View>(R.id.dashboard_loading_progress).visibility = View.GONE
        findViewById<View>(R.id.dashboard_loading_error).visibility = View.VISIBLE
        findViewById<View>(R.id.btn_dashboard_loading_retry).visibility = View.VISIBLE
    }

    private fun showDashboardContent() {
        dashboardLoading.visibility = View.GONE
        dashboardContent.visibility = View.VISIBLE
    }

    private fun setCategoryBar(fillView: View, percentText: TextView, ratio: Float) {
        val params = fillView.layoutParams as LinearLayout.LayoutParams
        params.weight = ratio.coerceIn(0f, 1f)
        fillView.layoutParams = params
        percentText.text = "${(ratio * 100).roundToInt()}%"
    }

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 1001
    }
}
