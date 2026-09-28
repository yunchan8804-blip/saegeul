/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.panel.PanelButtonKind
import org.fcitx.fcitx5.android.input.panel.PanelRecovery
import org.fcitx.fcitx5.android.input.panel.PanelStyle
import org.fcitx.fcitx5.android.input.panel.addStatusWithPreview
import org.fcitx.fcitx5.android.input.panel.panelButton
import org.fcitx.fcitx5.android.input.panel.panelButtonPair
import org.fcitx.fcitx5.android.input.panel.panelCheckRow
import org.fcitx.fcitx5.android.input.panel.panelColumn
import org.fcitx.fcitx5.android.input.panel.panelProgress
import org.fcitx.fcitx5.android.input.panel.panelStatusText
import org.fcitx.fcitx5.android.input.panel.showDisabledPanelAction
import org.fcitx.fcitx5.android.input.panel.showPanelAction
import org.fcitx.fcitx5.android.input.panel.showPanelActionOrRecovery
import splitties.dimensions.dp

class OcrUi(
    private val context: Context,
    private val theme: Theme
) {
    var onDownloadModel: (() -> Unit)? = null
    var onPickImage: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onInsert: (() -> Unit)? = null
    var onSelectionChanged: ((Set<String>) -> Boolean)? = null

    private val selectedIds = linkedSetOf<String>()
    private val status = context.panelStatusText(theme)
    private val progress = context.panelProgress(theme)
    private val blocks = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val scroller = ScrollView(context).apply {
        visibility = View.GONE
        addView(blocks, matchWrap())
    }
    private val primary = context.panelButton(theme, PanelButtonKind.Primary)
    private val secondary = context.panelButton(theme, PanelButtonKind.Secondary)

    val root: View = context.panelColumn().apply {
        setBackgroundColor(theme.keyboardColor)
        addView(TextView(context).apply {
            setText(R.string.ocr_title)
            setTextColor(theme.keyTextColor)
            textSize = PanelStyle.TEXT_TITLE
        }, matchWrap())
        addView(TextView(context).apply {
            setText(R.string.ocr_engine_attribution)
            setTextColor(theme.altKeyTextColor)
            textSize = PanelStyle.TEXT_CAPTION
        }, matchWrap())
        addStatusWithPreview(status, progress, scroller)
        addView(context.panelButtonPair(primary, secondary), matchWrap())
    }

    fun showCheckingModel() {
        status.setText(R.string.ocr_checking_model)
        clearBlocks()
        progress.visibility = View.VISIBLE
        primary.showDisabledPanelAction(R.string.ocr_checking_model)
        showBack()
    }

    /**
     * [recovery] takes over the primary slot when the download is blocked, so the panel
     * points at the setting behind the block instead of a disabled download button.
     */
    fun showModelMissing(
        canDownload: Boolean,
        failed: Boolean = false,
        recovery: PanelRecovery? = null
    ) {
        status.setText(
            when {
                failed -> R.string.ocr_model_download_failed
                canDownload -> R.string.ocr_model_missing
                else -> R.string.ocr_model_missing_offline
            }
        )
        clearBlocks()
        primary.showPanelActionOrRecovery(canDownload, R.string.ocr_model_download, recovery) {
            onDownloadModel?.invoke()
        }
        showBack()
    }

    fun showDownloadingModel() {
        status.setText(R.string.ocr_model_downloading)
        clearBlocks()
        progress.visibility = View.VISIBLE
        primary.showDisabledPanelAction(R.string.ocr_model_downloading)
        showCancel()
    }

    fun showReady() {
        status.setText(R.string.ocr_ready)
        clearBlocks()
        primary.showPanelAction(R.string.ocr_pick_image) { onPickImage?.invoke() }
        showBack()
    }

    fun showWaitingForImage() {
        status.setText(R.string.ocr_waiting_for_image)
        clearBlocks()
        primary.showDisabledPanelAction(R.string.ocr_pick_image)
        showCancel()
    }

    fun showRecognizing() {
        status.setText(R.string.ocr_recognizing)
        clearBlocks()
        progress.visibility = View.VISIBLE
        primary.showDisabledPanelAction(R.string.ocr_recognizing)
        showCancel()
    }

    fun showPreview(items: List<OcrTextBlock>) {
        selectedIds.clear()
        status.setText(R.string.ocr_preview)
        blocks.removeAllViews()
        items.forEach { block ->
            blocks.addView(context.panelCheckRow(theme, block.text) { checked ->
                if (checked) selectedIds += block.id else selectedIds -= block.id
                primary.isEnabled = onSelectionChanged?.invoke(selectedIds.toSet()) == true
            }, matchWrap().apply { bottomMargin = context.dp(PanelStyle.GAP_S_DP) })
        }
        progress.visibility = View.GONE
        scroller.visibility = View.VISIBLE
        primary.showPanelAction(R.string.ocr_insert, enabled = false) { onInsert?.invoke() }
        showCancel()
    }

    fun showRecognitionError(message: Int, canRetry: Boolean) {
        status.setText(message)
        clearBlocks()
        if (canRetry) {
            primary.showPanelAction(R.string.ocr_pick_image) { onPickImage?.invoke() }
        } else {
            primary.showDisabledPanelAction(R.string.ocr_pick_image)
        }
        showBack()
    }

    private fun showBack() = secondary.showPanelAction(R.string.ai_back) { onClose?.invoke() }

    private fun showCancel() = secondary.showPanelAction(android.R.string.cancel) { onCancel?.invoke() }

    private fun clearBlocks() {
        selectedIds.clear()
        blocks.removeAllViews()
        scroller.visibility = View.GONE
        progress.visibility = View.GONE
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )
}
