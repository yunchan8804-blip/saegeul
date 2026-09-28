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
import android.view.ViewGroup
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
import androidx.core.view.doOnNextLayout
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ads.DashboardBannerController
import org.fcitx.fcitx5.android.ads.TypingDnaInterstitialController
import org.fcitx.fcitx5.android.data.points.LevelRewardStore
import org.fcitx.fcitx5.android.input.ai.TypingDnaLevelCurve
import org.fcitx.fcitx5.android.input.ai.VaultHabitState
import org.fcitx.fcitx5.android.input.ai.TypingDnaPersistenceException
import org.fcitx.fcitx5.android.input.ai.TypingDnaSyncStatus
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentPhase
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentStatusStore
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentUiState
import org.fcitx.fcitx5.android.input.ai.rag.actionLabel
import org.fcitx.fcitx5.android.input.ai.rag.detail
import org.fcitx.fcitx5.android.input.ai.rag.guidance
import org.fcitx.fcitx5.android.input.ai.rag.secondaryGuidance
import org.fcitx.fcitx5.android.input.ai.rag.title
import org.fcitx.fcitx5.android.input.ai.CollectionDiagnostics
import org.fcitx.fcitx5.android.input.ai.persona.PersonaRegistry
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAiSupport
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.ImageButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
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
    private lateinit var tvHabitLine: TextView
    private lateinit var tvPointsLine: TextView

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
    private lateinit var tvStatusEngineValue: TextView
    private lateinit var tvStatusEnrichmentValue: TextView
    private lateinit var btnStatusEnrichmentAction: MaterialButton
    private lateinit var rowStatusNotification: View
    private lateinit var btnStatusNotificationAction: MaterialButton
    private lateinit var tvStatusCollectionValue: TextView
    private lateinit var tvStatusCollectionPending: TextView
    private lateinit var tvStatusCollectionToggle: TextView
    private lateinit var containerStatusCollectionRecent: LinearLayout
    private var collectionRecentExpanded = false
    private lateinit var aiRuntimeStatusStore: org.fcitx.fcitx5.android.input.ai.ondevice.AiRuntimeStatusStore
    private lateinit var btnSyncNow: MaterialButton
    private lateinit var btnClearDna: MaterialButton
    private lateinit var enrichmentStatusStore: GraphEnrichmentStatusStore
    private lateinit var interstitial: TypingDnaInterstitialController
    private lateinit var bannerContainer: ViewGroup
    private lateinit var banner: DashboardBannerController
    private var navBarsBottomInset = 0
    private lateinit var dashboardContent: View
    private lateinit var dashboardLoading: View
    private lateinit var dashboardLoadingMessage: TextView
    private lateinit var dashboardSyncBusy: View
    private lateinit var dashboardSyncError: TextView
    private var gemmaPreparationController: GemmaPreparationController? = null
    private lateinit var gemmaInstallCard: MaterialCardView
    private lateinit var gemmaInstallStatusView: org.fcitx.fcitx5.android.ui.main.ai.install.GemmaInstallStatusView
    private lateinit var gemmaVaultCard: MaterialCardView
    private lateinit var gemmaVaultAutomatic: SwitchMaterial
    private lateinit var gemmaVaultStatus: TextView
    private lateinit var gemmaVaultError: TextView
    private lateinit var gemmaVaultGenerate: MaterialButton
    private lateinit var gemmaVaultPersonalResult: TextView
    private lateinit var gemmaVaultStop: MaterialButton
    private lateinit var gemmaVaultStopNotice: TextView
    private lateinit var gemmaVaultModel: MaterialButton
    private lateinit var gemmaVaultCount: TextView
    private lateinit var gemmaVaultLastRun: TextView
    private var gemmaRefreshJob: Job? = null
    private var gemmaMutationJob: Job? = null
    private var gemmaStateGeneration = 0L
    private var renderingGemmaSnapshot = false
    private var gemmaMutationInProgress = false
    private var gemmaTransientError: String? = null
    private var gemmaSnapshot: GemmaPreparationSnapshot? = null

    private var refreshJob: Job? = null
    private var enrichmentRefreshJob: Job? = null
    private var enrichmentPollingJob: Job? = null
    private var syncJob: Job? = null
    private var clearJob: Job? = null
    private var hasRenderedSnapshot = false
    private var dashboardBusy = false
    private var enrichmentRunning = false
    private var graphActionInFlight = false
    private var snapshotGeneration = 0L
    private var enrichmentRefreshPending = false

    private val enrichmentStatusListener: () -> Unit = {
        runOnUiThread { requestEnrichmentRefresh() }
    }

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
    private lateinit var tvStyleCategories: TextView
    private lateinit var styleSections: StyleReportSections
    private lateinit var categoryDistributionContainer: LinearLayout

    private lateinit var tvLearningBody: TextView
    private lateinit var progressLearning: LinearProgressIndicator
    private lateinit var btnLearningAction: MaterialButton
    private lateinit var switchLearningAutomatic: SwitchMaterial
    private lateinit var tvLearningNotificationHint: TextView

    private lateinit var tvFeedbackPercent: TextView
    private lateinit var tvFeedbackCounts: TextView
    private lateinit var tvFeedbackPersonal: TextView

    private lateinit var btnStorageInfo: ImageButton
    private lateinit var rowStorageBackup: View

    private lateinit var devSection: View
    private lateinit var devSectionHeader: View
    private lateinit var devSectionBody: View
    private lateinit var tvDevSectionToggle: TextView
    private var devSectionExpanded = false

    private var currentEnrichment: DashboardEnrichmentSnapshot? = null
    private var latestNgramLastLearnedMs: Long = 0L
    private var latestSecurity: DashboardSecurity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_typing_dna_dashboard)
        applySystemBarInsets()

        snapshotReader = DashboardSnapshotReader(applicationContext)
        checkPendingCelebration()
        interstitial = TypingDnaInterstitialController(this)
        interstitial.prepare()
        bannerContainer = findViewById(R.id.banner_ad_container)
        banner = DashboardBannerController(this, bannerContainer)
        banner.attach(object : DashboardBannerController.VisibilityListener {
            override fun onBannerVisibilityChanged(visible: Boolean) {
                updateScrollBottomInset()
                if (visible) {
                    bannerContainer.doOnNextLayout { updateScrollBottomInset() }
                }
            }
        })
        enrichmentStatusStore = GraphEnrichmentStatusStore(this)
        gemmaPreparationController = GemmaPreparationFactory.create(applicationContext)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        dashboardContent = findViewById(R.id.dashboard_content)
        dashboardLoading = findViewById(R.id.dashboard_loading)
        dashboardLoadingMessage = findViewById(R.id.dashboard_loading_message)
        dashboardSyncBusy = findViewById(R.id.dashboard_sync_busy)
        dashboardSyncError = findViewById(R.id.dashboard_sync_error)
        gemmaInstallCard = findViewById(R.id.card_gemma_install)
        gemmaInstallStatusView =
            org.fcitx.fcitx5.android.ui.main.ai.install.GemmaInstallStatusView.bind(
                findViewById(R.id.gemma_install_card_status)
            )
        gemmaInstallStatusView.onAction = { button ->
            org.fcitx.fcitx5.android.ui.main.ai.install.GemmaInstallFlow.handle(this, button)
        }
        observeGemmaInstallState()
        gemmaVaultCard = findViewById(R.id.gemma_vault_card)
        gemmaVaultAutomatic = findViewById(R.id.gemma_vault_auto)
        gemmaVaultStatus = findViewById(R.id.gemma_vault_status)
        gemmaVaultError = findViewById(R.id.gemma_vault_error)
        gemmaVaultGenerate = findViewById(R.id.gemma_vault_generate)
        gemmaVaultPersonalResult = findViewById(R.id.gemma_vault_personal_result)
        gemmaVaultStop = findViewById(R.id.gemma_vault_stop)
        gemmaVaultStopNotice = findViewById(R.id.gemma_vault_stop_notice)
        gemmaVaultModel = findViewById(R.id.gemma_vault_model)
        gemmaVaultCount = findViewById(R.id.gemma_vault_count)
        gemmaVaultLastRun = findViewById(R.id.gemma_vault_last_run)

        chartView = findViewById(R.id.chart_view)
        tvLevelBadge = findViewById(R.id.tv_level_badge)
        tvLevelTitle = findViewById(R.id.tv_level_title)
        tvLevelDesc = findViewById(R.id.tv_level_desc)
        progressLevel = findViewById(R.id.progress_level)
        tvLevelProgressText = findViewById(R.id.tv_level_progress_text)
        tvHabitLine = findViewById(R.id.tv_habit_line)
        tvPointsLine = findViewById(R.id.tv_points_line)

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

        tvStatusEngineValue = findViewById(R.id.tv_status_engine_value)
        tvStatusEnrichmentValue = findViewById(R.id.tv_status_enrichment_value)
        btnStatusEnrichmentAction = findViewById(R.id.btn_status_enrichment_action)
        rowStatusNotification = findViewById(R.id.row_status_notification)
        btnStatusNotificationAction = findViewById(R.id.btn_status_notification_action)
        tvStatusCollectionValue = findViewById(R.id.tv_status_collection_value)
        tvStatusCollectionPending = findViewById(R.id.tv_status_collection_pending)
        tvStatusCollectionToggle = findViewById(R.id.tv_status_collection_toggle)
        containerStatusCollectionRecent = findViewById(R.id.container_status_collection_recent)
        aiRuntimeStatusStore = org.fcitx.fcitx5.android.input.ai.ondevice.AiRuntimeStatusStore(this)

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
        tvStyleCategories = findViewById(R.id.tv_style_categories)
        styleSections = StyleReportSections(findViewById(R.id.style_report_sections))
        categoryDistributionContainer = findViewById(R.id.category_distribution_container)

        tvLearningBody = findViewById(R.id.tv_learning_body)
        progressLearning = findViewById(R.id.progress_learning)
        btnLearningAction = findViewById(R.id.btn_learning_action)
        switchLearningAutomatic = findViewById(R.id.switch_learning_automatic)
        tvLearningNotificationHint = findViewById(R.id.tv_learning_notification_hint)

        tvFeedbackPercent = findViewById(R.id.tv_feedback_percent)
        tvFeedbackCounts = findViewById(R.id.tv_feedback_counts)
        tvFeedbackPersonal = findViewById(R.id.tv_feedback_personal)

        btnStorageInfo = findViewById(R.id.btn_storage_info)
        rowStorageBackup = findViewById(R.id.row_storage_backup)

        devSection = findViewById(R.id.dev_section)
        devSectionHeader = findViewById(R.id.dev_section_header)
        devSectionBody = findViewById(R.id.dev_section_body)
        tvDevSectionToggle = findViewById(R.id.tv_dev_section_toggle)
        devSection.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        devSectionHeader.setOnClickListener { toggleDevSection() }
        findViewById<View>(R.id.gemma_dev_experiment_link).setOnClickListener {
            if (!BuildConfig.DEBUG) return@setOnClickListener
            try {
                startActivity(
                    Intent()
                        .setClassName(this, "org.fcitx.fcitx5.android.debug.gemma.GemmaExperimentActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.gemma_vault_action_failed, Toast.LENGTH_SHORT).show()
            }
        }

        btnSyncNow = findViewById(R.id.btn_sync_now)
        btnClearDna = findViewById(R.id.btn_clear_dna)
        configureGemmaVault()
        btnStatusEnrichmentAction.setOnClickListener {
            btnEnrichmentSetup.performClick()
        }
        btnStatusNotificationAction.setOnClickListener {
            openNotificationSettings()
        }
        tvStatusCollectionToggle.setOnClickListener {
            toggleCollectionRecent()
        }
        findViewById<MaterialButton>(R.id.btn_dashboard_loading_retry).setOnClickListener {
            requestDashboardRefresh(animate = true)
        }

        btnSyncNow.setOnClickListener { startSync() }
        btnStorageInfo.setOnClickListener { showStorageInfoDialog() }
        rowStorageBackup.setOnClickListener { openVaultBackup() }

        btnClearDna.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.vault_storage_clear_button)
                .setMessage(R.string.vault_storage_clear_dialog_message)
                .setPositiveButton(R.string.delete) { _, _ ->
                    startClear()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        renderEngineStatusRow()
        renderNotificationStatusRow()
        requestDashboardRefresh(animate = true)
    }

    private fun toggleDevSection() {
        devSectionExpanded = !devSectionExpanded
        devSectionBody.visibility = if (devSectionExpanded) View.VISIBLE else View.GONE
        tvDevSectionToggle.setText(
            if (devSectionExpanded) R.string.vault_dev_section_collapse else R.string.vault_dev_section_expand
        )
    }

    private fun showStorageInfoDialog() {
        val security = latestSecurity
        val keyText = when {
            security == null -> null
            security.isStrongBoxBacked -> getString(R.string.vault_storage_dialog_key_strongbox)
            security.isHardwareBacked -> getString(R.string.vault_storage_dialog_key_tee)
            else -> getString(R.string.vault_storage_dialog_key_software)
        }
        val message = listOfNotNull(keyText, getString(R.string.vault_storage_dialog_body)).joinToString("\n\n")
        AlertDialog.Builder(this)
            .setTitle(R.string.vault_storage_dialog_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openVaultBackup() {
        val intent = Intent().setClassName(
            packageName, "org.fcitx.fcitx5.android.ui.main.ai.backup.VaultBackupActivity"
        )
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // 백업 화면이 없는 빌드(일부 플레이버)에서는 안내만 하고 조용히 넘어간다.
            Toast.makeText(this, R.string.vault_storage_backup_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        enrichmentStatusStore.addListener(enrichmentStatusListener)
        startGemmaSnapshotPolling()
        startEnrichmentPolling()
    }

    override fun onStop() {
        gemmaRefreshJob?.cancel()
        gemmaRefreshJob = null
        enrichmentPollingJob?.cancel()
        enrichmentPollingJob = null
        enrichmentStatusStore.removeListener(enrichmentStatusListener)
        super.onStop()
    }

    /**
     * The graph card's staging progress (chunk index, lease-wait timer) changes without touching
     * [GraphEnrichmentStatusStore]'s prefs, so [enrichmentStatusListener] never fires for it. Polls
     * every [ENRICHMENT_POLL_INTERVAL_MS] while the dashboard is visible and the graph is actually
     * running or waiting for its turn ([enrichmentRunning], set by [renderEnrichment]).
     */
    private fun startEnrichmentPolling() {
        if (enrichmentPollingJob?.isActive == true) return
        enrichmentPollingJob = lifecycleScope.launch {
            while (isActive) {
                delay(ENRICHMENT_POLL_INTERVAL_MS)
                if (enrichmentRunning) requestEnrichmentRefresh()
            }
        }
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
            navBarsBottomInset = navBars.bottom
            appBar.updatePadding(top = statusBars.top)
            scroll.updatePadding(
                left = navBars.left,
                right = navBars.right,
                bottom = 0
            )
            bannerContainer.updatePadding(
                left = navBars.left,
                right = navBars.right,
                bottom = navBars.bottom
            )
            updateScrollBottomInset()
            windowInsets
        }
    }

    private fun updateScrollBottomInset() {
        val scroll = findViewById<NestedScrollView>(R.id.dashboard_scroll)
        val bannerHeight =
            if (bannerContainer.visibility == View.VISIBLE) bannerContainer.height else 0
        scroll.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = if (bannerHeight > 0) bannerHeight else navBarsBottomInset
        }
    }

    private fun checkPendingCelebration() {
        val store = LevelRewardStore(applicationContext)
        val level = store.pendingCelebrationLevel()
        if (level <= 0) return
        store.clearCelebration()
        findViewById<ViewGroup>(R.id.dashboard_root).addView(
            CelebrationOverlayView(this),
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        AlertDialog.Builder(this)
            .setTitle("🎉 레벨업!")
            .setMessage("Lv.$level 달성! 레벨당 포인트 10점이 지급됐어요. 테마 상점에서 쓸 수 있어요.")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun observeGemmaInstallState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelInstaller.state(applicationContext)
                    .collect { state ->
                        val show = org.fcitx.fcitx5.android.ui.main.ai.install.GemmaInstallUiState.shouldShowVaultCard(state)
                        gemmaInstallCard.visibility = if (show) View.VISIBLE else View.GONE
                        if (show) {
                            gemmaInstallStatusView.render(
                                this@TypingDnaDashboardActivity,
                                org.fcitx.fcitx5.android.ui.main.ai.install.GemmaInstallUiState.from(state)
                            )
                        }
                    }
            }
        }
    }

    private fun configureGemmaVault() {
        switchLearningAutomatic.setOnCheckedChangeListener { _, enabled ->
            if (!renderingGemmaSnapshot) {
                changeGemmaAutomaticEnabled(enabled)
            }
        }
        val controller = gemmaPreparationController
        if (controller == null) {
            gemmaVaultCard.visibility = View.GONE
            return
        }
        btnSyncNow.visibility = View.GONE
        gemmaVaultAutomatic.setOnCheckedChangeListener { _, enabled ->
            if (!renderingGemmaSnapshot) {
                changeGemmaAutomaticEnabled(enabled)
            }
        }
        gemmaVaultGenerate.setOnClickListener {
            // Material generation's own manual request is no longer fired from here: the graph
            // worker requests it (via the existing, unmodified GemmaAccumulationScheduler API) once
            // the graph run it starts (inside startSync -> continueWithEnrichment) actually ends, so
            // the two do not race for the same on-device generation lease.
            startSync(gemmaVaultPersonalResult)
        }
        gemmaVaultStop.setOnClickListener {
            changeGemmaAutomaticEnabled(false)
        }
        gemmaVaultModel.setOnClickListener {
            controller.openModelManagement(this)
        }
        renderGemmaActionAvailability()
    }

    private fun startGemmaSnapshotPolling() {
        if (gemmaPreparationController == null || gemmaRefreshJob?.isActive == true) return
        gemmaRefreshJob = lifecycleScope.launch {
            while (isActive) {
                refreshGemmaSnapshot()
                delay(GEMMA_SNAPSHOT_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshGemmaSnapshot() {
        val controller = gemmaPreparationController ?: return
        val requestGeneration = gemmaStateGeneration
        try {
            val snapshot = withContext(Dispatchers.IO) { controller.snapshot() }
            if (
                requestGeneration != gemmaStateGeneration ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) return
            gemmaSnapshot = snapshot
            renderGemmaSnapshot(snapshot)
            renderEngineStatusRow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Timber.w(error, "TypingDnaDashboardActivity Gemma snapshot refresh failed")
            if (requestGeneration != gemmaStateGeneration || isFinishing || isDestroyed) return
            gemmaVaultError.setText(R.string.gemma_vault_state_read_failed)
            gemmaVaultError.visibility = View.VISIBLE
            renderGemmaActionAvailability()
        }
    }

    private fun changeGemmaAutomaticEnabled(enabled: Boolean) {
        val controller = gemmaPreparationController ?: return
        if (gemmaMutationJob?.isActive == true) return
        gemmaStateGeneration++
        gemmaTransientError = null
        gemmaMutationInProgress = true
        renderGemmaActionAvailability()
        gemmaMutationJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { controller.setAutomaticEnabled(enabled) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Timber.w(error, "TypingDnaDashboardActivity Gemma automatic-enabled toggle failed")
                gemmaTransientError = getString(R.string.gemma_vault_action_failed)
            } finally {
                gemmaMutationInProgress = false
                gemmaStateGeneration++
                refreshGemmaSnapshot()
            }
        }
    }

    private fun renderGemmaSnapshot(snapshot: GemmaPreparationSnapshot) {
        renderingGemmaSnapshot = true
        gemmaVaultAutomatic.isChecked = snapshot.automaticEnabled
        renderingGemmaSnapshot = false
        gemmaVaultCount.text = getString(R.string.gemma_vault_count, snapshot.stored)
        gemmaVaultLastRun.text = if (snapshot.lastRunEpochMs > 0L) {
            getString(
                R.string.gemma_vault_last_run,
                TypingDnaSyncStatus.formatTime(snapshot.lastRunEpochMs, System.currentTimeMillis())
            )
        } else {
            getString(R.string.gemma_vault_last_run_none)
        }
        gemmaVaultStatus.text = if (snapshot.manualRequested && !snapshot.running && snapshot.error == null) {
            getString(R.string.gemma_vault_manual_status, snapshot.status)
        } else {
            snapshot.status
        }
        val error = gemmaTransientError ?: snapshot.error
        gemmaVaultError.text = error
        gemmaVaultError.visibility = if (error == null) View.GONE else View.VISIBLE
        gemmaVaultGenerate.setText(R.string.gemma_vault_generate)
        val canStop = snapshot.manualRequested || snapshot.running
        gemmaVaultStop.visibility = if (canStop) View.VISIBLE else View.GONE
        gemmaVaultStopNotice.visibility = if (canStop) View.VISIBLE else View.GONE
        renderGemmaActionAvailability()
        renderLearningCard()
    }

    private fun renderGemmaActionAvailability() {
        val snapshot = gemmaSnapshot
        val mutationInProgress = gemmaMutationInProgress || gemmaMutationJob?.isActive == true
        gemmaVaultAutomatic.isEnabled = !mutationInProgress && snapshot != null
        switchLearningAutomatic.isEnabled = !mutationInProgress && snapshot != null
        gemmaVaultGenerate.isEnabled = snapshot != null &&
            !mutationInProgress &&
            !dashboardBusy &&
            syncJob?.isActive != true &&
            clearJob?.isActive != true
        gemmaVaultStop.isEnabled = !mutationInProgress
        gemmaVaultModel.isEnabled = !mutationInProgress
    }

    override fun onResume() {
        super.onResume()
        renderEngineStatusRow()
        renderNotificationStatusRow()
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

    private fun startSync(personalResult: TextView? = null) {
        if (syncJob?.isActive == true || clearJob?.isActive == true) return
        val ime = org.fcitx.fcitx5.android.input.FcitxInputMethodService.activeInstance
        snapshotGeneration++
        dashboardSyncError.visibility = View.GONE
        personalResult?.apply {
            setText(R.string.gemma_vault_personal_sync_running)
            visibility = View.VISIBLE
        }
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
                val personalResultMessage = when {
                    snapshot.typingStats.totalSentences > before -> getString(
                        R.string.gemma_vault_personal_sync_completed,
                        snapshot.typingStats.totalSentences
                    )
                    else -> getString(
                        R.string.gemma_vault_personal_sync_no_new,
                        snapshot.typingStats.totalSentences
                    )
                }
                val feedbackMessage = if (personalResult != null) {
                    personalResult.apply {
                        text = personalResultMessage
                        visibility = View.VISIBLE
                    }
                    personalResultMessage
                } else {
                    when {
                        snapshot.typingStats.totalSentences > before ->
                            getString(R.string.vault_sync_toast_completed, snapshot.typingStats.totalSentences)
                        snapshot.typingStats.totalSentences > 0 ->
                            getString(R.string.vault_sync_toast_no_new, snapshot.typingStats.totalSentences)
                        ime == null ->
                            getString(R.string.vault_sync_toast_loaded, snapshot.typingStats.totalSentences)
                        else -> getString(R.string.vault_sync_toast_no_new, snapshot.typingStats.totalSentences)
                    }
                }
                if (personalResult == null) {
                    Toast.makeText(this@TypingDnaDashboardActivity, feedbackMessage, Toast.LENGTH_SHORT).show()
                }
                interstitial.showAfterAction()
                continueWithEnrichment()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: TypingDnaPersistenceException) {
                android.util.Log.w("SaegeulAI", "dashboard sync failed: ${error.javaClass.simpleName}")
                showSyncFailure(personalResult)
            } catch (error: Throwable) {
                android.util.Log.w("SaegeulAI", "dashboard sync failed: ${error.javaClass.simpleName}")
                showSyncFailure(personalResult)
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
                Toast.makeText(this@TypingDnaDashboardActivity, R.string.vault_storage_cleared_toast, Toast.LENGTH_SHORT).show()
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

    private suspend fun continueWithEnrichment() {
        val controller = gemmaPreparationController
        if (controller == null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.sync_done_title)
                .setMessage(R.string.enrichment_unavailable_release_build)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        ensureNotificationPermission()
        val scheduled = withContext(Dispatchers.IO) { controller.requestGraphEnrichment() }
        requestEnrichmentRefresh()
        if (scheduled) {
            AlertDialog.Builder(this)
                .setTitle(R.string.enrich_bg_title)
                .setMessage(R.string.enrich_bg_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun renderDashboard(snapshot: DashboardSnapshot, animate: Boolean) {
        val stats = snapshot.typingStats
        tvLevelBadge.text = getString(R.string.vault_level_number, stats.level)
        tvLevelBadge.contentDescription = getString(R.string.vault_level_content_description, stats.level)
        if (stats.levelTitle.isNotBlank()) {
            tvLevelDesc.text = stats.levelTitle
            tvLevelDesc.visibility = View.VISIBLE
        } else {
            tvLevelDesc.visibility = View.GONE
        }

        progressLevel.progress = stats.levelProgressPercent

        tvDashSentences.text = "${stats.totalSentences}"
        tvDashBigrams.text = "${stats.bigramsCount}"
        tvDashEndings.text = "${stats.endingsCount}"
        tvDashPhrases.text = "${stats.phrasesCount}"

        tvLevelProgressText.text = if (stats.level >= TypingDnaLevelCurve.MAX_LEVEL) {
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

        renderHeroLine(stats.totalSentences, snapshot.habit)
        if (snapshot.pointBalance > 0) {
            tvPointsLine.visibility = View.VISIBLE
            tvPointsLine.text = getString(R.string.vault_home_points_chip, snapshot.pointBalance)
        } else {
            tvPointsLine.visibility = View.GONE
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
        latestNgramLastLearnedMs = snapshot.ngramLastLearnedMs
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
        latestSecurity = snapshot.security

        tvVaultSecuritySubtitle.text = when {
            snapshot.security.isStrongBoxBacked -> getString(R.string.vault_security_strongbox)
            snapshot.security.isHardwareBacked -> getString(R.string.vault_security_tee)
            else -> getString(R.string.vault_security_software)
        }

        renderFeedbackCard(metricsSummary)

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

        renderStyleCard(snapshot.frequentWords, emptyMap())
        renderStyleSections(animate)

        renderCategoryDistribution(
            snapshot.typingStats.categoryCounts,
            snapshot.categoryPending,
            snapshot.categoryPendingThreshold
        )
        renderCollectionStatusRow(snapshot)

        lifecycleScope.launch(Dispatchers.IO) {
            VaultWidgetProvider.updateAll(applicationContext)
        }
    }

    /** Hero's one line: total sentences learned (the single "배운 문장" count shown anywhere on this screen), plus the streak when there is one. */
    private fun renderHeroLine(totalSentences: Int, habit: VaultHabitState) {
        tvHabitLine.text = if (habit.streak > 0) {
            getString(R.string.vault_home_learned_line_with_streak, totalSentences, habit.streak)
        } else {
            getString(R.string.vault_home_learned_line, totalSentences)
        }
    }

    /** "추천이 도움이 됐나요" card: acceptance rate and how much of it came from the user's own recorded sentences. */
    private fun renderFeedbackCard(metrics: org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsStore.Summary) {
        val acceptance = AcceptanceCardUiState.from(metrics)
        if (!acceptance.hasData) {
            tvFeedbackPercent.visibility = View.GONE
            tvFeedbackCounts.visibility = View.VISIBLE
            tvFeedbackCounts.text = getString(R.string.vault_feedback_empty)
            tvFeedbackPersonal.visibility = View.GONE
            return
        }
        tvFeedbackPercent.visibility = View.VISIBLE
        tvFeedbackPercent.text = "${acceptance.acceptPercent}%"
        tvFeedbackCounts.visibility = View.VISIBLE
        tvFeedbackCounts.text = getString(
            R.string.vault_feedback_counts_line, acceptance.totalShown, acceptance.totalAccepted
        )
        if (acceptance.totalAccepted > 0) {
            tvFeedbackPersonal.visibility = View.VISIBLE
            tvFeedbackPersonal.text = getString(R.string.vault_feedback_personal_line, acceptance.personalPercent)
        } else {
            tvFeedbackPersonal.visibility = View.GONE
        }
    }

    /** Accumulated records, last 30 days, where I write, style traits - shown on the home by default. */
    private fun renderStyleSections(animate: Boolean) {
        lifecycleScope.launch {
            val data = try {
                withContext(Dispatchers.IO) { StyleReportSections.load() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                android.util.Log.w("SaegeulAI", "style sections load failed: ${error.javaClass.simpleName}")
                null
            }
            if (data == null || isFinishing || isDestroyed) return@launch
            styleSections.render(data, animate)
        }
    }

    /** "내 말투" card: frequent-word chips and the top app categories they're mostly typed in. */
    private fun renderStyleCard(frequentWords: List<String>, categoryCounts: Map<String, Int>) {
        val chips = VaultStyleChips.filterChips(frequentWords)
        chipGroupVaultWords.removeAllViews()
        chips.forEach { word ->
            val chip = Chip(this)
            chip.text = word
            chip.isClickable = false
            chip.isCheckable = false
            chip.isFocusable = false
            chipGroupVaultWords.addView(chip)
        }
        chipGroupVaultWords.visibility = if (chips.isEmpty()) View.GONE else View.VISIBLE

        val categoryLabels = VaultStyleChips.topCategoryIds(categoryCounts)
            .mapNotNull { id -> PersonaRegistry.byId(id)?.let { getString(it.labelRes) } }
        if (categoryLabels.isEmpty()) {
            tvStyleCategories.visibility = View.GONE
        } else {
            tvStyleCategories.visibility = View.VISIBLE
            tvStyleCategories.text = getString(R.string.vault_style_categories_line, categoryLabels.joinToString(" · "))
        }

        cardVaultWords.visibility = if (chips.isEmpty() && categoryLabels.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderEnrichment(enrichment: DashboardEnrichmentSnapshot) {
        currentEnrichment = enrichment
        val controller = gemmaPreparationController
        if (controller == null) {
            tvDashSyncLevel.setText(R.string.enrichment_unavailable_release_build)
            tvDashGraphStats.text = ""
            tvDashEnrichmentAvailability.visibility = View.GONE
            progressEnrichment.visibility = View.GONE
            btnEnrichmentSetup.visibility = View.GONE
            enrichmentRunning = false
            updateActionAvailability()
            renderEnrichmentStatusRow()
            renderNotificationStatusRow()
            renderLearningCard()
            return
        }

        val uiState = GraphEnrichmentUiState.from(
            phase = enrichment.phase,
            failure = enrichment.failure,
            staging = enrichment.staging,
            nowMs = System.currentTimeMillis(),
            automaticEnabled = gemmaSnapshot?.automaticEnabled ?: false,
            lastAppliedMs = enrichment.lastAppliedMs,
            graphNodes = enrichment.graphNodes,
            graphEdges = enrichment.graphEdges,
            graphTopics = enrichment.graphTopics,
            failureDetail = enrichment.failureDetail
        )
        tvDashSyncLevel.text = uiState.title(this)
        tvDashGraphStats.text = uiState.detail(this, appliedAtText = formatAppliedAt(uiState.lastAppliedMs))
            ?: ""
        val guidanceText = uiState.guidance(this) ?: uiState.secondaryGuidance(this)
        if (guidanceText != null) {
            tvDashEnrichmentAvailability.visibility = View.VISIBLE
            tvDashEnrichmentAvailability.text = guidanceText
        } else {
            tvDashEnrichmentAvailability.visibility = View.GONE
        }

        enrichmentRunning = enrichment.phase == GraphEnrichmentPhase.RUNNING
        val showsDeterminateProgress = uiState.kind == GraphEnrichmentUiState.Kind.RUNNING
        progressEnrichment.visibility = if (showsDeterminateProgress) View.VISIBLE else View.GONE
        if (showsDeterminateProgress) {
            progressEnrichment.isIndeterminate = false
            progressEnrichment.max = uiState.progressTotal.coerceAtLeast(1)
            progressEnrichment.setProgressCompat(uiState.progressCurrent, true)
        }
        updateActionAvailability()

        if (uiState.action != null && !graphActionInFlight) {
            btnEnrichmentSetup.visibility = View.VISIBLE
            btnEnrichmentSetup.isEnabled = true
            btnEnrichmentSetup.text = uiState.actionLabel(this)
            btnEnrichmentSetup.setOnClickListener { handleGraphAction(uiState.action) }
        } else if (uiState.action == null) {
            btnEnrichmentSetup.visibility = View.GONE
        }

        renderEnrichmentStatusRow()
        renderNotificationStatusRow()
        renderLearningCard()
    }

    /**
     * Recomputes and renders the "새글이 배우는 중" card from whatever's cached right now
     * ([currentEnrichment], [gemmaSnapshot], the AI engine's own status). Safe to call from any of
     * the several places those individually refresh on their own schedule (enrichment polling, the
     * 1s Gemma snapshot poll, [onResume]) since it always recomputes fresh rather than trusting a
     * stale flag.
     */
    private fun renderLearningCard() {
        val onDeviceAiAvailable = gemmaPreparationController != null
        val nowMs = System.currentTimeMillis()
        val engineStatus = DashboardStatusText.engineStatus(aiRuntimeStatusStore.snapshot(), OnDeviceAiSupport.isSupported, nowMs)
        val enrichment = currentEnrichment
        val graph = if (!onDeviceAiAvailable || enrichment == null) null else GraphEnrichmentUiState.from(
            phase = enrichment.phase,
            failure = enrichment.failure,
            staging = enrichment.staging,
            nowMs = nowMs,
            automaticEnabled = gemmaSnapshot?.automaticEnabled ?: false,
            lastAppliedMs = enrichment.lastAppliedMs,
            graphNodes = enrichment.graphNodes,
            graphEdges = enrichment.graphEdges,
            graphTopics = enrichment.graphTopics,
            failureDetail = enrichment.failureDetail
        )
        val notificationBlocked = DashboardStatusText.notificationBlocked(
            lastBlockedAtMs = org.fcitx.fcitx5.android.utils.BackgroundProgressNotifier.lastBlockedAtMs(this),
            permissionGranted = hasNotificationPermission()
        )
        val appliedAtMs = maxOf(enrichment?.lastAppliedMs ?: 0L, latestNgramLastLearnedMs)
        val materialActive = gemmaSnapshot?.let { it.running || it.queued } ?: false
        val state = LearningStatusUiState.from(
            onDeviceAiAvailable = onDeviceAiAvailable,
            engineFailed = engineStatus.state == DashboardStatusText.EngineState.FAILED,
            graph = graph,
            materialActive = materialActive,
            automaticEnabled = gemmaSnapshot?.automaticEnabled ?: false,
            notificationBlocked = notificationBlocked,
            appliedAtMs = appliedAtMs
        )
        renderLearningState(state)
    }

    private fun renderLearningState(state: LearningStatusUiState) {
        tvLearningBody.text = when {
            state.reasonRes != null -> getString(state.bodyRes, getString(state.reasonRes))
            state.bodyIntArg != null -> getString(state.bodyRes, state.bodyIntArg)
            state.tier == LearningStatusUiState.Tier.UP_TO_DATE && state.appliedAtMs > 0L ->
                getString(state.bodyRes, formatAppliedAtPlain(state.appliedAtMs))
            else -> getString(state.bodyRes)
        }

        progressLearning.visibility = if (state.showProgress) View.VISIBLE else View.GONE
        if (state.showProgress) {
            progressLearning.isIndeterminate = false
            progressLearning.max = state.progressTotal.coerceAtLeast(1)
            progressLearning.setProgressCompat(state.progressCurrent, true)
        }

        if (state.buttonLabelRes != null && state.buttonAction != null && !graphActionInFlight) {
            btnLearningAction.visibility = View.VISIBLE
            btnLearningAction.isEnabled = !dashboardBusy
            btnLearningAction.text = getString(state.buttonLabelRes)
            val action = state.buttonAction
            btnLearningAction.setOnClickListener { handleLearningAction(action) }
        } else {
            btnLearningAction.visibility = View.GONE
        }

        switchLearningAutomatic.visibility = if (state.showAutomaticSwitch) View.VISIBLE else View.GONE
        if (state.showAutomaticSwitch) {
            renderingGemmaSnapshot = true
            switchLearningAutomatic.isChecked = state.automaticChecked
            renderingGemmaSnapshot = false
        }

        tvLearningNotificationHint.visibility = if (state.showNotificationHint) View.VISIBLE else View.GONE
    }

    /**
     * Dispatches the unified learning card's single button. "Apply now" (RESUME/CREATE/RETRY)
     * reuses the existing [startSync] flow (personal analysis, then a graph request), matching what
     * the button already means everywhere else on this screen; STOP and OPEN_MODEL_MANAGEMENT are
     * graph-only and go straight through [handleGraphAction].
     */
    private fun handleLearningAction(action: GraphEnrichmentUiState.Action) {
        when (action) {
            GraphEnrichmentUiState.Action.RESUME,
            GraphEnrichmentUiState.Action.CREATE,
            GraphEnrichmentUiState.Action.RETRY -> startSync()
            GraphEnrichmentUiState.Action.STOP,
            GraphEnrichmentUiState.Action.OPEN_MODEL_MANAGEMENT -> handleGraphAction(action)
            GraphEnrichmentUiState.Action.PRIORITIZE_GRAPH -> Unit
        }
    }

    /** "오늘 HH:mm" / "어제 HH:mm" / "M월 d일" (or the English equivalent), or "" when [ms] is 0 (not yet applied). */
    private fun formatAppliedAt(ms: Long): String {
        if (ms <= 0L) return ""
        val nowMs = System.currentTimeMillis()
        val target = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        val now = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(target.time)
        val dayDiff = now.get(java.util.Calendar.DAY_OF_YEAR) - target.get(java.util.Calendar.DAY_OF_YEAR)
        return when {
            now.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) && dayDiff == 0 ->
                getString(R.string.enrichment_applied_today, time)
            now.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) && dayDiff == 1 ->
                getString(R.string.enrichment_applied_yesterday, time)
            else -> getString(
                R.string.enrichment_applied_date,
                target.get(java.util.Calendar.MONTH) + 1,
                target.get(java.util.Calendar.DAY_OF_MONTH)
            )
        }
    }

    /**
     * Same day math as [formatAppliedAt], but without its "applied" suffix, for the "새글이 배우는 중"
     * card's "모두 반영됐어요 · {오늘 13:06 / 어제 / 9월 21일}" line, which already says "반영됐어요" once.
     */
    private fun formatAppliedAtPlain(ms: Long): String {
        val nowMs = System.currentTimeMillis()
        val target = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        val now = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(target.time)
        val dayDiff = now.get(java.util.Calendar.DAY_OF_YEAR) - target.get(java.util.Calendar.DAY_OF_YEAR)
        return when {
            now.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) && dayDiff == 0 ->
                getString(R.string.vault_applied_at_today, time)
            now.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) && dayDiff == 1 ->
                getString(R.string.vault_applied_at_yesterday, time)
            else -> getString(
                R.string.vault_applied_at_date,
                target.get(java.util.Calendar.MONTH) + 1,
                target.get(java.util.Calendar.DAY_OF_MONTH)
            )
        }
    }

    /** Dispatches the relationship-graph card's single action button per [GraphEnrichmentUiState.Action]. */
    private fun handleGraphAction(action: GraphEnrichmentUiState.Action?) {
        val controller = gemmaPreparationController ?: return
        if (action == null || graphActionInFlight) return
        graphActionInFlight = true
        btnEnrichmentSetup.isEnabled = false
        lifecycleScope.launch {
            try {
                when (action) {
                    GraphEnrichmentUiState.Action.STOP ->
                        withContext(Dispatchers.IO) { controller.stopManualGraphEnrichment() }
                    GraphEnrichmentUiState.Action.PRIORITIZE_GRAPH ->
                        withContext(Dispatchers.IO) { controller.prioritizeGraphOverMaterial() }
                    GraphEnrichmentUiState.Action.RESUME,
                    GraphEnrichmentUiState.Action.CREATE,
                    GraphEnrichmentUiState.Action.RETRY -> {
                        ensureNotificationPermission()
                        withContext(Dispatchers.IO) { controller.requestGraphEnrichment() }
                    }
                    GraphEnrichmentUiState.Action.OPEN_MODEL_MANAGEMENT ->
                        org.fcitx.fcitx5.android.ui.main.ai.install.GemmaInstallFlow.start(this@TypingDnaDashboardActivity)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Timber.w(error, "TypingDnaDashboardActivity graph enrichment action failed action=%s", action)
                Toast.makeText(this@TypingDnaDashboardActivity, R.string.gemma_vault_action_failed, Toast.LENGTH_SHORT).show()
            } finally {
                graphActionInFlight = false
                requestEnrichmentRefresh()
            }
        }
    }

    /** Row (2) of the "right now" status card: a one-line summary of [renderEnrichment]'s state. */
    private fun renderEnrichmentStatusRow() {
        val phaseText = tvDashSyncLevel.text
        val reasonText = if (tvDashEnrichmentAvailability.visibility == View.VISIBLE) {
            tvDashEnrichmentAvailability.text
        } else null
        tvStatusEnrichmentValue.text = if (reasonText.isNullOrEmpty()) {
            phaseText
        } else {
            "$phaseText · $reasonText"
        }
        btnStatusEnrichmentAction.visibility = btnEnrichmentSetup.visibility
    }

    /** Row (3) of the "right now" status card: only shown while notifications are blocked. */
    private fun renderNotificationStatusRow() {
        val blocked = DashboardStatusText.notificationBlocked(
            lastBlockedAtMs = org.fcitx.fcitx5.android.utils.BackgroundProgressNotifier.lastBlockedAtMs(this),
            permissionGranted = hasNotificationPermission()
        )
        rowStatusNotification.visibility = if (blocked) View.VISIBLE else View.GONE
    }

    /** Row (1) of the "right now" status card: the automatic-suggestion engine's latest status. */
    private fun renderEngineStatusRow() {
        val nowMs = System.currentTimeMillis()
        val status = DashboardStatusText.engineStatus(aiRuntimeStatusStore.snapshot(), OnDeviceAiSupport.isSupported, nowMs)
        tvStatusEngineValue.text = when (status.state) {
            DashboardStatusText.EngineState.UNSUPPORTED_RELEASE ->
                getString(R.string.privacy_ai_automatic_release_summary)
            DashboardStatusText.EngineState.NOT_ATTEMPTED ->
                "${getString(R.string.dashboard_status_engine_not_attempted)}\n${getString(R.string.dashboard_status_engine_hint)}"
            DashboardStatusText.EngineState.READY -> {
                val relative = DateUtils.getRelativeTimeSpanString(
                    nowMs - status.ageMs, nowMs, DateUtils.SECOND_IN_MILLIS
                )
                val base = getString(
                    R.string.dashboard_status_engine_ready,
                    (status.backend ?: "cpu").uppercase(),
                    relative
                )
                if (status.recoveryCount > 0) {
                    base + getString(R.string.dashboard_status_engine_recovery_suffix, status.recoveryCount)
                } else base
            }
            DashboardStatusText.EngineState.FAILED -> {
                val relative = DateUtils.getRelativeTimeSpanString(
                    nowMs - status.ageMs, nowMs, DateUtils.SECOND_IN_MILLIS
                )
                val failureText = status.failureCode?.let {
                    org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceFailureText.of(it, resources)
                } ?: getString(R.string.enrichment_status_failed)
                val base = getString(R.string.dashboard_status_engine_failed, failureText, relative)
                val withRecovery = if (status.recoveryCount > 0) {
                    base + getString(R.string.dashboard_status_engine_recovery_suffix, status.recoveryCount)
                } else base
                "$withRecovery\n${getString(R.string.dashboard_status_engine_hint)}"
            }
        }
        renderLearningCard()
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun openNotificationSettings() {
        startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
        })
    }

    private fun setDashboardBusy(busy: Boolean) {
        dashboardBusy = busy
        dashboardSyncBusy.visibility = if (busy) View.VISIBLE else View.GONE
        updateActionAvailability()
        renderGemmaActionAvailability()
    }

    private fun updateActionAvailability() {
        btnSyncNow.isEnabled = !dashboardBusy && !enrichmentRunning
        btnClearDna.isEnabled = !dashboardBusy
        btnLearningAction.isEnabled = !dashboardBusy
    }

    private fun showSyncFailure(personalResult: TextView? = null) {
        if (isFinishing || isDestroyed) return
        dashboardSyncError.visibility = View.VISIBLE
        personalResult?.apply {
            setText(R.string.gemma_vault_personal_sync_failed)
            visibility = View.VISIBLE
        }
        if (personalResult == null) {
            Toast.makeText(this, R.string.typing_dna_persistence_failed, Toast.LENGTH_SHORT).show()
        }
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

    /** Renders one row per [PersonaRegistry.all] entry: share of analyzed sentences and pending count. */
    private fun renderCategoryDistribution(
        categoryCounts: Map<String, Int>,
        categoryPending: Map<String, Int>,
        pendingThreshold: Int
    ) {
        categoryDistributionContainer.removeAllViews()
        val total = categoryCounts.values.sum()
        PersonaRegistry.all.forEach { persona ->
            val count = categoryCounts[persona.id] ?: 0
            val pending = categoryPending[persona.id] ?: 0
            val row = layoutInflater.inflate(
                R.layout.view_dashboard_category_row, categoryDistributionContainer, false
            )
            row.findViewById<TextView>(R.id.tv_category_row_label).text = getString(persona.labelRes)
            val barContainer = row.findViewById<View>(R.id.container_category_row_bar)
            val fillView = row.findViewById<View>(R.id.view_category_row_fill)
            val valueText = row.findViewById<TextView>(R.id.tv_category_row_value)
            val emptyText = row.findViewById<TextView>(R.id.tv_category_row_empty)
            if (count <= 0 && pending <= 0) {
                barContainer.visibility = View.GONE
                valueText.visibility = View.GONE
                emptyText.visibility = View.VISIBLE
            } else {
                barContainer.visibility = View.VISIBLE
                valueText.visibility = View.VISIBLE
                emptyText.visibility = View.GONE
                val ratio = if (total > 0) count.toFloat() / total else 0f
                val params = fillView.layoutParams as LinearLayout.LayoutParams
                params.weight = ratio.coerceIn(0f, 1f)
                fillView.layoutParams = params
                val percent = (ratio * 100).roundToInt()
                valueText.text = if (pending > 0) {
                    getString(R.string.vault_category_row_value_with_pending, percent, pending, pendingThreshold)
                } else {
                    "$percent%"
                }
            }
            categoryDistributionContainer.addView(row)
        }
    }

    /** Row (4) of the "right now" status card: today's collection counters and pending queue. */
    private fun renderCollectionStatusRow(snapshot: DashboardSnapshot) {
        val counters = snapshot.collectionToday
        val learnedText = getString(R.string.dashboard_status_collection_learned, counters.emitted)
        val droppedTotal = counters.droppedByReason.values.sum()
        tvStatusCollectionValue.text = if (droppedTotal > 0) {
            val detail = counters.droppedByReason.entries.joinToString(" · ") { (reason, count) ->
                "${dropReasonLabel(reason)} $count"
            }
            val droppedText = getString(R.string.dashboard_status_collection_dropped_count, droppedTotal)
            "$learnedText · $droppedText($detail)"
        } else {
            learnedText
        }

        val pendingSummary = PersonaRegistry.all.mapNotNull { persona ->
            val pending = snapshot.categoryPending[persona.id] ?: 0
            if (pending > 0) "${getString(persona.labelRes)} $pending/${snapshot.categoryPendingThreshold}" else null
        }
        if (pendingSummary.isEmpty()) {
            tvStatusCollectionPending.visibility = View.GONE
        } else {
            tvStatusCollectionPending.visibility = View.VISIBLE
            tvStatusCollectionPending.text = pendingSummary.joinToString(" · ")
        }

        if (collectionRecentExpanded) {
            renderRecentCollectionEvents()
        }
    }

    private fun toggleCollectionRecent() {
        collectionRecentExpanded = !collectionRecentExpanded
        tvStatusCollectionToggle.setText(
            if (collectionRecentExpanded) R.string.dashboard_status_collection_recent_hide
            else R.string.dashboard_status_collection_recent_show
        )
        containerStatusCollectionRecent.visibility = if (collectionRecentExpanded) View.VISIBLE else View.GONE
        if (collectionRecentExpanded) {
            renderRecentCollectionEvents()
        }
    }

    private fun renderRecentCollectionEvents() {
        containerStatusCollectionRecent.removeAllViews()
        val events = org.fcitx.fcitx5.android.FcitxApplication.getInstance().collectionDiagnostics.recent()
        if (events.isEmpty()) {
            val row = layoutInflater.inflate(
                R.layout.view_dashboard_collection_event_row, containerStatusCollectionRecent, false
            ) as TextView
            row.setText(R.string.dashboard_status_collection_recent_empty)
            containerStatusCollectionRecent.addView(row)
            return
        }
        events.takeLast(10).asReversed().forEach { event ->
            val time = DateUtils.formatDateTime(this, event.atMs, DateUtils.FORMAT_SHOW_TIME)
            val kindLabel = getString(collectionEventKindRes(event.kind))
            val detail = when {
                event.reason != null -> dropReasonLabel(event.reason)
                event.category != null -> PersonaRegistry.byId(event.category)?.let { getString(it.labelRes) }
                    ?: event.category
                else -> null
            }
            val row = layoutInflater.inflate(
                R.layout.view_dashboard_collection_event_row, containerStatusCollectionRecent, false
            ) as TextView
            row.text = if (detail != null) "$time · $kindLabel · $detail" else "$time · $kindLabel"
            containerStatusCollectionRecent.addView(row)
        }
    }

    private fun dropReasonLabel(reason: String): String = getString(
        when (reason) {
            "privacy" -> R.string.collection_drop_reason_privacy
            "short" -> R.string.collection_drop_reason_short
            "backspace" -> R.string.collection_drop_reason_backspace
            "editorSwitch" -> R.string.collection_drop_reason_editor_switch
            "duplicate" -> R.string.collection_drop_reason_duplicate
            "blank" -> R.string.collection_drop_reason_blank
            else -> R.string.collection_drop_reason_unknown
        }
    )

    private fun collectionEventKindRes(kind: String): Int = when (kind) {
        "emitted" -> R.string.collection_event_kind_emitted
        "dropped" -> R.string.collection_event_kind_dropped
        "batchReady" -> R.string.collection_event_kind_batch_ready
        "compiled" -> R.string.collection_event_kind_compiled
        else -> R.string.collection_event_kind_emitted
    }

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 1001
        private const val GEMMA_SNAPSHOT_INTERVAL_MS = 1_000L
        private const val ENRICHMENT_POLL_INTERVAL_MS = 2_000L
    }
}
