/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ads.DashboardBannerController
import org.fcitx.fcitx5.android.ads.TypingDnaInterstitialController
import org.fcitx.fcitx5.android.data.points.LevelRewardStore
import org.fcitx.fcitx5.android.input.ai.TypingDnaPersistenceException
import org.fcitx.fcitx5.android.input.ai.ondevice.AiRuntimeStatusStore
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAiSupport
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentPhase
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentStatusStore
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentUiState
import org.fcitx.fcitx5.android.ui.main.ai.dashboard.ActivityStatusSection
import org.fcitx.fcitx5.android.ui.main.ai.dashboard.DeveloperSection
import org.fcitx.fcitx5.android.ui.main.ai.dashboard.EnrichmentCardSection
import org.fcitx.fcitx5.android.ui.main.ai.dashboard.FeedbackCardSection
import org.fcitx.fcitx5.android.ui.main.ai.dashboard.GemmaInstallCardSection
import org.fcitx.fcitx5.android.ui.main.ai.dashboard.GemmaVaultSection
import org.fcitx.fcitx5.android.ui.main.ai.dashboard.LearningCardSection
import org.fcitx.fcitx5.android.ui.main.ai.dashboard.LearningStatsSection
import org.fcitx.fcitx5.android.ui.main.ai.dashboard.LevelHeroSection
import org.fcitx.fcitx5.android.ui.main.ai.dashboard.StorageCardSection
import org.fcitx.fcitx5.android.ui.main.ai.dashboard.StyleCardSection
import org.fcitx.fcitx5.android.ui.main.ai.dashboard.VaultMetricsSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Dedicated visual dashboard for Typing DNA linguistic evolution.
 * Displays real-time accumulation charts, tone & persona balances,
 * top bigram transitions, and on-device privacy guarantee metrics.
 */
class TypingDnaDashboardActivity : AppCompatActivity() {

    private lateinit var snapshotReader: DashboardSnapshotReader
    private lateinit var levelHero: LevelHeroSection
    private lateinit var learningCard: LearningCardSection
    private lateinit var feedbackCard: FeedbackCardSection
    private lateinit var styleCard: StyleCardSection
    private lateinit var storageCard: StorageCardSection
    private lateinit var activityStatus: ActivityStatusSection
    private lateinit var gemmaVault: GemmaVaultSection
    private lateinit var enrichmentCard: EnrichmentCardSection
    private lateinit var vaultMetrics: VaultMetricsSection
    private lateinit var learningStats: LearningStatsSection

    private lateinit var aiRuntimeStatusStore: AiRuntimeStatusStore
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
    private var gemmaRefreshJob: Job? = null
    private var gemmaMutationJob: Job? = null
    private var gemmaStateGeneration = 0L
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

