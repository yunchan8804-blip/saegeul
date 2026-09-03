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
import android.widget.HorizontalScrollView
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
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
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
import splitties.views.dsl.appcompat.switch
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.packed
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.constraintlayout.topToTopOf
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
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

    private val variantLabel by lazy {
        createTextView(R.string.dark_keys, ripple = true)
    }
    private val variantSwitch by lazy {
        switch {
            isChecked = false
        }
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

    private val cropLabel by lazy {
        createTextView(R.string.recrop_image, ripple = true)
    }

    private val changeImageLabel by lazy {
        createTextView(R.string.theme_choose_image, ripple = true)
    }

    private val removeImageLabel by lazy {
        createTextView(R.string.theme_remove_image, ripple = true)
    }

    private val palettePresetContainer by lazy {
        HorizontalScrollView(this).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
        }
    }

    private val accentColorContainer by lazy {
        HorizontalScrollView(this).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
        }
    }

    private val surfaceColorContainer by lazy {
        HorizontalScrollView(this).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
        }
    }

    private val rgbModeContainer by lazy {
        HorizontalScrollView(this).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
        }
    }

    private val particleModeContainer by lazy {
        HorizontalScrollView(this).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
        }
    }

    private val glowColorContainer by lazy {
        HorizontalScrollView(this).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
        }
    }

    private val perKeyTargetContainer by lazy {
        HorizontalScrollView(this).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
        }
    }

    private val perKeyColorContainer by lazy {
        HorizontalScrollView(this).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
        }
    }

    private var selectedKeyTarget = "ALL"

    private val editorContainer by lazy {
        verticalLayout {
            setPadding(dp(12), dp(8), dp(12), dp(32))

            // 1. Preset Palettes Section
            add(createSectionHeader(getString(R.string.theme_style_palette)), lParams(matchParent, wrapContent))
            add(palettePresetContainer, lParams(matchParent, wrapContent) {
                bottomMargin = dp(8)
            })

            // 2. RGB Chroma Backlight Section
            add(createSectionHeader(getString(R.string.theme_rgb_backlight)), lParams(matchParent, wrapContent))
            add(rgbModeContainer, lParams(matchParent, wrapContent) {
                bottomMargin = dp(8)
            })

            // 3. Touch Particle & Sparkle Section
            add(createSectionHeader(getString(R.string.theme_particle_effect)), lParams(matchParent, wrapContent))
            add(particleModeContainer, lParams(matchParent, wrapContent) {
                bottomMargin = dp(8)
            })

            // 4. Keycap Glow & Aura Section
            add(createSectionHeader(getString(R.string.theme_key_glow_effect)), lParams(matchParent, wrapContent))
            add(glowColorContainer, lParams(matchParent, wrapContent) {
                bottomMargin = dp(8)
            })

            // 5. Per-Key Custom Studio Section
            add(createSectionHeader(getString(R.string.theme_per_key_customization)), lParams(matchParent, wrapContent))
            add(perKeyTargetContainer, lParams(matchParent, wrapContent) {
                bottomMargin = dp(4)
            })
            add(perKeyColorContainer, lParams(matchParent, wrapContent) {
                bottomMargin = dp(8)
            })

            // 6. Accent / Enter Color Swatches
            add(createSectionHeader(getString(R.string.theme_accent_key_color)), lParams(matchParent, wrapContent))
            add(accentColorContainer, lParams(matchParent, wrapContent) {
                bottomMargin = dp(8)
            })

            // 7. Keyboard Surface Color Swatches
            add(createSectionHeader(getString(R.string.theme_keyboard_bg_color)), lParams(matchParent, wrapContent))
            add(surfaceColorContainer, lParams(matchParent, wrapContent) {
                bottomMargin = dp(8)
            })

            // 8. Keycap & Style Options
            add(createSectionHeader(getString(R.string.theme_color_customization)), lParams(matchParent, wrapContent))
            val variantRow = horizontalLayout {
                gravity = Gravity.CENTER_VERTICAL
                add(variantLabel, lParams(0, dp(48)) {
                    weight = 1f
                })
                add(variantSwitch, lParams(wrapContent, wrapContent) {
                    rightMargin = dp(16)
                })
            }
            add(variantRow, lParams(matchParent, wrapContent))

            // 9. Background Image Section
            add(createSectionHeader(getString(R.string.theme_background_image)), lParams(matchParent, wrapContent))
            add(changeImageLabel, lParams(matchParent, dp(44)))
            add(cropLabel, lParams(matchParent, dp(44)))
            add(removeImageLabel, lParams(matchParent, dp(44)))

            val brightnessRow = horizontalLayout {
                gravity = Gravity.CENTER_VERTICAL
                add(brightnessLabel, lParams(0, dp(40)) {
                    weight = 1f
                })
                add(brightnessValue, lParams(wrapContent, dp(40)) {
                    rightMargin = dp(16)
                })
            }
            add(brightnessRow, lParams(matchParent, wrapContent))
            add(brightnessSeekBar, lParams(matchParent, wrapContent) {
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(16)
            })
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
    }

    private fun updateControlsVisibility() {
        val hasBg = theme.backgroundImage != null
        cropLabel.visibility = if (hasBg) View.VISIBLE else View.GONE
        removeImageLabel.visibility = if (hasBg) View.VISIBLE else View.GONE
        brightnessLabel.visibility = if (hasBg) View.VISIBLE else View.GONE
        brightnessValue.visibility = if (hasBg) View.VISIBLE else View.GONE
        brightnessSeekBar.visibility = if (hasBg) View.VISIBLE else View.GONE
    }

    private fun applyPreset(preset: Theme.Builtin) {
        val currentBg = theme.backgroundImage
        val custom = if (currentBg != null) {
            preset.deriveCustomBackground(
                name = theme.name,
                croppedBackgroundImage = currentBg.croppedFilePath,
                originBackgroundImage = currentBg.srcFilePath,
                brightness = brightnessSeekBar.progress,
                cropBackgroundRect = currentBg.cropRect,
                cropBackgroundRotation = currentBg.cropRotation
            )
        } else {
            preset.deriveCustomNoBackground(theme.name)
        }
        theme = custom
        variantSwitch.isChecked = !preset.isDark
        updatePreview()
    }

    private fun applyAccentColor(accentColor: Int, accentTextColor: Int = 0xffffffff.toInt()) {
        theme = theme.copy(
            accentKeyBackgroundColor = accentColor,
            accentKeyTextColor = accentTextColor,
            genericActiveBackgroundColor = accentColor,
            genericActiveForegroundColor = accentTextColor
        )
        updatePreview()
    }

    private fun applySurfaceColor(surfaceColor: Int, barColor: Int? = null) {
        val actualBar = barColor ?: surfaceColor
        theme = theme.copy(
            backgroundColor = surfaceColor,
            keyboardColor = surfaceColor,
            barColor = actualBar,
            popupBackgroundColor = actualBar
        )
        updatePreview()
    }

    private fun setKeyVariant(darkKeys: Boolean) {
        val template = if (darkKeys) ThemePreset.TransparentLight else ThemePreset.TransparentDark
        val bg = theme.backgroundImage
        theme = if (bg != null) {
            template.deriveCustomBackground(
                theme.name,
                bg.croppedFilePath,
                bg.srcFilePath,
                brightnessSeekBar.progress,
                bg.cropRect,
                bg.cropRotation
            )
        } else {
            template.deriveCustomNoBackground(theme.name)
        }
        updatePreview()
    }

    private fun setupPresetPaletteRow() {
        val presets = listOf(
            "한지" to ThemePreset.HanjiLight,
            "단청" to ThemePreset.DancheongDark,
            "백자" to ThemePreset.BaegjaLight,
            "청자" to ThemePreset.CheongjaDark,
            "자정 OLED" to ThemePreset.MidnightOLED,
            "안개 Glass" to ThemePreset.SeoulMistGlass,
            "픽셀 다크" to ThemePreset.PixelDark,
            "픽셀 라이트" to ThemePreset.PixelLight,
            "머티리얼 다크" to ThemePreset.MaterialDark,
            "머티리얼 라이트" to ThemePreset.MaterialLight,
            "노르딕 다크" to ThemePreset.NordDark,
            "노르딕 라이트" to ThemePreset.NordLight,
            "모노카이" to ThemePreset.Monokai,
            "딥블루" to ThemePreset.DeepBlue
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }

        presets.forEach { (name, preset) ->
            val pill = TextView(this).apply {
                text = name
                textSize = 12f
                paint.isFakeBoldText = true
                setTextColor(if (preset.isDark) Color.WHITE else Color.BLACK)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))

                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(20f)
                    setColor(preset.accentKeyBackgroundColor)
                    setStroke(dp(1), if (preset.isDark) Color.argb(60, 255, 255, 255) else Color.argb(40, 0, 0, 0))
                }
                background = RippleDrawable(ColorStateList.valueOf(Color.argb(50, 255, 255, 255)), shape, null)

                setOnClickListener {
                    applyPreset(preset)
                }
            }

            row.addView(pill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            })
        }

        palettePresetContainer.addView(row)
    }

    private fun setupAccentColorRow() {
        val accentColors = listOf(
            0xffb83a32.toInt() to 0xffffffff.toInt(), // 한지 적갈색
            0xffc84a3f.toInt() to 0xffffffff.toInt(), // 단청 주홍
            0xff1e40af.toInt() to 0xffffffff.toInt(), // 백자 코발트
            0xffc49a45.toInt() to 0xff1a1a1a.toInt(), // 청자 금색
            0xff00e699.toInt() to 0xff000000.toInt(), // 자정 네온 제이드
            0xff38bdf8.toInt() to 0xff0f172a.toInt(), // 안개 스카이
            0xff2563eb.toInt() to 0xffffffff.toInt(), // 로얄 블루
            0xff10b981.toInt() to 0xffffffff.toInt(), // 에메랄드
            0xffec4899.toInt() to 0xffffffff.toInt(), // 핑크 로즈
            0xff8b5cf6.toInt() to 0xffffffff.toInt(), // 바이올렛
            0xfff59e0b.toInt() to 0xff000000.toInt(), // 앰버 옐로우
            0xffffffff.toInt() to 0xff000000.toInt(), // 퓨어 화이트
            0xff212121.toInt() to 0xffffffff.toInt()  // 퓨어 블랙
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }

        accentColors.forEach { (bg, fg) ->
            val circle = View(this).apply {
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(bg)
                    setStroke(dp(2), Color.argb(80, 255, 255, 255))
                }
                background = shape
                setOnClickListener {
                    applyAccentColor(bg, fg)
                }
            }

            row.addView(circle, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                rightMargin = dp(10)
            })
        }

        accentColorContainer.addView(row)
    }

    private fun setupSurfaceColorRow() {
        val surfaces = listOf(
            0xfff5f6f8.toInt() to 0xffeef0f3.toInt(), // 백자 오프화이트
            0xffe9e1d2.toInt() to 0xfff3eddf.toInt(), // 한지 미색
            0xffffffff.toInt() to 0xffeeeeee.toInt(), // 퓨어 화이트
            0xff101918.toInt() to 0xff0b1211.toInt(), // 단청 묵색
            0xff0f1e1b.toInt() to 0xff0a1614.toInt(), // 청자 비색
            0xff000000.toInt() to 0xff080808.toInt(), // 자정 OLED
            0xff182230.toInt() to 0xff0f1722.toInt(), // 안개 슬레이트
            0xff2d2d2d.toInt() to 0xff373737.toInt(), // 픽셀 다크
            0xff263238.toInt() to 0xff21272b.toInt(), // 머티리얼 다크
            0xff2e3440.toInt() to 0xff434c5e.toInt()  // 노르딕 다크
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }

        surfaces.forEach { (surf, bar) ->
            val rect = View(this).apply {
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8f)
                    setColor(surf)
                    setStroke(dp(1), Color.argb(60, 255, 255, 255))
                }
                background = shape
                setOnClickListener {
                    applySurfaceColor(surf, bar)
                }
            }

            row.addView(rect, LinearLayout.LayoutParams(dp(44), dp(36)).apply {
                rightMargin = dp(10)
            })
        }

        surfaceColorContainer.addView(row)
    }

    private fun setupRgbModeRow() {
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
            getString(R.string.theme_rgb_mode_frost) to "frost_crystal",
            getString(R.string.theme_rgb_mode_reactive_ripple) to "reactive_ripple",
            getString(R.string.theme_rgb_mode_reactive_fade) to "reactive_fade",
            getString(R.string.theme_rgb_mode_reactive_firework) to "reactive_firework",
            getString(R.string.theme_rgb_mode_reactive_laser) to "reactive_laser"
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }

        modes.forEach { (label, modeKey) ->
            val pill = TextView(this).apply {
                text = label
                textSize = 12f
                paint.isFakeBoldText = true
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))

                val isCurrent = (theme.lightingEffect?.mode ?: "off") == modeKey
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(20f)
                    setColor(if (isCurrent) Color.parseColor("#7C3AED") else Color.parseColor("#27272A"))
                    setStroke(dp(1), if (isCurrent) Color.parseColor("#A78BFA") else Color.parseColor("#3F3F46"))
                }
                background = RippleDrawable(ColorStateList.valueOf(Color.argb(50, 255, 255, 255)), shape, null)

                setOnClickListener {
                    val defaultSpeed = when (modeKey) {
                        "neon_pulse" -> 1.5f
                        "matrix_flow" -> 1.2f
                        "starlight" -> 1.0f
                        "rgb_breathe" -> 0.85f
                        else -> 1.0f
                    }
                    theme = theme.copy(
                        lightingEffect = Theme.Custom.LightingEffectDef(
                            mode = modeKey,
                            speed = defaultSpeed,
                            intensity = 0.85f
                        )
                    )
                    updatePreview()
                    setupRgbModeRow()
                }
            }

            row.addView(pill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            })
        }

        rgbModeContainer.removeAllViews()
        rgbModeContainer.addView(row)
    }

    private fun setupParticleModeRow() {
        val particles = listOf(
            getString(R.string.theme_particle_mode_off) to "off",
            getString(R.string.theme_particle_mode_star) to "star_sparkle",
            getString(R.string.theme_particle_mode_dust) to "glowing_dust",
            getString(R.string.theme_particle_mode_burst) to "neon_burst",
            getString(R.string.theme_particle_mode_ripple) to "cosmic_ripple"
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }

        particles.forEach { (label, typeKey) ->
            val pill = TextView(this).apply {
                text = label
                textSize = 12f
                paint.isFakeBoldText = true
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))

                val isCurrent = (theme.particleEffect?.type ?: "off") == typeKey
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(20f)
                    setColor(if (isCurrent) Color.parseColor("#0EA5E9") else Color.parseColor("#27272A"))
                    setStroke(dp(1), if (isCurrent) Color.parseColor("#38BDF8") else Color.parseColor("#3F3F46"))
                }
                background = RippleDrawable(ColorStateList.valueOf(Color.argb(50, 255, 255, 255)), shape, null)

                setOnClickListener {
                    theme = theme.copy(
                        particleEffect = Theme.Custom.ParticleEffectDef(
                            type = typeKey,
                            particleCount = if (typeKey == "cosmic_ripple") 6 else 10,
                            lifetimeMs = 500L
                        )
                    )
                    updatePreview()
                    setupParticleModeRow()
                }
            }

            row.addView(pill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            })
        }

        particleModeContainer.removeAllViews()
        particleModeContainer.addView(row)
    }

    private fun setupGlowColorRow() {
        val glowColors = listOf(
            0 to "Off",
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
                val isOff = colorVal == 0
                val isSelected = if (isOff) (theme.keyGlowEffect?.enabled != true) else (theme.keyGlowEffect?.enabled == true && theme.keyGlowEffect?.glowColor == colorVal)
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (isOff) Color.parseColor("#27272A") else colorVal)
                    setStroke(dp(if (isSelected) 3 else 1), if (isSelected) Color.WHITE else Color.argb(80, 255, 255, 255))
                }
                background = shape
                setOnClickListener {
                    theme = if (isOff) {
                        theme.copy(keyGlowEffect = null)
                    } else {
                        theme.copy(keyGlowEffect = Theme.Custom.KeyGlowDef(enabled = true, glowColor = colorVal, glowRadius = 4f))
                    }
                    updatePreview()
                    setupGlowColorRow()
                }
            }

            row.addView(circle, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                rightMargin = dp(10)
            })
        }

        glowColorContainer.removeAllViews()
        glowColorContainer.addView(row)
    }

    private fun setupPerKeyRow() {
        val targets = listOf(
            getString(R.string.theme_target_all_keys) to "ALL",
            getString(R.string.theme_target_space) to "button_space",
            getString(R.string.theme_target_return) to "button_return",
            getString(R.string.theme_target_backspace) to "button_backspace",
            getString(R.string.theme_target_shift) to "button_shift"
        )

        val targetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }

        targets.forEach { (name, targetKey) ->
            val pill = TextView(this).apply {
                text = name
                textSize = 12f
                paint.isFakeBoldText = true
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), dp(6))

                val isSelected = selectedKeyTarget == targetKey
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16f)
                    setColor(if (isSelected) Color.parseColor("#E11D48") else Color.parseColor("#27272A"))
                    setStroke(dp(1), if (isSelected) Color.parseColor("#FB7185") else Color.parseColor("#3F3F46"))
                }
                background = RippleDrawable(ColorStateList.valueOf(Color.argb(50, 255, 255, 255)), shape, null)

                setOnClickListener {
                    selectedKeyTarget = targetKey
                    setupPerKeyRow()
                }
            }

            targetRow.addView(pill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            })
        }

        perKeyTargetContainer.removeAllViews()
        perKeyTargetContainer.addView(targetRow)

        val keyColors = listOf(
            null, // Reset / Default
            0xFF2D2D2D.toInt(),
            0xFF1E293B.toInt(),
            0xFFE11D48.toInt(),
            0xFF0EA5E9.toInt(),
            0xFF10B981.toInt(),
            0xFFF59E0B.toInt(),
            0xFF8B5CF6.toInt(),
            0xFFF8FAFC.toInt()
        )

        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }

        keyColors.forEach { col ->
            val circle = View(this).apply {
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(6f)
                    setColor(col ?: Color.TRANSPARENT)
                    setStroke(dp(if (col == null) 2 else 1), if (col == null) Color.RED else Color.argb(80, 255, 255, 255))
                }
                background = shape
                setOnClickListener {
                    if (selectedKeyTarget == "ALL") {
                        val currentGlobal = theme.globalKeyStyle ?: Theme.Custom.KeyCustomStyle()
                        theme = theme.copy(
                            globalKeyStyle = if (col == null) null else currentGlobal.copy(keyBackgroundColor = col)
                        )
                    } else {
                        val overrides = (theme.keyOverrides ?: emptyMap()).toMutableMap()
                        if (col == null) {
                            overrides.remove(selectedKeyTarget)
                        } else {
                            val current = overrides[selectedKeyTarget] ?: Theme.Custom.KeyCustomStyle()
                            overrides[selectedKeyTarget] = current.copy(keyBackgroundColor = col)
                        }
                        theme = theme.copy(keyOverrides = if (overrides.isEmpty()) null else overrides)
                    }
                    updatePreview()
                }
            }

            colorRow.addView(circle, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                rightMargin = dp(10)
            })
        }

        perKeyColorContainer.removeAllViews()
        perKeyColorContainer.addView(colorRow)
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
            // Use HanjiLight as starting template for brand-new custom theme
            theme = ThemePreset.HanjiLight.deriveCustomNoBackground(n)
        }
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
        // show Activity label on toolbar
        setSupportActionBar(toolbar)
        // show back button
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        setContentView(ui)

        // Setup preset rows
        setupPresetPaletteRow()
        setupRgbModeRow()
        setupParticleModeRow()
        setupGlowColorRow()
        setupPerKeyRow()
        setupAccentColorRow()
        setupSurfaceColorRow()

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
        }

        variantLabel.setOnClickListener {
            variantSwitch.isChecked = !variantSwitch.isChecked
        }
        variantSwitch.setOnCheckedChangeListener { _, isChecked ->
            setKeyVariant(darkKeys = isChecked)
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
            variantSwitch.isChecked = !theme.isDark
            updateBackgroundState()
        }

        updateControlsVisibility()

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
            .setMessage(getString(R.string.delete_theme_msg, theme.name))
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
