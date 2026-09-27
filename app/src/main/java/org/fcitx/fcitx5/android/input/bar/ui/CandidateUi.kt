/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent

class CandidateUi(override val ctx: Context, theme: Theme, private val horizontalView: View) : Ui {

    // K5 후속(design.md, 사용자 확정 2026-09-26): landscape 단일 줄에서 idle 툴바가 압축 상태
    // (`>`/`▾` 두 버튼만 보이는 상태)일 때, 그 줄을 이 후보 줄에 합쳐 총 48dp를 유지하기 위한 자리.
    // 평소에는 GONE이고 KawaiiBarComponent.updateBarHeight()가 병합 여부에 따라 보인다/숨긴다.
    // 클릭은 항상 idleUi의 같은 두 버튼으로 위임한다(단일 진실 소스, KawaiiBarComponent 참고).
    val menuButton = ToolButton(ctx, R.drawable.ic_baseline_expand_more_24, theme).apply {
        contentDescription = ctx.getString(R.string.expand_toolbar)
        visibility = View.GONE
    }

    val hideKeyboardButton = ToolButton(ctx, R.drawable.ic_baseline_arrow_drop_down_24, theme).apply {
        contentDescription = ctx.getString(R.string.hide_keyboard)
        visibility = View.GONE
    }

    val expandButton = ToolButton(ctx, R.drawable.ic_baseline_expand_more_24, theme).apply {
        id = R.id.expand_candidate_btn
        visibility = View.GONE
    }

    override val root = ctx.constraintLayout {
        add(menuButton, lParams(dp(KawaiiBarComponent.HEIGHT)) {
            startOfParent()
            topOfParent()
        })
        add(hideKeyboardButton, lParams(dp(KawaiiBarComponent.HEIGHT)) {
            endOfParent()
            topOfParent()
        })
        add(expandButton, lParams(dp(KawaiiBarComponent.HEIGHT)) {
            topOfParent()
            before(hideKeyboardButton)
        })
        add(horizontalView, lParams(matchConstraints, matchParent) {
            centerVertically()
            after(menuButton)
            before(expandButton)
        })
    }
}
