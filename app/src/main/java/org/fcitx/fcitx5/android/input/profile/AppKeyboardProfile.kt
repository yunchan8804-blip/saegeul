/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.profile

import org.fcitx.fcitx5.android.input.BufferedInputTransport
import org.fcitx.fcitx5.android.input.keyboard.MobileHangulLayout

enum class AppFeaturePolicy {
    Inherit,
    Allow,
    Block;

    internal fun resolve(defaultAllowed: Boolean): Boolean = when (this) {
        Inherit -> defaultAllowed
        Allow -> true
        Block -> false
    }
}

enum class AppToolbarVisibility {
    Inherit,
    Expanded,
    Collapsed;

    internal fun resolve(defaultExpanded: Boolean): Boolean = when (this) {
        Inherit -> defaultExpanded
        Expanded -> true
        Collapsed -> false
    }
}

data class AppKeyboardProfile(
    val packageName: String,
    val mobileHangulLayout: MobileHangulLayout? = null,
    val themeName: String? = null,
    val toolbarVisibility: AppToolbarVisibility = AppToolbarVisibility.Inherit,
    val bufferedInputTransport: BufferedInputTransport? = null,
    val networkPolicy: AppFeaturePolicy = AppFeaturePolicy.Inherit,
    val aiPolicy: AppFeaturePolicy = AppFeaturePolicy.Inherit
) {
    fun normalized(): AppKeyboardProfile = copy(
        packageName = normalizePackageName(packageName),
        themeName = themeName?.trim()?.take(MAX_THEME_NAME_LENGTH)?.takeIf(String::isNotEmpty)
    )

    fun validate(): AppKeyboardProfile = normalized().also {
        require(PACKAGE_NAME.matches(it.packageName)) { "Invalid Android package name" }
    }

    val hasOverrides: Boolean
        get() = mobileHangulLayout != null || themeName != null ||
            toolbarVisibility != AppToolbarVisibility.Inherit || bufferedInputTransport != null ||
            networkPolicy != AppFeaturePolicy.Inherit || aiPolicy != AppFeaturePolicy.Inherit

    companion object {
        const val MAX_THEME_NAME_LENGTH = 160
        private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")

        fun normalizePackageName(value: String): String = value.trim().take(255)
    }
}

data class AppKeyboardGlobalDefaults(
    val mobileHangulLayout: MobileHangulLayout,
    val themeName: String,
    val toolbarExpanded: Boolean,
    val bufferedInputTransport: BufferedInputTransport,
    /** Global offline mode is a kill switch and cannot be overridden by an app profile. */
    val offlineMode: Boolean,
    val networkAllowed: Boolean = true,
    val aiAllowed: Boolean = true
)

data class EffectiveAppKeyboardProfile(
    val source: AppKeyboardProfile?,
    val mobileHangulLayout: MobileHangulLayout,
    val themeName: String,
    val toolbarExpanded: Boolean,
    val bufferedInputTransport: BufferedInputTransport,
    val allowsNetwork: Boolean,
    val allowsAi: Boolean
)

object AppKeyboardProfileResolver {
    fun resolve(
        packageName: String?,
        profiles: Collection<AppKeyboardProfile>,
        defaults: AppKeyboardGlobalDefaults,
        privateEditor: Boolean
    ): EffectiveAppKeyboardProfile {
        val normalizedPackage = packageName?.let(AppKeyboardProfile::normalizePackageName).orEmpty()
        val profile = profiles.firstOrNull { it.packageName == normalizedPackage }
        val profileAllowsNetwork = profile?.networkPolicy
            ?.resolve(defaults.networkAllowed) ?: defaults.networkAllowed
        val profileAllowsAi = profile?.aiPolicy?.resolve(defaults.aiAllowed) ?: defaults.aiAllowed
        // Privacy and the global offline switch are hard denies. An app-level Allow never wins.
        val allowsNetwork = !privateEditor && !defaults.offlineMode && profileAllowsNetwork
        val allowsAi = allowsNetwork && profileAllowsAi
        return EffectiveAppKeyboardProfile(
            source = profile,
            mobileHangulLayout = profile?.mobileHangulLayout ?: defaults.mobileHangulLayout,
            themeName = profile?.themeName ?: defaults.themeName,
            toolbarExpanded = profile?.toolbarVisibility?.resolve(defaults.toolbarExpanded)
                ?: defaults.toolbarExpanded,
            bufferedInputTransport = profile?.bufferedInputTransport
                ?: defaults.bufferedInputTransport,
            allowsNetwork = allowsNetwork,
            allowsAi = allowsAi
        )
    }
}
