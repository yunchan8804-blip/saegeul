/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.panel.PanelButtonKind
import org.fcitx.fcitx5.android.input.panel.PanelRecovery
import org.fcitx.fcitx5.android.input.panel.PanelStyle
import org.fcitx.fcitx5.android.input.panel.panelButton
import org.fcitx.fcitx5.android.input.panel.panelSurface
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
    private val status = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_BODY
        gravity = Gravity.CENTER
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val progress = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(theme.accentKeyBackgroundColor)
        visibility = View.GONE
    }
    private val blocks = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val scroller = ScrollView(context).apply {
        visibility = View.GONE
        addView(blocks, matchWrap())
    }
    private val primary = context.panelButton(theme, PanelButtonKind.Primary)
    private val secondary = context.panelButton(theme, PanelButtonKind.Secondary)

    val root: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            dp(PanelStyle.PANEL_PADDING_H_DP), dp(PanelStyle.PANEL_PADDING_V_DP),
            dp(PanelStyle.PANEL_PADDING_H_DP), dp(PanelStyle.PANEL_PADDING_V_DP)
        )
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
        addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(PanelStyle.GAP_S_DP)
        })
        addView(scroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            3f
        ))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(primary, LinearLayout.LayoutParams(0, dp(PanelStyle.BUTTON_HEIGHT_DP), 1f).apply {
                marginEnd = dp(PanelStyle.GAP_S_DP)
            })
            addView(secondary, LinearLayout.LayoutParams(0, dp(PanelStyle.BUTTON_HEIGHT_DP), 1f).apply {
                marginStart = dp(PanelStyle.GAP_S_DP)
            })
        }, matchWrap())
    }

    fun showCheckingModel() {
        status.setText(R.string.ocr_checking_model)
        clearBlocks()
        progress.visibility = View.VISIBLE
        primary.apply {
            isEnabled = false
            setText(R.string.ocr_checking_model)
            setOnClickListener(null)
        }
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
        primary.apply {
            when {
                canDownload -> {
                    isEnabled = true
                    setText(R.string.ocr_model_download)
                    setOnClickListener { onDownloadModel?.invoke() }
                }
                recovery != null -> {
                    isEnabled = true
                    setText(recovery.labelRes)
                    setOnClickListener { recovery.run() }
                }
                else -> {
                    isEnabled = false
                    setText(R.string.ocr_model_download)
                    setOnClickListener(null)
                }
            }
        }
        showBack()
    }

    fun showDownloadingModel() {
        status.setText(R.string.ocr_model_downloading)
        clearBlocks()
        progress.visibility = View.VISIBLE
        primary.apply {
            isEnabled = false
            setText(R.string.ocr_model_downloading)
            setOnClickListener(null)
        }
        showCancel()
    }

    fun showReady() {
        status.setText(R.string.ocr_ready)
        clearBlocks()
        primary.apply {
            isEnabled = true
            setText(R.string.ocr_pick_image)
            setOnClickListener { onPickImage?.invoke() }
        }
        showBack()
    }

    fun showWaitingForImage() {
        status.setText(R.string.ocr_waiting_for_image)
        clearBlocks()
        primary.apply {
            isEnabled = false
            setText(R.string.ocr_pick_image)
            setOnClickListener(null)
        }
        showCancel()
    }

    fun showRecognizing() {
        status.setText(R.string.ocr_recognizing)
        clearBlocks()
        progress.visibility = View.VISIBLE
        primary.apply {
            isEnabled = false
            setText(R.string.ocr_recognizing)
            setOnClickListener(null)
        }
        showCancel()
    }

    fun showPreview(items: List<OcrTextBlock>) {
        selectedIds.clear()
        status.setText(R.string.ocr_preview)
        blocks.removeAllViews()
        items.forEach { block ->
            blocks.addView(CheckBox(context).apply {
                isChecked = false
                buttonTintList = ColorStateList.valueOf(theme.accentKeyBackgroundColor)
                setTextColor(theme.keyTextColor)
                textSize = PanelStyle.TEXT_BODY
                text = block.text
                setPadding(
                    dp(PanelStyle.GAP_M_DP), dp(PanelStyle.GAP_S_DP),
                    dp(PanelStyle.GAP_M_DP), dp(PanelStyle.GAP_S_DP)
                )
                background = context.panelSurface(theme.keyBackgroundColor)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedIds += block.id else selectedIds -= block.id
                    primary.isEnabled = onSelectionChanged?.invoke(selectedIds.toSet()) == true
                }
            }, matchWrap().apply { bottomMargin = context.dp(PanelStyle.GAP_S_DP) })
        }
        progress.visibility = View.GONE
        scroller.visibility = View.VISIBLE
        primary.apply {
            isEnabled = false
            setText(R.string.ocr_insert)
            setOnClickListener { onInsert?.invoke() }
        }
        showCancel()
    }

    fun showRecognitionError(message: Int, canRetry: Boolean) {
        status.setText(message)
        clearBlocks()
        primary.apply {
            isEnabled = canRetry
            setText(R.string.ocr_pick_image)
            setOnClickListener(if (canRetry) View.OnClickListener { onPickImage?.invoke() } else null)
        }
        showBack()
    }

    private fun showBack() {
        secondary.apply {
            isEnabled = true
            setText(R.string.ai_back)
            setOnClickListener { onClose?.invoke() }
        }
    }

    private fun showCancel() {
        secondary.apply {
            isEnabled = true
            setText(android.R.string.cancel)
            setOnClickListener { onCancel?.invoke() }
        }
    }

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
