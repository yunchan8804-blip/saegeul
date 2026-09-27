/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import splitties.dimensions.dp
import splitties.resources.color
import splitties.views.backgroundColor
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

private const val THEME_PREVIEW_MAX_WIDTH_DP = 640
private const val THEME_PREVIEW_CORNER_RADIUS_DP = 12

/**
 * Wraps [KeyboardPreviewUi.root] and scales it uniformly so the fake keyboard fills the
 * available width (instead of the previous fixed 0.5x scale), while clipping to rounded
 * corners with a hairline border. The child is measured at its true intrinsic size and
 * scaled via [View.setScaleX]/[View.setScaleY] from the top-left pivot, so the corner
 * radius and border (drawn on this un-scaled container) stay a crisp 12dp/1dp regardless
 * of the scale factor.
 */
private class ScaledPreviewFrame(
    ctx: Context,
    private val previewUi: KeyboardPreviewUi
) : FrameLayout(ctx) {

    private val cornerRadiusPx = ctx.dp(THEME_PREVIEW_CORNER_RADIUS_DP).toFloat()

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }
        foreground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setStroke(ctx.dp(1), ctx.color(R.color.saegeul_outline))
        }
        addView(previewUi.root, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val intrinsicWidth = previewUi.intrinsicWidth
        val intrinsicHeight = previewUi.intrinsicHeight
        val child = previewUi.root
        if (intrinsicWidth > 0 && intrinsicHeight > 0 && availableWidth > 0) {
            val scale = availableWidth.toFloat() / intrinsicWidth
            child.pivotX = 0f
            child.pivotY = 0f
            child.scaleX = scale
            child.scaleY = scale
            child.measure(
                MeasureSpec.makeMeasureSpec(intrinsicWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(intrinsicHeight, MeasureSpec.EXACTLY)
            )
            setMeasuredDimension(availableWidth, Math.round(intrinsicHeight * scale))
        } else {
            child.measure(widthMeasureSpec, heightMeasureSpec)
            setMeasuredDimension(availableWidth, child.measuredHeight)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val child = previewUi.root
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
    }
}

class ThemeFragment : Fragment() {

    private lateinit var previewUi: KeyboardPreviewUi

    private lateinit var tabLayout: TabLayout

    private lateinit var viewPager: ViewPager2

    @Keep
    private val onThemeChangeListener = ThemeManager.OnThemeChangeListener {
        lifecycleScope.launch {
            previewUi.setTheme(it)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = with(requireContext()) {
        previewUi = KeyboardPreviewUi(this, ThemeManager.activeTheme)
        previewUi.recalculateSize()
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        val previewFrame = ScaledPreviewFrame(this, previewUi)

        tabLayout = TabLayout(this)

        viewPager = ViewPager2(this).apply {
            adapter = object : FragmentStateAdapter(this@ThemeFragment) {
                override fun getItemCount() = 2
                override fun createFragment(position: Int): Fragment = when (position) {
                    0 -> ThemeListFragment()
                    else -> ThemeSettingsFragment()
                }
            }
        }

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = getString(
                when (position) {
                    0 -> R.string.theme
                    else -> R.string.configure
                }
            )
        }.attach()

        val previewWrapper = constraintLayout {
            add(previewFrame, lParams(matchConstraints, wrapContent) {
                topOfParent(dp(12))
                startOfParent(dp(16))
                endOfParent(dp(16))
                matchConstraintMaxWidth = dp(THEME_PREVIEW_MAX_WIDTH_DP)
            })
            add(tabLayout, lParams(matchParent, wrapContent) {
                below(previewFrame, dp(12))
                centerHorizontally()
                bottomOfParent()
            })
            backgroundColor = color(R.color.saegeul_canvas)
            elevation = dp(4f)
        }

        constraintLayout {
            add(previewWrapper, lParams(height = wrapContent) {
                topOfParent()
                startOfParent()
                endOfParent()
            })
            add(viewPager, lParams {
                below(previewWrapper)
                startOfParent()
                endOfParent()
                bottomOfParent()
            })
        }
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ThemeManager.syncToDeviceEncryptedStorage()
        }
        super.onStop()
    }

    override fun onDestroy() {
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        super.onDestroy()
    }
}
