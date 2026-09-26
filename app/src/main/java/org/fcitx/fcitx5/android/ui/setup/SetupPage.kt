/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.setup

import android.content.Context
import androidx.core.text.HtmlCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.ai.GemmaPreparationFactory
import org.fcitx.fcitx5.android.utils.InputMethodUtil

enum class SetupPage {
    Enable, Select, AiSuggestion;

    fun getHintText(context: Context) = context.getString(
        when (this) {
            Enable -> R.string.enable_ime_hint
            Select -> R.string.select_ime_hint
            AiSuggestion -> R.string.setup_ai_new_body
        }, context.getString(R.string.app_name)
    ).let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY) }

    fun getButtonText(context: Context) = context.getText(
        when (this) {
            Enable -> R.string.enable_ime
            Select -> R.string.select_ime
            AiSuggestion -> R.string.gemma_install_button_install
        }
    )

    /** [AiSuggestion]'s own button is never shown - the page's [org.fcitx.fcitx5.android.ui.main.ai.install.GemmaInstallStatusView] carries the install action instead. */
    fun getButtonAction(context: Context) = when (this) {
        Enable -> InputMethodUtil.startSettingsActivity(context)
        Select -> InputMethodUtil.showPicker()
        AiSuggestion -> Unit
    }

    fun isDone(context: Context) = when (this) {
        Enable -> InputMethodUtil.isEnabled()
        Select -> InputMethodUtil.isSelected()
        AiSuggestion -> GemmaPreparationFactory.create(context)?.isModelReady(context) ?: false
    }

    /** Whether this page must be completed before setup can be finished from the last page. */
    val isRequired: Boolean
        get() = this == Enable || this == Select

    companion object {
        /** Pages actually shown to the user; [AiSuggestion] is hidden where no controller exists. */
        fun visiblePages(context: Context): List<SetupPage> =
            entries.filter { it != AiSuggestion || GemmaPreparationFactory.create(context) != null }

        fun valueOf(context: Context, value: Int) = visiblePages(context)[value]
        fun SetupPage.isLastPage(context: Context) = this == visiblePages(context).last()
        fun Int.isLastPage(context: Context) = this == visiblePages(context).size - 1
        fun hasUndonePage(context: Context) =
            visiblePages(context).any { it.isRequired && !it.isDone(context) }

        fun firstUndonePage(context: Context) =
            visiblePages(context).firstOrNull { it.isRequired && !it.isDone(context) }
    }
}
