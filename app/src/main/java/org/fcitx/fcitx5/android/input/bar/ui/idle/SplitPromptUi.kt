/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ToolbarLayoutPolicy
import org.fcitx.fcitx5.android.utils.rippleDrawable
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent

/**
 * K7 (design.md, 사용자 승인 2026-09-26): idle 툴바 줄(48dp)의 내용을 대신해 보이는 분할 키보드
 * 안내. 한 줄 말줄임 안내문 + "나눠 쓰기"·"닫기" 텍스트 버튼. 색은 현재 키보드 테마 값만 쓴다.
 */
class SplitPromptUi(override val ctx: Context, private val theme: Theme) : Ui {

    private val minTouchWidth = ctx.dp(ToolbarLayoutPolicy.TOUCH_TARGET_DP)
    private val buttonHorizontalPadding = ctx.dp(12)

    val message = textView {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(theme.altKeyTextColor)
        text = ctx.getString(R.string.split_expanded_prompt_message)
    }

    val splitButton = textView {
        isSingleLine = true
        gravity = Gravity.CENTER
        minWidth = minTouchWidth
        setPadding(buttonHorizontalPadding, 0, buttonHorizontalPadding, 0)
        setTextColor(theme.accentKeyBackgroundColor)
        text = ctx.getString(R.string.split_expanded_prompt_split)
        isClickable = true
        isFocusable = true
        background = rippleDrawable(theme.keyPressHighlightColor)
    }

    val closeButton = textView {
        isSingleLine = true
        gravity = Gravity.CENTER
        minWidth = minTouchWidth
        setPadding(buttonHorizontalPadding, 0, buttonHorizontalPadding, 0)
        setTextColor(theme.altKeyTextColor)
        text = ctx.getString(R.string.split_expanded_prompt_close)
        isClickable = true
        isFocusable = true
        background = rippleDrawable(theme.keyPressHighlightColor)
    }

    private val buttonsRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(splitButton, LinearLayout.LayoutParams(wrapContent, matchParent))
        addView(closeButton, LinearLayout.LayoutParams(wrapContent, matchParent))
    }

    override val root = constraintLayout {
        val spacing = dp(4)
        add(message, lParams(matchConstraints, wrapContent) {
            startOfParent(spacing)
            before(buttonsRow, spacing)
            centerVertically()
        })
        add(buttonsRow, lParams(wrapContent, matchParent) {
            after(message)
            endOfParent()
        })
    }
}
