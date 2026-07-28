/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
        textSize = 15f
        setPadding(context.dp(12), 0, context.dp(8), 0)
        background = rounded(theme.altKeyBackgroundColor, context.dp(12).toFloat())
        setOnClickListener { onQueryClick?.invoke() }
    }

    private val searchButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_baseline_search_24)
        imageTintList = ColorStateList.valueOf(theme.altKeyTextColor)
        background = null
        contentDescription = context.getString(R.string.gif_search_action)
        setOnClickListener { onQueryClick?.invoke() }
    }

    private val queryRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(8), context.dp(6), context.dp(4), context.dp(2))
        addView(queryText, LinearLayout.LayoutParams(0, context.dp(42), 1f))
        addView(searchButton, LinearLayout.LayoutParams(context.dp(44), context.dp(44)))
    }

    private val keywordRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(context.dp(6), 0, context.dp(6), context.dp(2))
    }

    private val keywordScroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(keywordRow)
    }

    private val providerLabel = TextView(context).apply {
        setTextColor(theme.altKeyTextColor)
        alpha = 0.72f
        textSize = 10f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, context.dp(1), 0, context.dp(3))
    }

    private val moreGifSettingsButton = Button(context).apply {
        isAllCaps = false
        text = context.getString(R.string.gif_more_settings)
        textSize = 11f
        minHeight = 0
        minimumHeight = 0
        setPadding(context.dp(9), 0, context.dp(9), 0)
        setTextColor(theme.genericActiveForegroundColor)
        background = rounded(theme.genericActiveBackgroundColor, context.dp(12).toFloat())
        contentDescription = text
        visibility = View.GONE
        setOnClickListener { onMoreGifSettings?.invoke() }
    }

    private val providerRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(8), 0, context.dp(8), context.dp(2))
        addView(providerLabel, LinearLayout.LayoutParams(
            0,
            context.dp(30),
            1f
        ))
        addView(moreGifSettingsButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            context.dp(30)
        ).apply { marginStart = context.dp(6) })
    }

    val recyclerView = RecyclerView(context).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        setPadding(context.dp(4))
        clipToPadding = false
    }

    private val centerMessage = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(theme.keyTextColor)
        textSize = 15f
        setPadding(context.dp(24))
        visibility = View.GONE
    }

    private val progress = ProgressBar(context).apply {
        indeterminateTintList = ColorStateList.valueOf(theme.genericActiveBackgroundColor)
        visibility = View.GONE
    }

    private val retryButton = Button(context).apply {
        isAllCaps = false
        text = context.getString(R.string.gif_retry)
        visibility = View.GONE
        setOnClickListener { onRetry?.invoke() }
    }

    private val statusOverlay = FrameLayout(context).apply {
        addView(centerMessage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        addView(progress, FrameLayout.LayoutParams(context.dp(42), context.dp(42), Gravity.CENTER))
        addView(retryButton, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            context.dp(44),
            Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        ).apply { bottomMargin = context.dp(22) })
    }

    private val actionStatus = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setBackgroundColor(0xcc202124.toInt())
        textSize = 13f
        setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(6))
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
            context.dp(32)
        ))
        column.addView(providerRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            context.dp(32)
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
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(context.dp(12), 0, context.dp(12), 0)
                background = rounded(theme.altKeyBackgroundColor, context.dp(14).toFloat())
                setOnClickListener { onKeyword?.invoke(query) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                context.dp(30)
            ).apply { marginEnd = context.dp(5) })
        }
    }

    fun setMoreGifSettingsVisible(visible: Boolean) {
        moreGifSettingsButton.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setProviderLabel(label: CharSequence) {
        providerLabel.text = label
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
        actionStatus.visibility = View.GONE
    }

    fun showResults(hasResults: Boolean) {
        recyclerView.visibility = View.VISIBLE
        progress.visibility = View.GONE
        retryButton.visibility = View.GONE
        if (hasResults) {
            statusOverlay.visibility = View.GONE
        } else {
            statusOverlay.visibility = View.VISIBLE
            centerMessage.text = context.getString(R.string.gif_no_results)
            centerMessage.visibility = View.VISIBLE
        }
        actionStatus.visibility = View.GONE
    }

    fun showBlockingMessage(message: String, retry: Boolean = false) {
        recyclerView.visibility = View.INVISIBLE
        statusOverlay.visibility = View.VISIBLE
        centerMessage.text = message
        centerMessage.visibility = View.VISIBLE
        progress.visibility = View.GONE
        retryButton.visibility = if (retry) View.VISIBLE else View.GONE
        actionStatus.visibility = View.GONE
    }

    fun showActionStatus(message: String, isError: Boolean = false) {
        actionStatus.text = message
        actionStatus.setBackgroundColor(if (isError) 0xdd8b1e1e.toInt() else 0xcc202124.toInt())
        actionStatus.visibility = View.VISIBLE
    }

    fun clearActionStatus() {
        actionStatus.visibility = View.GONE
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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
