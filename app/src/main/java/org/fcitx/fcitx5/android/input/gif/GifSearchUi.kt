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
import org.fcitx.fcitx5.android.input.keyboard.HangulKeyLegends

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
        gravity = Gravity.CENTER
        setPadding(0, context.dp(1), 0, context.dp(3))
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

    private val queryKeyboard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(4), context.dp(3), context.dp(4), context.dp(5))
        visibility = View.GONE
    }

    private val letterKeys = mutableListOf<Pair<Char, TextView>>()
    private lateinit var shiftKey: TextView
    private lateinit var languageKey: TextView

    var onQueryClick: (() -> Unit)? = null
    var onKeyword: ((String) -> Unit)? = null
    var onRetry: (() -> Unit)? = null
    var onQueryCharacter: ((Char) -> Unit)? = null
    var onQueryBackspace: (() -> Unit)? = null
    var onQuerySpace: (() -> Unit)? = null
    var onQueryClear: (() -> Unit)? = null
    var onQueryLanguage: (() -> Unit)? = null
    var onQueryShift: (() -> Unit)? = null
    var onQuerySubmit: (() -> Unit)? = null

    init {
        val keywords = listOf(
            R.string.gif_keyword_celebrate,
            R.string.gif_keyword_laugh,
            R.string.gif_keyword_love,
            R.string.gif_keyword_applause,
            R.string.gif_keyword_thanks,
            R.string.gif_keyword_surprise,
            R.string.gif_keyword_angry,
            R.string.gif_keyword_sad
        )
        keywords.forEach { stringRes ->
            val value = context.getString(stringRes)
            keywordRow.addView(TextView(context).apply {
                text = value
                gravity = Gravity.CENTER
                setTextColor(theme.altKeyTextColor)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(context.dp(12), 0, context.dp(12), 0)
                background = rounded(theme.altKeyBackgroundColor, context.dp(14).toFloat())
                setOnClickListener { onKeyword?.invoke(value) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                context.dp(30)
            ).apply { marginEnd = context.dp(5) })
        }

        queryKeyboard.addView(letterRow("qwertyuiop", horizontalInset = 0), weightedRowParams())
        queryKeyboard.addView(letterRow("asdfghjkl", horizontalInset = 14), weightedRowParams())
        queryKeyboard.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            shiftKey = actionKey("⇧") { onQueryShift?.invoke() }
            addView(shiftKey, keyParams(1.25f))
            "zxcvbnm".forEach { key -> addLetterKey(key) }
            addView(actionKey("⌫") { onQueryBackspace?.invoke() }, keyParams(1.25f))
        }, weightedRowParams())
        queryKeyboard.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            languageKey = actionKey("한/영") { onQueryLanguage?.invoke() }
            addView(languageKey, keyParams(1.35f))
            addView(actionKey("space") { onQuerySpace?.invoke() }, keyParams(3.4f))
            addView(actionKey(context.getString(R.string.gif_query_clear)) {
                onQueryClear?.invoke()
            }, keyParams(1.25f))
            addView(actionKey(context.getString(R.string.gif_search_action), active = true) {
                onQuerySubmit?.invoke()
            }, keyParams(1.55f))
        }, weightedRowParams())
        column.addView(queryRow)
        column.addView(keywordScroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            context.dp(32)
        ))
        column.addView(providerLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        column.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        column.addView(queryKeyboard, LinearLayout.LayoutParams(
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

    fun setProviderLabel(label: CharSequence) {
        providerLabel.text = label
    }

    fun setQuery(query: String) {
        queryText.text = query.ifBlank { context.getString(R.string.gif_recommended) }
    }

    fun showQueryEditor(state: GifSearchQueryState) {
        content.visibility = View.GONE
        keywordScroller.visibility = View.GONE
        providerLabel.visibility = View.GONE
        queryKeyboard.visibility = View.VISIBLE
        renderQueryEditor(state)
    }

    fun renderQueryEditor(state: GifSearchQueryState) {
        queryText.text = state.text.ifBlank { context.getString(R.string.gif_query_inline_hint) }
        queryText.alpha = if (state.text.isBlank()) 0.65f else 1f
        letterKeys.forEach { (key, view) ->
            view.text = when (state.language) {
                GifQueryLanguage.Korean ->
                    HangulKeyLegends.legend(key.toString(), state.shifted, "Dubeolsik")
                        ?: key.toString()
                GifQueryLanguage.English ->
                    (if (state.shifted) key.uppercaseChar() else key).toString()
            }
            view.contentDescription = view.text
        }
        shiftKey.alpha = if (state.shifted) 1f else 0.7f
        shiftKey.contentDescription = if (state.shifted) "Shift on" else "Shift off"
        languageKey.text = when (state.language) {
            GifQueryLanguage.Korean -> "한글"
            GifQueryLanguage.English -> "ABC"
        }
        languageKey.contentDescription = languageKey.text
    }

    fun hideQueryEditor() {
        queryKeyboard.visibility = View.GONE
        content.visibility = View.VISIBLE
        keywordScroller.visibility = View.VISIBLE
        providerLabel.visibility = View.VISIBLE
        queryText.alpha = 1f
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

    private fun letterRow(keys: String, horizontalInset: Int): LinearLayout =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(horizontalInset), 0, context.dp(horizontalInset), 0)
            keys.forEach { key -> addLetterKey(key) }
        }

    private fun LinearLayout.addLetterKey(key: Char) {
        val view = actionKey(key.toString()) { onQueryCharacter?.invoke(key) }
        letterKeys += key to view
        addView(view, keyParams(1f))
    }

    private fun actionKey(
        label: String,
        active: Boolean = false,
        action: () -> Unit
    ) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(if (active) theme.genericActiveForegroundColor else theme.keyTextColor)
        textSize = if (label.length > 4) 11f else 16f
        background = rounded(
            if (active) theme.genericActiveBackgroundColor else theme.keyBackgroundColor,
            context.dp(7).toFloat()
        )
        setOnClickListener { action() }
        isClickable = true
        isFocusable = true
        contentDescription = label
    }

    private fun keyParams(weight: Float) = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.MATCH_PARENT,
        weight
    ).apply {
        setMargins(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
    }

    private fun weightedRowParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        0,
        1f
    )
}
