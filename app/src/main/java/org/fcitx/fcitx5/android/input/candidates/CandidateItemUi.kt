/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.updateLayoutParams
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
import kotlin.math.max

/**
 * Clean, compact, utilitarian candidate item view.
 * Borderless cells with standard native tactile press feedback, avoiding bulky containers,
 * heavy card borders, or nested badge backgrounds in accordance with minimalist UI principles.
 *
 * When [isSentenceRow] is true and the candidate carries an AI badge (see [CandidateBadge]), the
 * chip renders as a pill (bordered, rounded, floating within the row) instead of the flat
 * borderless style, so the sentence row's automatic AI candidates read as tappable chips. Word
 * row candidates, and sentence-row candidates without a badge, keep the flat style.
 */
class CandidateItemUi(
    override val ctx: Context,
    val theme: Theme,
    private val isSentenceRow: Boolean = false
) : Ui {

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

    // 출처 배지는 이모지가 아니라 4dp 원형 점으로 표시한다(2026-09-26 확정, design.md 라운드 3 다듬기).
    private val aiBadgeDot = view(::View) {
        visibility = View.GONE
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme.accentKeyBackgroundColor)
        }
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
        add(aiBadgeDot, lParams(dp(4), dp(4)) {
            marginEnd = dp(4)
        })
        add(aiBadge, lParams(wrapContent, wrapContent) {
            marginEnd = dp(3)
        })
        add(aiText, lParams(wrapContent, wrapContent))
        add(nativeText, lParams(wrapContent, wrapContent))
    }

    // Pill background/ripple for sentence-row AI badge chips. Radius is a fixed 12dp per spec,
    // not ThemePrefs.clipboardEntryRadius (that's a user-tunable clipboard-only setting).
    private val pillCornerRadius = ctx.dp(12).toFloat()

    private val pillBackground: GradientDrawable by lazy {
        GradientDrawable().apply {
            cornerRadius = pillCornerRadius
            setColor(theme.keyBackgroundColor)
            setStroke(max(1, ctx.dp(1)), theme.dividerColor)
        }
    }

    private val pillRipple: RippleDrawable by lazy {
        RippleDrawable(
            ColorStateList.valueOf(theme.keyPressHighlightColor), null,
            GradientDrawable().apply {
                cornerRadius = pillCornerRadius
                setColor(Color.WHITE)
            }
        )
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
        /**
         * Kept for callers outside this packet's scope (e.g. `input/ai` and `androidTest`
         * helpers) that still depend on the old signature. Delegates to [CandidateBadge.iconFor].
         */
        fun resolveBadgeIcon(badgeText: String): String = CandidateBadge.iconFor(badgeText) ?: ""

        /**
         * Sentence-row automatic candidates (SENTENCE mode, AI badge) render as a pill chip; word
         * row candidates and sentence-row candidates without a badge keep the flat borderless
         * style. Exposed standalone so it can be unit tested without an Android/Theme dependency.
         */
        internal fun shouldRenderPill(isSentenceRow: Boolean, comment: String?): Boolean =
            isSentenceRow && CandidateBadge.isBadge(comment)
    }

    fun updateCandidate(candidate: CandidateWord, isFeatured: Boolean = false) {
        val fg = theme.candidateTextColor
        val altFg = theme.candidateCommentColor

        val icon = CandidateBadge.iconFor(candidate.comment)
        val isAiBadge = icon != null
        val showPill = isSentenceRow && isAiBadge

        if (showPill) {
            // Pill chip: bordered, rounded, floating within the row's fixed height. The ripple is
            // confined to the pill's own shape via foreground, so root itself stays background-free.
            root.background = null
            root.setPadding(0, 0, 0, 0)
            root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginStart = ctx.dp(8)
                marginEnd = ctx.dp(8)
            }
            chipContainer.background = pillBackground
            chipContainer.foreground = pillRipple
            chipContainer.setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
            chipContainer.minimumHeight = ctx.dp(40)
            chipContainer.updateLayoutParams<FrameLayout.LayoutParams> {
                height = FrameLayout.LayoutParams.WRAP_CONTENT
            }
        } else {
            // Flat, borderless native cell with press ripple (strip containers/borders)
            root.background = pressHighlightDrawable(theme.keyPressHighlightColor)
            root.setPadding(ctx.dp(6), 0, ctx.dp(6), 0)
            if (isSentenceRow) {
                root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    marginStart = 0
                    marginEnd = 0
                }
            }
            chipContainer.background = null
            chipContainer.foreground = null
            chipContainer.setPadding(0, 0, 0, 0)
            chipContainer.minimumHeight = 0
            chipContainer.updateLayoutParams<FrameLayout.LayoutParams> {
                height = FrameLayout.LayoutParams.MATCH_PARENT
            }
        }

        if (isAiBadge) {
            // 출처 배지(AI·개인화·업무·일정·감사 등)는 이모지 대신 모두 같은 4dp 점으로 표시한다
            // (2026-09-26 확정, design.md 라운드 3 다듬기). 출처 이름은 contentDescription이 전한다.
            aiBadgeDot.visibility = View.VISIBLE
            aiBadge.visibility = View.GONE

            aiText.text = candidate.text
            aiText.typeface = if (isFeatured) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            if (!candidate.text.contains(" ") || candidate.text.length <= 20) {
                aiText.ellipsize = null
            } else {
                aiText.ellipsize = TextUtils.TruncateAt.END
            }
            aiText.visibility = View.VISIBLE
            nativeText.visibility = View.GONE
            // Screen readers and UI automation see one label per chip. The dot is decorative (no
            // separate a11y node), so its source is spoken as a "배지: 후보" prefix instead.
            root.contentDescription = "${candidate.comment.trim()}: ${candidate.text}"
        } else {
            root.contentDescription = null
            aiBadgeDot.visibility = View.GONE
            aiBadge.visibility = View.GONE
            aiText.visibility = View.GONE
            nativeText.visibility = View.VISIBLE
            nativeText.text = buildSpannedString {
                color(fg) {
                    append(candidate.text)
                }
                if (candidate.comment.isNotBlank() && candidate.comment != "추천" && !candidate.comment.contains("추천") && !candidate.comment.contains("🌐") && !CandidateBadge.isNextWordMarker(candidate.comment)) {
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
