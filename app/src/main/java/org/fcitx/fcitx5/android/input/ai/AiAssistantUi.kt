/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.setPadding
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.panel.PanelButtonKind
import org.fcitx.fcitx5.android.input.panel.PanelRecovery
import org.fcitx.fcitx5.android.input.panel.PanelStyle
import org.fcitx.fcitx5.android.input.panel.panelButton
import org.fcitx.fcitx5.android.input.panel.panelSurface
import org.fcitx.fcitx5.android.input.panel.pressableChipSurface
import org.fcitx.fcitx5.android.input.panel.pressablePanelSurface
import splitties.dimensions.dp

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
        setPadding(
            dp(PanelStyle.PANEL_PADDING_H_DP),
            dp(PanelStyle.PANEL_PADDING_V_DP),
            dp(PanelStyle.PANEL_PADDING_H_DP),
            dp(PanelStyle.PANEL_PADDING_V_DP)
        )
    }

    var onActionSelected: ((AiAction) -> Unit)? = null
    var onCustomPromptRequested: ((String) -> Unit)? = null
    var onSuggestionReplace: ((String) -> Unit)? = null
    var onSuggestionAppend: ((String) -> Unit)? = null
    var onSelectedChangesApply: ((AiTextPatch, Set<Int>) -> Unit)? = null
    var onUndo: (() -> Unit)? = null
    var onRetry: (() -> Unit)? = null
    var onClipboardSourceRequested: (() -> Unit)? = null
    var onClipboardSourceSelected: ((Int) -> Unit)? = null
    var onClipboardSourceSelectionCancelled: (() -> Unit)? = null
    var onSetupRequested: (() -> Unit)? = null

    private val actionButtons = mutableMapOf<AiAction, Button>()
    private val resultInteractionViews = mutableListOf<View>()
    private val resultAppliedLabels = mutableListOf<TextView>()
    private var selectedAction: AiAction? = null
    private var intakeAvailable = false
    private var replySourceOrigin: AiReplySourceOrigin? = null
    private var sourcePreviewCanExpand = false
    private var resultActionControlsEnabled = true
    private var sourceReviewReturnsToResults = false
    private var resultPresentation: AiResultPresentation? = null

    private val sourceText = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_BODY
        maxLines = SOURCE_PREVIEW_COLLAPSED_LINES
        ellipsize = TextUtils.TruncateAt.END
        setPadding(
            dp(PanelStyle.CARD_PADDING_H_DP),
            dp(PanelStyle.GAP_S_DP),
            dp(PanelStyle.CARD_PADDING_H_DP),
            dp(PanelStyle.GAP_S_DP)
        )
        background = context.pressablePanelSurface(theme, theme.altKeyBackgroundColor)
        setOnClickListener { toggleSourcePreview() }
    }
    private val sourceMeta = TextView(context).apply {
        setTextColor(theme.altKeyTextColor)
        textSize = PanelStyle.TEXT_CAPTION
        setPadding(dp(PanelStyle.GAP_XS_DP), 0, 0, dp(PanelStyle.GAP_XS_DP))
    }
    private val transmissionDisclosure = TextView(context).apply {
        setTextColor(theme.altKeyTextColor)
        textSize = PanelStyle.TEXT_CAPTION
        setPadding(dp(PanelStyle.GAP_XS_DP), dp(PanelStyle.GAP_XS_DP), dp(PanelStyle.GAP_XS_DP), 0)
        visibility = View.GONE
    }
    private val sourcePreviewToggle = TextView(context).apply {
        setTextColor(theme.genericActiveForegroundColor)
        textSize = PanelStyle.TEXT_CAPTION
        gravity = Gravity.CENTER
        minHeight = dp(PanelStyle.CHIP_HEIGHT_DP)
        setPadding(dp(PanelStyle.GAP_M_DP), 0, dp(PanelStyle.GAP_M_DP), 0)
        background = context.pressableChipSurface(theme, theme.genericActiveBackgroundColor)
        visibility = View.GONE
        setOnClickListener { toggleSourcePreview() }
    }
    private val sourceMetaRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(sourceMeta, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(sourcePreviewToggle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(PanelStyle.CHIP_HEIGHT_DP)
        ))
    }
    private val sourceSection = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(sourceMetaRow, matchWrap())
        addView(sourceText, matchWrap())
        addView(transmissionDisclosure, matchWrap())
    }
    private val sourceReviewTitle = TextView(context).apply {
        setTextColor(theme.altKeyTextColor)
        textSize = PanelStyle.TEXT_CAPTION
        setPadding(dp(PanelStyle.GAP_XS_DP), 0, 0, dp(PanelStyle.GAP_S_DP))
    }
    private val sourceReviewText = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_BODY
        setPadding(
            dp(PanelStyle.CARD_PADDING_H_DP),
            dp(PanelStyle.CARD_PADDING_V_DP),
            dp(PanelStyle.CARD_PADDING_H_DP),
            dp(PanelStyle.CARD_PADDING_V_DP)
        )
        background = context.panelSurface(theme.altKeyBackgroundColor)
    }
    private val sourceReviewScroll = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        visibility = View.GONE
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(sourceReviewTitle, matchWrap())
            addView(sourceReviewText, matchWrap())
            addView(context.panelButton(theme, PanelButtonKind.Secondary).apply {
                setText(R.string.ai_source_preview_back)
                setOnClickListener { hideSourceReview() }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(PanelStyle.COMPACT_BUTTON_HEIGHT_DP)
            ).apply { topMargin = dp(PanelStyle.GAP_M_DP) })
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private val actionSection = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addActionGroup(R.string.ai_actions_writing, AiActionMenuPolicy.primary, columns = 2)
        addActionGroup(R.string.ai_actions_tone, AiActionMenuPolicy.tone, columns = 3)
        addActionGroup(
            R.string.ai_actions_translation,
            AiActionMenuPolicy.translation,
            columns = 4
        )
    }
    private val actionsScroll = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(actionSection, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private val progress = ProgressBar(context).apply {
        isIndeterminate = true
        visibility = View.GONE
        contentDescription = context.getString(R.string.ai_loading)
    }
    private val status = TextView(context).apply {
        setTextColor(theme.altKeyTextColor)
        textSize = PanelStyle.TEXT_BODY
        gravity = Gravity.CENTER
        setPadding(dp(PanelStyle.GAP_M_DP))
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
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        addView(results, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private val retryButton = context.panelButton(theme, PanelButtonKind.Primary).apply {
        setText(R.string.ai_retry)
        visibility = View.GONE
        setOnClickListener { onRetry?.invoke() }
    }
    // The setup slot doubles as the fix-it action for blocked states: same position,
    // label swapped to whatever destination actually resolves the block.
    private var pendingRecovery: PanelRecovery? = null

    private val setupButton = context.panelButton(theme, PanelButtonKind.Primary).apply {
        setText(R.string.ai_setup_action)
        visibility = View.GONE
        setOnClickListener {
            pendingRecovery?.let { it.run() } ?: onSetupRequested?.invoke()
        }
    }
    private val undoButton = context.panelButton(theme, PanelButtonKind.Secondary).apply {
        setText(R.string.ai_undo)
        visibility = View.GONE
        setOnClickListener { onUndo?.invoke() }
    }
    private val footer = LinearLayout(context).apply {
        gravity = Gravity.END
        addView(setupButton, LinearLayout.LayoutParams(0, dp(PanelStyle.COMPACT_BUTTON_HEIGHT_DP), 1f))
        addView(retryButton, LinearLayout.LayoutParams(0, dp(PanelStyle.COMPACT_BUTTON_HEIGHT_DP), 1f))
        addView(undoButton, LinearLayout.LayoutParams(0, dp(PanelStyle.COMPACT_BUTTON_HEIGHT_DP), 1f).apply {
            marginStart = dp(PanelStyle.GAP_S_DP)
        })
        visibility = View.GONE
    }

    init {
        root.addView(sourceSection, matchWrap().apply { topMargin = context.dp(PanelStyle.GAP_S_DP) })
        // The source stays pinned, while the action catalog can scroll on compact keyboards.
        // All groups are still rendered from the first frame; scrolling is only a fallback for
        // very short IME windows instead of silently clipping the lower actions.
        root.addView(actionsScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply { topMargin = context.dp(PanelStyle.GAP_S_DP) })
        root.addView(sourceReviewScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply { topMargin = context.dp(PanelStyle.GAP_S_DP) })
        root.addView(statusArea, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        root.addView(resultsScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply { topMargin = context.dp(PanelStyle.GAP_S_DP) })
        root.addView(footer, matchWrap().apply { topMargin = context.dp(PanelStyle.GAP_S_DP) })
    }

    /** Shows the exact editor text that will be sent, before any network request begins. */
    fun showSourcePreview(
        source: String,
        providerLabel: String? = null,
        origin: AiReplySourceOrigin? = null,
        scope: AiSourceScope = AiSourceScope.CursorContext
    ) {
        replySourceOrigin = origin
        sourceReviewReturnsToResults = false
        resultPresentation = null
        useFullHeightActionCatalog()
        sourcePreviewCanExpand = source.length > SOURCE_PREVIEW_EXPAND_CHARACTERS ||
            source.count { it == '\n' } >= SOURCE_PREVIEW_EXPAND_NEWLINES
        sourceText.text = source
        sourceMeta.text = context.getString(R.string.ai_source_label) + " · " + context.getString(
            when (origin) {
                AiReplySourceOrigin.Shared -> R.string.ai_reply_source_shared
                AiReplySourceOrigin.Clipboard -> R.string.ai_reply_source_clipboard
                null -> when (scope) {
                    AiSourceScope.Selection -> R.string.ai_source_scope_selection
                    AiSourceScope.EntireEditor -> R.string.ai_source_scope_editor
                    AiSourceScope.CursorContext -> R.string.ai_source_scope_cursor_context
                    AiSourceScope.ExternalReply -> R.string.ai_reply_source_shared
                }
            }
        )
        renderSourcePreviewMeta()
        sourceText.contentDescription = when (origin) {
            AiReplySourceOrigin.Shared -> context.getString(R.string.ai_reply_source_shared)
            AiReplySourceOrigin.Clipboard -> context.getString(R.string.ai_reply_source_clipboard)
            null -> context.getString(R.string.ai_source_label)
        } + ": " + source
        sourceText.visibility = if (source.isBlank()) View.GONE else View.VISIBLE
        sourceMeta.visibility = sourceText.visibility
        sourcePreviewToggle.visibility = if (source.isNotBlank() && sourcePreviewCanExpand) {
            View.VISIBLE
        } else {
            View.GONE
        }
        updateSourceSectionVisibility()
        sourceReviewScroll.visibility = View.GONE
        renderActionCatalog(AiActionCatalogState.Source)
        actionsScroll.post { actionsScroll.scrollTo(0, 0) }
        setProvider(providerLabel)
        selectedAction = null
        updateActionButtons(AiActionMenuPolicy.enabledActions(source.isNotBlank()))
        clearResultActionState()
        results.removeAllViews()
        resultsScroll.visibility = View.GONE
        progress.visibility = View.GONE
        statusArea.visibility = View.GONE
        status.visibility = View.GONE
        retryButton.visibility = View.GONE
        setupButton.visibility = View.GONE
        setIntakeInteractionEnabled(true)
        updateFooterVisibility()
    }

    /** Keeps the source visible while the selected action is running. */
    fun showLoading(action: AiAction, providerLabel: String) {
        selectedAction = action
        resultPresentation = null
        // Keep the catalog useful while a request is running.  A full-height status pane
        // otherwise takes half of a compact IME and hides the lower tone/translation actions.
        useActionCatalogWithCompactStatus()
        setProvider(providerLabel)
        sourceText.visibility = if (sourceText.text.isNullOrBlank()) View.GONE else View.VISIBLE
        updateSourceSectionVisibility()
        sourcePreviewToggle.visibility = View.GONE
        sourceReviewScroll.visibility = View.GONE
        // Keep every action discoverable while the request is in flight.  The buttons become
        // disabled below, but only the direct-prompt editor is allowed to replace this catalog.
        renderActionCatalog(AiActionCatalogState.Loading)
        updateActionButtons(emptySet())
        clearResultActionState()
        results.removeAllViews()
        resultsScroll.visibility = View.GONE
        statusArea.visibility = View.VISIBLE
        status.visibility = View.GONE
        progress.visibility = View.VISIBLE
        retryButton.visibility = View.GONE
        setupButton.visibility = View.GONE
        setIntakeInteractionEnabled(false)
        updateFooterVisibility()
        root.announceForAccessibility(context.getString(R.string.ai_loading))
    }

    /** Renders at most three suggestions, each with explicit Replace and Append actions. */
    fun showResults(
        action: AiAction,
        providerLabel: String,
        source: String,
        suggestions: List<String>
    ) {
        selectedAction = action
        setProvider(providerLabel)
        sourceReviewReturnsToResults = false
        val visibleSuggestions = suggestions.asSequence()
            .filter { it.isNotBlank() }
            .take(action.maxSuggestions.coerceIn(1, 3))
            .toList()
        if (visibleSuggestions.isEmpty()) {
            showError(context.getString(R.string.ai_error_empty_result), providerLabel)
            return
        }
        val presentation = AiResultPresentationPolicy.decide(
            source = source,
            suggestions = visibleSuggestions,
            hasExternalReplySource = replySourceOrigin != null
        )
        resultPresentation = presentation
        if (presentation == AiResultPresentation.ApplyDecision) {
            useResultPriorityLayout()
        } else {
            // An unchanged result has no decision to make.  Give the action catalog its viewport
            // back instead of leaving a tall, empty result pane below one visible action row.
            useNoOpResultLayout()
        }
        // Keep a compact original-text chip in view for every action, not only proofreading.
        // Replacing tone or translation output without the original nearby is too easy to misread.
        sourceReviewScroll.visibility = View.GONE
        showResultSourceContext()
        // Keep follow-up actions reachable without letting their catalog push the explicit
        // Replace/Append decision below the fold.
        renderActionCatalog(AiActionCatalogState.Results)
        updateActionButtons(AiActionMenuPolicy.allEntryPoints())
        progress.visibility = View.GONE
        statusArea.visibility = View.GONE
        status.visibility = View.GONE
        retryButton.visibility = View.GONE
        setupButton.visibility = View.GONE
        clearResultActionState()
        results.removeAllViews()
        if (presentation == AiResultPresentation.NoChanges) {
            results.addView(noChangesResultCard(), matchWrap().apply {
                bottomMargin = context.dp(PanelStyle.GAP_M_DP)
            })
        } else {
            visibleSuggestions
                .filter { suggestion ->
                    AiResultApplyPolicy.canApply(
                        source = source,
                        suggestion = suggestion,
                        hasExternalReplySource = replySourceOrigin != null
                    )
                }
                .forEachIndexed { index, suggestion ->
                    results.addView(resultCard(action, index, source, suggestion), matchWrap().apply {
                        bottomMargin = context.dp(PanelStyle.GAP_M_DP)
                    })
                }
        }
        if (results.childCount == 0) {
            showError(context.getString(R.string.ai_error_empty_result), providerLabel)
            return
        }
        resultsScroll.visibility = View.VISIBLE
        resultsScroll.post { resultsScroll.scrollTo(0, 0) }
        setIntakeInteractionEnabled(true)
        updateFooterVisibility()
        resultsScroll.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        resultsScroll.announceForAccessibility(context.getString(R.string.ai_results_ready))
    }

    /**
     * [recovery] fills the setup slot when the failure is a policy or connection block, so
     * the user gets the exact setting that reopens it next to the message.
     */
    fun showError(
        message: String,
        providerLabel: String? = null,
        canRetry: Boolean = true,
        recovery: PanelRecovery? = null
    ) {
        setProvider(providerLabel)
        resultPresentation = null
        // Error copy is one small status item, not a second half-height pane.  Keeping it compact
        // leaves the complete non-direct action catalog visible above it.
        useActionCatalogWithCompactStatus()
        sourceText.visibility = if (sourceText.text.isNullOrBlank()) View.GONE else View.VISIBLE
        updateSourceSectionVisibility()
        sourcePreviewToggle.visibility = View.GONE
        sourceReviewScroll.visibility = View.GONE
        // Network and provider errors should leave the next writing action immediately visible.
        renderActionCatalog(AiActionCatalogState.Error)
        updateActionButtons(AiActionMenuPolicy.allEntryPoints())
        clearResultActionState()
        results.removeAllViews()
        resultsScroll.visibility = View.GONE
        progress.visibility = View.GONE
        statusArea.visibility = View.VISIBLE
        status.apply {
            text = context.getString(R.string.ai_error, message)
            contentDescription = text
            setTextColor(PanelStyle.errorTextColor(theme))
            visibility = View.VISIBLE
        }
        retryButton.visibility = if (canRetry) View.VISIBLE else View.GONE
        setRecovery(recovery)
        setIntakeInteractionEnabled(true)
        updateFooterVisibility()
    }

    /** Points the setup slot at [recovery], or hides it when there is nothing to fix. */
    private fun setRecovery(recovery: PanelRecovery?) {
        pendingRecovery = recovery
        setupButton.setText(recovery?.labelRes ?: R.string.ai_setup_action)
        setupButton.visibility = if (recovery == null) View.GONE else View.VISIBLE
    }

    fun showSetupRequired(message: String) {
        setProvider(null)
        resultPresentation = null
        useFullHeightActionCatalog()
        sourceText.visibility = View.GONE
        sourceSection.visibility = View.GONE
        sourceReviewScroll.visibility = View.GONE
        renderActionCatalog(AiActionCatalogState.SetupRequired)
        updateActionButtons(emptySet())
        clearResultActionState()
        results.removeAllViews()
        resultsScroll.visibility = View.GONE
        progress.visibility = View.GONE
        statusArea.visibility = View.VISIBLE
        status.apply {
            text = message
            contentDescription = context.getString(
                R.string.ai_setup_required_accessibility,
                message
            )
            setTextColor(theme.keyTextColor)
            visibility = View.VISIBLE
        }
        retryButton.visibility = View.GONE
        setRecovery(null)
        setupButton.visibility = View.VISIBLE
        setIntakeAvailable(false)
        updateFooterVisibility()
    }

    /**
     * Renders clipboard choices in the IME-owned window instead of opening an attached Android
     * dialog. This remains valid across the prompt-to-assistant handoff on foldables.
     */
    fun showClipboardSourceChoices(items: List<AiClipboardPickerItem>) {
        replySourceOrigin = null
        resultPresentation = null
        useFullHeightActionCatalog()
        sourceText.visibility = View.GONE
        sourceSection.visibility = View.GONE
        sourceReviewScroll.visibility = View.GONE
        renderActionCatalog(AiActionCatalogState.ClipboardPicker)
        updateActionButtons(emptySet())
        progress.visibility = View.GONE
        statusArea.visibility = View.GONE
        retryButton.visibility = View.GONE
        setupButton.visibility = View.GONE
        clearResultActionState()
        results.removeAllViews()
        results.addView(sectionLabel(R.string.ai_clipboard_source_title), matchWrap().apply {
            bottomMargin = context.dp(PanelStyle.GAP_S_DP)
        })
        items.forEach { item ->
            results.addView(context.panelButton(theme, PanelButtonKind.Secondary).apply {
                text = item.label
                contentDescription = item.label
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { onClipboardSourceSelected?.invoke(item.id) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(PanelStyle.BUTTON_HEIGHT_DP)
            ).apply { bottomMargin = context.dp(PanelStyle.GAP_S_DP) })
        }
        results.addView(context.panelButton(theme, PanelButtonKind.Secondary).apply {
            setText(android.R.string.cancel)
            setOnClickListener { onClipboardSourceSelectionCancelled?.invoke() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            context.dp(PanelStyle.COMPACT_BUTTON_HEIGHT_DP)
        ))
        resultsScroll.visibility = View.VISIBLE
        resultsScroll.post { resultsScroll.scrollTo(0, 0) }
        setIntakeInteractionEnabled(true)
        updateFooterVisibility()
        resultsScroll.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    fun setUndoAvailable(available: Boolean) {
        undoButton.setText(if (available) R.string.ai_undo_applied else R.string.ai_undo)
        undoButton.contentDescription = undoButton.text
        undoButton.visibility = if (available) View.VISIBLE else View.GONE
        updateFooterVisibility()
    }

    fun setIntakeAvailable(available: Boolean) {
        intakeAvailable = available
    }

    /**
     * A reviewed result may mutate the editor exactly once. Keep the card state honest after an
     * accepted apply instead of leaving Replace/Append looking tappable while the gate rejects it.
     */
    fun setIntakeInteractionEnabled(enabled: Boolean) {
        resultActionControlsEnabled = enabled
        if (!enabled) {
            resultInteractionViews.forEach { control ->
                control.isEnabled = false
                // Buttons fade via state color lists; only alpha-fade the other controls.
                if (control !is Button) control.alpha = RESULT_CONTROL_DISABLED_ALPHA
            }
        }
        resultAppliedLabels.forEach { label ->
            label.visibility = if (enabled) View.GONE else View.VISIBLE
        }
    }

    private fun clearResultActionState() {
        resultInteractionViews.clear()
        resultAppliedLabels.clear()
        resultActionControlsEnabled = true
    }

    private fun registerResultActionControl(button: Button): Button = button.apply {
        resultInteractionViews += this
        // Buttons fade through their state color lists; no manual alpha on top.
        if (!resultActionControlsEnabled) isEnabled = false
    }

    private fun <T : View> registerResultInteraction(view: T): T = view.apply {
        resultInteractionViews += this
        if (!resultActionControlsEnabled) {
            isEnabled = false
            alpha = RESULT_CONTROL_DISABLED_ALPHA
        }
    }

    private fun appliedResultLabel(): TextView = TextView(context).apply {
        setText(R.string.ai_result_applied)
        setTextColor(theme.altKeyTextColor)
        textSize = PanelStyle.TEXT_BODY
        gravity = Gravity.END
        visibility = if (resultActionControlsEnabled) View.GONE else View.VISIBLE
        resultAppliedLabels += this
    }

    private fun resultCard(
        action: AiAction,
        index: Int,
        source: String,
        suggestion: String
    ): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(PanelStyle.CARD_PADDING_H_DP),
                dp(PanelStyle.CARD_PADDING_V_DP),
                dp(PanelStyle.CARD_PADDING_H_DP),
                dp(PanelStyle.CARD_PADDING_V_DP)
            )
            background = context.panelSurface(theme.altKeyBackgroundColor)

            val patch = AiTextDiff.compute(source, suggestion)

            addView(TextView(context).apply {
                text = context.getString(R.string.ai_result_number, index + 1)
                setTextColor(theme.altKeyTextColor)
                textSize = PanelStyle.TEXT_CAPTION
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrap())

            addView(TextView(context).apply {
                text = if (action == AiAction.Proofread) highlightedSuggestion(patch) else suggestion
                setTextColor(theme.keyTextColor)
                textSize = PanelStyle.TEXT_EMPHASIS
                setPadding(0, dp(PanelStyle.GAP_S_DP), 0, dp(PanelStyle.GAP_S_DP))
            }, matchWrap())

            if (action == AiAction.Proofread) {
                addView(partialChanges(patch), matchWrap().apply {
                    bottomMargin = dp(PanelStyle.GAP_S_DP)
                })
            }

            val canApplySuggestion = AiResultApplyPolicy.canApply(
                source = source,
                suggestion = suggestion,
                hasExternalReplySource = replySourceOrigin != null
            )
            if (!canApplySuggestion) {
                if (action != AiAction.Proofread) {
                    addView(TextView(context).apply {
                        setText(R.string.ai_result_no_changes)
                        setTextColor(theme.altKeyTextColor)
                        textSize = PanelStyle.TEXT_BODY
                    }, matchWrap())
                }
                return@apply
            }

            addView(LinearLayout(context).apply {
                gravity = Gravity.END
                if (source.isEmpty()) {
                    addView(registerResultActionControl(
                        context.panelButton(theme, PanelButtonKind.Primary).apply {
                            setText(R.string.ai_insert)
                            setOnClickListener { onSuggestionReplace?.invoke(suggestion) }
                        }
                    ), LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(PanelStyle.BUTTON_HEIGHT_DP)
                    ))
                } else if (replySourceOrigin != null) {
                    addView(registerResultActionControl(
                        context.panelButton(theme, PanelButtonKind.Primary).apply {
                            setText(R.string.ai_insert_reply)
                            setOnClickListener { onSuggestionReplace?.invoke(suggestion) }
                        }
                    ), LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(PanelStyle.BUTTON_HEIGHT_DP)
                    ))
                } else {
                    addView(registerResultActionControl(
                        context.panelButton(theme, PanelButtonKind.Primary).apply {
                            setText(R.string.ai_replace)
                            setOnClickListener { onSuggestionReplace?.invoke(suggestion) }
                        }
                    ), LinearLayout.LayoutParams(0, dp(PanelStyle.BUTTON_HEIGHT_DP), 1f))
                    addView(registerResultActionControl(
                        context.panelButton(theme, PanelButtonKind.Secondary).apply {
                            setText(R.string.ai_append)
                            setOnClickListener { onSuggestionAppend?.invoke(suggestion) }
                        }
                    ), LinearLayout.LayoutParams(0, dp(PanelStyle.BUTTON_HEIGHT_DP), 1f).apply {
                        marginStart = dp(PanelStyle.GAP_S_DP)
                    })
                }
            }, matchWrap())
            addView(appliedResultLabel(), matchWrap().apply { topMargin = dp(PanelStyle.GAP_S_DP) })
        }

    private fun noChangesResultCard(): View = TextView(context).apply {
        setText(R.string.ai_result_no_changes)
        setTextColor(theme.altKeyTextColor)
        textSize = PanelStyle.TEXT_BODY
        setPadding(
            dp(PanelStyle.CARD_PADDING_H_DP),
            dp(PanelStyle.CARD_PADDING_V_DP),
            dp(PanelStyle.CARD_PADDING_H_DP),
            dp(PanelStyle.CARD_PADDING_V_DP)
        )
        background = context.panelSurface(theme.altKeyBackgroundColor)
    }

    private fun partialChanges(patch: AiTextPatch): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (patch.changes.isEmpty()) {
            addView(TextView(context).apply {
                setText(R.string.ai_diff_no_changes)
                setTextColor(theme.altKeyTextColor)
                textSize = PanelStyle.TEXT_BODY
            }, matchWrap())
            return@apply
        }

        addView(sectionLabel(R.string.ai_diff_changes), matchWrap())
        val selected = linkedSetOf<Int>()
        val applyButton = registerResultActionControl(
            context.panelButton(theme, PanelButtonKind.Primary).apply {
                setText(R.string.ai_diff_apply_selected)
                isEnabled = false
                setOnClickListener {
                    if (selected.isEmpty()) return@setOnClickListener
                    isEnabled = false
                    onSelectedChangesApply?.invoke(patch, selected.toSet())
                }
            }
        )
        patch.changes.forEachIndexed { index, change ->
            addView(registerResultInteraction(CheckBox(context).apply {
                text = context.getString(
                    R.string.ai_diff_change,
                    index + 1,
                    change.original.visibleDiffText(),
                    change.replacement.visibleDiffText()
                )
                setTextColor(theme.keyTextColor)
                textSize = PanelStyle.TEXT_BODY
                minHeight = dp(PanelStyle.COMPACT_BUTTON_HEIGHT_DP)
                setPadding(dp(PanelStyle.GAP_XS_DP), 0, dp(PanelStyle.GAP_XS_DP), 0)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected += change.id else selected -= change.id
                    applyButton.isEnabled = resultActionControlsEnabled && selected.isNotEmpty()
                }
            }), matchWrap())
        }
        addView(applyButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(PanelStyle.COMPACT_BUTTON_HEIGHT_DP)
        ).apply { topMargin = dp(PanelStyle.GAP_S_DP) })
    }

    private fun highlightedSuggestion(patch: AiTextPatch): CharSequence =
        SpannableString(patch.target).apply {
            patch.changes.forEach { change ->
                if (change.targetStart == change.targetEnd) return@forEach
                setSpan(
                    BackgroundColorSpan(theme.genericActiveBackgroundColor),
                    change.targetStart,
                    change.targetEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                setSpan(
                    ForegroundColorSpan(theme.genericActiveForegroundColor),
                    change.targetStart,
                    change.targetEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

    private fun String.visibleDiffText(): String = when {
        isEmpty() -> context.getString(R.string.ai_diff_empty)
        else -> replace(" ", "␠").replace("\n", "↵").replace("\t", "⇥")
    }

    private fun LinearLayout.addActionGroup(
        @StringRes labelRes: Int,
        actions: List<AiAction>,
        columns: Int
    ) {
        addView(sectionLabel(labelRes), matchWrap().apply { topMargin = dp(PanelStyle.GAP_XS_DP) })
        actions.chunked(columns).forEach { rowActions ->
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                rowActions.forEachIndexed { index, action ->
                    // The source surface deliberately keeps every action group visible before
                    // scrolling. This compact IME row is still comfortably larger than a character
                    // key on the same keyboard and avoids hiding translation actions below the
                    // fold on a phone.
                    addView(actionButton(action), LinearLayout.LayoutParams(
                        0,
                        dp(PanelStyle.COMPACT_BUTTON_HEIGHT_DP),
                        1f
                    ).apply {
                        if (index > 0) marginStart = dp(PanelStyle.GAP_S_DP)
                    })
                }
            }, matchWrap().apply { topMargin = dp(PanelStyle.GAP_XS_DP) })
        }
    }

    private fun actionButton(action: AiAction): Button =
        context.panelButton(
            theme,
            PanelButtonKind.Secondary,
            horizontalPaddingDp = PanelStyle.GAP_S_DP
        ).apply {
            setText(action.labelRes())
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            contentDescription = context.getString(action.labelRes())
            actionButtons[action] = this
            setOnClickListener {
                selectedAction = action
                updateActionButtons(AiActionMenuPolicy.allEntryPoints())
                if (action == AiAction.Custom) {
                    onCustomPromptRequested?.invoke("")
                } else {
                    onActionSelected?.invoke(action)
                }
            }
        }

    private fun updateActionButtons(enabledActions: Set<AiAction>) {
        actionButtons.forEach { (action, button) ->
            val selected = action == selectedAction
            val actionEnabled = action in enabledActions
            button.isEnabled = actionEnabled
            button.setTextColor(PanelStyle.stateColors(
                if (selected) theme.genericActiveForegroundColor else theme.keyTextColor
            ))
            button.backgroundTintList = PanelStyle.stateColors(
                if (selected) theme.genericActiveBackgroundColor else theme.keyBackgroundColor
            )
            button.isSelected = selected
            button.contentDescription = if (selected) {
                context.getString(R.string.ai_action_selected, context.getString(action.labelRes()))
            } else {
                context.getString(action.labelRes())
            }
        }
    }

    private fun setProvider(label: String?) {
        transmissionDisclosure.visibility = if (label.isNullOrBlank()) {
            View.GONE
        } else {
            transmissionDisclosure.text =
                context.getString(R.string.ai_transmission_disclosure, label)
            View.VISIBLE
        }
        updateSourceSectionVisibility()
    }

    private fun updateSourceSectionVisibility() {
        sourceSection.visibility =
            if (sourceText.visibility == View.VISIBLE ||
                transmissionDisclosure.visibility == View.VISIBLE
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun toggleSourcePreview() {
        if (!sourcePreviewCanExpand || actionsScroll.visibility != View.VISIBLE) return
        openSourceReview()
    }

    private fun renderSourcePreviewMeta() {
        sourceText.maxLines = SOURCE_PREVIEW_COLLAPSED_LINES
        sourceText.ellipsize = TextUtils.TruncateAt.END
        sourceText.isClickable = sourcePreviewCanExpand
        sourceText.isFocusable = sourcePreviewCanExpand
        sourcePreviewToggle.text = context.getString(R.string.ai_source_preview_full)
        sourcePreviewToggle.contentDescription = sourcePreviewToggle.text
    }

    /** Shows the exact source in its own scrollable view instead of pretending a short preview is full. */
    private fun openSourceReview() {
        val source = sourceText.text?.toString().orEmpty()
        if (source.isBlank()) return
        sourceReviewReturnsToResults = resultsScroll.visibility == View.VISIBLE
        sourceReviewTitle.text = sourceMeta.text
        sourceReviewText.text = source
        sourceSection.visibility = View.GONE
        renderActionCatalog(AiActionCatalogState.SourceReview)
        statusArea.visibility = View.GONE
        resultsScroll.visibility = View.GONE
        sourceReviewScroll.visibility = View.VISIBLE
        sourceReviewScroll.post { sourceReviewScroll.scrollTo(0, 0) }
        sourceReviewScroll.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    private fun hideSourceReview() {
        if (sourceReviewScroll.visibility != View.VISIBLE) return
        sourceReviewScroll.visibility = View.GONE
        updateSourceSectionVisibility()
        if (sourceReviewReturnsToResults) {
            if (resultPresentation == AiResultPresentation.NoChanges) {
                useNoOpResultLayout()
            } else {
                useResultPriorityLayout()
            }
            renderActionCatalog(AiActionCatalogState.Results)
            resultsScroll.visibility = View.VISIBLE
            resultsScroll.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        } else {
            useFullHeightActionCatalog()
            renderActionCatalog(AiActionCatalogState.Source)
            actionsScroll.post { actionsScroll.scrollTo(0, 0) }
            actionsScroll.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
        sourceReviewReturnsToResults = false
    }

    private fun showResultSourceContext() {
        val hasSource = !sourceText.text.isNullOrBlank()
        sourceText.maxLines = 1
        sourceText.ellipsize = TextUtils.TruncateAt.END
        sourceText.isClickable = sourcePreviewCanExpand
        sourceText.isFocusable = sourcePreviewCanExpand
        sourceText.visibility = if (hasSource) View.VISIBLE else View.GONE
        sourceMeta.text = context.getString(R.string.ai_result_source)
        sourceMeta.visibility = sourceText.visibility
        sourcePreviewToggle.visibility = if (hasSource && sourcePreviewCanExpand) View.VISIBLE else View.GONE
        updateSourceSectionVisibility()
    }

    private fun renderActionCatalog(state: AiActionCatalogState) {
        val visibility = if (AiActionCatalogPolicy.isVisible(state)) View.VISIBLE else View.GONE
        actionSection.visibility = visibility
        actionsScroll.visibility = visibility
    }

    /** Lets source and error states use the full scrollable catalog height. */
    private fun useFullHeightActionCatalog() {
        setPaneWeight(actionsScroll, height = 0, weight = 1f)
        setPaneWeight(resultsScroll, height = 0, weight = 1f)
        setPaneWeight(statusArea, height = 0, weight = 1f)
    }

    /** Lets loading and error copy stay compact so their action catalog remains usable. */
    private fun useActionCatalogWithCompactStatus() {
        setPaneWeight(actionsScroll, height = 0, weight = 1f)
        setPaneWeight(resultsScroll, height = 0, weight = 1f)
        setPaneWeight(
            statusArea,
            height = LinearLayout.LayoutParams.WRAP_CONTENT,
            weight = 0f
        )
    }

    /**
     * A completed suggestion is the next decision, not the action menu. Keep the catalog visible
     * for a follow-up action, but cap it so the first result and its explicit apply controls get
     * the rest of the IME viewport.
     */
    private fun useResultPriorityLayout() {
        setPaneWeight(
            actionsScroll,
            height = context.dp(RESULT_ACTION_CATALOG_MAX_HEIGHT_DP),
            weight = 0f
        )
        setPaneWeight(resultsScroll, height = 0, weight = 1f)
        setPaneWeight(statusArea, height = 0, weight = 1f)
    }

    /** A no-op has no Replace/Append decision, so keep its tiny status below the full catalog. */
    private fun useNoOpResultLayout() {
        setPaneWeight(actionsScroll, height = 0, weight = 1f)
        setPaneWeight(
            resultsScroll,
            height = LinearLayout.LayoutParams.WRAP_CONTENT,
            weight = 0f
        )
        setPaneWeight(statusArea, height = 0, weight = 1f)
    }

    private fun setPaneWeight(view: View, height: Int, weight: Float) {
        val params = view.layoutParams as LinearLayout.LayoutParams
        if (params.height == height && params.weight == weight) return
        params.height = height
        params.weight = weight
        view.layoutParams = params
    }

    private fun updateFooterVisibility() {
        footer.visibility = if (
            setupButton.visibility == View.VISIBLE ||
            retryButton.visibility == View.VISIBLE ||
            undoButton.visibility == View.VISIBLE
        ) View.VISIBLE else View.GONE
    }

    private fun sectionLabel(@StringRes textRes: Int) = TextView(context).apply {
        setText(textRes)
        setTextColor(theme.altKeyTextColor)
        textSize = PanelStyle.TEXT_CAPTION
        gravity = Gravity.CENTER_VERTICAL
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
        AiAction.Custom -> R.string.ai_action_custom
        AiAction.TranslateEnglish -> R.string.ai_action_translate_english
        AiAction.TranslateKorean -> R.string.ai_action_translate_korean
        AiAction.TranslateJapanese -> R.string.ai_action_translate_japanese
        AiAction.TranslateChinese -> R.string.ai_action_translate_chinese
        // Programmatic-only action (dashboard graph enrichment); never shown in the writing menu,
        // but the exhaustive when needs a branch. A valid label keeps it safe if ever rendered.
        AiAction.GraphEnrich -> R.string.enrich_now_button
        AiAction.ContinueTyping -> R.string.ai_action_compose
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private companion object {
        // Cap = 2 × COMPACT_BUTTON_HEIGHT_DP: exactly two compact catalog rows stay visible
        // above the result pane.
        const val RESULT_ACTION_CATALOG_MAX_HEIGHT_DP = 2 * PanelStyle.COMPACT_BUTTON_HEIGHT_DP
        const val RESULT_CONTROL_DISABLED_ALPHA = PanelStyle.DISABLED_ALPHA
        const val SOURCE_PREVIEW_COLLAPSED_LINES = 2
        const val SOURCE_PREVIEW_EXPAND_CHARACTERS = 140
        const val SOURCE_PREVIEW_EXPAND_NEWLINES = 2
    }
}

/**
 * The action catalog is the default AI-writing surface.  A direct prompt is intentionally the
 * only writing state that replaces it; setup, source review, and clipboard picking are separate
 * focused screens rather than a collapsed action menu.
 */
internal enum class AiActionCatalogState {
    Source,
    Loading,
    Results,
    Error,
    SetupRequired,
    ClipboardPicker,
    SourceReview,
    DirectPrompt
}

internal object AiActionCatalogPolicy {
    fun isVisible(state: AiActionCatalogState): Boolean = when (state) {
        AiActionCatalogState.Source,
        AiActionCatalogState.Loading,
        AiActionCatalogState.Results,
        AiActionCatalogState.Error -> true

        AiActionCatalogState.SetupRequired,
        AiActionCatalogState.ClipboardPicker,
        AiActionCatalogState.SourceReview,
        AiActionCatalogState.DirectPrompt -> false
    }
}

/** Chooses the viewport priority without ever enabling a no-op suggestion. */
internal enum class AiResultPresentation {
    ApplyDecision,
    NoChanges
}

internal object AiResultPresentationPolicy {
    fun decide(
        source: String,
        suggestions: List<String>,
        hasExternalReplySource: Boolean
    ): AiResultPresentation = if (suggestions.any { suggestion ->
            AiResultApplyPolicy.canApply(source, suggestion, hasExternalReplySource)
        }) {
        AiResultPresentation.ApplyDecision
    } else {
        AiResultPresentation.NoChanges
    }
}
