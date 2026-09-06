/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter

/**
 * Clean, compact, utilitarian candidate item view.
 * Borderless cells with standard native tactile press feedback, avoiding bulky containers,
 * heavy card borders, or nested badge backgrounds in accordance with minimalist UI principles.
 */
class CandidateItemUi(override val ctx: Context, val theme: Theme) : Ui {

    private val nativeText = view(::AutoScaleTextView) {
        scaleMode = AutoScaleTextView.Mode.Proportional
        textSize = 15f // sp - compact word candidate
        isSingleLine = true
        gravity = gravityCenter
        setTextColor(theme.candidateTextColor)
    }

    private val aiBadge = view(::TextView) {
        textSize = 11.5f // sp - subtle glyph/icon
        isSingleLine = true
        gravity = gravityCenter
        includeFontPadding = false
        visibility = View.GONE
    }

    private val aiText = view(::TextView) {
        textSize = 14f // sp - crisp, legible candidate text
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        maxLines = 1
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(theme.candidateTextColor)
        visibility = View.GONE
    }

    private val chipContainer = view(::LinearLayout) {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        add(aiBadge, lParams(wrapContent, wrapContent) {
            marginEnd = dp(3)
        })
        add(aiText, lParams(wrapContent, wrapContent))
        add(nativeText, lParams(wrapContent, wrapContent))
    }

    override val root = view(::CustomGestureView) {
        background = pressHighlightDrawable(theme.keyPressHighlightColor)

        /**
         * candidate long press feedback is handled by [org.fcitx.fcitx5.android.input.BaseInputView.showCandidateActionMenu]
         */
        longPressFeedbackEnabled = false
        setPadding(dp(6), 0, dp(6), 0)

        add(chipContainer, lParams(wrapContent, matchParent) {
            gravity = gravityCenter
        })
    }

    companion object {
        fun resolveBadgeIcon(badgeText: String): String {
            val clean = badgeText.trim()
            return when {
                clean.contains("일정") || clean.contains("시간") -> "📅"
                clean.contains("업무") || clean.contains("보고") || clean.contains("비즈니스") || clean.contains("개발") -> "💼"
                clean.contains("양해") || clean.contains("안심") || clean.contains("지연") -> "⏳"
                clean.contains("감사") || clean.contains("응원") || clean.contains("축하") || clean.contains("존댓말") -> "🙏"
                clean.contains("제안") || clean.contains("방안") || clean.contains("아이디어") -> "💡"
                clean.contains("이메일") || clean.contains("📧") -> "📧"
                clean.contains("교정") || clean.contains("✏️") -> "✏️"
                clean.contains("답변") || clean.contains("대화") || clean.contains("친근") || clean.contains("구문") || clean.contains("자주") || clean.contains("일상") -> "💬"
                clean.contains("맞춤") || clean.contains("AI") || clean.contains("스타일") || clean.contains("✨") -> "✨"
                clean.contains("웹") || clean.contains("🌐") -> "🌐"
                else -> ""
            }
        }
    }

    fun updateCandidate(candidate: CandidateWord, isFeatured: Boolean = false) {
        val fg = theme.candidateTextColor
        val altFg = theme.candidateCommentColor

        val icon = resolveBadgeIcon(candidate.comment)
        val isAiBadge = candidate.comment.isNotBlank() && icon.isNotEmpty()

        // Flat, borderless native cell with press ripple (strip containers/borders)
        root.background = pressHighlightDrawable(theme.keyPressHighlightColor)
        chipContainer.background = null
        chipContainer.setPadding(0, 0, 0, 0)

        if (isAiBadge) {
            val icon = resolveBadgeIcon(candidate.comment)
            if (icon.isNotEmpty()) {
                aiBadge.text = icon
                aiBadge.background = null
                aiBadge.setPadding(0, 0, 0, 0)
                aiBadge.visibility = View.VISIBLE
            } else {
                aiBadge.visibility = View.GONE
            }

            aiText.text = candidate.text
            aiText.typeface = if (isFeatured) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            if (!candidate.text.contains(" ") || candidate.text.length <= 20) {
                aiText.ellipsize = null
            } else {
                aiText.ellipsize = TextUtils.TruncateAt.END
            }
            aiText.visibility = View.VISIBLE
            nativeText.visibility = View.GONE
        } else {
            aiBadge.visibility = View.GONE
            aiText.visibility = View.GONE
            nativeText.visibility = View.VISIBLE
            nativeText.text = buildSpannedString {
                color(fg) {
                    append(candidate.text)
                }
                if (candidate.comment.isNotBlank() && candidate.comment != "추천" && !candidate.comment.contains("추천") && !candidate.comment.contains("🌐")) {
                    if (candidate.spaceBetweenComment) {
                        append(" ")
                    }
                    color(altFg) {
                        append(candidate.comment)
                    }
                }
            }
        }
    }
}
