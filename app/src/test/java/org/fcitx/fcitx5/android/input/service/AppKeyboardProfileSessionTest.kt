/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.service

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.buffered.BufferedInputTransport
import org.fcitx.fcitx5.android.input.keyboard.MobileHangulLayout
import org.fcitx.fcitx5.android.input.profile.AppFeaturePolicy
import org.fcitx.fcitx5.android.input.profile.AppKeyboardGlobalDefaults
import org.fcitx.fcitx5.android.input.profile.AppKeyboardProfile
import org.fcitx.fcitx5.android.input.profile.AppToolbarVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppKeyboardProfileSessionTest {

    private class Fixture(
        var credentialProtected: Boolean = true,
        var profiles: List<AppKeyboardProfile> = emptyList(),
        var viewsExist: Boolean = true,
        var appliedThemeName: String? = GLOBAL_THEME.name,
        var upsertFails: Boolean = false
    ) {
        var profileReads = 0
        val replaced = mutableListOf<Theme>()
        val upserted = mutableListOf<AppKeyboardProfile>()

        val session = AppKeyboardProfileSession(
            AppKeyboardProfileSession.Host(
                allowsCredentialProtectedFeatures = { credentialProtected },
                profiles = {
                    profileReads++
                    profiles
                },
                upsertProfile = {
                    check(!upsertFails) { "store unavailable" }
                    upserted += it
                },
                globalTheme = { GLOBAL_THEME },
                globalDefaults = { theme ->
                    AppKeyboardGlobalDefaults(
                        mobileHangulLayout = MobileHangulLayout.Chunjiin,
                        themeName = theme.name,
                        toolbarExpanded = false,
                        bufferedInputTransport = BufferedInputTransport.DirectCommit,
                        offlineMode = false
                    )
                },
                findSelectableTheme = { name -> SELECTABLE_THEMES.firstOrNull { it.name == name } },
                canReplaceInputViews = { viewsExist },
                appliedThemeName = { appliedThemeName },
                replaceInputViews = {
                    replaced += it
                    appliedThemeName = it.name
                }
            )
        )
    }

    @Test
    fun `direct boot resolves no profile and never touches the store`() {
        val fixture = Fixture()
        fixture.profiles = listOf(AppKeyboardProfile(PACKAGE, mobileHangulLayout = MobileHangulLayout.Danmoum))
        fixture.session.resolveAndApply(editor(), CapabilityFlags.DefaultFlags)
        assertNotNull(fixture.session.effective)
        val readsBefore = fixture.profileReads

        fixture.credentialProtected = false
        fixture.session.resolveAndApply(editor(), CapabilityFlags.DefaultFlags)

        assertNull(fixture.session.effective)
        assertEquals(readsBefore, fixture.profileReads)
        assertTrue(fixture.replaced.isEmpty())
    }

    @Test
    fun `a private editor wins over an app policy that allows the network`() {
        val fixture = Fixture(
            profiles = listOf(
                AppKeyboardProfile(PACKAGE, networkPolicy = AppFeaturePolicy.Allow, aiPolicy = AppFeaturePolicy.Allow)
            )
        )

        fixture.session.resolveAndApply(editor(), CapabilityFlags.DefaultFlags)
        assertTrue(fixture.session.effective!!.allowsNetwork)

        fixture.session.resolveAndApply(editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD), CapabilityFlags.DefaultFlags)
        val effective = fixture.session.effective!!
        assertFalse(effective.allowsNetwork)
        assertFalse(effective.allowsAi)
    }

    @Test
    fun `an app profile overrides the global layout and toolbar`() {
        val fixture = Fixture(
            profiles = listOf(
                AppKeyboardProfile(
                    PACKAGE,
                    mobileHangulLayout = MobileHangulLayout.Danmoum,
                    toolbarVisibility = AppToolbarVisibility.Expanded
                )
            )
        )

        assertEquals(MobileHangulLayout.Physical, fixture.session.effectiveMobileHangulLayout(MobileHangulLayout.Physical))
        assertFalse(fixture.session.effectiveToolbarExpanded(false))

        fixture.session.resolveAndApply(editor(), CapabilityFlags.DefaultFlags)

        assertEquals(MobileHangulLayout.Danmoum, fixture.session.effectiveMobileHangulLayout(MobileHangulLayout.Physical))
        assertTrue(fixture.session.effectiveToolbarExpanded(false))
    }

    @Test
    fun `views are replaced only when they exist and the theme differs`() {
        val fixture = Fixture(
            profiles = listOf(AppKeyboardProfile(PACKAGE, themeName = APP_THEME.name)),
            viewsExist = false
        )

        fixture.session.resolveAndApply(editor(), CapabilityFlags.DefaultFlags)
        assertEquals(APP_THEME.name, fixture.session.effective!!.themeName)
        assertTrue(fixture.replaced.isEmpty())

        fixture.viewsExist = true
        fixture.session.resolveAndApply(editor(), CapabilityFlags.DefaultFlags)
        assertEquals(listOf(APP_THEME), fixture.replaced)

        fixture.session.resolveAndApply(editor(), CapabilityFlags.DefaultFlags)
        assertEquals(listOf(APP_THEME), fixture.replaced)
    }

    @Test
    fun `a theme the app cannot select falls back to the global theme`() {
        val fixture = Fixture(
            profiles = listOf(AppKeyboardProfile(PACKAGE, themeName = "UnownedShopTheme"))
        )

        fixture.session.resolveAndApply(editor(), CapabilityFlags.DefaultFlags)

        assertEquals(GLOBAL_THEME.name, fixture.session.effective!!.themeName)
        assertTrue(fixture.replaced.isEmpty())
    }

    @Test
    fun `updating the layout needs a current app profile and survives a store failure`() {
        val profile = AppKeyboardProfile(PACKAGE, mobileHangulLayout = MobileHangulLayout.Danmoum)
        val fixture = Fixture(profiles = listOf(profile))
        assertFalse(fixture.session.updateCurrentAppMobileHangulLayout(MobileHangulLayout.Physical))

        fixture.session.resolveAndApply(editor(), CapabilityFlags.DefaultFlags)
        fixture.upsertFails = true
        assertFalse(fixture.session.updateCurrentAppMobileHangulLayout(MobileHangulLayout.Physical))
        assertEquals(MobileHangulLayout.Danmoum, fixture.session.effective!!.mobileHangulLayout)

        fixture.upsertFails = false
        assertTrue(fixture.session.updateCurrentAppMobileHangulLayout(MobileHangulLayout.Physical))
        assertEquals(listOf(profile.copy(mobileHangulLayout = MobileHangulLayout.Physical)), fixture.upserted)
        assertEquals(MobileHangulLayout.Physical, fixture.session.effective!!.mobileHangulLayout)
    }

    @Test
    fun `an app without a profile has no source to update`() {
        val fixture = Fixture()

        fixture.session.resolveAndApply(editor(), CapabilityFlags.DefaultFlags)

        assertNotNull(fixture.session.effective)
        assertNull(fixture.session.effective!!.source)
        assertFalse(fixture.session.updateCurrentAppMobileHangulLayout(MobileHangulLayout.Physical))
    }

    private fun editor(inputType: Int = InputType.TYPE_CLASS_TEXT) = EditorInfo().apply {
        packageName = PACKAGE
        this.inputType = inputType
    }

    private companion object {
        const val PACKAGE = "com.example.chat"
        val GLOBAL_THEME: Theme = ThemePreset.HanjiLight
        val APP_THEME: Theme = ThemePreset.DancheongDark
        val SELECTABLE_THEMES = listOf(GLOBAL_THEME, APP_THEME)
    }
}
