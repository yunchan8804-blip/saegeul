/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.ai.dashboard.TypingDnaChartView

/**
 * "내 말투 리포트": the detail screen the vault home's "내 말투" card links out to. All numbers and
 * lists shown here come from a single [StyleReportUiState.from] call; this activity only resolves
 * string resources and binds views to it, plus feeds the raw [org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaStats]
 * into the two [TypingDnaChartView] sections it reuses (accumulation bars, tone balance) since that
 * view already draws those directly from stats.
 */
class StyleReportActivity : AppCompatActivity() {

    private lateinit var scroll: View
    private lateinit var emptyView: View
    private lateinit var loading: ProgressBar
    private lateinit var sections: StyleReportSections

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_style_report)

        findViewById<MaterialToolbar>(R.id.style_report_toolbar).setNavigationOnClickListener { finish() }

        scroll = findViewById(R.id.style_report_scroll)
        emptyView = findViewById(R.id.style_report_empty)
        loading = findViewById(R.id.style_report_loading)
        sections = StyleReportSections(findViewById(R.id.style_report_content))

        loadAndRender()
    }

    private fun loadAndRender() {
        loading.visibility = View.VISIBLE
        scroll.visibility = View.GONE
        emptyView.visibility = View.GONE
        lifecycleScope.launch {
            val data = try {
                withContext(Dispatchers.IO) { StyleReportSections.load() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w("SaegeulAI", "style report load failed: ${error.javaClass.simpleName}")
                null
            }
            if (isFinishing || isDestroyed) return@launch
            loading.visibility = View.GONE
            if (data == null || data.state.isEmpty) {
                emptyView.visibility = View.VISIBLE
            } else {
                scroll.visibility = View.VISIBLE
                findViewById<View>(R.id.style_report_content).visibility = View.VISIBLE
                sections.render(data, animate = true)
            }
        }
    }
}
