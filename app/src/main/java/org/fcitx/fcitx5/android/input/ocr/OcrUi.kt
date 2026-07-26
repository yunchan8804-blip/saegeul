/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme

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
        textSize = 14f
        gravity = Gravity.CENTER
    }
    private val blocks = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val scroller = ScrollView(context).apply {
        visibility = View.GONE
        addView(blocks, matchWrap())
    }
    private val primary = actionButton(active = true)
    private val secondary = actionButton(active = false)

    val root: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setBackgroundColor(theme.keyboardColor)
        addView(TextView(context).apply {
            setText(R.string.ocr_title)
            setTextColor(theme.keyTextColor)
            textSize = 18f
        }, matchWrap())
        addView(TextView(context).apply {
            setText(R.string.ocr_engine_attribution)
            setTextColor(theme.altKeyTextColor)
            textSize = 11f
        }, matchWrap())
        addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        addView(scroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            3f
        ))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(primary, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginEnd = dp(5)
            })
            addView(secondary, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(5)
            })
        }, matchWrap())
    }

    fun showCheckingModel() {
        status.setText(R.string.ocr_checking_model)
        clearBlocks()
        primary.apply {
            isEnabled = false
            setText(R.string.ocr_checking_model)
            setOnClickListener(null)
        }
        showBack()
    }

    fun showModelMissing(canDownload: Boolean, failed: Boolean = false) {
        status.setText(
            when {
                failed -> R.string.ocr_model_download_failed
                canDownload -> R.string.ocr_model_missing
                else -> R.string.ocr_model_missing_offline
            }
        )
        clearBlocks()
        primary.apply {
            isEnabled = canDownload
            setText(R.string.ocr_model_download)
            setOnClickListener(
                if (canDownload) View.OnClickListener { onDownloadModel?.invoke() } else null
            )
        }
        showBack()
    }

    fun showDownloadingModel() {
        status.setText(R.string.ocr_model_downloading)
        clearBlocks()
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
                textSize = 14f
                text = block.text
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = rounded(theme.keyBackgroundColor, dp(8))
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedIds += block.id else selectedIds -= block.id
                    primary.isEnabled = onSelectionChanged?.invoke(selectedIds.toSet()) == true
                }
            }, matchWrap().apply { bottomMargin = dp(5) })
        }
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
    }

    private fun actionButton(active: Boolean) = Button(context).apply {
        isAllCaps = false
        textSize = 13f
        minHeight = 0
        minimumHeight = 0
        setTextColor(if (active) theme.accentKeyTextColor else theme.keyTextColor)
        backgroundTintList = ColorStateList.valueOf(
            if (active) theme.accentKeyBackgroundColor else theme.keyBackgroundColor
        )
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
