/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.TypingDnaRepository
import org.fcitx.fcitx5.android.input.ai.TypingDnaStats
import java.io.File

/**
 * Dedicated visual dashboard for Typing DNA linguistic evolution.
 * Displays real-time accumulation charts, tone & persona balances,
 * top bigram transitions, and on-device privacy guarantee metrics.
 */
class TypingDnaDashboardActivity : AppCompatActivity() {

    private lateinit var repository: TypingDnaRepository
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_typing_dna_dashboard)
        applySystemBarInsets()

        repository = TypingDnaRepository(File(filesDir, "typing_dna.json"))

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

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

        val btnSyncNow = findViewById<MaterialButton>(R.id.btn_sync_now)
        val btnClearDna = findViewById<MaterialButton>(R.id.btn_clear_dna)

        btnSyncNow.setOnClickListener {
            val ime = org.fcitx.fcitx5.android.input.FcitxInputMethodService.activeInstance
            val before = repository.getStats(forceReload = true).totalSentences
            if (ime != null) {
                runCatching { ime.triggerInstantTypingDnaSync() }
            }
            repository.invalidateCache()
            val stats = repository.getStats(forceReload = true)
            updateUi(stats, animate = true)
            val message = when {
                ime == null -> "저장된 언어 지문을 불러왔습니다 (분석 문장: ${stats.totalSentences}개)"
                stats.totalSentences > before -> "최신 언어 지문 분석 완료 (분석 문장: ${stats.totalSentences}개)"
                else -> "대기 중인 새 문장이 없습니다 (분석 문장: ${stats.totalSentences}개)"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        btnClearDna.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("언어 지문 전체 초기화")
                .setMessage("기기 내에 학습된 모든 말투, 종결 어미, 나만의 표현을 영구 삭제하시겠습니까?")
                .setPositiveButton(R.string.delete) { _, _ ->
                    repository.clear()
                    runCatching {
                        org.fcitx.fcitx5.android.input.FcitxInputMethodService.activeInstance?.typingDnaVault?.purge()
                    }
                    updateUi(repository.getStats(forceReload = true), animate = true)
                    Toast.makeText(this, "언어 지문이 안전하게 초기화되었습니다.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        loadAndDisplay()
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
        loadAndDisplay()
    }

    private fun loadAndDisplay() {
        val stats = repository.getStats(forceReload = true)
        updateUi(stats, animate = true)
    }

    private fun updateUi(stats: TypingDnaStats, animate: Boolean) {
        tvLevelBadge.text = "Lv.${stats.level}"
        tvLevelBadge.contentDescription = "학습 레벨 ${stats.level}"
        tvLevelTitle.text = stats.levelTitle

        tvLevelDesc.text = when (stats.level) {
            1 -> "키보드로 타이핑한 문장을 바탕으로 내 고유의 말투와 단어 연결 습관을 기기 내에서 학습 중입니다."
            2 -> "자주 쓰는 종결 어미와 단어 쌍이 정착되고 있습니다. 문맥에 맞는 다음 단어 제안이 강화됩니다."
            3 -> "메신저와 업무 환경의 말투 차이를 인식하기 시작했습니다. 문체별 자연스러운 맞춤 문장이 제안됩니다."
            4 -> "나만의 고유한 어휘와 문장 스타일이 정밀하게 동기화되었습니다."
            else -> "완성형 언어 지문입니다. 키보드가 나의 다음 생각과 문장을 가장 자연스럽게 완성해 줍니다."
        }

        progressLevel.progress = stats.levelProgressPercent

        tvDashSentences.text = "${stats.totalSentences}"
        tvDashBigrams.text = "${stats.bigramsCount}"
        tvDashEndings.text = "${stats.endingsCount}"
        tvDashPhrases.text = "${stats.phrasesCount}"

        tvLevelProgressText.text = if (stats.level >= 5) {
            "최고 레벨 달성 · 지속적으로 나만의 표현을 학습하고 업데이트합니다"
        } else {
            val remain = (stats.nextLevelTargetSentences - stats.totalSentences).coerceAtLeast(1)
            "다음 레벨(Lv.${stats.level + 1})까지 문장 ${remain}개 남음 · 진행률 ${stats.levelProgressPercent}%"
        }

        chartView.setStats(stats, animate = animate)
    }
}
