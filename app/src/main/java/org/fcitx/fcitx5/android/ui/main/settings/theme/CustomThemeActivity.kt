/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeColorGenerator
import org.fcitx.fcitx5.android.data.theme.ThemeContrast
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.CropImageActivity.CropContract
import org.fcitx.fcitx5.android.ui.main.CropImageActivity.CropOption
import org.fcitx.fcitx5.android.ui.main.CropImageActivity.CropResult
import org.fcitx.fcitx5.android.utils.DarkenColorFilter
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.parcelable
import splitties.dimensions.dp
import splitties.resources.color
import splitties.resources.resolveThemeAttribute
import splitties.resources.styledColor
import splitties.resources.styledDrawable
import splitties.views.backgroundColor
import splitties.views.bottomPadding
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.editText
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.seekBar
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.core.wrapInScrollView
import splitties.views.gravityVerticalCenter
import splitties.views.horizontalPadding
import splitties.views.imageResource
import splitties.views.textAppearance
import splitties.views.topPadding
import java.io.File

class CustomThemeActivity : AppCompatActivity() {

    sealed interface BackgroundResult : Parcelable {
        @Parcelize
        data class Updated(val theme: Theme.Custom) : BackgroundResult

        @Parcelize
        data class Created(val theme: Theme.Custom) : BackgroundResult

        @Parcelize
        data class Deleted(val name: String) : BackgroundResult
    }

    class Contract : ActivityResultContract<Theme.Custom?, BackgroundResult?>() {
        override fun createIntent(context: Context, input: Theme.Custom?): Intent =
            Intent(context, CustomThemeActivity::class.java).apply {
                putExtra(ORIGIN_THEME, input)
            }

        override fun parseResult(resultCode: Int, intent: Intent?): BackgroundResult? =
            intent?.parcelable(RESULT)
    }

    private val toolbar by lazy {
        view(::Toolbar) {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
        }
    }

    private lateinit var previewUi: KeyboardPreviewUi

    private fun createTextView(@StringRes string: Int? = null, ripple: Boolean = false) = textView {
        if (string != null) {
            setText(string)
        }
        gravity = gravityVerticalCenter
        textAppearance = resolveThemeAttribute(android.R.attr.textAppearanceListItem)
        horizontalPadding = dp(16)
        if (ripple) {
            background = styledDrawable(android.R.attr.selectableItemBackground)
        }
    }

    private fun createSectionHeader(title: String) = textView {
        text = title
        textSize = 13f
        paint.isFakeBoldText = true
        setTextColor(styledColor(android.R.attr.colorPrimary))
        setPadding(dp(16), dp(16), dp(16), dp(6))
    }

    // ---- Unified chip / swatch styling (design.md round 2 "테마" item 3) ----

