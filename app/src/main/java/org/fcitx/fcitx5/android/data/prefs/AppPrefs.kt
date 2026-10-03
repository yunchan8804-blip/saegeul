/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fcitx.fcitx5.android.input.buffered.BufferedInputTransport
import org.fcitx.fcitx5.android.input.KeyTextScale
import org.fcitx.fcitx5.android.input.OneHandMode
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesOrientation
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode
import org.fcitx.fcitx5.android.input.keyboard.LangSwitchBehavior
import org.fcitx.fcitx5.android.input.keyboard.MobileHangulLayout
import org.fcitx.fcitx5.android.input.keyboard.NumberKeyboard
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.keyboard.SwipeSymbolDirection
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.EmojiModifier
import org.fcitx.fcitx5.android.utils.DeviceUtil
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.vibrator

class AppPrefs(private val sharedPreferences: SharedPreferences) {

    inner class Internal : ManagedPreferenceInternal(sharedPreferences) {
        val firstRun = bool("first_run", true)
        val lastSymbolLayout = string("last_symbol_layout", NumberKeyboard.Name)
        val lastPickerType = string("last_picker_type", PickerWindow.Key.Emoji.name)
        val verboseLog = bool("verbose_log", false)
        val pid = int("pid", 0)
        val editorInfoInspector = bool("editor_info_inspector", false)
        val needNotifications = bool("need_notifications", true)
        val automaticOnDeviceSuggestionsOptIn =
            bool("automatic_ondevice_suggestions_opt_in", true)
        val automaticOnDeviceSuggestionsUseGpu =
            bool("automatic_ondevice_suggestions_use_gpu", true)
        val backgroundProgressNotifications =
            bool("background_progress_notifications", true)
        val collectionFeedbackInKeyboard =
            bool("collection_feedback_in_keyboard", true)
        /** Whether the "새글 AI 모델(Google Gemma)을 Hugging Face에서 받아요" consent notice has been shown once (onboarding or the install dialog); [org.fcitx.fcitx5.android.ui.main.ai.install.GemmaInstallFlow] skips it after. */
        val gemmaInstallConsentShown =
            bool("gemma_install_consent_shown", false)
        /** K7 (design.md, 사용자 승인 2026-09-26): 펼친 화면 분할 키보드 안내를 어느 버튼으로든 한 번 닫으면 다시 보이지 않는다. */
        val splitExpandedPromptDone =
            bool("split_expanded_prompt_done", false)
        /** Side the toolbar restores when it turns one-hand mode back on; see [org.fcitx.fcitx5.android.input.bar.OneHandModeTogglePolicy]. */
        val lastOneHandModeSide = stringLike(
            "last_one_hand_mode_side",
            object : ManagedPreference.StringLikeCodec<OneHandMode> {
                override fun decode(raw: String): OneHandMode = enumValueOf(raw)
            },
            OneHandMode.Right
        )
    }

    inner class Advanced : ManagedPreferenceCategory(R.string.advanced, sharedPreferences) {
        val ignoreSystemCursor = switch(R.string.ignore_sys_cursor, "ignore_system_cursor", false)
        val offlineMode = switch(
            R.string.offline_mode,
            "offline_mode",
            false,
            R.string.offline_mode_summary
        )
        val autoSnippetExpansion = switch(
            R.string.auto_snippet_expansion,
            "auto_snippet_expansion",
            true,
            R.string.auto_snippet_expansion_summary
        )
        val bufferedHangulInput = switch(
            R.string.buffered_hangul_input,
            "buffered_hangul_input",
            false,
            R.string.buffered_hangul_input_summary
        )
        val bufferedHangulTransport = enumList(
            R.string.buffered_input_transport,
            "buffered_hangul_transport",
            BufferedInputTransport.SystemPaste
        ) { bufferedHangulInput.getValue() }
        val hideKeyConfig = switch(R.string.hide_key_config, "hide_key_config", true)
        val disableAnimation = switch(R.string.disable_animation, "disable_animation", false)
        val vivoKeypressWorkaround = switch(
            R.string.vivo_keypress_workaround,
            "vivo_keypress_workaround",
            // there's some feedback that this workaround is no longer necessary on Origin OS 4, which based on Android 14
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && DeviceUtil.isVivoOriginOS
        )
        val ignoreSystemWindowInsets = switch(
            R.string.ignore_system_window_insets, "ignore_system_window_insets", false
        )
    }

