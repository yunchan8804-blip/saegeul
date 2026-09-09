/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.TypingDnaStats

/**
 * Embedded home card preference for MainFragment.
 * Renders user's real-time Typing DNA evolution level, sentence counts,
 * and launches the dedicated [TypingDnaDashboardActivity].
 */
class TypingDnaCardPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : Preference(context, attrs, defStyleAttr) {

    private var refreshScope: CoroutineScope? = null
    private var refreshJob: Job? = null
    private var snapshot: TypingDnaCardSnapshot? = null
    private var latestLoadFailed = false
    private var refreshPending = true
    private var refreshQueued = false
    private var attachmentGeneration = 0L
    private var lastRenderedChartStats: TypingDnaStats? = null

    init {
        layoutResource = R.layout.view_typing_dna_card_preference
        isSelectable = true
        isIconSpaceReserved = false
    }

    fun refresh() {
        requestRefresh()
    }

    override fun onAttached() {
        super.onAttached()
        attachmentGeneration++
        refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        if (refreshPending) {
            refreshPending = false
            requestRefresh()
        }
    }

    override fun onDetached() {
        refreshJob?.cancel()
        refreshJob = null
        refreshScope?.cancel()
        refreshScope = null
        refreshQueued = false
        refreshPending = true
        super.onDetached()
    }

    override fun onClick() {
        launchDashboard()
    }

    private fun launchDashboard() {
        val ctx = context
        val intent = Intent(ctx, TypingDnaDashboardActivity::class.java)
        if (ctx !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    private fun requestRefresh() {
        val scope = refreshScope
        if (scope == null) {
            refreshPending = true
            return
        }
        if (refreshJob?.isActive == true) {
            refreshQueued = true
            return
        }
        refreshPending = false
        val generation = attachmentGeneration
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                do {
                    refreshQueued = false
                    try {
                        snapshot = withContext(Dispatchers.IO) { TypingDnaCardSnapshotReader.read() }
                        latestLoadFailed = false
                        notifyChanged()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        Log.w("SaegeulAI", "typing dna card refresh failed: ${error.javaClass.simpleName}")
                        latestLoadFailed = true
                        notifyChanged()
                    }
                } while (refreshQueued && isActive)
            } finally {
                if (
                    attachmentGeneration == generation &&
                    refreshScope === scope &&
                    refreshJob === job
                ) {
                    refreshJob = null
                }
            }
        }
        refreshJob = job
        job.start()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val card = holder.itemView.findViewById<MaterialCardView>(R.id.card_typing_dna)
        val tvLevelBadge = holder.itemView.findViewById<TextView>(R.id.tv_main_card_level_badge)
        val tvTitle = holder.itemView.findViewById<TextView>(R.id.tv_main_card_title)
        val tvSummary = holder.itemView.findViewById<TextView>(R.id.tv_main_card_summary)
        val progressBar = holder.itemView.findViewById<LinearProgressIndicator>(R.id.progress_main_card)

        val tvMetricSentences = holder.itemView.findViewById<TextView>(R.id.tv_metric_sentences)
        val tvMetricBigrams = holder.itemView.findViewById<TextView>(R.id.tv_metric_bigrams)
        val tvMetricEndings = holder.itemView.findViewById<TextView>(R.id.tv_metric_endings)
        val tvMetricPhrases = holder.itemView.findViewById<TextView>(R.id.tv_metric_phrases)
        val btnMore = holder.itemView.findViewById<View>(R.id.btn_dna_card_more)
        val miniChart = holder.itemView.findViewById<TypingDnaChartView>(R.id.mini_chart)
        miniChart?.setCompact(true)

        val currentSnapshot = snapshot
        if (currentSnapshot == null) {
            bindWithoutSnapshot(
                holder.itemView,
                tvLevelBadge,
                tvTitle,
                tvSummary,
                progressBar,
                tvMetricSentences,
                tvMetricBigrams,
                tvMetricEndings,
                tvMetricPhrases,
                miniChart
            )
        } else {
            bindSnapshot(
                holder.itemView,
                currentSnapshot,
                latestLoadFailed,
                tvLevelBadge,
                tvTitle,
                tvSummary,
                progressBar,
                tvMetricSentences,
                tvMetricBigrams,
                tvMetricEndings,
                tvMetricPhrases,
                miniChart
            )
        }

        val clickListener = View.OnClickListener { launchDashboard() }
        holder.itemView.setOnClickListener(clickListener)
        card?.setOnClickListener(clickListener)
        btnMore?.setOnClickListener(clickListener)
    }

