/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent

/**
 * Real-time Hangul buffer compatibility mode selection window.
 * Allows quick toggling between standard composing and various transport delivery methods.
 */
class BufferedHangulWindow : InputWindow.ExtendedInputWindow<BufferedHangulWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val theme: Theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()

    private val prefs = AppPrefs.getInstance().advanced
    private val bufferedInputPref = prefs.bufferedHangulInput
    private val bufferedTransportPref = prefs.bufferedHangulTransport

    private val titleBar by lazy {
        context.horizontalLayout {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(8), 0)

            val titleView = TextView(context).apply {
                text = context.getString(R.string.buffered_hangul_title)
                textSize = 15f
                paint.isFakeBoldText = true
                setTextColor(theme.keyTextColor)
            }
            add(titleView, lParams(0, wrapContent) { weight = 1f })

            val closeButton = ToolButton(context, R.drawable.ic_baseline_arrow_back_24, theme).apply {
                contentDescription = context.getString(R.string.buffered_hangul_title)
                setOnClickListener {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }
            add(closeButton, lParams(dp(40), dp(40)))
        }
    }

    override fun onCreateBarExtension(): View = titleBar

    override fun onCreateView(): View {
        val root = context.verticalLayout {
            setPadding(dp(12), dp(4), dp(12), dp(12))
            setBackgroundColor(theme.backgroundColor)

            val isEnabled = bufferedInputPref.getValue()
            val currentTransport = bufferedTransportPref.getValue()

            // Option 1: Standard real-time composing (OFF)
            add(
                createModeCard(
                    title = context.getString(R.string.buffered_hangul_off),
                    description = context.getString(R.string.buffered_hangul_off_desc),
                    iconRes = R.drawable.ic_baseline_check_circle_24,
                    isSelected = !isEnabled,
                    onClick = {
                        bufferedInputPref.setValue(false)
                        showToast(context.getString(R.string.buffered_hangul_toast_off))
                        windowManager.attachWindow(KeyboardWindow)
                    }
                ),
                lParams(matchParent, wrapContent) { bottomMargin = dp(8) }
            )

            // Option 2: System Paste
            add(
                createModeCard(
                    title = context.getString(R.string.buffered_input_transport_system_paste),
                    description = context.getString(R.string.buffered_hangul_paste_desc),
                    iconRes = R.drawable.ic_baseline_content_paste_24,
                    isSelected = isEnabled && currentTransport == BufferedInputTransport.SystemPaste,
                    onClick = {
                        bufferedTransportPref.setValue(BufferedInputTransport.SystemPaste)
                        bufferedInputPref.setValue(true)
                        showToast(
                            context.getString(
                                R.string.buffered_hangul_toast_on,
                                context.getString(R.string.buffered_input_transport_system_paste)
                            )
                        )
                        windowManager.attachWindow(KeyboardWindow)
                    }
                ),
                lParams(matchParent, wrapContent) { bottomMargin = dp(8) }
            )

            // Option 3: Ctrl+V Virtual Keycode
            add(
                createModeCard(
                    title = context.getString(R.string.buffered_input_transport_ctrl_v),
                    description = context.getString(R.string.buffered_hangul_ctrl_v_desc),
                    iconRes = R.drawable.ic_baseline_keyboard_24,
                    isSelected = isEnabled && currentTransport == BufferedInputTransport.CtrlV,
                    onClick = {
                        bufferedTransportPref.setValue(BufferedInputTransport.CtrlV)
                        bufferedInputPref.setValue(true)
                        showToast(
                            context.getString(
                                R.string.buffered_hangul_toast_on,
                                context.getString(R.string.buffered_input_transport_ctrl_v)
                            )
                        )
                        windowManager.attachWindow(KeyboardWindow)
                    }
                ),
                lParams(matchParent, wrapContent) { bottomMargin = dp(8) }
            )

            // Option 4: Direct Commit
            add(
                createModeCard(
                    title = context.getString(R.string.buffered_input_transport_direct_commit),
                    description = context.getString(R.string.buffered_hangul_direct_desc),
                    iconRes = R.drawable.ic_baseline_done_24,
                    isSelected = isEnabled && currentTransport == BufferedInputTransport.DirectCommit,
                    onClick = {
                        bufferedTransportPref.setValue(BufferedInputTransport.DirectCommit)
                        bufferedInputPref.setValue(true)
                        showToast(
                            context.getString(
                                R.string.buffered_hangul_toast_on,
                                context.getString(R.string.buffered_input_transport_direct_commit)
                            )
                        )
                        windowManager.attachWindow(KeyboardWindow)
                    }
                ),
                lParams(matchParent, wrapContent) { bottomMargin = dp(4) }
            )
        }

        return ScrollView(context).apply {
            isFillViewport = true
            addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun createModeCard(
        title: String,
        description: String,
        iconRes: Int,
        isSelected: Boolean,
        onClick: () -> Unit
    ): View {
        val accentColor = theme.accentKeyBackgroundColor
        val cardBg = if (isSelected) {
            ColorUtils.setAlphaComponent(accentColor, 40)
        } else {
            theme.keyBackgroundColor
        }
        val strokeColor = if (isSelected) accentColor else ColorUtils.setAlphaComponent(theme.keyTextColor, 40)
        val strokeWidth = context.dp(if (isSelected) 2 else 1)

        val shape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(12f)
            setColor(cardBg)
            setStroke(strokeWidth, strokeColor)
        }

        return context.horizontalLayout {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(accentColor, 60)), shape, null)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            val iconView = ImageView(context).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(if (isSelected) accentColor else theme.keyTextColor)
            }
            add(iconView, lParams(dp(28), dp(28)) {
                rightMargin = dp(12)
            })

            val textLayout = context.verticalLayout {
                val titleTv = TextView(context).apply {
                    text = title
                    textSize = 14f
                    paint.isFakeBoldText = true
                    setTextColor(if (isSelected) accentColor else theme.keyTextColor)
                }
                add(titleTv, lParams(matchParent, wrapContent))

                val descTv = TextView(context).apply {
                    text = description
                    textSize = 11.5f
                    setTextColor(ColorUtils.setAlphaComponent(theme.keyTextColor, 180))
                }
                add(descTv, lParams(matchParent, wrapContent) { topMargin = dp(2) })
            }
            add(textLayout, lParams(0, wrapContent) { weight = 1f })

            if (isSelected) {
                val checkView = ImageView(context).apply {
                    setImageResource(R.drawable.ic_baseline_check_24)
                    imageTintList = ColorStateList.valueOf(accentColor)
                }
                add(checkView, lParams(dp(22), dp(22)) { leftMargin = dp(8) })
            }
        }
    }

    private fun showToast(msg: String) {
        service.lifecycleScope.launch {
            Toast.makeText(service, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAttached() {
    }

    override fun onDetached() {
    }
}
