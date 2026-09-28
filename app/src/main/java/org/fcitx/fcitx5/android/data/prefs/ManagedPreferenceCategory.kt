/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.preference.PreferenceScreen

abstract class ManagedPreferenceCategory(
    @StringRes val title: Int,
    protected val sharedPreferences: SharedPreferences
) : ManagedPreferenceProvider() {

    /**
     * A storage-less group header shown as a [androidx.preference.PreferenceCategory] above the
     * items declared after it. Does not create a [ManagedPreference], so it has no key, no
     * default value, and is never touched by backup/export.
     */
    protected fun header(
        @StringRes
        title: Int
    ) {
        ManagedPreferenceUi.Header(title).registerUi()
    }

    protected fun switch(
        @StringRes
        title: Int,
        key: String,
        defaultValue: Boolean,
        @StringRes
        summary: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ): ManagedPreference.PBool {
        val pref = ManagedPreference.PBool(sharedPreferences, key, defaultValue)
        val ui = ManagedPreferenceUi.Switch(title, key, defaultValue, summary, enableUiOn)
        pref.register()
        ui.registerUi()
        return pref
    }

    protected fun <T : Any> list(
        @StringRes
        title: Int,
        key: String,
        defaultValue: T,
        codec: ManagedPreference.StringLikeCodec<T>,
        entryValues: List<T>,
        @StringRes
        entryLabels: List<Int>,
        enableUiOn: (() -> Boolean)? = null
    ): ManagedPreference.PStringLike<T> {
        val pref = ManagedPreference.PStringLike(sharedPreferences, key, defaultValue, codec)
        val ui = ManagedPreferenceUi.StringList(
            title, key, defaultValue, codec, entryValues, entryLabels, enableUiOn
        )
        pref.register()
        ui.registerUi()
        return pref
    }

    protected inline fun <reified T> enumList(
        @StringRes
        title: Int,
        key: String,
        defaultValue: T,
        noinline enableUiOn: (() -> Boolean)? = null
    ): ManagedPreference.PStringLike<T> where T : Enum<T>, T : ManagedPreferenceEnum {
        val codec = object : ManagedPreference.StringLikeCodec<T> {
            override fun decode(raw: String): T = enumValueOf(raw)
        }
        val entryValues = enumValues<T>().toList()
        val entryLabels = entryValues.map { it.stringRes }
        return list(title, key, defaultValue, codec, entryValues, entryLabels, enableUiOn)
    }

    protected fun voiceInputPreference(
        @StringRes
        title: Int,
        key: String,
        defaultValue: String,
        enableUiOn: (() -> Boolean)? = null
    ): ManagedPreference.PString {
        val pref = ManagedPreference.PString(sharedPreferences, key, defaultValue)
        val ui = ManagedPreferenceUi.VoiceInputList(title, key, defaultValue, enableUiOn)
        pref.register()
        ui.registerUi()
        return pref
    }

    protected fun int(
        spec: IntPreferenceSpec,
        enableUiOn: (() -> Boolean)? = null
    ): ManagedPreference.PInt {
        val pref = ManagedPreference.PInt(sharedPreferences, spec.key, spec.defaultValue)
        // Int can overflow when min < 0 && max == Int.MAX_VALUE
        val ui = if ((spec.max.toLong() - spec.min.toLong()) / spec.step.toLong() >= 240L)
            ManagedPreferenceUi.EditTextInt(
                spec.title, spec.key, spec.defaultValue, spec.min, spec.max, spec.unit, enableUiOn
            )
        else
            ManagedPreferenceUi.SeekBarInt(
                spec.title, spec.key, spec.defaultValue, spec.min, spec.max, spec.unit, spec.step,
                spec.defaultLabel, enableUiOn
            )
        pref.register()
        ui.registerUi()
        return pref
    }

    protected fun twinInt(
        spec: TwinIntPreferenceSpec,
        enableUiOn: (() -> Boolean)? = null
    ): Pair<ManagedPreference.PInt, ManagedPreference.PInt> {
        val primary = ManagedPreference.PInt(
            sharedPreferences,
            spec.primary.key, spec.primary.defaultValue,
        )
        val secondary = ManagedPreference.PInt(
            sharedPreferences,
            spec.secondary.key, spec.secondary.defaultValue
        )
        val ui = ManagedPreferenceUi.TwinSeekBarInt(
            spec.title,
            spec.primary.label, spec.primary.key, spec.primary.defaultValue,
            spec.secondary.label, spec.secondary.key, spec.secondary.defaultValue,
            spec.min, spec.max, spec.unit, spec.step, spec.defaultLabel, enableUiOn
        )
        primary.register()
        secondary.register()
        ui.registerUi()
        return primary to secondary
    }

    override fun createUi(screen: PreferenceScreen) {
        val ctx = screen.context
        managedPreferencesUi.forEach {
            screen.addPreference(it.createUi(ctx).apply {
                isEnabled = it.isEnabled()
            })
        }
    }
}