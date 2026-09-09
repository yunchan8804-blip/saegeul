/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.sentencepack.SentencePackCatalog
import org.fcitx.fcitx5.android.input.ai.sentencepack.SentencePackRepository
import org.fcitx.fcitx5.android.input.ai.sentencepack.SentencePackStatus
import splitties.dimensions.dp

class SentencePackDialog private constructor(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val repository: SentencePackRepository,
    private val isOfflineMode: () -> Boolean
) {
    private val horizontalPadding = context.dp(20)
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(horizontalPadding, context.dp(8), horizontalPadding, context.dp(8))
    }
    private val scrollContent = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maximum = context.dp(
                (context.resources.configuration.screenHeightDp / 2).coerceIn(180, 360)
            )
            val available = View.MeasureSpec.getSize(heightMeasureSpec)
            super.onMeasure(
                widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(
                    if (available > 0) minOf(available, maximum) else maximum,
                    View.MeasureSpec.AT_MOST
                )
            )
        }
    }.apply { addView(content) }
    private val message = TextView(context).apply {
        setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Body1)
    }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        visibility = View.GONE
        isIndeterminate = false
    }
    private val progressText = TextView(context).apply {
        visibility = View.GONE
        setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Caption)
    }
    private val sourceLicense = TextView(context).apply {
        text = context.getString(R.string.sentence_packs_source_license)
        minHeight = context.dp(48)
        gravity = android.view.Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Button)
        setOnClickListener { showLicense() }
    }
    private lateinit var dialog: AlertDialog
    private var statusJob: Job? = null
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            statusJob?.cancel()
            if (::dialog.isInitialized && dialog.isShowing) dialog.dismiss()
        }
    }

    fun show() {
        content.addView(message)
        content.addView(
            progress,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(16) }
        )
        content.addView(progressText)
        content.addView(sourceLicense)
        dialog = AlertDialog.Builder(context)
            .setTitle(R.string.sentence_packs_dialog_title)
            .setView(scrollContent)
            .setPositiveButton(null, null)
            .setNegativeButton(R.string.sentence_packs_close, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).minHeight = context.dp(48)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).minHeight = context.dp(48)
            render(repository.status.value)
        }
        dialog.setOnDismissListener {
            statusJob?.cancel()
            statusJob = null
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        dialog.show()
        repository.prepare()
        statusJob = lifecycleOwner.lifecycleScope.launch {
            repository.status.collect(::render)
        }
    }

    private fun render(status: SentencePackStatus) {
        if (!dialog.isShowing) return
        val offline = isOfflineMode()
        val actionButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val closeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        actionButton.isEnabled = true
        closeButton.text = context.getString(
            if (status.isDownloading || status.installedCount > 0) {
                R.string.sentence_packs_close
            } else {
                R.string.sentence_packs_later
            }
        )
        when {
            status.isDownloading -> {
                message.text = context.getString(
                    R.string.sentence_packs_download_message
                ) + "\n\n" + context.getString(R.string.sentence_packs_download_continues)
                progress.visibility = View.VISIBLE
                progressText.visibility = View.VISIBLE
                val total = status.totalBytes.coerceAtLeast(1L)
                progress.max = 1000
                progress.progress = ((status.downloadedBytes * 1000L) / total).toInt().coerceIn(0, 1000)
                progressText.text = context.getString(
                    R.string.sentence_packs_progress,
                    android.text.format.Formatter.formatFileSize(context, status.downloadedBytes),
                    android.text.format.Formatter.formatFileSize(context, total)
                )
                actionButton.visibility = View.VISIBLE
                actionButton.text = context.getString(R.string.sentence_packs_cancel)
                actionButton.setOnClickListener { repository.cancelDownload() }
            }
            status.installedCount > 0 -> {
                message.text = context.getString(
                    R.string.sentence_packs_installed_message,
                    status.installedCount
                ).let { installed -> status.error?.let { "$installed\n\n$it" } ?: installed }
                progress.visibility = View.GONE
                progressText.visibility = if (status.error == null) View.VISIBLE else View.GONE
                progressText.text = if (status.error == null) {
                    context.getString(R.string.sentence_packs_latest)
                } else {
                    ""
                }
                actionButton.visibility = View.VISIBLE
                actionButton.text = context.getString(R.string.sentence_packs_remove)
                actionButton.setOnClickListener { repository.removeDownloaded() }
            }
            offline -> {
                message.text = context.getString(R.string.sentence_packs_offline_message)
                progress.visibility = View.GONE
                progressText.visibility = View.GONE
                actionButton.visibility = View.GONE
            }
            status.error != null -> {
                message.text = status.error
                progress.visibility = View.GONE
                progressText.visibility = View.GONE
                actionButton.visibility = View.VISIBLE
                actionButton.text = context.getString(R.string.sentence_packs_retry)
                actionButton.setOnClickListener(::requestDownload)
            }
            else -> {
                message.text = context.getString(R.string.sentence_packs_download_message)
                progress.visibility = View.GONE
                progressText.visibility = View.GONE
                actionButton.visibility = View.VISIBLE
                actionButton.text = context.getString(R.string.sentence_packs_download)
                actionButton.setOnClickListener(::requestDownload)
            }
        }
    }

    private fun requestDownload(view: View) {
        if (isOfflineMode()) {
            render(repository.status.value)
            return
        }
        view.isEnabled = false
        repository.download()
    }

    private fun showLicense() {
        val text = TextView(context).apply {
            text = buildString {
                append(SentencePackCatalog.SOURCE_NAME)
                append('\n')
                append(SentencePackCatalog.SOURCE_URL)
                append("\n\n")
                append(SentencePackCatalog.LICENSE_TEXT)
            }
            setTextIsSelectable(true)
            setPadding(horizontalPadding, context.dp(8), horizontalPadding, context.dp(8))
        }
        val scroll = ScrollView(context).apply { addView(text) }
        AlertDialog.Builder(context)
            .setTitle(R.string.sentence_packs_source_license)
            .setView(scroll)
            .setPositiveButton(R.string.sentence_packs_close, null)
            .show()
    }

    companion object {
        fun show(
            context: Context,
            lifecycleOwner: LifecycleOwner,
            repository: SentencePackRepository,
            isOfflineMode: () -> Boolean
        ) {
            SentencePackDialog(
                context = context,
                lifecycleOwner = lifecycleOwner,
                repository = repository,
                isOfflineMode = isOfflineMode
            ).show()
        }
    }
}
