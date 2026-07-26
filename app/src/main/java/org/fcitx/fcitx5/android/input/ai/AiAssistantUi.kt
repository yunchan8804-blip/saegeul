/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.setPadding
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme

/**
 * Compact, programmatic UI for AI writing actions inside the input method.
 *
 * The caller owns network state and text replacement. This class only renders state and emits
 * explicit user intents, so a suggestion is never inserted without a Replace or Append tap.
 */
class AiAssistantUi(
    private val context: Context,
    private val theme: Theme
) {
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(theme.barColor)
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }

    var onActionSelected: ((AiAction) -> Unit)? = null
    var onSuggestionReplace: ((String) -> Unit)? = null
    var onSuggestionAppend: ((String) -> Unit)? = null
    var onUndo: (() -> Unit)? = null
    var onRetry: (() -> Unit)? = null

    private val actionButtons = mutableMapOf<AiAction, Button>()
    private var selectedAction: AiAction? = null

    private val provider = TextView(context).apply {
        setTextColor(theme.altKeyTextColor)
        textSize = 11f
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        maxLines = 1
        visibility = View.GONE
    }
    private val sourceLabel = sectionLabel(R.string.ai_source_label)
    private val sourceText = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = 14f
        maxLines = 3
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = rounded(theme.altKeyBackgroundColor, dp(10))
    }
    private val sourceSection = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(sourceLabel, LinearLayout.LayoutParams(0, dp(28), 1f))
            addView(provider, LinearLayout.LayoutParams(0, dp(28), 1f))
        }, matchWrap())
        addView(sourceText, matchWrap().apply { topMargin = dp(2) })
    }

    private val actionSection = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addActionGroup(
            R.string.ai_actions_writing,
            listOf(AiAction.Proofread, AiAction.Compose, AiAction.Reply)
        )
        addActionGroup(
            R.string.ai_actions_tone,
            listOf(
                AiAction.Polite,
                AiAction.Casual,
                AiAction.Business,
                AiAction.Decline,
                AiAction.Apology,
                AiAction.CustomerService
            )
        )
        addActionGroup(
            R.string.ai_actions_translation,
            listOf(
                AiAction.TranslateEnglish,
                AiAction.TranslateKorean,
                AiAction.TranslateJapanese,
                AiAction.TranslateChinese
            )
        )
    }

    private val progress = ProgressBar(context).apply {
        isIndeterminate = true
        visibility = View.GONE
        contentDescription = context.getString(R.string.ai_loading)
    }
    private val status = TextView(context).apply {
        setTextColor(theme.altKeyTextColor)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(8))
        setText(R.string.ai_select_action)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val statusArea = FrameLayout(context).apply {
        addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        addView(progress, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
    }

    private val results = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val resultsScroll = ScrollView(context).apply {
        isFillViewport = true
        addView(results, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private val retryButton = compactButton(R.string.ai_retry, active = true).apply {
        visibility = View.GONE
        setOnClickListener { onRetry?.invoke() }
    }
    private val undoButton = compactButton(R.string.ai_undo, active = false).apply {
        visibility = View.GONE
        setOnClickListener { onUndo?.invoke() }
    }
    private val footer = LinearLayout(context).apply {
        gravity = Gravity.END
        addView(retryButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        addView(undoButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginStart = dp(6)
        })
        visibility = View.GONE
    }

    init {
        root.addView(sourceSection, matchWrap().apply { topMargin = dp(2) })
        root.addView(actionSection, matchWrap().apply { topMargin = dp(4) })
        root.addView(statusArea, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        root.addView(resultsScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply { topMargin = dp(4) })
        root.addView(footer, matchWrap().apply { topMargin = dp(4) })
    }

    /** Shows the exact editor text that will be sent, before any network request begins. */
    fun showSourcePreview(source: String, providerLabel: String? = null) {
        sourceText.text = source
        sourceText.visibility = View.VISIBLE
        sourceSection.visibility = View.VISIBLE
        actionSection.visibility = View.VISIBLE
        setProvider(providerLabel)
        selectedAction = null
        updateActionButtons(enabled = source.isNotBlank())
        results.removeAllViews()
        resultsScroll.visibility = View.GONE
        progress.visibility = View.GONE
        status.apply {
            setText(R.string.ai_select_action)
            setTextColor(theme.altKeyTextColor)
            visibility = View.VISIBLE
        }
        retryButton.visibility = View.GONE
        updateFooterVisibility()
    }

    /** Keeps the source visible while the selected action is running. */
    fun showLoading(action: AiAction, providerLabel: String) {
        selectedAction = action
        setProvider(providerLabel)
        sourceText.visibility = View.VISIBLE
        sourceSection.visibility = View.VISIBLE
        actionSection.visibility = View.GONE
        updateActionButtons(enabled = false)
        results.removeAllViews()
        resultsScroll.visibility = View.GONE
        status.visibility = View.GONE
        progress.visibility = View.VISIBLE
        retryButton.visibility = View.GONE
        updateFooterVisibility()
        root.announceForAccessibility(context.getString(R.string.ai_loading))
    }

    /** Renders at most three suggestions, each with explicit Replace and Append actions. */
    fun showResults(action: AiAction, providerLabel: String, suggestions: List<String>) {
        selectedAction = action
        setProvider(providerLabel)
        sourceText.visibility = View.GONE
        sourceSection.visibility = View.VISIBLE
        actionSection.visibility = View.GONE
        updateActionButtons(enabled = true)
        progress.visibility = View.GONE
        status.visibility = View.GONE
        retryButton.visibility = View.GONE
        results.removeAllViews()
        suggestions.asSequence()
            .filter { it.isNotBlank() }
            .take(action.maxSuggestions.coerceIn(1, 3))
            .forEachIndexed { index, suggestion ->
                results.addView(resultCard(index, suggestion), matchWrap().apply {
                    bottomMargin = dp(6)
                })
            }
        if (results.childCount == 0) {
            showError(context.getString(R.string.ai_error_empty_result), providerLabel)
            return
        }
        resultsScroll.visibility = View.VISIBLE
        updateFooterVisibility()
        resultsScroll.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    fun showError(message: String, providerLabel: String? = null, canRetry: Boolean = true) {
        setProvider(providerLabel)
        sourceText.visibility = View.VISIBLE
        sourceSection.visibility = View.VISIBLE
        actionSection.visibility = View.GONE
        updateActionButtons(enabled = true)
        results.removeAllViews()
        resultsScroll.visibility = View.GONE
        progress.visibility = View.GONE
        status.apply {
            text = context.getString(R.string.ai_error, message)
            setTextColor(Color.rgb(220, 85, 85))
            visibility = View.VISIBLE
        }
        retryButton.visibility = if (canRetry) View.VISIBLE else View.GONE
        updateFooterVisibility()
        status.sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
    }

    fun setUndoAvailable(available: Boolean) {
        undoButton.visibility = if (available) View.VISIBLE else View.GONE
        updateFooterVisibility()
    }

    private fun resultCard(index: Int, suggestion: String): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = rounded(theme.altKeyBackgroundColor, dp(10))

            addView(TextView(context).apply {
                text = context.getString(R.string.ai_result_number, index + 1)
                setTextColor(theme.altKeyTextColor)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrap())

            addView(TextView(context).apply {
                text = suggestion
                setTextColor(theme.keyTextColor)
                textSize = 15f
                setPadding(0, dp(4), 0, dp(5))
            }, matchWrap())

            addView(LinearLayout(context).apply {
                gravity = Gravity.END
                addView(compactButton(R.string.ai_replace, active = true).apply {
                    setOnClickListener { onSuggestionReplace?.invoke(suggestion) }
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(compactButton(R.string.ai_append, active = false).apply {
                    setOnClickListener { onSuggestionAppend?.invoke(suggestion) }
                }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    marginStart = dp(6)
                })
            }, matchWrap())
        }

    private fun LinearLayout.addActionGroup(@StringRes labelRes: Int, actions: List<AiAction>) {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            actions.forEach { action ->
                addView(actionButton(action), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(38)
                ).apply { marginEnd = dp(5) })
            }
        }
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(sectionLabel(labelRes), LinearLayout.LayoutParams(dp(54), dp(38)))
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            }, LinearLayout.LayoutParams(0, dp(38), 1f))
        }, matchWrap())
    }

    private fun actionButton(action: AiAction): Button =
        compactButton(action.labelRes(), active = false).apply {
            actionButtons[action] = this
            setOnClickListener {
                selectedAction = action
                updateActionButtons(enabled = true)
                onActionSelected?.invoke(action)
            }
        }

    private fun updateActionButtons(enabled: Boolean) {
        actionButtons.forEach { (action, button) ->
            val selected = action == selectedAction
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.45f
            button.setTextColor(
                if (selected) theme.genericActiveForegroundColor else theme.keyTextColor
            )
            button.backgroundTintList = ColorStateList.valueOf(
                if (selected) theme.genericActiveBackgroundColor else theme.keyBackgroundColor
            )
            button.isSelected = selected
        }
    }

    private fun setProvider(label: String?) {
        provider.text = label?.let { context.getString(R.string.ai_provider, it) }.orEmpty()
        provider.visibility = if (label.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun updateFooterVisibility() {
        footer.visibility = if (
            retryButton.visibility == View.VISIBLE || undoButton.visibility == View.VISIBLE
        ) View.VISIBLE else View.GONE
    }

    private fun sectionLabel(@StringRes textRes: Int) = TextView(context).apply {
        setText(textRes)
        setTextColor(theme.altKeyTextColor)
        textSize = 11f
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun compactButton(@StringRes textRes: Int, active: Boolean) = Button(context).apply {
        isAllCaps = false
        setText(textRes)
        textSize = 12f
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        setTextColor(if (active) theme.genericActiveForegroundColor else theme.keyTextColor)
        backgroundTintList = ColorStateList.valueOf(
            if (active) theme.genericActiveBackgroundColor else theme.keyBackgroundColor
        )
    }

    private fun AiAction.labelRes(): Int = when (this) {
        AiAction.Proofread -> R.string.ai_action_proofread
        AiAction.Polite -> R.string.ai_action_polite
        AiAction.Casual -> R.string.ai_action_casual
        AiAction.Business -> R.string.ai_action_business
        AiAction.Decline -> R.string.ai_action_decline
        AiAction.Apology -> R.string.ai_action_apology
        AiAction.CustomerService -> R.string.ai_action_customer_service
        AiAction.Compose -> R.string.ai_action_compose
        AiAction.Reply -> R.string.ai_action_reply
        AiAction.TranslateEnglish -> R.string.ai_action_translate_english
        AiAction.TranslateKorean -> R.string.ai_action_translate_korean
        AiAction.TranslateJapanese -> R.string.ai_action_translate_japanese
        AiAction.TranslateChinese -> R.string.ai_action_translate_chinese
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
