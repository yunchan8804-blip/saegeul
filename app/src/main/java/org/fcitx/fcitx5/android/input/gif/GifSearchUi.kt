/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.panel.PanelButtonKind
import org.fcitx.fcitx5.android.input.panel.PanelRecovery
import org.fcitx.fcitx5.android.input.panel.PanelStyle
import org.fcitx.fcitx5.android.input.panel.panelButton
import org.fcitx.fcitx5.android.input.panel.pressableChipSurface
import org.fcitx.fcitx5.android.input.panel.pressablePanelSurface
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable
import splitties.dimensions.dp

class GifSearchUi(private val context: Context, private val theme: Theme) {

    val root = FrameLayout(context).apply {
        setBackgroundColor(theme.barColor)
    }

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    private val queryText = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_EMPHASIS
        setPadding(dp(PanelStyle.CARD_PADDING_H_DP), 0, dp(PanelStyle.GAP_M_DP), 0)
        background = context.pressablePanelSurface(theme, theme.altKeyBackgroundColor)
        setOnClickListener { onQueryClick?.invoke() }
    }

    private val searchButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_baseline_search_24)
        imageTintList = ColorStateList.valueOf(theme.altKeyTextColor)
        // Half of the 48dp touch target, like the toolbar tool buttons.
        background = borderlessRippleDrawable(theme.keyPressHighlightColor, dp(24))
        contentDescription = context.getString(R.string.gif_search_action)
        setOnClickListener { onQueryClick?.invoke() }
    }

    private val queryRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(
            dp(PanelStyle.GAP_M_DP),
            dp(PanelStyle.GAP_S_DP),
            dp(PanelStyle.GAP_S_DP),
            dp(PanelStyle.GAP_S_DP)
        )
        addView(queryText, LinearLayout.LayoutParams(0, dp(PanelStyle.BUTTON_HEIGHT_DP), 1f))
        addView(searchButton, LinearLayout.LayoutParams(
            dp(PanelStyle.BUTTON_HEIGHT_DP),
            dp(PanelStyle.BUTTON_HEIGHT_DP)
        ))
    }

    private val keywordRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(PanelStyle.GAP_M_DP), 0, dp(PanelStyle.GAP_M_DP), dp(PanelStyle.GAP_S_DP))
    }

    private val keywordScroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(keywordRow)
    }

    private val providerLabel = TextView(context).apply {
        setTextColor(theme.altKeyTextColor)
        textSize = PanelStyle.TEXT_CAPTION
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(PanelStyle.GAP_S_DP))
    }
    private val providerDisclosure = TextView(context).apply {
        setTextColor(theme.altKeyTextColor)
        textSize = PanelStyle.TEXT_CAPTION
        setPadding(dp(PanelStyle.GAP_M_DP), 0, dp(PanelStyle.GAP_M_DP), dp(PanelStyle.GAP_S_DP))
    }

    private val moreGifSettingsButton = Button(context).apply {
        isAllCaps = false
        text = context.getString(R.string.gif_more_settings)
        textSize = PanelStyle.TEXT_CAPTION
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(PanelStyle.CARD_PADDING_H_DP), 0, dp(PanelStyle.CARD_PADDING_H_DP), 0)
        // Settings entry keeps the genericActive emphasis tokens; not a committing action.
        setTextColor(theme.genericActiveForegroundColor)
        background = context.pressableChipSurface(theme, theme.genericActiveBackgroundColor)
        contentDescription = text
        visibility = View.GONE
        setOnClickListener { onMoreGifSettings?.invoke() }
    }

    private val providerRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(PanelStyle.GAP_M_DP), 0, dp(PanelStyle.GAP_M_DP), dp(PanelStyle.GAP_XS_DP))
        addView(providerLabel, LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ))
        addView(moreGifSettingsButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(PanelStyle.CHIP_HEIGHT_DP)
        ).apply { marginStart = dp(PanelStyle.GAP_M_DP) })
    }

    val recyclerView = RecyclerView(context).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        setPadding(dp(PanelStyle.GAP_S_DP))
        clipToPadding = false
    }

    private val centerMessage = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_EMPHASIS
        setPadding(dp(PanelStyle.PANEL_PADDING_H_DP))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = View.GONE
    }

    private val progress = ProgressBar(context).apply {
        indeterminateTintList = ColorStateList.valueOf(theme.genericActiveBackgroundColor)
        visibility = View.GONE
    }

    private val retryButton = context.panelButton(theme, PanelButtonKind.Primary).apply {
        text = context.getString(R.string.gif_retry)
        visibility = View.GONE
        setOnClickListener { onRetry?.invoke() }
    }

    // Fix-it action that leaves the panel for the setting behind the block.
    private var recovery: PanelRecovery? = null

    private val recoveryButton = context.panelButton(theme, PanelButtonKind.Secondary).apply {
        visibility = View.GONE
        setOnClickListener { recovery?.run?.invoke() }
    }

    private val statusActions = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        addView(retryButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(PanelStyle.BUTTON_HEIGHT_DP)
        ))
        addView(recoveryButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(PanelStyle.BUTTON_HEIGHT_DP)
        ).apply { marginStart = dp(PanelStyle.GAP_M_DP) })
    }

    private val statusOverlay = FrameLayout(context).apply {
        addView(centerMessage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        addView(progress, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        addView(statusActions, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        ).apply { bottomMargin = dp(20) })
    }

    private val actionStatus = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(PanelStyle.STATUS_SCRIM_TEXT)
        setBackgroundColor(PanelStyle.STATUS_SCRIM_NEUTRAL)
        textSize = PanelStyle.TEXT_BODY
        setPadding(
            dp(PanelStyle.CARD_PADDING_H_DP),
            dp(PanelStyle.CARD_PADDING_V_DP),
            dp(PanelStyle.CARD_PADDING_H_DP),
            dp(PanelStyle.CARD_PADDING_V_DP)
        )
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = View.GONE
    }

    private val content = FrameLayout(context).apply {
        addView(recyclerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        addView(statusOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        addView(actionStatus, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))
    }

    var onQueryClick: (() -> Unit)? = null
    var onKeyword: ((String) -> Unit)? = null
    var onRetry: (() -> Unit)? = null
    var onMoreGifSettings: (() -> Unit)? = null

    init {
        column.addView(queryRow)
        column.addView(keywordScroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        column.addView(providerRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        column.addView(providerDisclosure, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        column.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setQuery("")
    }

    internal fun setQuickSuggestions(suggestions: List<GifQuickSuggestion>) {
        keywordRow.removeAllViews()
        suggestions.forEach { suggestion ->
            val (labelRes, query) = suggestion.labelAndQuery()
            keywordRow.addView(TextView(context).apply {
                text = context.getString(labelRes)
                gravity = Gravity.CENTER
                setTextColor(theme.altKeyTextColor)
                textSize = PanelStyle.TEXT_BODY
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(PanelStyle.CARD_PADDING_H_DP), 0, dp(PanelStyle.CARD_PADDING_H_DP), 0)
                background = context.pressableChipSurface(theme, theme.altKeyBackgroundColor)
                setOnClickListener { onKeyword?.invoke(query) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                context.dp(PanelStyle.CHIP_HEIGHT_DP)
            ).apply { marginEnd = context.dp(PanelStyle.GAP_S_DP) })
        }
    }

    fun setMoreGifSettingsVisible(visible: Boolean) {
        moreGifSettingsButton.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setProviderLabel(label: CharSequence) {
        providerLabel.text = label
    }

    fun setProviderDisclosure(disclosure: CharSequence) {
        providerDisclosure.text = disclosure
        providerDisclosure.contentDescription = disclosure
    }

    fun setQuery(query: String) {
        queryText.text = query.ifBlank { context.getString(R.string.gif_recommended) }
    }

    fun showLoading() {
        recyclerView.visibility = View.INVISIBLE
        statusOverlay.visibility = View.VISIBLE
        centerMessage.text = context.getString(R.string.gif_loading)
        centerMessage.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        retryButton.visibility = View.GONE
        setRecovery(null)
        actionStatus.visibility = View.GONE
    }

    fun showResults(hasResults: Boolean) {
        recyclerView.visibility = View.VISIBLE
        progress.visibility = View.GONE
        retryButton.visibility = View.GONE
        setRecovery(null)
        if (hasResults) {
            statusOverlay.visibility = View.GONE
        } else {
            statusOverlay.visibility = View.VISIBLE
            centerMessage.text = context.getString(R.string.gif_no_results)
            centerMessage.visibility = View.VISIBLE
        }
        actionStatus.visibility = View.GONE
    }

    fun showBlockingMessage(
        message: String,
        retry: Boolean = false,
        recovery: PanelRecovery? = null
    ) {
        recyclerView.visibility = View.INVISIBLE
        statusOverlay.visibility = View.VISIBLE
        centerMessage.text = message
        centerMessage.visibility = View.VISIBLE
        progress.visibility = View.GONE
        retryButton.visibility = if (retry) View.VISIBLE else View.GONE
        setRecovery(recovery)
        actionStatus.visibility = View.GONE
    }

    private fun setRecovery(recovery: PanelRecovery?) {
        this.recovery = recovery
        if (recovery == null) {
            recoveryButton.visibility = View.GONE
            return
        }
        recoveryButton.setText(recovery.labelRes)
        recoveryButton.contentDescription = recoveryButton.text
        recoveryButton.visibility = View.VISIBLE
    }

    fun showActionStatus(message: String, isError: Boolean = false) {
        actionStatus.text = message
        actionStatus.setBackgroundColor(
            if (isError) PanelStyle.STATUS_SCRIM_ERROR else PanelStyle.STATUS_SCRIM_NEUTRAL
        )
        actionStatus.visibility = View.VISIBLE
    }

    fun clearActionStatus() {
        actionStatus.visibility = View.GONE
    }

    private fun GifQuickSuggestion.labelAndQuery(): Pair<Int, String> = when (this) {
        GifQuickSuggestion.Trending -> R.string.gif_keyword_trending to ""
        GifQuickSuggestion.Meme -> R.string.gif_keyword_meme to
            context.getString(R.string.gif_keyword_meme)
        GifQuickSuggestion.LeaveWork -> R.string.gif_keyword_leave_work to
            context.getString(R.string.gif_keyword_leave_work)
        GifQuickSuggestion.Monday -> R.string.gif_keyword_monday to
            context.getString(R.string.gif_keyword_monday)
        GifQuickSuggestion.Laugh -> R.string.gif_keyword_laugh to
            context.getString(R.string.gif_keyword_laugh)
        GifQuickSuggestion.Awkward -> R.string.gif_keyword_awkward to
            context.getString(R.string.gif_keyword_awkward)
        GifQuickSuggestion.Agree -> R.string.gif_keyword_agree to
            context.getString(R.string.gif_keyword_agree)
        GifQuickSuggestion.Wow -> R.string.gif_keyword_wow to
            context.getString(R.string.gif_keyword_wow)
        GifQuickSuggestion.Celebrate -> R.string.gif_keyword_celebrate to
            context.getString(R.string.gif_keyword_celebrate)
        GifQuickSuggestion.Fighting -> R.string.gif_keyword_fighting to
            context.getString(R.string.gif_keyword_fighting)
        GifQuickSuggestion.Love -> R.string.gif_keyword_love to
            context.getString(R.string.gif_keyword_love)
        GifQuickSuggestion.Thanks -> R.string.gif_keyword_thanks to
            context.getString(R.string.gif_keyword_thanks)
        GifQuickSuggestion.Angry -> R.string.gif_keyword_angry to
            context.getString(R.string.gif_keyword_angry)
        GifQuickSuggestion.Sad -> R.string.gif_keyword_sad to
            context.getString(R.string.gif_keyword_sad)
    }

}
