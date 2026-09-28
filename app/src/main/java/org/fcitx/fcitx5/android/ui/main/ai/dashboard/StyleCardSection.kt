/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.dashboard

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.persona.PersonaRegistry
import org.fcitx.fcitx5.android.ui.main.ai.StyleReportSections

/** "내 말투" card plus the style report sections shown under it on the home. */
internal class StyleCardSection(private val activity: AppCompatActivity) {
    private val card: MaterialCardView = activity.findViewById(R.id.card_vault_words)
    private val wordChips: ChipGroup = activity.findViewById(R.id.chip_group_vault_words)
    private val categories: TextView = activity.findViewById(R.id.tv_style_categories)
    private val reportSections = StyleReportSections(activity.findViewById(R.id.style_report_sections))

    /** Frequent-word chips and the top app categories they're mostly typed in. */
    fun render(frequentWords: List<String>, categoryCounts: Map<String, Int>) {
        val chips = VaultStyleChips.filterChips(frequentWords)
        wordChips.removeAllViews()
        chips.forEach { word ->
            val chip = Chip(activity)
            chip.text = word
            chip.isClickable = false
            chip.isCheckable = false
            chip.isFocusable = false
            wordChips.addView(chip)
        }
        wordChips.visibility = if (chips.isEmpty()) View.GONE else View.VISIBLE

        val categoryLabels = VaultStyleChips.topCategoryIds(categoryCounts)
            .mapNotNull { id -> PersonaRegistry.byId(id)?.let { activity.getString(it.labelRes) } }
        if (categoryLabels.isEmpty()) {
            categories.visibility = View.GONE
        } else {
            categories.visibility = View.VISIBLE
            categories.text = activity.getString(R.string.vault_style_categories_line, categoryLabels.joinToString(" · "))
        }

        card.visibility = if (chips.isEmpty() && categoryLabels.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Accumulated records, last 30 days, where I write, style traits - shown on the home by default. */
    fun renderReportSections(animate: Boolean) {
        activity.lifecycleScope.launch {
            val data = try {
                withContext(Dispatchers.IO) { StyleReportSections.load() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                android.util.Log.w("SaegeulAI", "style sections load failed: ${error.javaClass.simpleName}")
                null
            }
            if (data == null || activity.isFinishing || activity.isDestroyed) return@launch
            reportSections.render(data, animate)
        }
    }
}