    inner class Keyboard : ManagedPreferenceCategory(R.string.virtual_keyboard, sharedPreferences) {

        // 1. 자판·크기
        init {
            header(R.string.settings_header_layout_size)
        }

        val mobileHangulLayout = enumList(
            R.string.mobile_hangul_layout,
            "mobile_hangul_layout",
            MobileHangulLayout.Physical
        )
        val showNumberRow = switch(
            R.string.show_number_row,
            "show_number_row",
            false,
            R.string.show_number_row_summary
        )

        val keyboardHeightPercent: ManagedPreference.PInt
        val keyboardHeightPercentLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                TwinIntPreferenceSpec(
                    title = R.string.keyboard_height,
                    primary = TwinIntSide(R.string.portrait, "keyboard_height_percent", 30),
                    secondary = TwinIntSide(R.string.landscape, "keyboard_height_percent_landscape", 49),
                    min = 10,
                    max = 90,
                    unit = "%"
                )
            )
            keyboardHeightPercent = primary
            keyboardHeightPercentLandscape = secondary
        }

        val keyboardSidePadding: ManagedPreference.PInt
        val keyboardSidePaddingLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                TwinIntPreferenceSpec(
                    title = R.string.keyboard_side_padding,
                    primary = TwinIntSide(R.string.portrait, "keyboard_side_padding", 0),
                    secondary = TwinIntSide(R.string.landscape, "keyboard_side_padding_landscape", 0),
                    min = 0,
                    max = 300,
                    unit = "dp"
                )
            )
            keyboardSidePadding = primary
            keyboardSidePaddingLandscape = secondary
        }

        val keyboardBottomPadding: ManagedPreference.PInt
        val keyboardBottomPaddingLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                TwinIntPreferenceSpec(
                    title = R.string.keyboard_bottom_padding,
                    primary = TwinIntSide(R.string.portrait, "keyboard_bottom_padding", 0),
                    secondary = TwinIntSide(R.string.landscape, "keyboard_bottom_padding_landscape", 0),
                    min = 0,
                    max = 100,
                    unit = "dp"
                )
            )
            keyboardBottomPadding = primary
            keyboardBottomPaddingLandscape = secondary
        }

        val oneHandMode = enumList(
            R.string.one_hand_mode,
            "one_hand_mode",
            OneHandMode.Off
        )

        val keyTextScale = int(
            IntPreferenceSpec(
                title = R.string.key_text_scale,
                key = "key_text_scale",
                defaultValue = KeyTextScale.DEFAULT_PERCENT,
                min = KeyTextScale.MIN_PERCENT,
                max = KeyTextScale.MAX_PERCENT,
                unit = "%",
                step = KeyTextScale.STEP_PERCENT
            )
        )

        val splitKeyboardCompact = switch(
            R.string.split_keyboard_compact,
            "split_keyboard_compact",
            false,
            R.string.split_keyboard_compact_summary
        )
        val splitKeyboardExpanded = switch(
            R.string.split_keyboard_expanded,
            "split_keyboard_expanded",
            true,
            R.string.split_keyboard_expanded_summary
        )

        val splitKeyboardCompactGapPortrait: ManagedPreference.PInt
        val splitKeyboardCompactGapLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                TwinIntPreferenceSpec(
                    title = R.string.split_keyboard_compact_gap,
                    primary = TwinIntSide(R.string.portrait, "split_keyboard_compact_gap_portrait", 48),
                    secondary = TwinIntSide(R.string.landscape, "split_keyboard_compact_gap_landscape", 72),
                    min = 24,
                    max = 180,
                    unit = "dp",
                    step = 4
                )
            ) { splitKeyboardCompact.getValue() }
            splitKeyboardCompactGapPortrait = primary
            splitKeyboardCompactGapLandscape = secondary
        }

        val splitKeyboardExpandedGapPortrait: ManagedPreference.PInt
        val splitKeyboardExpandedGapLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                TwinIntPreferenceSpec(
                    title = R.string.split_keyboard_expanded_gap,
                    primary = TwinIntSide(R.string.portrait, "split_keyboard_expanded_gap_portrait", 96),
                    secondary = TwinIntSide(R.string.landscape, "split_keyboard_expanded_gap_landscape", 128),
                    min = 32,
                    max = 240,
                    unit = "dp",
                    step = 4
                )
            ) { splitKeyboardExpanded.getValue() }
            splitKeyboardExpandedGapPortrait = primary
            splitKeyboardExpandedGapLandscape = secondary
        }

        // 2. 누르기·제스처
        init {
            header(R.string.settings_header_press_gestures)
        }

        val popupOnKeyPress = switch(R.string.popup_on_key_press, "popup_on_key_press", true)
        val longPressDelay = int(
            IntPreferenceSpec(
                title = R.string.keyboard_long_press_delay,
                key = "keyboard_long_press_delay",
                defaultValue = 300,
                min = 100,
                max = 700,
                unit = "ms",
                step = 10
            )
        )
        val swipeSymbolDirection = enumList(
            R.string.swipe_symbol_behavior,
            "swipe_symbol_behavior",
            SwipeSymbolDirection.Down
        )
        val spaceKeyLongPressBehavior = enumList(
            R.string.space_long_press_behavior,
            "space_long_press_behavior",
            SpaceLongPressBehavior.None
        )
        val spaceSwipeMoveCursor =
            switch(R.string.space_swipe_move_cursor, "space_swipe_move_cursor", true)
        val showLangSwitchKey =
            switch(R.string.show_lang_switch_key, "show_lang_switch_key", true)
        val langSwitchKeyBehavior = enumList(
            R.string.lang_switch_key_behavior,
            "lang_switch_key_behavior",
            LangSwitchBehavior.Enumerate
        ) { showLangSwitchKey.getValue() }
        val expandKeypressArea =
            switch(R.string.expand_keypress_area, "expand_keypress_area", false)
        val keepLettersUppercase = switch(
            R.string.keep_keyboard_letters_uppercase,
            "keep_keyboard_letters_uppercase",
            false
        )

        // 3. 진동·소리
        init {
            header(R.string.settings_header_vibration_sound)
        }

        val hapticOnKeyPress =
            enumList(
                R.string.button_haptic_feedback,
                "haptic_on_keypress",
                InputFeedbackMode.FollowingSystem
            )
        val hapticOnKeyUp = switch(
            R.string.button_up_haptic_feedback,
            "haptic_on_keyup",
            false
        ) { hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled }
        val hapticOnRepeat = switch(R.string.haptic_on_repeat, "haptic_on_repeat", false)

        val buttonPressVibrationMilliseconds: ManagedPreference.PInt
        val buttonLongPressVibrationMilliseconds: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                TwinIntPreferenceSpec(
                    title = R.string.button_vibration_milliseconds,
                    primary = TwinIntSide(R.string.button_press, "button_vibration_press_milliseconds", 0),
                    secondary = TwinIntSide(R.string.button_long_press, "button_vibration_long_press_milliseconds", 0),
                    min = 0,
                    max = 100,
                    unit = "ms",
                    defaultLabel = R.string.system_default
                )
            ) { hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled }
            buttonPressVibrationMilliseconds = primary
            buttonLongPressVibrationMilliseconds = secondary
        }

        val buttonPressVibrationAmplitude: ManagedPreference.PInt
        val buttonLongPressVibrationAmplitude: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                TwinIntPreferenceSpec(
                    title = R.string.button_vibration_amplitude,
                    primary = TwinIntSide(R.string.button_press, "button_vibration_press_amplitude", 0),
                    secondary = TwinIntSide(R.string.button_long_press, "button_vibration_long_press_amplitude", 0),
                    min = 0,
                    max = 255,
                    defaultLabel = R.string.system_default
                )
            ) {
                (hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled)
                        // hide this if using default duration
                        && (buttonPressVibrationMilliseconds.getValue() != 0 || buttonLongPressVibrationMilliseconds.getValue() != 0)
                        && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appContext.vibrator.hasAmplitudeControl())
            }
            buttonPressVibrationAmplitude = primary
            buttonLongPressVibrationAmplitude = secondary
        }

        val soundOnKeyPress = enumList(
            R.string.button_sound,
            "sound_on_keypress",
            InputFeedbackMode.FollowingSystem
        )
        val soundOnKeyPressVolume = int(
            IntPreferenceSpec(
                title = R.string.button_sound_volume,
                key = "button_sound_volume",
                defaultValue = 0,
                min = 0,
                max = 100,
                unit = "%",
                defaultLabel = R.string.system_default
            )
        ) {
            soundOnKeyPress.getValue() != InputFeedbackMode.Disabled
        }

        // 4. 툴바·입력칸 (나머지 전부)
        init {
            header(R.string.settings_header_toolbar_fields)
        }

        val focusChangeResetKeyboard =
            switch(R.string.reset_keyboard_on_focus_change, "reset_keyboard_on_focus_change", true)
        val expandToolbarByDefault =
            switch(R.string.expand_toolbar_by_default, "expand_toolbar_by_default", false)
        val inlineSuggestions = switch(R.string.inline_suggestions, "inline_suggestions", true)
        // Redundant while the keyboard already pins a number row of its own.
        val toolbarNumRowOnPassword = switch(
            R.string.toolbar_num_row_on_password,
            "toolbar_num_row_on_password",
            true
        ) { !showNumberRow.getValue() }

        val showVoiceInputButton = switch(
            R.string.show_voice_input_button,
            "show_voice_input_button",
            true,
            R.string.show_voice_input_button_summary
        )
        val preferredVoiceInput = voiceInputPreference(
            R.string.preferred_voice_input, "preferred_voice_input", ""
        ) { showVoiceInputButton.getValue() }

        val horizontalCandidateStyle = enumList(
            R.string.horizontal_candidate_style,
            "horizontal_candidate_style",
            HorizontalCandidateMode.AutoFillWidth
        )
        val twoRowCandidateBar get() = candidates.twoRowCandidateBar
        val expandedCandidateStyle = enumList(
            R.string.expanded_candidate_style,
            "expanded_candidate_style",
            ExpandedCandidateStyle.Grid
        )

        val expandedCandidateGridSpanCount: ManagedPreference.PInt
        val expandedCandidateGridSpanCountLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                TwinIntPreferenceSpec(
                    title = R.string.expanded_candidate_grid_span_count,
                    primary = TwinIntSide(R.string.portrait, "expanded_candidate_grid_span_count_portrait", 6),
                    secondary = TwinIntSide(R.string.landscape, "expanded_candidate_grid_span_count_landscape", 8),
                    min = 4,
                    max = 12
                )
            )
            expandedCandidateGridSpanCount = primary
            expandedCandidateGridSpanCountLandscape = secondary
        }

    }

    inner class Candidates :
        ManagedPreferenceCategory(R.string.candidates_window, sharedPreferences) {
        val mode = enumList(
            R.string.show_candidates_window,
            "show_candidates_window",
            FloatingCandidatesMode.InputDevice
        )

        val twoRowCandidateBar = switch(
            R.string.two_row_candidate_bar,
            "two_row_candidate_bar",
            true,
            R.string.two_row_candidate_bar_summary
        )

        val orientation = enumList(
            R.string.candidates_orientation,
            "candidates_window_orientation",
            FloatingCandidatesOrientation.Automatic
        )

        val windowMinWidth = int(
            IntPreferenceSpec(
                title = R.string.candidates_window_min_width,
                key = "candidates_window_min_width",
                defaultValue = 0,
                min = 0,
                max = 640,
                unit = "dp",
                step = 10
            )
        )

        val windowPadding = int(
            IntPreferenceSpec(
                title = R.string.candidates_window_padding,
                key = "candidates_window_padding",
                defaultValue = 4,
                min = 0,
                max = 32,
                unit = "dp"
            )
        )

        val fontSize = int(
            IntPreferenceSpec(
                title = R.string.candidates_font_size,
                key = "candidates_window_font_size",
                defaultValue = 20,
                min = 4,
                max = 64,
                unit = "sp"
            )
        )

        val windowRadius = int(
            IntPreferenceSpec(
                title = R.string.candidates_window_radius,
                key = "candidates_window_radius",
                defaultValue = 0,
                min = 0,
                max = 48,
                unit = "dp"
            )
        )

        val itemPaddingVertical: ManagedPreference.PInt
        val itemPaddingHorizontal: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                TwinIntPreferenceSpec(
                    title = R.string.candidates_padding,
                    primary = TwinIntSide(R.string.vertical, "candidates_item_padding_vertical", 2),
                    secondary = TwinIntSide(R.string.horizontal, "candidates_item_padding_horizontal", 4),
                    min = 0,
                    max = 64,
                    unit = "dp"
                )
            )
            itemPaddingVertical = primary
            itemPaddingHorizontal = secondary
        }
    }

    inner class Clipboard : ManagedPreferenceCategory(R.string.clipboard, sharedPreferences) {
        val clipboardListening = switch(R.string.clipboard_listening, "clipboard_enable", false)
        val clipboardHistoryLimit = int(
            IntPreferenceSpec(R.string.clipboard_limit, "clipboard_limit", 10)
        ) { clipboardListening.getValue() }
        val clipboardSuggestion = switch(
            R.string.clipboard_suggestion, "clipboard_suggestion", true
        ) { clipboardListening.getValue() }
        val clipboardItemTimeout = int(
            IntPreferenceSpec(
                title = R.string.clipboard_suggestion_timeout,
                key = "clipboard_item_timeout",
                defaultValue = 30,
                min = -1,
                max = Int.MAX_VALUE,
                unit = "s"
            )
        ) { clipboardListening.getValue() && clipboardSuggestion.getValue() }
        val clipboardReturnAfterPaste = switch(
            R.string.clipboard_return_after_paste, "clipboard_return_after_paste", false
        ) { clipboardListening.getValue() }
        val clipboardMaskSensitive = switch(
            R.string.clipboard_mask_sensitive, "clipboard_mask_sensitive", true
        ) { clipboardListening.getValue() }
    }

    inner class Symbols : ManagedPreferenceCategory(R.string.emoji_and_symbols, sharedPreferences) {
        val hideUnsupportedEmojis = switch(
            R.string.hide_unsupported_emojis,
            "hide_unsupported_emojis",
            true
        )

        val defaultEmojiSkinTone = enumList(
            R.string.default_emoji_skin_tone,
            "default_emoji_skin_tone",
            EmojiModifier.SkinTone.Default,
        )
    }

    private val providers = mutableListOf<ManagedPreferenceProvider>()

    fun <T : ManagedPreferenceProvider> registerProvider(
        providerF: (SharedPreferences) -> T
    ): T {
        val provider = providerF(sharedPreferences)
        providers.add(provider)
        return provider
    }

    private fun <T : ManagedPreferenceProvider> T.register() = this.apply {
        registerProvider { this }
    }

    val internal = Internal().register()
    val keyboard = Keyboard().register()
    val candidates = Candidates().register()
    val clipboard = Clipboard().register()
    val symbols = Symbols().register()
    val advanced = Advanced().register()

    @Keep
    private val onSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null) return@OnSharedPreferenceChangeListener
            providers.forEach {
                it.fireChange(key)
            }
        }

    @RequiresApi(Build.VERSION_CODES.N)
    fun syncToDeviceEncryptedStorage() {
        val ctx = appContext.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            listOf(
                internal.verboseLog,
                internal.editorInfoInspector,
                advanced.ignoreSystemCursor,
                advanced.offlineMode,
                advanced.disableAnimation,
                advanced.vivoKeypressWorkaround
            ).forEach {
                it.putValueTo(this@edit)
            }
            listOf(
                keyboard,
                candidates,
                clipboard
            ).forEach { category ->
                category.managedPreferences.forEach {
                    it.value.putValueTo(this@edit)
                }
            }
        }
    }

    companion object {
        private var instance: AppPrefs? = null

        /**
         * MUST call before use
         */
        fun init(sharedPreferences: SharedPreferences) {
            if (instance != null)
                return
            instance = AppPrefs(sharedPreferences)
            sharedPreferences.registerOnSharedPreferenceChangeListener(getInstance().onSharedPreferenceChangeListener)
        }

        fun getInstance() = instance!!
    }
}
