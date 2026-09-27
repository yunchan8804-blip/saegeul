/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Context
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.activityViewModels
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.ai.TypingDnaCardPreference
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeDisplayNames
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.setup

class MainFragment : PaddingPreferenceFragment() {

    private val viewModel: MainViewModel by activityViewModels()
    private var typingDnaCardPreference: TypingDnaCardPreference? = null

    override fun onStart() {
        super.onStart()
        viewModel.enableAboutButton()
    }

    override fun onResume() {
        super.onResume()
        typingDnaCardPreference?.refresh()
    }

    override fun onStop() {
        viewModel.disableAboutButton()
        super.onStop()
    }

    private fun PreferenceCategory.addDestinationPreference(
        @StringRes title: Int,
        @DrawableRes icon: Int,
        route: SettingsRoute,
        summary: String? = null
    ) {
        addPreference(Preference(context).apply {
            setup(context.getString(title), summary, icon) {
                navigateWithAnim(route)
            }
        })
    }

    private fun themeSummary(ctx: Context): String {
        val prefs = ThemeManager.prefs
        return if (prefs.followSystemDayNightTheme.getValue()) {
            val light = ThemeDisplayNames.displayName(ctx, prefs.lightModeTheme.getValue())
            val dark = ThemeDisplayNames.displayName(ctx, prefs.darkModeTheme.getValue())
            ctx.getString(R.string.settings_summary_theme_split, light, dark)
        } else {
            ThemeDisplayNames.displayName(ctx, prefs.normalModeTheme.getValue())
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        val productSurfaces = ProductSurfacePolicy.forBuild(BuildConfig.SHOW_DEVELOPER_SURFACES)
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            val dnaCard = TypingDnaCardPreference(ctx)
            typingDnaCardPreference = dnaCard
            addPreference(dnaCard)
            addCategory(R.string.languages_and_input) {
                addDestinationPreference(
                    R.string.input_methods,
                    R.drawable.ic_baseline_language_24,
                    SettingsRoute.InputMethodList,
                    ctx.getString(R.string.settings_summary_input_methods)
                )
            }
            addCategory(R.string.product_settings) {
                addDestinationPreference(
                    R.string.theme,
                    R.drawable.ic_baseline_palette_24,
                    SettingsRoute.Theme,
                    themeSummary(ctx)
                )
                addDestinationPreference(
                    R.string.virtual_keyboard,
                    R.drawable.ic_baseline_keyboard_24,
                    SettingsRoute.VirtualKeyboard,
                    ctx.getString(R.string.settings_summary_virtual_keyboard)
                )
                addDestinationPreference(
                    R.string.candidates_window,
                    R.drawable.ic_baseline_list_alt_24,
                    SettingsRoute.CandidatesWindow,
                    ctx.getString(R.string.settings_summary_candidates_window)
                )
                addDestinationPreference(
                    R.string.clipboard,
                    R.drawable.ic_clipboard,
                    SettingsRoute.Clipboard,
                    ctx.getString(R.string.settings_summary_clipboard)
                )
                addDestinationPreference(
                    R.string.emoji_and_symbols,
                    R.drawable.ic_baseline_emoji_symbols_24,
                    SettingsRoute.Symbol,
                    ctx.getString(R.string.settings_summary_emoji_and_symbols)
                )
                if (productSurfaces.showPluginManager) {
                    addDestinationPreference(
                        R.string.plugins,
                        R.drawable.ic_baseline_android_24,
                        SettingsRoute.Plugin
                    )
                }
                addDestinationPreference(
                    R.string.advanced,
                    R.drawable.ic_baseline_more_horiz_24,
                    SettingsRoute.Advanced,
                    ctx.getString(R.string.settings_summary_advanced)
                )
                addDestinationPreference(
                    R.string.privacy_ai,
                    R.drawable.ic_baseline_auto_awesome_24,
                    SettingsRoute.PrivacyAi,
                    ctx.getString(R.string.settings_summary_privacy_ai)
                )
                addDestinationPreference(
                    R.string.app_profiles,
                    R.drawable.ic_baseline_settings_24,
                    SettingsRoute.AppProfiles,
                    ctx.getString(R.string.settings_summary_app_profiles)
                )
                addDestinationPreference(
                    R.string.personal_dictionary,
                    R.drawable.ic_baseline_library_books_24,
                    SettingsRoute.PersonalDictionary,
                    ctx.getString(R.string.settings_summary_personal_dictionary)
                )
            }
            if (productSurfaces.showRawEngineSettings) {
                addCategory(R.string.engine_developer_tools) {
                    addDestinationPreference(
                        R.string.global_options,
                        R.drawable.ic_baseline_tune_24,
                        SettingsRoute.GlobalConfig
                    )
                    addDestinationPreference(
                        R.string.addons,
                        R.drawable.ic_baseline_extension_24,
                        SettingsRoute.AddonList
                    )
                }
            }
        }
    }
}
