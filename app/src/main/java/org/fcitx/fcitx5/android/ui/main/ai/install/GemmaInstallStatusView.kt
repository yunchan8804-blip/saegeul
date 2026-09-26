/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.install

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.fcitx.fcitx5.android.R

/**
 * Binds `res/layout/view_gemma_install_status.xml` (included wherever the design packet's shared
 * "새글 AI" status card is shown - the vault home, the onboarding page, the product
 * model-management screen) to a [GemmaInstallUiState]. A layout+binder rather than a custom [View]
 * subclass, matching how the rest of this activity's cards bind `<include>`d layouts.
 */
class GemmaInstallStatusView private constructor(
    private val title: TextView,
    private val detail: TextView,
    private val progress: LinearProgressIndicator,
    private val button: MaterialButton
) {
    var onAction: ((GemmaInstallUiState.Button) -> Unit)? = null
    private var currentButton: GemmaInstallUiState.Button? = null

    init {
        button.setOnClickListener {
            currentButton?.let { onAction?.invoke(it) }
        }
    }

    fun render(context: Context, state: GemmaInstallUiState) {
        title.text = context.getString(state.titleRes)

        if (state.detailRes != null) {
            detail.text = context.getString(state.detailRes, *state.detailArgs.toTypedArray())
            detail.visibility = View.VISIBLE
        } else {
            detail.visibility = View.GONE
        }

        when (state.progressKind) {
            GemmaInstallUiState.ProgressKind.NONE -> progress.visibility = View.GONE
            GemmaInstallUiState.ProgressKind.INDETERMINATE -> {
                progress.visibility = View.GONE
                progress.isIndeterminate = true
                progress.visibility = View.VISIBLE
            }
            GemmaInstallUiState.ProgressKind.DETERMINATE -> {
                progress.visibility = View.GONE
                progress.isIndeterminate = false
                progress.max = 100
                progress.visibility = View.VISIBLE
                progress.setProgressCompat(state.progressPercent, true)
            }
        }

        currentButton = state.button
        if (state.buttonRes != null && state.button != null) {
            button.text = context.getString(state.buttonRes)
            styleButton(context, state.button)
            button.visibility = View.VISIBLE
        } else {
            button.visibility = View.GONE
        }
    }

    /** Start/resume/retry are the card's call to action (filled); the rest are secondary (outlined). */
    private fun styleButton(context: Context, kind: GemmaInstallUiState.Button) {
        val action = ContextCompat.getColor(context, R.color.saegeul_action)
        val primary = when (kind) {
            GemmaInstallUiState.Button.INSTALL,
            GemmaInstallUiState.Button.RESUME,
            GemmaInstallUiState.Button.RETRY -> true
            GemmaInstallUiState.Button.PAUSE,
            GemmaInstallUiState.Button.MOBILE_DATA,
            GemmaInstallUiState.Button.OPEN_SETTINGS -> false
        }
        if (primary) {
            button.backgroundTintList = ColorStateList.valueOf(action)
            button.setTextColor(ContextCompat.getColor(context, R.color.saegeul_on_action))
            button.strokeWidth = 0
        } else {
            button.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            button.setTextColor(action)
            button.strokeColor = ColorStateList.valueOf(action)
            button.strokeWidth = (context.resources.displayMetrics.density * 1.5f).toInt()
        }
    }

    companion object {
        /** [root] is the view inflated from `view_gemma_install_status.xml` (or its `<include>` root). */
        fun bind(root: View): GemmaInstallStatusView = GemmaInstallStatusView(
            title = root.findViewById(R.id.gemma_install_status_title),
            detail = root.findViewById(R.id.gemma_install_status_detail),
            progress = root.findViewById(R.id.gemma_install_status_progress),
            button = root.findViewById(R.id.gemma_install_status_button)
        )
    }
}
