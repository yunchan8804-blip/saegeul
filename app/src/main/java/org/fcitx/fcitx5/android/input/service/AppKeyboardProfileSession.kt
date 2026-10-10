/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.service

import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.EditorPrivacyPolicy
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.MobileHangulLayout
import org.fcitx.fcitx5.android.input.profile.AppKeyboardGlobalDefaults
import org.fcitx.fcitx5.android.input.profile.AppKeyboardProfile
import org.fcitx.fcitx5.android.input.profile.AppKeyboardProfileResolver
import org.fcitx.fcitx5.android.input.profile.AppToolbarVisibility
import org.fcitx.fcitx5.android.input.profile.EffectiveAppKeyboardProfile

/**
 * The per-app keyboard profile of the current editor: resolved on every editor start, never in
 * Direct Boot (the profile store is credential-encrypted), and applied to the input views when it
 * selects a different theme.
 */
internal class AppKeyboardProfileSession(private val host: Host) {

    /** The service state and actions a profile resolution reads or triggers. */
    data class Host(
        val allowsCredentialProtectedFeatures: () -> Boolean,
        val profiles: () -> List<AppKeyboardProfile>,
        val upsertProfile: (AppKeyboardProfile) -> Unit,
        val globalTheme: () -> Theme,
        val globalDefaults: (globalTheme: Theme) -> AppKeyboardGlobalDefaults,
        val findSelectableTheme: (name: String) -> Theme?,
        /** False until the input views exist, so a profile resolved before them replaces nothing. */
        val canReplaceInputViews: () -> Boolean,
        val appliedThemeName: () -> String?,
        val replaceInputViews: (Theme) -> Unit
    )

    @Volatile
    var effective: EffectiveAppKeyboardProfile? = null
        private set

    fun effectiveMobileHangulLayout(global: MobileHangulLayout): MobileHangulLayout =
        effective?.source?.mobileHangulLayout ?: global

    fun effectiveToolbarExpanded(global: Boolean): Boolean = when (
        effective?.source?.toolbarVisibility
    ) {
        AppToolbarVisibility.Expanded -> true
        AppToolbarVisibility.Collapsed -> false
        else -> global
    }

    fun updateCurrentAppMobileHangulLayout(layout: MobileHangulLayout): Boolean {
        val current = effective ?: return false
        val source = current.source ?: return false
        return runCatching {
            val updated = source.copy(mobileHangulLayout = layout)
            host.upsertProfile(updated)
            effective = current.copy(source = updated, mobileHangulLayout = layout)
        }.isSuccess
    }

    fun resolveAndApply(info: EditorInfo, flags: CapabilityFlags) {
        if (!host.allowsCredentialProtectedFeatures()) {
            effective = null
            return
        }
        val globalTheme = host.globalTheme()
        val resolved = AppKeyboardProfileResolver.resolve(
            packageName = info.packageName,
            profiles = host.profiles(),
            defaults = host.globalDefaults(globalTheme),
            privateEditor = EditorPrivacyPolicy.forbidsTextInspection(info, flags)
        )
        val targetTheme = host.findSelectableTheme(resolved.themeName) ?: globalTheme
        effective = resolved.copy(themeName = targetTheme.name)
        if (host.canReplaceInputViews() && host.appliedThemeName() != targetTheme.name) {
            host.replaceInputViews(targetTheme)
        }
    }
}
