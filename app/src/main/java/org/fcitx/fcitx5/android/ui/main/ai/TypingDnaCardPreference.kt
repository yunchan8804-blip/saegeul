/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.fcitx.fcitx5.android.R

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

    init {
        layoutResource = R.layout.view_typing_dna_card_preference
        isSelectable = true
        isIconSpaceReserved = false
    }

    fun refresh() {
        notifyChanged()
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

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val ctx = context
        val repo = org.fcitx.fcitx5.android.FcitxApplication.getInstance().typingDnaRepository
        val stats = repo.getStats(forceReload = true)

        val card = holder.itemView.findViewById<MaterialCardView>(R.id.card_typing_dna)
        val tvLevelBadge = holder.itemView.findViewById<TextView>(R.id.tv_main_card_level_badge)
        val tvTitle = holder.itemView.findViewById<TextView>(R.id.tv_main_card_title)
        val tvSummary = holder.itemView.findViewById<TextView>(R.id.tv_main_card_summary)
        val progressBar = holder.itemView.findViewById<LinearProgressIndicator>(R.id.progress_main_card)

        val tvMetricSentences = holder.itemView.findViewById<TextView>(R.id.tv_metric_sentences)
        val tvMetricBigrams = holder.itemView.findViewById<TextView>(R.id.tv_metric_bigrams)
        val tvMetricEndings = holder.itemView.findViewById<TextView>(R.id.tv_metric_endings)
        val tvMetricPhrases = holder.itemView.findViewById<TextView>(R.id.tv_metric_phrases)
        val btnMore = holder.itemView.findViewById<android.view.View>(R.id.btn_dna_card_more)
        val miniChart = holder.itemView.findViewById<TypingDnaChartView>(R.id.mini_chart)

        tvLevelBadge?.text = "Lv.${stats.level}"
        tvLevelBadge?.contentDescription = "학습 레벨 ${stats.level}"
        tvTitle?.text = "${stats.levelTitle} · 온디바이스 학습 중"
        val app = org.fcitx.fcitx5.android.FcitxApplication.getInstance()
        val ngramStats = app.personalNgramModel.stats()
        val pending = app.typingDnaVault.totalBufferedCount()
        tvSummary?.text = buildString {
            if (stats.totalSentences == 0) {
                append("키보드를 사용하면 내 말투와 어휘 습관이 이곳에 축적됩니다.")
                if (ngramStats.unigrams >= 1) {
                    append(ctx.getString(R.string.typing_dna_card_ngram_suffix, ngramStats.unigrams))
                }
            } else {
                append("분석 문장 ${stats.totalSentences}개 · 단어쌍 ${stats.bigramsCount}개 · 종결어미 ${stats.endingsCount}개")
                append(ctx.getString(R.string.typing_dna_card_ngram_suffix, ngramStats.unigrams))
            }
            if (pending >= 1) {
                append(ctx.getString(R.string.typing_dna_card_pending_suffix, pending))
            }
            if (app.vaultCipher.isHardwareBacked) {
                append(ctx.getString(R.string.typing_dna_card_hardware_suffix))
            }
        }
        progressBar?.progress = stats.levelProgressPercent

        tvMetricSentences?.text = "문장 ${stats.totalSentences}"
        tvMetricBigrams?.text = "단어쌍 ${stats.bigramsCount}"
        tvMetricEndings?.text = "어미 ${stats.endingsCount}"
        tvMetricPhrases?.text = "상용구 ${stats.phrasesCount}"

        miniChart?.setCompact(true)
        miniChart?.setStats(stats, animate = true)
        holder.itemView.contentDescription =
            "AI 언어 지문 학습 현황. 레벨 ${stats.level} ${stats.levelTitle}. 분석 문장 ${stats.totalSentences}개. 탭하면 상세 그래프 대시보드를 엽니다."

        val clickListener = android.view.View.OnClickListener { launchDashboard() }
        holder.itemView.setOnClickListener(clickListener)
        card?.setOnClickListener(clickListener)
        btnMore?.setOnClickListener(clickListener)
    }
}
