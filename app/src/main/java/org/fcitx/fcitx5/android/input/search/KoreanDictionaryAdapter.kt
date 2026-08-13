/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.search

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.panel.PanelStyle
import org.fcitx.fcitx5.android.input.panel.panelSurface
import org.fcitx.fcitx5.android.utils.rippleDrawable
import splitties.dimensions.dp

class KoreanDictionaryAdapter(
    private val context: Context,
    private val theme: Theme,
    private val onOpenSource: (KoreanDictionaryEntry) -> Unit
) : RecyclerView.Adapter<KoreanDictionaryAdapter.ViewHolder>() {
    private var entries: List<KoreanDictionaryEntry> = emptyList()

    fun submit(newEntries: List<KoreanDictionaryEntry>) {
        entries = newEntries
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemUi(context, theme))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.ui.bind(entry)
        holder.ui.sourceAction.setOnClickListener { onOpenSource(entry) }
    }

    override fun getItemCount(): Int = entries.size

    class ViewHolder(val ui: ItemUi) : RecyclerView.ViewHolder(ui.root)

    class ItemUi(context: Context, theme: Theme) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                context.dp(PanelStyle.CARD_PADDING_H_DP), context.dp(PanelStyle.CARD_PADDING_V_DP),
                context.dp(PanelStyle.CARD_PADDING_H_DP), context.dp(PanelStyle.CARD_PADDING_V_DP)
            )
            background = context.panelSurface(theme.altKeyBackgroundColor)
        }
        private val title = TextView(context).apply {
            setTextColor(theme.keyTextColor)
            textSize = PanelStyle.TEXT_EMPHASIS
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val definitions = TextView(context).apply {
            setTextColor(theme.altKeyTextColor)
            textSize = PanelStyle.TEXT_BODY
            setLineSpacing(context.dp(PanelStyle.GAP_XS_DP).toFloat(), 1f)
            setPadding(0, context.dp(PanelStyle.GAP_S_DP), 0, context.dp(PanelStyle.GAP_S_DP))
        }
        val sourceAction = TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(theme.accentKeyBackgroundColor)
            textSize = PanelStyle.TEXT_BODY
            setText(R.string.korean_dictionary_source_action)
            isClickable = true
            isFocusable = true
            background = rippleDrawable(theme.keyPressHighlightColor)
            minHeight = context.dp(PanelStyle.BUTTON_HEIGHT_DP)
        }

        init {
            root.addView(title)
            root.addView(definitions)
            root.addView(sourceAction, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        fun bind(entry: KoreanDictionaryEntry) {
            title.text = if (entry.partOfSpeech.isBlank()) {
                entry.word
            } else {
                root.context.getString(
                    R.string.korean_dictionary_word_and_pos,
                    entry.word,
                    entry.partOfSpeech
                )
            }
            definitions.text = entry.definitions.mapIndexed { index, definition ->
                "${index + 1}. $definition"
            }.joinToString("\n")
            sourceAction.contentDescription = root.context.getString(
                R.string.korean_dictionary_source_description,
                entry.word
            )
            root.contentDescription = listOf(title.text, definitions.text, sourceAction.text)
                .joinToString(", ")
        }
    }
}
