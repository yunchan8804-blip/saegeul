/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.profile

import org.fcitx.fcitx5.android.input.BufferedInputTransport
import org.fcitx.fcitx5.android.input.keyboard.MobileHangulLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppKeyboardProfileTest {
    private val defaults = AppKeyboardGlobalDefaults(
        mobileHangulLayout = MobileHangulLayout.Physical,
        themeName = "Global theme",
        toolbarExpanded = false,
        bufferedInputTransport = BufferedInputTransport.SystemPaste,
        offlineMode = false
    )

    @Test
    fun `missing profile preserves every global setting`() {
        val effective = AppKeyboardProfileResolver.resolve(
            "com.example.chat",
            emptyList(),
            defaults,
            privateEditor = false
        )

        assertNull(effective.source)
        assertEquals(defaults.mobileHangulLayout, effective.mobileHangulLayout)
        assertEquals(defaults.themeName, effective.themeName)
        assertEquals(defaults.toolbarExpanded, effective.toolbarExpanded)
        assertEquals(defaults.bufferedInputTransport, effective.bufferedInputTransport)
        assertTrue(effective.allowsNetwork)
        assertTrue(effective.allowsAi)
    }

    @Test
    fun `matching package applies all overrides`() {
        val profile = AppKeyboardProfile(
            packageName = "com.example.chat",
            mobileHangulLayout = MobileHangulLayout.ChunjiinPlus,
            themeName = "Hanji Light",
            toolbarVisibility = AppToolbarVisibility.Expanded,
            bufferedInputTransport = BufferedInputTransport.DirectCommit,
            networkPolicy = AppFeaturePolicy.Allow,
            aiPolicy = AppFeaturePolicy.Block
        )
        val effective = AppKeyboardProfileResolver.resolve(
            profile.packageName,
            listOf(profile),
            defaults,
            privateEditor = false
        )

        assertEquals(MobileHangulLayout.ChunjiinPlus, effective.mobileHangulLayout)
        assertEquals("Hanji Light", effective.themeName)
        assertTrue(effective.toolbarExpanded)
        assertEquals(BufferedInputTransport.DirectCommit, effective.bufferedInputTransport)
        assertTrue(effective.allowsNetwork)
        assertFalse(effective.allowsAi)
    }

    @Test
    fun `private editor overrides explicit app allow policies`() {
        val profile = AppKeyboardProfile(
            "com.example.private",
            networkPolicy = AppFeaturePolicy.Allow,
            aiPolicy = AppFeaturePolicy.Allow
        )
        val effective = AppKeyboardProfileResolver.resolve(
            profile.packageName,
            listOf(profile),
            defaults,
            privateEditor = true
        )

        assertFalse(effective.allowsNetwork)
        assertFalse(effective.allowsAi)
    }

    @Test
    fun `explicit allow can override a feature default but not offline mode`() {
        val profile = AppKeyboardProfile(
            "com.example.chat",
            networkPolicy = AppFeaturePolicy.Allow,
            aiPolicy = AppFeaturePolicy.Allow
        )
        val effective = AppKeyboardProfileResolver.resolve(
            profile.packageName,
            listOf(profile),
            defaults.copy(networkAllowed = false, aiAllowed = false),
            privateEditor = false
        )

        assertTrue(effective.allowsNetwork)
        assertTrue(effective.allowsAi)
    }

    @Test
    fun `global offline mode overrides explicit app allow policies`() {
        val profile = AppKeyboardProfile(
            "com.example.chat",
            networkPolicy = AppFeaturePolicy.Allow,
            aiPolicy = AppFeaturePolicy.Allow
        )
        val effective = AppKeyboardProfileResolver.resolve(
            profile.packageName,
            listOf(profile),
            defaults.copy(offlineMode = true),
            privateEditor = false
        )

        assertFalse(effective.allowsNetwork)
        assertFalse(effective.allowsAi)
    }

    @Test
    fun `json round trip keeps profiles and excludes editor data`() {
        val profiles = listOf(
            AppKeyboardProfile(
                packageName = "com.example.chat",
                mobileHangulLayout = MobileHangulLayout.Danmoum,
                themeName = "Dark",
                toolbarVisibility = AppToolbarVisibility.Collapsed,
                bufferedInputTransport = BufferedInputTransport.CtrlV,
                networkPolicy = AppFeaturePolicy.Block,
                aiPolicy = AppFeaturePolicy.Block
            )
        )

        val encoded = encodeAppKeyboardProfiles(profiles)
        assertEquals(profiles, decodeAppKeyboardProfiles(encoded))
        val json = encoded.toString(Charsets.UTF_8)
        assertFalse(json.contains("editorText"))
        assertFalse(json.contains("apiKey"))
    }

    @Test
    fun `legacy unversioned profile migrates and unknown values inherit`() {
        val decoded = decodeAppKeyboardProfiles(
            """{
                "profiles":[{
                    "package":"com.example.legacy",
                    "layout":"Chunjiin",
                    "theme":"Legacy",
                    "toolbar":"RemovedValue",
                    "network":"Block"
                }]
            }""".trimIndent().toByteArray()
        )

        assertEquals(1, decoded.size)
        assertEquals(MobileHangulLayout.Chunjiin, decoded.single().mobileHangulLayout)
        assertEquals(AppToolbarVisibility.Inherit, decoded.single().toolbarVisibility)
        assertEquals(AppFeaturePolicy.Block, decoded.single().networkPolicy)
    }

    @Test
    fun `malformed future or invalid package data fails closed to no profiles`() {
        assertEquals(emptyList<AppKeyboardProfile>(), decodeAppKeyboardProfiles("bad".toByteArray()))
        assertEquals(
            emptyList<AppKeyboardProfile>(),
            decodeAppKeyboardProfiles("{\"version\":99,\"profiles\":[]}".toByteArray())
        )
        assertEquals(
            emptyList<AppKeyboardProfile>(),
            decodeAppKeyboardProfiles(
                "{\"version\":1,\"profiles\":[{\"package\":\"bad package\",\"ai\":\"Allow\"}]}"
                    .toByteArray()
            )
        )
    }
}
