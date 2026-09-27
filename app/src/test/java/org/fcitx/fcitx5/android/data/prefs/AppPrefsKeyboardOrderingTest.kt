/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * app/design.md round 3 ("메뉴·설정 구조"): the virtual keyboard settings screen was regrouped
 * into 4 headed sections without touching any storage key or default value. These tests pin
 * down the two invariants: the storage key set is unchanged, and the new group headers never
 * enter the storage/backup path.
 */
class AppPrefsKeyboardOrderingTest {

    private class DummySharedPreferences : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            defValues

        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor = DummyEditor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
        }
    }

    private class DummyEditor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = this
        override fun putStringSet(key: String?, values: MutableSet<String>?) = this
        override fun putInt(key: String?, value: Int) = this
        override fun putLong(key: String?, value: Long) = this
        override fun putFloat(key: String?, value: Float) = this
        override fun putBoolean(key: String?, value: Boolean) = this
        override fun remove(key: String?) = this
        override fun clear() = this
        override fun commit() = true
        override fun apply() {}
    }

    /** The exact key set the pre-reorder `Keyboard` class registered. */
    private val expectedKeyboardKeys = setOf(
        "mobile_hangul_layout",
        "haptic_on_keypress",
        "haptic_on_keyup",
        "haptic_on_repeat",
        "button_vibration_press_milliseconds",
        "button_vibration_long_press_milliseconds",
        "button_vibration_press_amplitude",
        "button_vibration_long_press_amplitude",
        "sound_on_keypress",
        "button_sound_volume",
        "reset_keyboard_on_focus_change",
        "expand_toolbar_by_default",
        "inline_suggestions",
        "show_number_row",
        "toolbar_num_row_on_password",
        "popup_on_key_press",
        "keep_keyboard_letters_uppercase",
        "show_voice_input_button",
        "preferred_voice_input",
        "expand_keypress_area",
        "swipe_symbol_behavior",
        "keyboard_long_press_delay",
        "space_long_press_behavior",
        "space_swipe_move_cursor",
        "show_lang_switch_key",
        "lang_switch_key_behavior",
        "keyboard_height_percent",
        "keyboard_height_percent_landscape",
        "keyboard_side_padding",
        "keyboard_side_padding_landscape",
        "keyboard_bottom_padding",
        "keyboard_bottom_padding_landscape",
        "one_hand_mode",
        "key_text_scale",
        "split_keyboard_compact",
        "split_keyboard_expanded",
        "split_keyboard_compact_gap_portrait",
        "split_keyboard_compact_gap_landscape",
        "split_keyboard_expanded_gap_portrait",
        "split_keyboard_expanded_gap_landscape",
        "horizontal_candidate_style",
        "expanded_candidate_style",
        "expanded_candidate_grid_span_count_portrait",
        "expanded_candidate_grid_span_count_landscape",
    )

    @Test
    fun testKeyboardStorageKeysUnchangedAfterReordering() {
        val prefs = AppPrefs(DummySharedPreferences())
        assertEquals(
            "AppPrefs.Keyboard 저장 키 집합이 재배열 후에도 그대로 유지되어야 한다",
            expectedKeyboardKeys,
            prefs.keyboard.managedPreferences.keys
        )
    }

    @Test
    fun testKeyboardHeadersAreUiOnlyNotStorage() {
        val prefs = AppPrefs(DummySharedPreferences())
        val headerUis = prefs.keyboard.managedPreferencesUi.filterIsInstance<ManagedPreferenceUi.Header>()

        assertEquals(4, headerUis.size)
        headerUis.forEach { header ->
            assertFalse(
                "머리글 키가 저장 맵에 들어가면 안 된다: ${header.key}",
                prefs.keyboard.managedPreferences.containsKey(header.key)
            )
        }
        // 머리글이 아닌 모든 UI 항목은 저장 맵에 대응 키가 있어야 한다(head만 저장이 없는 예외).
        prefs.keyboard.managedPreferencesUi
            .filter { it !is ManagedPreferenceUi.Header }
            .forEach { ui ->
                assertTrue(
                    "저장 UI 항목은 저장 맵에 키가 있어야 한다: ${ui.key}",
                    prefs.keyboard.managedPreferences.containsKey(ui.key)
                )
            }
    }
}