    private fun bindWithoutSnapshot(
        itemView: View,
        levelBadge: TextView?,
        title: TextView?,
        summary: TextView?,
        progressBar: LinearProgressIndicator?,
        metricSentences: TextView?,
        metricBigrams: TextView?,
        metricEndings: TextView?,
        metricPhrases: TextView?,
        miniChart: TypingDnaChartView?
    ) {
        val failed = latestLoadFailed
        levelBadge?.text = context.getString(R.string.typing_dna_card_value_unknown)
        levelBadge?.contentDescription = context.getString(R.string.typing_dna_card_value_unknown)
        title?.setText(if (failed) R.string.typing_dna_card_load_failed_title else R.string.typing_dna_card_loading_title)
        summary?.setText(if (failed) R.string.typing_dna_card_load_failed_summary else R.string.typing_dna_card_loading_summary)
        progressBar?.apply {
            visibility = if (failed) View.INVISIBLE else View.VISIBLE
            isIndeterminate = !failed
        }
        metricSentences?.setText(R.string.typing_dna_card_metric_sentences_unknown)
        metricBigrams?.setText(R.string.typing_dna_card_metric_bigrams_unknown)
        metricEndings?.setText(R.string.typing_dna_card_metric_endings_unknown)
        metricPhrases?.setText(R.string.typing_dna_card_metric_phrases_unknown)
        miniChart?.visibility = View.GONE
        itemView.contentDescription = context.getString(
            if (failed) R.string.typing_dna_card_load_failed_title else R.string.typing_dna_card_loading_title
        )
    }

    private fun bindSnapshot(
        itemView: View,
        snapshot: TypingDnaCardSnapshot,
        loadFailed: Boolean,
        levelBadge: TextView?,
        title: TextView?,
        summary: TextView?,
        progressBar: LinearProgressIndicator?,
        metricSentences: TextView?,
        metricBigrams: TextView?,
        metricEndings: TextView?,
        metricPhrases: TextView?,
        miniChart: TypingDnaChartView?
    ) {
        val stats = snapshot.stats
        levelBadge?.text = "Lv.${stats.level}"
        levelBadge?.contentDescription = "학습 레벨 ${stats.level}"
        title?.text = if (loadFailed) {
            context.getString(R.string.typing_dna_card_load_failed_title)
        } else {
            "${stats.levelTitle} · 온디바이스 학습 중"
        }
        summary?.text = buildString {
            if (stats.totalSentences == 0) {
                append("키보드를 사용하면 내 말투와 어휘 습관이 이곳에 축적됩니다.")
                if (snapshot.ngramUnigrams >= 1) {
                    append(context.getString(R.string.typing_dna_card_ngram_suffix, snapshot.ngramUnigrams))
                }
            } else {
                append("분석 문장 ${stats.totalSentences}개 · 단어쌍 ${stats.bigramsCount}개 · 종결어미 ${stats.endingsCount}개")
                append(context.getString(R.string.typing_dna_card_ngram_suffix, snapshot.ngramUnigrams))
            }
            if (snapshot.pendingSentences >= 1) {
                append(context.getString(R.string.typing_dna_card_pending_suffix, snapshot.pendingSentences))
            }
            if (snapshot.isHardwareBacked) {
                append(context.getString(R.string.typing_dna_card_hardware_suffix))
            }
        }
        progressBar?.apply {
            visibility = View.VISIBLE
            isIndeterminate = false
            progress = stats.levelProgressPercent
        }

        metricSentences?.text = "문장 ${stats.totalSentences}"
        metricBigrams?.text = "단어쌍 ${stats.bigramsCount}"
        metricEndings?.text = "어미 ${stats.endingsCount}"
        metricPhrases?.text = "상용구 ${stats.phrasesCount}"

        miniChart?.apply {
            visibility = View.VISIBLE
            setStats(stats, animate = lastRenderedChartStats != stats)
        }
        lastRenderedChartStats = stats
        itemView.contentDescription =
            "AI 언어 지문 학습 현황. 레벨 ${stats.level} ${stats.levelTitle}. 분석 문장 ${stats.totalSentences}개. 탭하면 상세 그래프 대시보드를 엽니다."
    }
}
