/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.search

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme

class KoreanSearchAdapter(
    private val context: Context,
    private val theme: Theme,
    private val onInsert: (KoreanSearchResult) -> Unit
) : RecyclerView.Adapter<KoreanSearchAdapter.ViewHolder>() {
    private var results: List<KoreanSearchResult> = emptyList()
    private var actionLocked = false

    fun submit(newResults: List<KoreanSearchResult>) {
        results = newResults
        notifyDataSetChanged()
    }

    fun setActionLocked(locked: Boolean) {
        actionLocked = locked
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemUi(context, theme))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val result = results[position]
        holder.ui.bind(result)
        holder.itemView.isEnabled = !actionLocked
        holder.itemView.alpha = if (actionLocked) 0.55f else 1f
        holder.itemView.setOnClickListener {
            if (!actionLocked) onInsert(result)
        }
    }

    override fun getItemCount(): Int = results.size

    class ViewHolder(val ui: ItemUi) : RecyclerView.ViewHolder(ui.root)

    class ItemUi(context: Context, private val theme: Theme) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(12))
            background = GradientDrawable().apply {
                setColor(theme.altKeyBackgroundColor)
                cornerRadius = context.dp(12).toFloat()
            }
            isClickable = true
            isFocusable = true
        }
        private val source = TextView(context).apply {
            setTextColor(theme.altKeyTextColor)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
        }
        private val primary = TextView(context).apply {
            setTextColor(theme.keyTextColor)
            textSize = 16f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, context.dp(3), 0, 0)
        }
        private val secondary = TextView(context).apply {
            setTextColor(theme.altKeyTextColor)
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, context.dp(3), 0, 0)
        }

        init {
            root.addView(source)
            root.addView(primary)
            root.addView(secondary)
        }

        fun bind(result: KoreanSearchResult) {
            val entry = result.entry
            source.text = root.context.getString(when (entry.source) {
                KoreanSearchSource.QuickPhrase -> R.string.korean_search_source_quick_phrase
                KoreanSearchSource.Clipboard -> R.string.korean_search_source_clipboard
                KoreanSearchSource.Emotion -> R.string.korean_search_source_emotion
                KoreanSearchSource.Emoji -> R.string.korean_search_source_emoji
            })
            primary.text = entry.primaryText
            primary.textSize = if (
                entry.source == KoreanSearchSource.Emoji ||
                entry.source == KoreanSearchSource.Emotion
            ) 27f else 16f
            secondary.text = entry.secondaryText.orEmpty()
            secondary.visibility = if (entry.secondaryText.isNullOrBlank()) TextView.GONE else TextView.VISIBLE
            root.contentDescription = listOfNotNull(
                source.text.toString(),
                entry.primaryText,
                entry.secondaryText
            ).joinToString(", ")
        }

        private fun Context.dp(value: Int): Int =
            (value * resources.displayMetrics.density).toInt()
    }
}