    private fun styledChip(label: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        minimumHeight = dp(48)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20f)
            if (selected) {
                setColor(color(R.color.saegeul_action))
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), color(R.color.saegeul_outline))
            }
        }
        setTextColor(if (selected) color(R.color.saegeul_on_action) else color(R.color.saegeul_ink))
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(40, 0, 0, 0)), shape, null)
        setOnClickListener { onClick() }
    }

    private fun horizontalChipRow(chips: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(12), dp(4), dp(12), dp(4))
        chips.forEach { chip ->
            addView(
                chip,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(8)
                }
            )
        }
    }

    private fun twoOptionSegment(
        labelA: String,
        labelB: String,
        isASelected: Boolean,
        onSelectA: () -> Unit,
        onSelectB: () -> Unit
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(
            styledChip(labelA, isASelected, onSelectA),
            LinearLayout.LayoutParams(0, dp(48)).apply { weight = 1f; rightMargin = dp(6) }
        )
        addView(
            styledChip(labelB, !isASelected, onSelectB),
            LinearLayout.LayoutParams(0, dp(48)).apply { weight = 1f }
        )
    }

    private fun colorSwatchView(currentColor: Int) = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8f)
            setColor(currentColor)
            setStroke(dp(1), color(R.color.saegeul_outline))
        }
    }

    /**
     * A label + tappable color swatch row that opens [showColorPickerDialog].
     * [currentColor] and [onPicked] are called lazily so the row always
     * reflects the live [theme], even after it changes from elsewhere (e.g.
     * a preset chip).
     */
    private fun colorSwatchRow(
        label: String,
        refresherList: MutableList<() -> Unit> = swatchRefreshers,
        currentColor: () -> Int,
        onPicked: (Int) -> Unit
    ): LinearLayout {
        val swatch = colorSwatchView(currentColor())
        val refresh: () -> Unit = { (swatch.background as GradientDrawable).setColor(currentColor()) }
        swatch.setOnClickListener {
            showColorPickerDialog(currentColor()) { picked ->
                onPicked(picked)
                refresh()
            }
        }
        refresherList += refresh
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(16), dp(2), dp(16), dp(2))
            addView(
                TextView(this@CustomThemeActivity).apply {
                    text = label
                    setTextColor(color(R.color.saegeul_ink))
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
            )
            addView(swatch, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
    }

    /**
     * A tappable color swatch (circular seed swatch or the color-picker's preset palette)
     * that shows a ring + check mark when [isSelected] is true (design.md round 2 "테마"
     * item 2). The ring sits 2dp outside the swatch with a 3dp stroke, so selecting a swatch
     * never changes its own footprint; the check mark is tinted for whichever of white/ink
     * contrasts best against the swatch color. Returns the tappable container together with
     * a `refresh()` callback the caller re-runs whenever [isSelected] may have changed.
     */
    private fun selectableSwatch(
        swatchColor: Int,
        oval: Boolean,
        swatchSizePx: Int,
        description: String,
        isSelected: () -> Boolean,
        onClick: () -> Unit
    ): Pair<FrameLayout, () -> Unit> {
        val ringWidthPx = dp(3)
        val insetPx = dp(2) + ringWidthPx
        val containerSizePx = swatchSizePx + insetPx * 2
        val shapeType = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        val ring = View(this).apply {
            background = GradientDrawable().apply {
                shape = shapeType
                if (!oval) cornerRadius = dp(10f)
                setStroke(ringWidthPx, color(R.color.saegeul_ink))
            }
        }
        val swatch = View(this).apply {
            background = GradientDrawable().apply {
                shape = shapeType
                if (!oval) cornerRadius = dp(8f)
                setColor(swatchColor)
                setStroke(dp(1), color(R.color.saegeul_outline))
            }
        }
        val check = ImageView(this).apply {
            setImageResource(R.drawable.ic_baseline_check_24)
        }
        val container = FrameLayout(this).apply {
            contentDescription = description
            addView(ring, FrameLayout.LayoutParams(containerSizePx, containerSizePx))
            addView(swatch, FrameLayout.LayoutParams(swatchSizePx, swatchSizePx).apply {
                gravity = Gravity.CENTER
            })
            addView(check, FrameLayout.LayoutParams(swatchSizePx / 2, swatchSizePx / 2).apply {
                gravity = Gravity.CENTER
            })
            setOnClickListener { onClick() }
        }
        val refresh: () -> Unit = {
            val selected = isSelected()
            ring.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            check.visibility = if (selected) View.VISIBLE else View.GONE
            container.isSelected = selected
            ViewCompat.setStateDescription(
                container,
                if (selected) getString(R.string.theme_swatch_selected) else null
            )
            if (selected) {
                val onInk = ThemeContrast.ratio(color(R.color.saegeul_ink), swatchColor)
                val onWhite = ThemeContrast.ratio(Color.WHITE, swatchColor)
                check.imageTintList = ColorStateList.valueOf(
                    if (onWhite >= onInk) Color.WHITE else color(R.color.saegeul_ink)
                )
            }
        }
        refresh()
        return container to refresh
    }

    private val colorPickerPalette = listOf(
        0xFF101827.toInt(), 0xFFFFF9ED.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(),
        0xFF176B50.toInt(), 0xFF55D6A6.toInt(), 0xFF2F5BD3.toInt(), 0xFFB83A32.toInt(),
        0xFFC49A45.toInt(), 0xFF38BDF8.toInt(), 0xFFEC4899.toInt(), 0xFF8B5CF6.toInt(),
        0xFFF59E0B.toInt(), 0xFF10B981.toInt(), 0xFF52605D.toInt(), 0xFFD2DED7.toInt()
    )

    private fun showColorPickerDialog(current: Int, onPicked: (Int) -> Unit) {
        val hexInput: EditText = editText {
            setText(ThemeHexColor.format(current))
            hint = getString(R.string.theme_color_hex_hint)
        }
        val errorText = TextView(this).apply {
            text = getString(R.string.theme_color_hex_invalid)
            setTextColor(color(R.color.red_400))
            textSize = 12f
            visibility = View.GONE
        }
        val paletteContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        var selectedPaletteColor: Int? = current.takeIf { it in colorPickerPalette }
        val paletteRefreshers = mutableListOf<() -> Unit>()
        colorPickerPalette.forEachIndexed { index, swatchColor ->
            val (swatch, refresh) = selectableSwatch(
                swatchColor = swatchColor,
                oval = true,
                swatchSizePx = dp(36),
                description = getString(R.string.theme_swatch_color_index, index + 1),
                isSelected = { selectedPaletteColor == swatchColor },
                onClick = {
                    hexInput.setText(ThemeHexColor.format(swatchColor))
                    errorText.visibility = View.GONE
                    selectedPaletteColor = swatchColor
                    paletteRefreshers.forEach { it() }
                }
            )
            paletteRefreshers += refresh
            paletteContent.addView(
                swatch,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { rightMargin = dp(8) }
            )
        }
        val paletteRow = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(paletteContent)
        }
        val container = verticalLayout {
            setPadding(dp(20), dp(12), dp(20), dp(0))
            add(paletteRow, lParams(matchParent, wrapContent) { bottomMargin = dp(12) })
            add(hexInput, lParams(matchParent, wrapContent))
            add(errorText, lParams(matchParent, wrapContent) { topMargin = dp(4) })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.theme_color_picker_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val parsed = ThemeHexColor.parse(hexInput.text.toString())
            if (parsed == null) {
                errorText.visibility = View.VISIBLE
            } else {
                onPicked(parsed)
                dialog.dismiss()
            }
        }
    }

    // ---- Easy tier containers ----

    private val startingThemeContainer by lazy {
        HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
    }

    private val seedBrightnessContainer by lazy {
        LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    }

    private val seedSwatchContainer by lazy {
        HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
    }

    private val keyToneSectionContainer by lazy {
        verticalLayout { }
    }

    private val contrastWarningContainer by lazy {
        LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    }

    // ---- Deeper tier ("더 꾸미기") ----

    private var moreExpanded = false

    private val moreHeaderIcon by lazy {
        imageView {
            imageResource = R.drawable.ic_baseline_expand_more_24
            imageTintList = ColorStateList.valueOf(color(R.color.saegeul_ink))
        }
    }

    private val moreSummaryText by lazy {
        textView {
            textSize = 12f
            setTextColor(color(R.color.saegeul_secondary))
        }
    }

    private val moreHeaderRow by lazy {
        horizontalLayout {
            gravity = Gravity.CENTER_VERTICAL
            background = styledDrawable(android.R.attr.selectableItemBackground)
            setOnClickListener { toggleMoreExpanded() }
            add(
                textView {
                    text = getString(R.string.theme_section_more)
                    textSize = 13f
                    paint.isFakeBoldText = true
                    setTextColor(color(R.color.saegeul_ink))
                },
                lParams(wrapContent, wrapContent) { leftMargin = dp(16) }
            )
            add(moreSummaryText, lParams(0, wrapContent) { weight = 1f; leftMargin = dp(8) })
            add(moreHeaderIcon, lParams(dp(24), dp(24)) { rightMargin = dp(16) })
        }
    }

    private val moreContainer by lazy {
        verticalLayout { visibility = View.GONE }
    }

    private val detailColorContainer by lazy {
        verticalLayout { }
    }

    private fun effectsInUseCount(): Int {
        var n = 0
        theme.lightingEffect?.let { if (it.mode != "off") n++ }
        theme.particleEffect?.let { if (it.type != "off") n++ }
        theme.keyGlowEffect?.let { if (it.enabled) n++ }
        if (theme.globalKeyStyle != null) n++
        if (!theme.keyOverrides.isNullOrEmpty()) n++
        return n
    }

    private fun toggleMoreExpanded() {
        moreExpanded = !moreExpanded
        updateMoreVisibility()
    }

    private fun updateMoreVisibility() {
        moreContainer.visibility = if (moreExpanded) View.VISIBLE else View.GONE
        moreHeaderIcon.imageResource =
            if (moreExpanded) R.drawable.ic_baseline_expand_less_24 else R.drawable.ic_baseline_expand_more_24
        val count = effectsInUseCount()
        moreSummaryText.text = if (!moreExpanded && count > 0) getString(R.string.theme_more_summary_effects, count) else ""
    }

    private val ambientModeContainer by lazy {
        HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
    }

    private val ambientDirectionContainer by lazy {
        HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
    }

    private val reactiveModeContainer by lazy {
        HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
    }

    private val particleModeContainer by lazy {
        HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
    }

    private val glowColorContainer by lazy {
        HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
    }

    private val perKeyTargetContainer by lazy {
        HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
    }

    private val perKeyDetailContainer by lazy {
        verticalLayout { }
    }

    // Fine-tuning Controls: Ambient Speed
    private val ambientSpeedLabel by lazy { createTextView(R.string.theme_rgb_speed) }
    private val ambientSpeedValue by lazy { createTextView() }
    private val ambientSpeedSeekBar by lazy {
        seekBar {
            max = 300 // 0.2x ~ 3.0x -> 20 ~ 300
            progress = 100
        }
    }

    // Fine-tuning Controls: Ambient Intensity / Brightness
    private val ambientIntensityLabel by lazy { createTextView(R.string.theme_rgb_intensity) }
    private val ambientIntensityValue by lazy { createTextView() }
    private val ambientIntensitySeekBar by lazy {
        seekBar {
            max = 100 // 10% ~ 100%
            progress = 80
        }
    }

    // Fine-tuning Controls: Key Translucency (Pudding Keycaps)
    private val keyTranslucencyLabel by lazy { createTextView(R.string.theme_key_translucency) }
    private val keyTranslucencyValue by lazy { createTextView() }
    private val keyTranslucencySeekBar by lazy {
        seekBar {
            max = 60 // 0% ~ 60%
            progress = 0
        }
    }

    // Fine-tuning Controls: Particle Count
    private val particleCountLabel by lazy { createTextView(R.string.theme_particle_count) }
    private val particleCountValue by lazy { createTextView() }
    private val particleCountSeekBar by lazy {
        seekBar {
            max = 28 // 4 ~ 28
            progress = 8
        }
    }

    // Fine-tuning Controls: Particle Lifetime
    private val particleLifetimeLabel by lazy { createTextView(R.string.theme_particle_lifetime) }
    private val particleLifetimeValue by lazy { createTextView() }
    private val particleLifetimeSeekBar by lazy {
        seekBar {
            max = 1200 // 200ms ~ 1200ms
            progress = 450
        }
    }

    // Fine-tuning Controls: Particle Speed
    private val particleSpeedLabel by lazy { createTextView(R.string.theme_particle_speed) }
    private val particleSpeedValue by lazy { createTextView() }
    private val particleSpeedSeekBar by lazy {
        seekBar {
            max = 300 // 0.3x ~ 3.0x
            progress = 100
        }
    }

    // Fine-tuning Controls: Key Glow Radius
    private val glowRadiusLabel by lazy { createTextView(R.string.theme_key_glow_radius) }
    private val glowRadiusValue by lazy { createTextView() }
    private val glowRadiusSeekBar by lazy {
        seekBar {
            max = 16 // 2dp ~ 16dp
            progress = 4
        }
    }

    private var selectedKeyTarget = "ALL"

    /** Refresh callbacks for every "세부 색" swatch, run after any theme change so they stay in sync. */
    private val swatchRefreshers = mutableListOf<() -> Unit>()

    /** Same as [swatchRefreshers] but for the "개별 키" section, which is rebuilt when the target changes. */
    private var perKeyRefreshers = mutableListOf<() -> Unit>()

    // Whether the "색 하나로 만들기" seed generator is set to produce a light or dark palette.
    private var seedGenIsDark = false

    // Whether the "키 톤" segment (background-image key legibility) is set to dark-colored keys.
    private var keyToneIsDark = false

    private val backgroundImageLabel by lazy { createSectionHeader(getString(R.string.theme_background_image)) }

    private val cropLabel by lazy {
        createTextView(R.string.recrop_image, ripple = true)
    }

    private val changeImageLabel by lazy {
        createTextView(R.string.theme_choose_image, ripple = true)
    }

    private val removeImageLabel by lazy {
        createTextView(R.string.theme_remove_image, ripple = true)
    }

    private val brightnessLabel by lazy {
        createTextView(R.string.brightness)
    }
    private val brightnessValue by lazy {
        createTextView()
    }
    private val brightnessSeekBar by lazy {
        seekBar {
            max = 100
        }
    }

    // Sub-containers for grouped visibility
    private lateinit var ambientControlsLayout: LinearLayout
    private lateinit var particleControlsLayout: LinearLayout
    private lateinit var glowControlsLayout: LinearLayout

    private val editorContainer by lazy {
        verticalLayout {
            setPadding(dp(12), dp(8), dp(12), dp(32))

            // ---- Easy tier ----

            // a. Starting theme
            add(createSectionHeader(getString(R.string.theme_section_starting_theme)), lParams(matchParent, wrapContent))
            add(startingThemeContainer, lParams(matchParent, wrapContent) { bottomMargin = dp(8) })

            // b. Make from one color
            add(createSectionHeader(getString(R.string.theme_section_seed_color)), lParams(matchParent, wrapContent))
            add(seedBrightnessContainer, lParams(matchParent, dp(48)) {
                leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(8)
            })
            add(seedSwatchContainer, lParams(matchParent, wrapContent) { bottomMargin = dp(8) })

            // c. Background image (+ key tone segment when a background image is set)
            add(backgroundImageLabel, lParams(matchParent, wrapContent))
            add(changeImageLabel, lParams(matchParent, dp(48)))
            add(cropLabel, lParams(matchParent, dp(48)))
            add(removeImageLabel, lParams(matchParent, dp(48)))
            val brightnessRow = horizontalLayout {
                gravity = Gravity.CENTER_VERTICAL
                add(brightnessLabel, lParams(0, dp(40)) { weight = 1f })
                add(brightnessValue, lParams(wrapContent, dp(40)) { rightMargin = dp(16) })
            }
            add(brightnessRow, lParams(matchParent, wrapContent))
            add(brightnessSeekBar, lParams(matchParent, wrapContent) {
                leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(8)
            })
            add(keyToneSectionContainer, lParams(matchParent, wrapContent) { bottomMargin = dp(8) })

            // d. Contrast warning (only rendered when there is an issue)
            add(contrastWarningContainer, lParams(matchParent, wrapContent) { bottomMargin = dp(8) })

            // ---- Deeper tier ----
            add(moreHeaderRow, lParams(matchParent, dp(48)))
            add(moreContainer, lParams(matchParent, wrapContent))
        }
    }

    private fun buildMoreContainer() {
        moreContainer.apply {
            // e. Detail colors
            add(createSectionHeader(getString(R.string.theme_section_detail_colors)), lParams(matchParent, wrapContent))
            add(detailColorContainer, lParams(matchParent, wrapContent) { bottomMargin = dp(8) })

            // f. Ambient lighting / reactive / particle / glow
            add(createSectionHeader(getString(R.string.theme_rgb_backlight)), lParams(matchParent, wrapContent))
            add(ambientModeContainer, lParams(matchParent, wrapContent) { bottomMargin = dp(6) })

            ambientControlsLayout = verticalLayout {
                add(createTextView(R.string.theme_rgb_direction), lParams(matchParent, dp(36)))
                add(ambientDirectionContainer, lParams(matchParent, wrapContent) { bottomMargin = dp(6) })

                val speedRow = horizontalLayout {
                    gravity = Gravity.CENTER_VERTICAL
                    add(ambientSpeedLabel, lParams(0, dp(36)) { weight = 1f })
                    add(ambientSpeedValue, lParams(wrapContent, dp(36)) { rightMargin = dp(16) })
                }
                add(speedRow, lParams(matchParent, wrapContent))
                add(ambientSpeedSeekBar, lParams(matchParent, wrapContent) {
                    leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(8)
                })

                val intensityRow = horizontalLayout {
                    gravity = Gravity.CENTER_VERTICAL
                    add(ambientIntensityLabel, lParams(0, dp(36)) { weight = 1f })
                    add(ambientIntensityValue, lParams(wrapContent, dp(36)) { rightMargin = dp(16) })
                }
                add(intensityRow, lParams(matchParent, wrapContent))
                add(ambientIntensitySeekBar, lParams(matchParent, wrapContent) {
                    leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(8)
                })

                val transRow = horizontalLayout {
                    gravity = Gravity.CENTER_VERTICAL
                    add(keyTranslucencyLabel, lParams(0, dp(36)) { weight = 1f })
                    add(keyTranslucencyValue, lParams(wrapContent, dp(36)) { rightMargin = dp(16) })
                }
                add(transRow, lParams(matchParent, wrapContent))
                add(keyTranslucencySeekBar, lParams(matchParent, wrapContent) {
                    leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(8)
                })
            }
            add(ambientControlsLayout, lParams(matchParent, wrapContent))

            add(createSectionHeader(getString(R.string.theme_rgb_reactive_title)), lParams(matchParent, wrapContent))
            add(reactiveModeContainer, lParams(matchParent, wrapContent) { bottomMargin = dp(8) })

            add(createSectionHeader(getString(R.string.theme_particle_effect)), lParams(matchParent, wrapContent))
            add(particleModeContainer, lParams(matchParent, wrapContent) { bottomMargin = dp(6) })

            particleControlsLayout = verticalLayout {
                val pCountRow = horizontalLayout {
                    gravity = Gravity.CENTER_VERTICAL
                    add(particleCountLabel, lParams(0, dp(36)) { weight = 1f })
                    add(particleCountValue, lParams(wrapContent, dp(36)) { rightMargin = dp(16) })
                }
                add(pCountRow, lParams(matchParent, wrapContent))
                add(particleCountSeekBar, lParams(matchParent, wrapContent) {
                    leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(8)
                })

                val pLifeRow = horizontalLayout {
                    gravity = Gravity.CENTER_VERTICAL
                    add(particleLifetimeLabel, lParams(0, dp(36)) { weight = 1f })
                    add(particleLifetimeValue, lParams(wrapContent, dp(36)) { rightMargin = dp(16) })
                }
                add(pLifeRow, lParams(matchParent, wrapContent))
                add(particleLifetimeSeekBar, lParams(matchParent, wrapContent) {
                    leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(8)
                })

                val pSpeedRow = horizontalLayout {
                    gravity = Gravity.CENTER_VERTICAL
                    add(particleSpeedLabel, lParams(0, dp(36)) { weight = 1f })
                    add(particleSpeedValue, lParams(wrapContent, dp(36)) { rightMargin = dp(16) })
                }
                add(pSpeedRow, lParams(matchParent, wrapContent))
                add(particleSpeedSeekBar, lParams(matchParent, wrapContent) {
                    leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(8)
                })
            }
            add(particleControlsLayout, lParams(matchParent, wrapContent))

            add(createSectionHeader(getString(R.string.theme_key_glow_effect)), lParams(matchParent, wrapContent))
            add(glowColorContainer, lParams(matchParent, wrapContent) { bottomMargin = dp(6) })

            glowControlsLayout = verticalLayout {
                val glowRadRow = horizontalLayout {
                    gravity = Gravity.CENTER_VERTICAL
                    add(glowRadiusLabel, lParams(0, dp(36)) { weight = 1f })
                    add(glowRadiusValue, lParams(wrapContent, dp(36)) { rightMargin = dp(16) })
                }
                add(glowRadRow, lParams(matchParent, wrapContent))
                add(glowRadiusSeekBar, lParams(matchParent, wrapContent) {
                    leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(8)
                })
            }
            add(glowControlsLayout, lParams(matchParent, wrapContent))

            // g. Per-key
            add(createSectionHeader(getString(R.string.theme_per_key_customization)), lParams(matchParent, wrapContent))
            add(perKeyTargetContainer, lParams(matchParent, wrapContent) { bottomMargin = dp(4) })
            add(perKeyDetailContainer, lParams(matchParent, wrapContent) { bottomMargin = dp(8) })
        }
    }

    private val scrollView by lazy {
        verticalLayout {
            add(previewUi.root, lParams(wrapContent, wrapContent) {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
                bottomMargin = dp(12)
            })
            add(editorContainer, lParams(matchParent, wrapContent))
        }.wrapInScrollView {
            isFillViewport = true
        }
    }

    private val ui by lazy {
        constraintLayout {
            add(toolbar, lParams(matchParent, wrapContent) {
                topOfParent()
                centerHorizontally()
            })
            add(scrollView, lParams {
                below(toolbar)
                centerHorizontally()
                bottomOfParent()
            })
        }
    }

    private var newCreated = true

    private lateinit var theme: Theme.Custom

    private class BackgroundStates {
        lateinit var launcher: ActivityResultLauncher<CropOption>
        var srcImageExtension: String? = null
        var srcImageBuffer: ByteArray? = null
        var cropRect: Rect? = null
        var cropRotation: Int = 0
        var croppedBitmap: Bitmap? = null
        var filteredDrawable: BitmapDrawable? = null
        var srcImageFile: File? = null
        var croppedImageFile: File? = null
    }

    private val backgroundStates by lazy { BackgroundStates() }

    private inline fun whenHasBackground(
        block: BackgroundStates.(Theme.Custom.CustomBackground) -> Unit,
    ) {
        if (theme.backgroundImage != null)
            block(backgroundStates, theme.backgroundImage!!)
    }

    private fun updatePreview() {
        val bgDrawable = if (theme.backgroundImage != null && backgroundStates.filteredDrawable != null) {
            backgroundStates.filteredDrawable
        } else {
            null
        }
        previewUi.setTheme(theme, bgDrawable)
        updateControlsVisibility()
        swatchRefreshers.forEach { it() }
        perKeyRefreshers.forEach { it() }
        refreshContrastRow()
        if (!moreExpanded) updateMoreVisibility()
    }

    @SuppressLint("SetTextI18n")
    private fun updateControlsVisibility() {
        val hasBg = theme.backgroundImage != null
        cropLabel.visibility = if (hasBg) View.VISIBLE else View.GONE
        removeImageLabel.visibility = if (hasBg) View.VISIBLE else View.GONE
        brightnessLabel.visibility = if (hasBg) View.VISIBLE else View.GONE
        brightnessValue.visibility = if (hasBg) View.VISIBLE else View.GONE
        brightnessSeekBar.visibility = if (hasBg) View.VISIBLE else View.GONE
        keyToneSectionContainer.visibility = if (hasBg) View.VISIBLE else View.GONE

        val hasAmbient = (theme.lightingEffect?.effectiveAmbientMode ?: "off") != "off"
        ambientControlsLayout.visibility = if (hasAmbient) View.VISIBLE else View.GONE

        val hasParticle = (theme.particleEffect?.type ?: "off") != "off"
        particleControlsLayout.visibility = if (hasParticle) View.VISIBLE else View.GONE

        val hasGlow = theme.keyGlowEffect?.enabled == true
        glowControlsLayout.visibility = if (hasGlow) View.VISIBLE else View.GONE

        theme.lightingEffect?.let { l ->
            ambientSpeedValue.text = "%.1fx".format(l.speed)
            ambientIntensityValue.text = "${(l.intensity * 100).toInt()}%"
            keyTranslucencyValue.text = "${(l.keyTranslucency * 100).toInt()}%"
        }

        theme.particleEffect?.let { p ->
            particleCountValue.text = "${p.particleCount}개"
            particleLifetimeValue.text = "${p.lifetimeMs}ms"
            particleSpeedValue.text = "%.1fx".format(p.speed)
        }

        theme.keyGlowEffect?.let { g ->
            glowRadiusValue.text = "${g.glowRadius.toInt()}dp"
        }
    }

    // ---- a. Starting theme ----

    private fun applyPreset(preset: Theme.Builtin) {
        theme = ThemeColorApply.replaceColors(theme, preset)
        updatePreview()
        setupStartingThemeRow()
    }

    private fun setupStartingThemeRow() {
        val chips = ThemeManager.BuiltinThemes.map { preset ->
            val selected = theme.keyTextColor == preset.keyTextColor &&
                theme.backgroundColor == preset.backgroundColor &&
                theme.accentKeyBackgroundColor == preset.accentKeyBackgroundColor
            styledChip(ThemeDisplayNames.displayName(this, preset), selected) { applyPreset(preset) }
        }
        startingThemeContainer.removeAllViews()
        startingThemeContainer.addView(horizontalChipRow(chips))
    }

    // ---- b. Make from one color ----

    private val seedSwatches = listOf(
        0xFF55D6A6.toInt(), // 새글 제이드
        0xFF2F5BD3.toInt(), // 새글 네이비 기반 청색
        0xFFC84141.toInt(),
        0xFFC88541.toInt(),
        0xFFC8C841.toInt(),
        0xFF85C841.toInt(),
        0xFF41C885.toInt(),
        0xFF419BC8.toInt(),
        0xFF6E41C8.toInt(),
        0xFFB141C8.toInt(),
        0xFFC8419B.toInt(),
        0xFFC84158.toInt()
    )

    // The seed swatch most recently applied, so the editor can show which one is
    // "selected" even though the generated theme's colors no longer equal the seed itself.
    private var lastAppliedSeedColor: Int? = null

    private fun applySeedColor(seedColor: Int) {
        val generated = ThemeColorGenerator.generate(seedColor, seedGenIsDark, theme.name)
        theme = ThemeColorApply.replaceColors(theme, generated)
        lastAppliedSeedColor = seedColor
        updatePreview()
        setupStartingThemeRow()
        setupSeedSwatchRow()
    }

    private fun setupSeedBrightnessRow() {
        seedBrightnessContainer.removeAllViews()
        seedBrightnessContainer.addView(
            twoOptionSegment(
                getString(R.string.theme_brightness_light),
                getString(R.string.theme_brightness_dark),
                isASelected = !seedGenIsDark,
                onSelectA = { seedGenIsDark = false; setupSeedBrightnessRow() },
                onSelectB = { seedGenIsDark = true; setupSeedBrightnessRow() }
            ),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        )
    }

    private fun setupSeedSwatchRow() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        seedSwatches.forEachIndexed { index, seedColor ->
            val (swatch, _) = selectableSwatch(
                swatchColor = seedColor,
                oval = true,
                swatchSizePx = dp(40),
                description = getString(R.string.theme_swatch_color_index, index + 1),
                isSelected = { lastAppliedSeedColor == seedColor },
                onClick = { applySeedColor(seedColor) }
            )
            row.addView(
                swatch,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { rightMargin = dp(10) }
            )
        }
        seedSwatchContainer.removeAllViews()
        seedSwatchContainer.addView(row)
    }

    // ---- c. Background image / key tone ----

    private fun applyKeyTone(darkKeys: Boolean) {
        val template = if (darkKeys) ThemePreset.TransparentLight else ThemePreset.TransparentDark
        theme = ThemeColorApply.replaceColors(theme, template)
        keyToneIsDark = darkKeys
        updatePreview()
        setupKeyToneRow()
    }

    private fun setupKeyToneRow() {
        keyToneSectionContainer.removeAllViews()
        if (theme.backgroundImage == null) return
        keyToneSectionContainer.add(
            twoOptionSegment(
                getString(R.string.theme_key_tone_light),
                getString(R.string.theme_key_tone_dark),
                isASelected = !keyToneIsDark,
                onSelectA = { applyKeyTone(false) },
                onSelectB = { applyKeyTone(true) }
            ),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        )
    }

    // ---- d. Contrast warning ----

    private fun roleStringRes(role: ThemeContrast.Role): Int = when (role) {
        ThemeContrast.Role.Key -> R.string.theme_contrast_role_key
        ThemeContrast.Role.AltKey -> R.string.theme_contrast_role_alt_key
        ThemeContrast.Role.AccentKey -> R.string.theme_contrast_role_accent_key
        ThemeContrast.Role.Candidate -> R.string.theme_contrast_role_candidate
        ThemeContrast.Role.KeyOverride -> R.string.theme_contrast_role_key_override
    }

    private fun refreshContrastRow() {
        contrastWarningContainer.removeAllViews()
        val issues = ThemeContrast.findIssues(theme)
        val worst = issues.minByOrNull { it.ratio } ?: return
        val roleName = getString(roleStringRes(worst.role))
        val ratioText = "%.1f".format(worst.ratio)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        row.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_baseline_warning_24)
                imageTintList = ColorStateList.valueOf(color(R.color.red_400))
            },
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) }
        )
        row.addView(
            TextView(this).apply {
                text = getString(R.string.theme_contrast_warning, roleName, ratioText)
                textSize = 12f
                setTextColor(color(R.color.red_400))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
                rightMargin = dp(8)
            }
        )
        row.addView(
            styledChip(getString(R.string.theme_contrast_autofix), false) {
                theme = ThemeContrast.autoFix(theme)
                updatePreview()
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36))
        )
        contrastWarningContainer.addView(row)
    }

    // ---- e. Detail colors (21 fields) ----

    private data class ColorField(
        @StringRes val labelRes: Int,
        val get: (Theme.Custom) -> Int,
        val set: (Theme.Custom, Int) -> Theme.Custom
    )

    private fun setupDetailColorSection() {
        val groups = listOf(
            R.string.theme_color_group_keyboard to listOf(
                ColorField(R.string.theme_field_background, { it.backgroundColor }, { t, c -> t.copy(backgroundColor = c) }),
                ColorField(R.string.theme_field_keyboard, { it.keyboardColor }, { t, c -> t.copy(keyboardColor = c) }),
                ColorField(R.string.theme_field_bar, { it.barColor }, { t, c -> t.copy(barColor = c) })
            ),
            R.string.theme_color_group_key to listOf(
                ColorField(R.string.theme_field_key_background, { it.keyBackgroundColor }, { t, c -> t.copy(keyBackgroundColor = c) }),
                ColorField(R.string.theme_field_key_text, { it.keyTextColor }, { t, c -> t.copy(keyTextColor = c) }),
                ColorField(R.string.theme_field_space_bar, { it.spaceBarColor }, { t, c -> t.copy(spaceBarColor = c) }),
                ColorField(R.string.theme_field_key_press_highlight, { it.keyPressHighlightColor }, { t, c -> t.copy(keyPressHighlightColor = c) }),
                ColorField(R.string.theme_field_key_shadow, { it.keyShadowColor }, { t, c -> t.copy(keyShadowColor = c) })
            ),
            R.string.theme_color_group_alt_key to listOf(
                ColorField(R.string.theme_field_alt_key_background, { it.altKeyBackgroundColor }, { t, c -> t.copy(altKeyBackgroundColor = c) }),
                ColorField(R.string.theme_field_alt_key_text, { it.altKeyTextColor }, { t, c -> t.copy(altKeyTextColor = c) })
            ),
            R.string.theme_color_group_accent_key to listOf(
                ColorField(R.string.theme_field_accent_key_background, { it.accentKeyBackgroundColor }, { t, c -> t.copy(accentKeyBackgroundColor = c) }),
                ColorField(R.string.theme_field_accent_key_text, { it.accentKeyTextColor }, { t, c -> t.copy(accentKeyTextColor = c) }),
                ColorField(R.string.theme_field_generic_active_background, { it.genericActiveBackgroundColor }, { t, c -> t.copy(genericActiveBackgroundColor = c) }),
                ColorField(R.string.theme_field_generic_active_text, { it.genericActiveForegroundColor }, { t, c -> t.copy(genericActiveForegroundColor = c) })
            ),
            R.string.theme_color_group_candidate to listOf(
                ColorField(R.string.theme_field_candidate_text, { it.candidateTextColor }, { t, c -> t.copy(candidateTextColor = c) }),
                ColorField(R.string.theme_field_candidate_label, { it.candidateLabelColor }, { t, c -> t.copy(candidateLabelColor = c) }),
                ColorField(R.string.theme_field_candidate_comment, { it.candidateCommentColor }, { t, c -> t.copy(candidateCommentColor = c) }),
                ColorField(R.string.theme_field_divider, { it.dividerColor }, { t, c -> t.copy(dividerColor = c) })
            ),
            R.string.theme_color_group_popup to listOf(
                ColorField(R.string.theme_field_popup_background, { it.popupBackgroundColor }, { t, c -> t.copy(popupBackgroundColor = c) }),
                ColorField(R.string.theme_field_popup_text, { it.popupTextColor }, { t, c -> t.copy(popupTextColor = c) })
            ),
            R.string.theme_color_group_clipboard to listOf(
                ColorField(R.string.theme_field_clipboard_entry, { it.clipboardEntryColor }, { t, c -> t.copy(clipboardEntryColor = c) })
            )
        )
        detailColorContainer.apply {
            groups.forEach { (groupLabelRes, fields) ->
                add(createSectionHeader(getString(groupLabelRes)), lParams(matchParent, wrapContent))
                fields.forEach { field ->
                    add(
                        colorSwatchRow(
                            getString(field.labelRes),
                            currentColor = { field.get(theme) },
                            onPicked = { picked -> theme = field.set(theme, picked); updatePreview() }
                        ),
                        lParams(matchParent, wrapContent)
                    )
                }
            }
        }
    }

    // ---- f. Ambient / reactive / particle / glow (unchanged behavior, unified chip styling) ----

    private fun setupAmbientModeRow() {
        val modes = listOf(
            getString(R.string.theme_rgb_mode_off) to "off",
            getString(R.string.theme_rgb_mode_wave) to "rgb_wave",
            getString(R.string.theme_rgb_mode_breathe) to "rgb_breathe",
            getString(R.string.theme_rgb_mode_cyberpunk) to "cyberpunk",
            getString(R.string.theme_rgb_mode_matrix) to "matrix_flow",
            getString(R.string.theme_rgb_mode_pulse) to "neon_pulse",
            getString(R.string.theme_rgb_mode_aurora) to "aurora",
            getString(R.string.theme_rgb_mode_starlight) to "starlight",
            getString(R.string.theme_rgb_mode_ocean) to "ocean_tide",
            getString(R.string.theme_rgb_mode_fire) to "fire_ember",
            getString(R.string.theme_rgb_mode_supernova) to "supernova",
            getString(R.string.theme_rgb_mode_sakura) to "sakura_breeze",
            getString(R.string.theme_rgb_mode_frost) to "frost_crystal"
        )
        val currentAmbient = theme.lightingEffect?.effectiveAmbientMode ?: "off"
        val chips = modes.map { (label, modeKey) ->
            styledChip(label, currentAmbient == modeKey) {
                val currentDef = theme.lightingEffect ?: Theme.Custom.LightingEffectDef()
                val defaultSpeed = when (modeKey) {
                    "neon_pulse" -> 1.5f
                    "matrix_flow" -> 1.2f
                    "starlight" -> 1.0f
                    "rgb_breathe" -> 0.85f
                    else -> 1.0f
                }
                theme = theme.copy(
                    lightingEffect = currentDef.copy(
                        mode = modeKey,
                        speed = if (currentDef.speed == 1.0f) defaultSpeed else currentDef.speed
                    )
                )
                ambientSpeedSeekBar.progress = (theme.lightingEffect!!.speed * 100).toInt()
                ambientIntensitySeekBar.progress = (theme.lightingEffect!!.intensity * 100).toInt()
                keyTranslucencySeekBar.progress = (theme.lightingEffect!!.keyTranslucency * 100).toInt()
                updatePreview()
                setupAmbientModeRow()
            }
        }
        ambientModeContainer.removeAllViews()
        ambientModeContainer.addView(horizontalChipRow(chips))
    }

    private fun setupAmbientDirectionRow() {
        val directions = listOf(
            getString(R.string.theme_rgb_dir_left_to_right) to "left_to_right",
            getString(R.string.theme_rgb_dir_right_to_left) to "right_to_left",
            getString(R.string.theme_rgb_dir_top_to_bottom) to "top_to_bottom",
            getString(R.string.theme_rgb_dir_bottom_to_top) to "bottom_to_top",
            getString(R.string.theme_rgb_dir_diagonal) to "diagonal",
            getString(R.string.theme_rgb_dir_radial) to "radial"
        )
        val currentDir = theme.lightingEffect?.direction ?: "left_to_right"
        val chips = directions.map { (label, dirKey) ->
            styledChip(label, currentDir == dirKey) {
                val currentDef = theme.lightingEffect ?: Theme.Custom.LightingEffectDef()
                theme = theme.copy(lightingEffect = currentDef.copy(direction = dirKey))
                updatePreview()
                setupAmbientDirectionRow()
            }
        }
        ambientDirectionContainer.removeAllViews()
        ambientDirectionContainer.addView(horizontalChipRow(chips))
    }

    private fun setupReactiveModeRow() {
        val reactives = listOf(
            getString(R.string.theme_reactive_mode_off) to "off",
            getString(R.string.theme_reactive_mode_ripple) to "ripple",
            getString(R.string.theme_reactive_mode_fade) to "fade",
            getString(R.string.theme_reactive_mode_firework) to "firework",
            getString(R.string.theme_reactive_mode_laser) to "laser"
        )
        val currentReactive = theme.lightingEffect?.effectiveReactiveMode ?: "off"
        val chips = reactives.map { (label, modeKey) ->
            styledChip(label, currentReactive == modeKey) {
                val currentDef = theme.lightingEffect ?: Theme.Custom.LightingEffectDef()
                theme = theme.copy(lightingEffect = currentDef.copy(reactiveMode = modeKey))
                updatePreview()
                setupReactiveModeRow()
            }
        }
        reactiveModeContainer.removeAllViews()
        reactiveModeContainer.addView(horizontalChipRow(chips))
    }

    private fun setupParticleModeRow() {
        val particles = listOf(
            getString(R.string.theme_particle_mode_off) to "off",
            getString(R.string.theme_particle_mode_star) to "star_sparkle",
            getString(R.string.theme_particle_mode_dust) to "glowing_dust",
            getString(R.string.theme_particle_mode_burst) to "neon_burst",
            getString(R.string.theme_particle_mode_ripple) to "cosmic_ripple"
        )
        val chips = particles.map { (label, typeKey) ->
            val isCurrent = (theme.particleEffect?.type ?: "off") == typeKey
            styledChip(label, isCurrent) {
                val currentP = theme.particleEffect ?: Theme.Custom.ParticleEffectDef()
                theme = theme.copy(
                    particleEffect = currentP.copy(
                        type = typeKey,
                        particleCount = if (typeKey == "cosmic_ripple") 6 else 10,
                        lifetimeMs = 500L
                    )
                )
                particleCountSeekBar.progress = theme.particleEffect!!.particleCount
                particleLifetimeSeekBar.progress = theme.particleEffect!!.lifetimeMs.toInt()
                particleSpeedSeekBar.progress = (theme.particleEffect!!.speed * 100).toInt()
                updatePreview()
                setupParticleModeRow()
            }
        }
        particleModeContainer.removeAllViews()
        particleModeContainer.addView(horizontalChipRow(chips))
    }

    private fun setupGlowColorRow() {
        val glowColors = listOf(
            null to getString(R.string.theme_rgb_mode_off),
            0xFF00F0FF.toInt() to "Cyan",
            0xFFFF007F.toInt() to "Pink",
            0xFFFFE600.toInt() to "Gold",
            0xFF00FF66.toInt() to "Green",
            0xFFA855F7.toInt() to "Purple",
            0xFFFFFFFF.toInt() to "White"
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        glowColors.forEach { (colorVal, _) ->
            val circle = View(this).apply {
                val isOff = colorVal == null
                val isSelected = if (isOff) (theme.keyGlowEffect?.enabled != true)
                else (theme.keyGlowEffect?.enabled == true && theme.keyGlowEffect?.glowColor == colorVal)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorVal ?: color(R.color.saegeul_outline))
                    setStroke(
                        dp(if (isSelected) 3 else 1),
                        if (isSelected) color(R.color.saegeul_action) else color(R.color.saegeul_outline)
                    )
                }
                setOnClickListener {
                    theme = if (isOff) {
                        theme.copy(keyGlowEffect = null)
                    } else {
                        val curRadius = theme.keyGlowEffect?.glowRadius ?: 4f
                        theme.copy(keyGlowEffect = Theme.Custom.KeyGlowDef(enabled = true, glowColor = colorVal, glowRadius = curRadius))
                    }
                    glowRadiusSeekBar.progress = (theme.keyGlowEffect?.glowRadius ?: 4f).toInt()
                    updatePreview()
                    setupGlowColorRow()
                }
            }
            row.addView(circle, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(10) })
        }
        glowColorContainer.removeAllViews()
        glowColorContainer.addView(row)
    }

    // ---- g. Per-key ----

    private fun currentPerKeyStyle(): Theme.Custom.KeyCustomStyle =
        if (selectedKeyTarget == "ALL") theme.globalKeyStyle ?: Theme.Custom.KeyCustomStyle()
        else (theme.keyOverrides ?: emptyMap())[selectedKeyTarget] ?: Theme.Custom.KeyCustomStyle()

    private fun updatePerKeyStyle(mutate: (Theme.Custom.KeyCustomStyle) -> Theme.Custom.KeyCustomStyle) {
        val updated = mutate(currentPerKeyStyle())
        theme = if (selectedKeyTarget == "ALL") {
            theme.copy(globalKeyStyle = updated)
        } else {
            val overrides = (theme.keyOverrides ?: emptyMap()).toMutableMap()
            overrides[selectedKeyTarget] = updated
            theme.copy(keyOverrides = overrides)
        }
        updatePreview()
    }

    private fun resetPerKeyStyle() {
        theme = if (selectedKeyTarget == "ALL") {
            theme.copy(globalKeyStyle = null)
        } else {
            val overrides = (theme.keyOverrides ?: emptyMap()).toMutableMap()
            overrides.remove(selectedKeyTarget)
            theme.copy(keyOverrides = if (overrides.isEmpty()) null else overrides)
        }
        updatePreview()
        setupPerKeyDetailSection()
    }

    private fun setupPerKeyRow() {
        val targets = listOf(
            getString(R.string.theme_target_all_keys) to "ALL",
            getString(R.string.theme_target_space) to "button_space",
            getString(R.string.theme_target_return) to "button_return",
            getString(R.string.theme_target_backspace) to "button_backspace",
            getString(R.string.theme_target_shift) to "button_shift"
        )
        val chips = targets.map { (name, targetKey) ->
            styledChip(name, selectedKeyTarget == targetKey) {
                selectedKeyTarget = targetKey
                setupPerKeyRow()
                setupPerKeyDetailSection()
            }
        }
        perKeyTargetContainer.removeAllViews()
        perKeyTargetContainer.addView(horizontalChipRow(chips))
    }

    private fun setupPerKeyDetailSection() {
        perKeyRefreshers = mutableListOf()
        perKeyDetailContainer.removeAllViews()

        val bgRow = colorSwatchRow(
            getString(R.string.theme_key_bg_color),
            refresherList = perKeyRefreshers,
            currentColor = { currentPerKeyStyle().keyBackgroundColor ?: theme.keyBackgroundColor },
            onPicked = { picked -> updatePerKeyStyle { it.copy(keyBackgroundColor = picked) } }
        )
        val textRow = colorSwatchRow(
            getString(R.string.theme_key_text_color),
            refresherList = perKeyRefreshers,
            currentColor = { currentPerKeyStyle().keyTextColor ?: theme.keyTextColor },
            onPicked = { picked -> updatePerKeyStyle { it.copy(keyTextColor = picked) } }
        )
        val borderRow = colorSwatchRow(
            getString(R.string.theme_key_border_color),
            refresherList = perKeyRefreshers,
            currentColor = { currentPerKeyStyle().keyBorderColor ?: theme.keyShadowColor },
            onPicked = { picked -> updatePerKeyStyle { it.copy(keyBorderColor = picked) } }
        )

        val radiusLabel = createTextView(R.string.theme_key_border_radius)
        val radiusValue = createTextView()
        val radiusSeekBar = seekBar {
            max = 24
            progress = currentPerKeyStyle().cornerRadius?.toInt() ?: 0
        }
        fun refreshRadiusValue() {
            radiusValue.text = "${radiusSeekBar.progress}dp"
        }
        refreshRadiusValue()
        radiusSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                refreshRadiusValue()
                if (fromUser) {
                    updatePerKeyStyle { it.copy(cornerRadius = progress.toFloat()) }
                }
            }
        })
        perKeyRefreshers.add { radiusSeekBar.progress = currentPerKeyStyle().cornerRadius?.toInt() ?: radiusSeekBar.progress }

        val resetButton = styledChip(getString(R.string.theme_key_reset_button), false) {
            resetPerKeyStyle()
        }

        perKeyDetailContainer.apply {
            add(bgRow, lParams(matchParent, wrapContent))
            add(textRow, lParams(matchParent, wrapContent))
            add(borderRow, lParams(matchParent, wrapContent))
            val radiusRow = horizontalLayout {
                gravity = Gravity.CENTER_VERTICAL
                add(radiusLabel, lParams(0, dp(36)) { weight = 1f })
                add(radiusValue, lParams(wrapContent, dp(36)) { rightMargin = dp(16) })
            }
            add(radiusRow, lParams(matchParent, wrapContent))
            add(radiusSeekBar, lParams(matchParent, wrapContent) {
                leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(8)
            })
            add(resetButton, lParams(wrapContent, dp(48)) { leftMargin = dp(16); bottomMargin = dp(8) })
        }
    }

    private fun setupSeekBars() {
        ambientSpeedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val speed = (progress.coerceAtLeast(20) / 100f)
                    val currentDef = theme.lightingEffect ?: Theme.Custom.LightingEffectDef()
                    theme = theme.copy(lightingEffect = currentDef.copy(speed = speed))
                    updatePreview()
                }
            }
        })

        ambientIntensitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val intensity = (progress.coerceAtLeast(10) / 100f)
                    val currentDef = theme.lightingEffect ?: Theme.Custom.LightingEffectDef()
                    theme = theme.copy(lightingEffect = currentDef.copy(intensity = intensity))
                    updatePreview()
                }
            }
        })

        keyTranslucencySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val trans = (progress / 100f)
                    val currentDef = theme.lightingEffect ?: Theme.Custom.LightingEffectDef()
                    theme = theme.copy(lightingEffect = currentDef.copy(keyTranslucency = trans))
                    updatePreview()
                }
            }
        })

        particleCountSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val count = progress.coerceAtLeast(3)
                    val currentP = theme.particleEffect ?: Theme.Custom.ParticleEffectDef()
                    theme = theme.copy(particleEffect = currentP.copy(particleCount = count))
                    updatePreview()
                }
            }
        })

        particleLifetimeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val lifetime = progress.coerceAtLeast(200).toLong()
                    val currentP = theme.particleEffect ?: Theme.Custom.ParticleEffectDef()
                    theme = theme.copy(particleEffect = currentP.copy(lifetimeMs = lifetime))
                    updatePreview()
                }
            }
        })

        particleSpeedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val speed = (progress.coerceAtLeast(30) / 100f)
                    val currentP = theme.particleEffect ?: Theme.Custom.ParticleEffectDef()
                    theme = theme.copy(particleEffect = currentP.copy(speed = speed))
                    updatePreview()
                }
            }
        })

        glowRadiusSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val radius = progress.coerceAtLeast(2).toFloat()
                    val currentG = theme.keyGlowEffect ?: Theme.Custom.KeyGlowDef(enabled = true)
                    theme = theme.copy(keyGlowEffect = currentG.copy(glowRadius = radius))
                    updatePreview()
                }
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // recover from bundle
        val originTheme = intent?.parcelable<Theme.Custom>(ORIGIN_THEME)?.also { t ->
            theme = t
            whenHasBackground {
                croppedImageFile = File(it.croppedFilePath)
                srcImageFile = File(it.srcFilePath)
                cropRect = it.cropRect
                cropRotation = it.cropRotation
                if (croppedImageFile?.exists() == true) {
                    croppedBitmap = BitmapFactory.decodeFile(it.croppedFilePath)
                    filteredDrawable = BitmapDrawable(resources, croppedBitmap)
                }
            }
            newCreated = false
        }
        // create new
        if (originTheme == null) {
            val (n, c, s) = ThemeFilesManager.newCustomBackgroundImages()
            backgroundStates.apply {
                croppedImageFile = c
                srcImageFile = s
            }
            // Use SaegeulIvory as the starting template for a brand-new custom theme
            theme = ThemePreset.SaegeulIvory.deriveCustomNoBackground(n)
        }
        seedGenIsDark = theme.isDark
        keyToneIsDark = !theme.isDark
        previewUi = KeyboardPreviewUi(this, theme)

        enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(ui) { _, windowInsets ->
            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            ui.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
            }
            toolbar.topPadding = statusBars.top
            scrollView.bottomPadding = navBars.bottom
            windowInsets
        }
        // show Activity label on toolbar (explicit, so it follows the app's language
        // setting instead of the manifest label resolved from the system locale)
        setSupportActionBar(toolbar)
        supportActionBar!!.title = getString(R.string.edit_theme)
        // show back button
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        setContentView(ui)

        // Build the deeper "더 꾸미기" tier before it's needed by updateMoreVisibility()/updatePreview()
        buildMoreContainer()

        // Setup rows
        setupStartingThemeRow()
        setupSeedBrightnessRow()
        setupSeedSwatchRow()
        setupKeyToneRow()
        setupDetailColorSection()
        setupAmbientModeRow()
        setupAmbientDirectionRow()
        setupReactiveModeRow()
        setupParticleModeRow()
        setupGlowColorRow()
        setupPerKeyRow()
        setupPerKeyDetailSection()
        setupSeekBars()

        backgroundStates.launcher = registerForActivityResult(CropContract()) {
            when (it) {
                CropResult.Fail -> {
                    // Crop cancelled or failed
                }
                is CropResult.Success -> {
                    if (backgroundStates.croppedImageFile == null || backgroundStates.srcImageFile == null) {
                        val (n, c, s) = ThemeFilesManager.newCustomBackgroundImages()
                        backgroundStates.croppedImageFile = c
                        backgroundStates.srcImageFile = s
                    }
                    backgroundStates.srcImageExtension = MimeTypeMap.getSingleton()
                        .getExtensionFromMimeType(contentResolver.getType(it.srcUri))
                    backgroundStates.srcImageBuffer =
                        contentResolver.openInputStream(it.srcUri)!!.use { x -> x.readBytes() }
                    backgroundStates.cropRect = it.rect
                    backgroundStates.cropRotation = it.rotation
                    backgroundStates.croppedBitmap = it.bitmap
                    backgroundStates.filteredDrawable = BitmapDrawable(resources, it.bitmap)

                    val bg = Theme.Custom.CustomBackground(
                        croppedFilePath = backgroundStates.croppedImageFile!!.absolutePath,
                        srcFilePath = backgroundStates.srcImageFile!!.absolutePath,
                        brightness = brightnessSeekBar.progress,
                        cropRect = it.rect,
                        cropRotation = it.rotation
                    )
                    theme = theme.copy(backgroundImage = bg)
                    updateBackgroundState()
                    setupKeyToneRow()
                }
            }
        }

        changeImageLabel.setOnClickListener {
            backgroundStates.launcher.launch(CropOption.New(previewUi.intrinsicWidth, previewUi.intrinsicHeight))
        }

        cropLabel.setOnClickListener {
            backgroundStates.launchCrop(previewUi.intrinsicWidth, previewUi.intrinsicHeight)
        }

        removeImageLabel.setOnClickListener {
            theme = theme.copy(backgroundImage = null)
            backgroundStates.filteredDrawable = null
            updatePreview()
            setupKeyToneRow()
        }

        brightnessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}

            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) updateBackgroundState()
            }
        })

        whenHasBackground { background ->
            brightnessSeekBar.progress = background.brightness
            updateBackgroundState()
        }

        // Initialize SeekBars with theme properties
        theme.lightingEffect?.let { l ->
            ambientSpeedSeekBar.progress = (l.speed * 100).toInt()
            ambientIntensitySeekBar.progress = (l.intensity * 100).toInt()
            keyTranslucencySeekBar.progress = (l.keyTranslucency * 100).toInt()
        }
        theme.particleEffect?.let { p ->
            particleCountSeekBar.progress = p.particleCount
            particleLifetimeSeekBar.progress = p.lifetimeMs.toInt()
            particleSpeedSeekBar.progress = (p.speed * 100).toInt()
        }
        theme.keyGlowEffect?.let { g ->
            glowRadiusSeekBar.progress = g.glowRadius.toInt()
        }

        updateControlsVisibility()
        refreshContrastRow()
        updateMoreVisibility()

        onBackPressedDispatcher.addCallback {
            cancel()
        }
    }

    private fun BackgroundStates.launchCrop(w: Int, h: Int) {
        val srcFile = srcImageFile
        if (srcFile != null && srcFile.exists()) {
            launcher.launch(
                CropOption.Edit(
                    width = w,
                    height = h,
                    Uri.fromFile(srcFile),
                    initialRect = cropRect,
                    initialRotation = cropRotation
                )
            )
        } else {
            launcher.launch(CropOption.New(w, h))
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateBackgroundState() {
        val progress = brightnessSeekBar.progress
        brightnessValue.text = "$progress%"
        backgroundStates.filteredDrawable?.colorFilter = DarkenColorFilter(100 - progress)
        updatePreview()
    }

    private fun cancel() {
        setResult(
            RESULT_CANCELED,
            Intent().apply { putExtra(RESULT, null as BackgroundResult?) }
        )
        finish()
    }

    private fun done() {
        lifecycleScope.withLoadingDialog(this) {
            whenHasBackground {
                withContext(Dispatchers.IO) {
                    val cropFile = croppedImageFile
                    val cropBmp = croppedBitmap
                    if (cropFile != null && cropBmp != null) {
                        cropFile.delete()
                        cropFile.outputStream().use {
                            cropBmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                    val srcFile = srcImageFile
                    val srcBuf = srcImageBuffer
                    if (srcFile != null && srcBuf != null) {
                        if (srcImageExtension != null) {
                            backgroundStates.srcImageFile = File("${srcFile.absolutePath}.$srcImageExtension")
                            theme = theme.copy(
                                backgroundImage = it.copy(
                                    srcFilePath = backgroundStates.srcImageFile!!.absolutePath
                                )
                            )
                        }
                        backgroundStates.srcImageFile!!.writeBytes(srcBuf)
                    }
                }
            }
            setResult(
                RESULT_OK,
                Intent().apply {
                    var newTheme = theme
                    whenHasBackground {
                        newTheme = theme.copy(
                            backgroundImage = it.copy(
                                brightness = brightnessSeekBar.progress,
                                cropRect = cropRect,
                                cropRotation = cropRotation
                            )
                        )
                    }
                    putExtra(
                        RESULT,
                        if (newCreated)
                            BackgroundResult.Created(newTheme)
                        else
                            BackgroundResult.Updated(newTheme)
                    )
                })
            finish()
        }
    }

    private fun delete() {
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(RESULT, BackgroundResult.Deleted(theme.name))
            }
        )
        finish()
    }

    private fun promptDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_theme)
            .setMessage(getString(R.string.delete_theme_msg, ThemeDisplayNames.displayName(this, theme)))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                delete()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (!newCreated) {
            val iconTint = color(R.color.red_400)
            menu.item(R.string.save, R.drawable.ic_baseline_delete_24, iconTint, true) {
                promptDelete()
            }
        }
        val iconTint = styledColor(android.R.attr.colorControlNormal)
        menu.item(R.string.save, R.drawable.ic_baseline_check_24, iconTint, true) {
            done()
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> {
            cancel()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        const val RESULT = "result"
        const val ORIGIN_THEME = "origin_theme"
    }
}
