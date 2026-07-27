/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.search

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
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
import org.fcitx.fcitx5.android.input.emotion.KoreanEmotionLexicon

class KoreanSearchUi(private val context: Context, private val theme: Theme) {
    val root = FrameLayout(context).apply { setBackgroundColor(theme.barColor) }
    val recyclerView = RecyclerView(context).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        setPadding(context.dp(8))
        clipToPadding = false
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
        contentDescription = context.getString(R.string.korean_search_action)
        setOnClickListener { onQueryClick?.invoke() }
    }
    private val localNotice = TextView(context).apply {
        text = context.getString(R.string.korean_search_local_notice)
        setTextColor(theme.altKeyTextColor)
        textSize = 10f
        gravity = Gravity.CENTER
    }
    private val emotionRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(context.dp(6), 0, context.dp(6), context.dp(2))
    }
    private val emotionScroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(emotionRow)
    }
    private val initialPad = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(6), context.dp(2), context.dp(6), context.dp(4))
    }
    private val queryRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(8), context.dp(6), context.dp(4), context.dp(2))
        addView(queryText, LinearLayout.LayoutParams(0, context.dp(42), 1f))
        addView(searchButton, LinearLayout.LayoutParams(context.dp(44), context.dp(44)))
    }
    private val message = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(theme.keyTextColor)
        textSize = 15f
        setPadding(context.dp(24))
    }
    private val progress = ProgressBar(context).apply {
        indeterminateTintList = ColorStateList.valueOf(theme.genericActiveBackgroundColor)
        visibility = View.GONE
    }
    private val statusOverlay = FrameLayout(context).apply {
        addView(message, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        addView(progress, FrameLayout.LayoutParams(context.dp(42), context.dp(42), Gravity.CENTER))
    }
    private val actionStatus = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 13f
        setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(6))
        visibility = View.GONE
    }

    var onQueryClick: (() -> Unit)? = null
    var onParticleSuggestions: (() -> Unit)? = null
    var onDictionary: (() -> Unit)? = null
    var onEmotionQuery: ((String) -> Unit)? = null
    var onInitial: ((String) -> Unit)? = null
    var onBackspace: (() -> Unit)? = null
    var onClear: (() -> Unit)? = null

    init {
        emotionRow.addView(TextView(context).apply {
            setText(R.string.korean_particle_chip)
            gravity = Gravity.CENTER
            setTextColor(theme.genericActiveForegroundColor)
            textSize = 12f
            background = rounded(theme.genericActiveBackgroundColor, context.dp(14).toFloat())
            setPadding(context.dp(12), 0, context.dp(12), 0)
            setOnClickListener { onParticleSuggestions?.invoke() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            context.dp(30)
        ).apply { marginEnd = context.dp(5) })
        emotionRow.addView(TextView(context).apply {
            setText(R.string.korean_dictionary_chip)
            gravity = Gravity.CENTER
            setTextColor(theme.genericActiveForegroundColor)
            textSize = 12f
            background = rounded(theme.genericActiveBackgroundColor, context.dp(14).toFloat())
            setPadding(context.dp(12), 0, context.dp(12), 0)
            setOnClickListener { onDictionary?.invoke() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            context.dp(30)
        ).apply { marginEnd = context.dp(5) })
        KoreanEmotionLexicon.quickQueries.forEach { query ->
            emotionRow.addView(TextView(context).apply {
                text = query
                gravity = Gravity.CENTER
                setTextColor(theme.altKeyTextColor)
                textSize = 12f
                background = rounded(theme.altKeyBackgroundColor, context.dp(14).toFloat())
                contentDescription = query
                setPadding(context.dp(12), 0, context.dp(12), 0)
                setOnClickListener { onEmotionQuery?.invoke(query) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                context.dp(30)
            ).apply { marginEnd = context.dp(5) })
        }
        listOf(
            listOf("ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ", "ㄹ", "ㅁ"),
            listOf("ㅂ", "ㅃ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅉ"),
            listOf("ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ", "⌫", context.getString(R.string.korean_search_clear))
        ).forEach { keys ->
            initialPad.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                keys.forEach { key ->
                    addView(TextView(context).apply {
                        text = key
                        gravity = Gravity.CENTER
                        setTextColor(theme.altKeyTextColor)
                        textSize = if (key.length > 2) 11f else 17f
                        background = rounded(theme.altKeyBackgroundColor, context.dp(8).toFloat())
                        contentDescription = when (key) {
                            "⌫" -> context.getString(R.string.korean_search_backspace)
                            context.getString(R.string.korean_search_clear) -> key
                            else -> context.getString(R.string.korean_search_initial_key, key)
                        }
                        setOnClickListener {
                            when (key) {
                                "⌫" -> onBackspace?.invoke()
                                context.getString(R.string.korean_search_clear) -> onClear?.invoke()
                                else -> onInitial?.invoke(key)
                            }
                        }
                    }, LinearLayout.LayoutParams(0, context.dp(34), 1f).apply {
                        marginStart = context.dp(2)
                        marginEnd = context.dp(2)
                        bottomMargin = context.dp(3)
                    })
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(37)
            ))
        }
        val content = FrameLayout(context).apply {
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
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(queryRow)
            addView(localNotice, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(22)
            ))
            addView(emotionScroller, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(34)
            ))
            addView(initialPad, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(115)
            ))
            addView(content, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setQuery("")
        showPrompt()
    }

    fun setQuery(query: String) {
        queryText.text = query.ifBlank { context.getString(R.string.korean_search_query_hint) }
    }

    fun setContentMode(emotion: Boolean = false, dictionary: Boolean = false) {
        initialPad.visibility = if (emotion || dictionary) View.GONE else View.VISIBLE
        localNotice.setText(
            if (dictionary) R.string.korean_dictionary_notice
            else R.string.korean_search_local_notice
        )
    }

    fun showPrompt() = showMessage(context.getString(R.string.korean_search_prompt))

    fun showLoading() {
        recyclerView.visibility = View.INVISIBLE
        statusOverlay.visibility = View.VISIBLE
        message.visibility = View.GONE
        progress.visibility = View.VISIBLE
        actionStatus.visibility = View.GONE
    }

    fun showResults(hasResults: Boolean) {
        progress.visibility = View.GONE
        actionStatus.visibility = View.GONE
        if (hasResults) {
            recyclerView.visibility = View.VISIBLE
            statusOverlay.visibility = View.GONE
        } else {
            showMessage(context.getString(R.string.korean_search_no_results))
        }
    }

    fun showMessage(text: String) {
        recyclerView.visibility = View.INVISIBLE
        statusOverlay.visibility = View.VISIBLE
        message.text = text
        message.visibility = View.VISIBLE
        progress.visibility = View.GONE
        actionStatus.visibility = View.GONE
    }

    fun showActionStatus(text: String, isError: Boolean = false) {
        actionStatus.text = text
        actionStatus.setBackgroundColor(if (isError) 0xdd8b1e1e.toInt() else 0xcc202124.toInt())
        actionStatus.visibility = View.VISIBLE
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