    private var currentEnrichment: DashboardEnrichmentSnapshot? = null
    private var latestNgramLastLearnedMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_typing_dna_dashboard)
        applySystemBarInsets()

        snapshotReader = DashboardSnapshotReader(applicationContext)
        checkPendingCelebration()
        setupAds()
        enrichmentStatusStore = GraphEnrichmentStatusStore(this)
        gemmaPreparationController = GemmaPreparationFactory.create(applicationContext)
        aiRuntimeStatusStore = AiRuntimeStatusStore(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        bindLoadingViews()
        bindSections()
        configureGemmaVault()

        renderEngineStatusRow()
        renderNotificationStatusRow()
        requestDashboardRefresh(animate = true)
    }

    private fun setupAds() {
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
    }

    private fun bindLoadingViews() {
        dashboardContent = findViewById(R.id.dashboard_content)
        dashboardLoading = findViewById(R.id.dashboard_loading)
        dashboardLoadingMessage = findViewById(R.id.dashboard_loading_message)
        dashboardSyncBusy = findViewById(R.id.dashboard_sync_busy)
        dashboardSyncError = findViewById(R.id.dashboard_sync_error)
        findViewById<MaterialButton>(R.id.btn_dashboard_loading_retry).setOnClickListener {
            requestDashboardRefresh(animate = true)
        }
    }

    private fun bindSections() {
        GemmaInstallCardSection(this).observe()
        gemmaVault = GemmaVaultSection(this)
        levelHero = LevelHeroSection(this)
        learningStats = LearningStatsSection(this)
        enrichmentCard = EnrichmentCardSection(this, onSyncNow = { startSync() })
        activityStatus = ActivityStatusSection(this, onEnrichmentAction = { enrichmentCard.performActionClick() })
        vaultMetrics = VaultMetricsSection(this)
        styleCard = StyleCardSection(this)
        learningCard = LearningCardSection(
            this,
            onAction = ::handleLearningAction,
            onAutomaticChanged = ::changeGemmaAutomaticEnabled
        )
        feedbackCard = FeedbackCardSection(this)
        storageCard = StorageCardSection(this, onClearConfirmed = ::startClear)
        DeveloperSection(this).bind()
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
            .setTitle(R.string.vault_level_up_title)
            .setMessage(getString(R.string.vault_level_up_message, level))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun configureGemmaVault() {
        val controller = gemmaPreparationController
        if (controller == null) {
            gemmaVault.hide()
            return
        }
        enrichmentCard.hideSyncNow()
        gemmaVault.bind(
            onAutomaticChanged = ::changeGemmaAutomaticEnabled,
            onGenerate = { personalResult -> startSync(personalResult) },
            onOpenModelManagement = { controller.openModelManagement(this) }
        )
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
            gemmaVault.showReadFailure()
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
        gemmaVault.render(snapshot, gemmaTransientError)
        renderGemmaActionAvailability()
        renderLearningCard()
    }

    private fun renderGemmaActionAvailability() {
        val snapshot = gemmaSnapshot
        val mutationInProgress = gemmaMutationInProgress || gemmaMutationJob?.isActive == true
        gemmaVault.renderAvailability(
            hasSnapshot = snapshot != null,
            mutationInProgress = mutationInProgress,
            canGenerate = snapshot != null &&
                !mutationInProgress &&
                !dashboardBusy &&
                syncJob?.isActive != true &&
                clearJob?.isActive != true
        )
        learningCard.setAutomaticSwitchEnabled(!mutationInProgress && snapshot != null)
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
        levelHero.render(snapshot)
        learningStats.render(snapshot, animate)
        latestNgramLastLearnedMs = snapshot.ngramLastLearnedMs
        renderEnrichment(snapshot.enrichment)

        storageCard.security = snapshot.security
        vaultMetrics.renderSecurity(snapshot.security)
        feedbackCard.render(snapshot.metrics)
        vaultMetrics.renderMetrics(snapshot.metrics)

        styleCard.render(snapshot.frequentWords, emptyMap())
        styleCard.renderReportSections(animate)

        learningStats.renderCategoryDistribution(
            snapshot.typingStats.categoryCounts,
            snapshot.categoryPending,
            snapshot.categoryPendingThreshold
        )
        activityStatus.renderCollection(snapshot)

        lifecycleScope.launch(Dispatchers.IO) {
            VaultWidgetProvider.updateAll(applicationContext)
        }
    }

    private fun graphUiState(enrichment: DashboardEnrichmentSnapshot, nowMs: Long) = GraphEnrichmentUiState.from(
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

    private fun renderEnrichment(enrichment: DashboardEnrichmentSnapshot) {
        currentEnrichment = enrichment
        if (gemmaPreparationController == null) {
            enrichmentCard.renderUnavailable()
            enrichmentRunning = false
        } else {
            enrichmentCard.render(
                graphUiState(enrichment, System.currentTimeMillis()),
                graphActionInFlight,
                onAction = ::handleGraphAction
            )
            enrichmentRunning = enrichment.phase == GraphEnrichmentPhase.RUNNING
        }
        updateActionAvailability()
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
        val graph = if (!onDeviceAiAvailable || enrichment == null) null else graphUiState(enrichment, nowMs)
        val appliedAtMs = maxOf(enrichment?.lastAppliedMs ?: 0L, latestNgramLastLearnedMs)
        val materialActive = gemmaSnapshot?.let { it.running || it.queued } ?: false
        val state = LearningStatusUiState.from(
            onDeviceAiAvailable = onDeviceAiAvailable,
            engineFailed = engineStatus.state == DashboardStatusText.EngineState.FAILED,
            graph = graph,
            materialActive = materialActive,
            automaticEnabled = gemmaSnapshot?.automaticEnabled ?: false,
            notificationBlocked = notificationBlocked(),
            appliedAtMs = appliedAtMs
        )
        learningCard.render(state, graphActionInFlight, dashboardBusy)
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

    /** Dispatches the relationship-graph card's single action button per [GraphEnrichmentUiState.Action]. */
    private fun handleGraphAction(action: GraphEnrichmentUiState.Action?) {
        val controller = gemmaPreparationController ?: return
        if (action == null || graphActionInFlight) return
        graphActionInFlight = true
        enrichmentCard.disableAction()
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

    private fun renderEnrichmentStatusRow() {
        activityStatus.renderEnrichment(
            enrichmentCard.phaseText,
            enrichmentCard.reasonText,
            enrichmentCard.actionVisibility
        )
    }

    private fun renderNotificationStatusRow() {
        activityStatus.renderNotification(notificationBlocked())
    }

    private fun renderEngineStatusRow() {
        val nowMs = System.currentTimeMillis()
        activityStatus.renderEngine(
            DashboardStatusText.engineStatus(aiRuntimeStatusStore.snapshot(), OnDeviceAiSupport.isSupported, nowMs),
            nowMs
        )
        renderLearningCard()
    }

    private fun notificationBlocked(): Boolean = DashboardStatusText.notificationBlocked(
        lastBlockedAtMs = org.fcitx.fcitx5.android.utils.BackgroundProgressNotifier.lastBlockedAtMs(this),
        permissionGranted = hasNotificationPermission()
    )

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun setDashboardBusy(busy: Boolean) {
        dashboardBusy = busy
        dashboardSyncBusy.visibility = if (busy) View.VISIBLE else View.GONE
        updateActionAvailability()
        renderGemmaActionAvailability()
    }

    private fun updateActionAvailability() {
        enrichmentCard.setSyncNowEnabled(!dashboardBusy && !enrichmentRunning)
        storageCard.setClearEnabled(!dashboardBusy)
        learningCard.setActionEnabled(!dashboardBusy)
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

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 1001
        private const val GEMMA_SNAPSHOT_INTERVAL_MS = 1_000L
        private const val ENRICHMENT_POLL_INTERVAL_MS = 2_000L
    }
}
