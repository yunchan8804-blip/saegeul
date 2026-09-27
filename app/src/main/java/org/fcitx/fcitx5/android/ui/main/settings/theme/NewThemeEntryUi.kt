/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.view.View
import android.view.ViewOutlineProvider
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.R
import splitties.dimensions.dp
import splitties.resources.color
import splitties.resources.drawable
import splitties.resources.styledDrawable
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import splitties.views.setPaddingDp

class NewThemeEntryUi(override val ctx: Context) : Ui {
    val text = textView {
        setText(R.string.new_theme)
        setTextColor(ctx.color(R.color.saegeul_ink))
    }

    val icon = imageView {
        imageDrawable = ctx.drawable(R.drawable.ic_baseline_plus_24)!!.apply {
            setTint(ctx.color(R.color.saegeul_ink))
        }
    }

    private val card = constraintLayout {
        foreground = styledDrawable(android.R.attr.selectableItemBackground)
        background = ctx.drawable(R.drawable.bkg_theme_new_surface)
        // Same outline/elevation treatment as ThemeThumbnailUi.card, so the shadow
        // never peeks out behind rounded corners the outline doesn't know about.
        outlineProvider = ViewOutlineProvider.BOUNDS
        elevation = dp(2f)
        add(icon, lParams(dp(24), dp(24)) {
            topOfParent()
            centerHorizontally()
            above(text, dp(4))
            verticalChainStyle = ConstraintLayout.LayoutParams.CHAIN_PACKED
        })
        add(text, lParams(wrapContent, wrapContent) {
            below(icon)
            centerHorizontally()
            bottomOfParent()
        })
    }

    // Blank row matching ThemeThumbnailUi.nameLabel's height (same text size/padding,
    // just invisible), so the "새 테마" tile's card box lines up with real thumbnails
    // instead of stretching to fill the whole grid cell.
    private val nameLabelSpacer = textView {
        textSize = 11f
        maxLines = 1
        setPaddingDp(2, 4, 2, 0)
        visibility = View.INVISIBLE
    }

    override val root = verticalLayout {
        add(card, lParams(matchParent, 0) { weight = 1f })
        add(nameLabelSpacer, lParams(matchParent, wrapContent))
    }
}
