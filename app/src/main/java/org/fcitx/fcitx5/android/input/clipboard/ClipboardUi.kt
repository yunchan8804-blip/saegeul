/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.view.View
import android.widget.ViewAnimator
import androidx.transition.Fade
import androidx.transition.TransitionManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.coordinatorlayout.coordinatorLayout
import splitties.views.dsl.coordinatorlayout.defaultLParams
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.recyclerview.recyclerView
import timber.log.Timber

class ClipboardUi(override val ctx: Context, private val theme: Theme) : Ui {

    private var smartSurfaceShown = false

    val recyclerView = recyclerView {
        addItemDecoration(SpacesItemDecoration(dp(4)))
    }

    val enableUi = ClipboardInstructionUi.Enable(ctx, theme)

    val emptyUi = ClipboardInstructionUi.Empty(ctx, theme)

    val smartUi = SmartClipboardUi(ctx, theme)

    val viewAnimator =  view(::ViewAnimator) {
        add(recyclerView, lParams(matchParent, matchParent))
        add(emptyUi.root, lParams(matchParent, matchParent))
        add(enableUi.root, lParams(matchParent, matchParent))
        add(smartUi.root, lParams(matchParent, matchParent))
    }

    private val keyBorder by ThemeManager.prefs.keyBorder
    private val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

    override val root = coordinatorLayout {
        if (!keyBorder) {
            backgroundColor = theme.barColor
        }
        add(viewAnimator, defaultLParams(matchParent, matchParent))
    }

    val deleteAllButton = ToolButton(ctx, R.drawable.ic_baseline_delete_sweep_24, theme).apply {
        contentDescription = ctx.getString(R.string.delete_all)
    }

    val smartButton = ToolButton(ctx, R.drawable.ic_baseline_auto_awesome_24, theme).apply {
        contentDescription = ctx.getString(R.string.smart_clipboard)
    }

    val extension = horizontalLayout {
        add(smartButton, lParams(dp(40), dp(40)))
        add(deleteAllButton, lParams(dp(40), dp(40)))
    }

    private fun setDeleteButtonShown(enabled: Boolean) {
        deleteAllButton.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }

    private fun setSmartButtonShown(enabled: Boolean) {
        smartButton.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }

    fun setSmartSelectionMode(enabled: Boolean) {
        smartButton.isSelected = enabled
        smartButton.contentDescription = ctx.getString(
            if (enabled) R.string.smart_clipboard_choose_action else R.string.smart_clipboard
        )
        setDeleteButtonShown(!enabled)
    }

    fun showSmartPreview(preview: SmartClipboardPreview) {
        smartSurfaceShown = true
        smartUi.showPreview(preview)
        viewAnimator.displayedChild = 3
        smartButton.visibility = View.INVISIBLE
        deleteAllButton.visibility = View.INVISIBLE
    }

    fun showSmartMessage(message: String, allowBack: Boolean = false) {
        smartSurfaceShown = true
        smartUi.showMessage(message, allowBack)
        viewAnimator.displayedChild = 3
        smartButton.visibility = View.INVISIBLE
        deleteAllButton.visibility = View.INVISIBLE
    }

    fun hideSmartSurface() {
        smartSurfaceShown = false
        viewAnimator.displayedChild = 0
        smartButton.visibility = View.VISIBLE
        setSmartSelectionMode(true)
    }

    fun resetSmartMode() {
        smartSurfaceShown = false
        viewAnimator.displayedChild = 0
        smartButton.visibility = View.VISIBLE
        setSmartSelectionMode(false)
    }

    fun switchUiByState(state: ClipboardStateMachine.State) {
        Timber.d("Switch clipboard to $state")
        if (smartSurfaceShown) return
        if (!disableAnimation)
            TransitionManager.beginDelayedTransition(root, Fade().apply { duration = 100L })
        when (state) {
            ClipboardStateMachine.State.Normal -> {
                viewAnimator.displayedChild = 0
                setDeleteButtonShown(true)
                setSmartButtonShown(true)
            }
            ClipboardStateMachine.State.AddMore -> {
                viewAnimator.displayedChild = 1
                setDeleteButtonShown(false)
                setSmartButtonShown(false)
            }
            ClipboardStateMachine.State.EnableListening -> {
                viewAnimator.displayedChild = 2
                setDeleteButtonShown(false)
                setSmartButtonShown(false)
            }
        }
    }
}
