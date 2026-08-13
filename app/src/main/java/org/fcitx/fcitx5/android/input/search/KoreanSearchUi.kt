/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.search

import android.content.Context
import android.content.res.ColorStateList
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
import org.fcitx.fcitx5.android.input.panel.PanelStyle
import org.fcitx.fcitx5.android.input.panel.pressableChipSurface
import org.fcitx.fcitx5.android.input.panel.pressablePanelSurface
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable
import splitties.dimensions.dp

class KoreanSearchUi(private val context: Context, private val theme: Theme) {
    val root = FrameLayout(context).apply { setBackgroundColor(theme.barColor) }
    val recyclerView = RecyclerView(context).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        setPadding(context.dp(PanelStyle.GAP_M_DP))
        clipToPadding = false
    }

    private val queryText = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_EMPHASIS
        setPadding(context.dp(PanelStyle.CARD_PADDING_H_DP), 0, context.dp(PanelStyle.GAP_M_DP), 0)
        background = context.pressablePanelSurface(theme, theme.altKeyBackgroundColor)
        setOnClickListener { onQueryClick?.invoke() }
    }
    private val searchButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_baseline_search_24)
        imageTintList = ColorStateList.valueOf(theme.altKeyTextColor)
        background = borderlessRippleDrawable(theme.keyPressHighlightColor)
        contentDescription = context.getString(R.string.korean_search_action)
        setOnClickListener { onQueryClick?.invoke() }
    }
    private val localNotice = TextView(context).apply {
        text = context.getString(R.string.korean_search_local_notice)
        setTextColor(theme.altKeyTextColor)
        textSize = PanelStyle.TEXT_CAPTION
        gravity = Gravity.CENTER
        setPadding(0, context.dp(PanelStyle.GAP_XS_DP), 0, context.dp(PanelStyle.GAP_XS_DP))
    }
    private val emotionRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(
            context.dp(PanelStyle.GAP_M_DP), 0,
            context.dp(PanelStyle.GAP_M_DP), context.dp(PanelStyle.GAP_S_DP)
        )
    }
    private val emotionScroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        // Fading edge hints that more chips are available past the right edge.
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(context.dp(PanelStyle.PANEL_PADDING_H_DP))
        addView(emotionRow)
    }
    private val initialPad = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            context.dp(PanelStyle.GAP_M_DP), context.dp(PanelStyle.GAP_S_DP),
            context.dp(PanelStyle.GAP_M_DP), context.dp(PanelStyle.GAP_S_DP)
        )
    }
    private val queryRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(
            context.dp(PanelStyle.GAP_M_DP), context.dp(PanelStyle.GAP_S_DP),
            context.dp(PanelStyle.GAP_S_DP), context.dp(PanelStyle.GAP_S_DP)
        )
        addView(queryText, LinearLayout.LayoutParams(0, context.dp(PanelStyle.BUTTON_HEIGHT_DP), 1f))
        addView(searchButton, LinearLayout.LayoutParams(
            context.dp(PanelStyle.BUTTON_HEIGHT_DP),
            context.dp(PanelStyle.BUTTON_HEIGHT_DP)
        ))
    }
    private val message = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_EMPHASIS
        setPadding(context.dp(PanelStyle.PANEL_PADDING_H_DP))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
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
        addView(progress, FrameLayout.LayoutParams(
            context.dp(PanelStyle.COMPACT_BUTTON_HEIGHT_DP),
            context.dp(PanelStyle.COMPACT_BUTTON_HEIGHT_DP),
            Gravity.CENTER
        ))
    }
    private val actionStatus = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(PanelStyle.STATUS_SCRIM_TEXT)
        textSize = PanelStyle.TEXT_BODY
        setPadding(
            context.dp(PanelStyle.GAP_M_DP), context.dp(PanelStyle.GAP_S_DP),
            context.dp(PanelStyle.GAP_M_DP), context.dp(PanelStyle.GAP_S_DP)
        )
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
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
            textSize = PanelStyle.TEXT_BODY
            background = context.pressableChipSurface(theme, theme.genericActiveBackgroundColor)
            setPadding(context.dp(PanelStyle.CARD_PADDING_H_DP), 0, context.dp(PanelStyle.CARD_PADDING_H_DP), 0)
            setOnClickListener { onParticleSuggestions?.invoke() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            context.dp(PanelStyle.CHIP_HEIGHT_DP)
        ).apply { marginEnd = context.dp(PanelStyle.GAP_S_DP) })
        emotionRow.addView(TextView(context).apply {
            setText(R.string.korean_dictionary_chip)
            gravity = Gravity.CENTER
            setTextColor(theme.genericActiveForegroundColor)
            textSize = PanelStyle.TEXT_BODY
            background = context.pressableChipSurface(theme, theme.genericActiveBackgroundColor)
            setPadding(context.dp(PanelStyle.CARD_PADDING_H_DP), 0, context.dp(PanelStyle.CARD_PADDING_H_DP), 0)
            setOnClickListener { onDictionary?.invoke() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            context.dp(PanelStyle.CHIP_HEIGHT_DP)
        ).apply { marginEnd = context.dp(PanelStyle.GAP_S_DP) })
        KoreanEmotionLexicon.quickQueries.forEach { query ->
            emotionRow.addView(TextView(context).apply {
                text = query
                gravity = Gravity.CENTER
                setTextColor(theme.altKeyTextColor)
                textSize = PanelStyle.TEXT_BODY
                background = context.pressableChipSurface(theme, theme.altKeyBackgroundColor)
                contentDescription = query
                setPadding(context.dp(PanelStyle.CARD_PADDING_H_DP), 0, context.dp(PanelStyle.CARD_PADDING_H_DP), 0)
                setOnClickListener { onEmotionQuery?.invoke(query) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                context.dp(PanelStyle.CHIP_HEIGHT_DP)
            ).apply { marginEnd = context.dp(PanelStyle.GAP_S_DP) })
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
                        textSize = if (key.length > 2) PanelStyle.TEXT_CAPTION else PanelStyle.TEXT_TITLE
                        background = context.pressablePanelSurface(theme, theme.altKeyBackgroundColor)
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
                    }, LinearLayout.LayoutParams(0, context.dp(PanelStyle.CHIP_HEIGHT_DP), 1f).apply {
                        marginStart = context.dp(PanelStyle.GAP_XS_DP)
                        marginEnd = context.dp(PanelStyle.GAP_XS_DP)
                        bottomMargin = context.dp(PanelStyle.GAP_S_DP)
                    })
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
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
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(emotionScroller, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(initialPad, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
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
        actionStatus.setBackgroundColor(
            if (isError) PanelStyle.STATUS_SCRIM_ERROR else PanelStyle.STATUS_SCRIM_NEUTRAL
        )
        actionStatus.visibility = View.VISIBLE
    }
}
