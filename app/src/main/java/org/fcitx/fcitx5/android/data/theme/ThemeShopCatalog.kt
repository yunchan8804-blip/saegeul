/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.data.points.PointPricing

/**
 * Point-only builtin themes. They behave like builtin themes (no files,
 * no export) but stay locked until bought with points earned by
 * opted-in rewarded ads.
 */
object ThemeShopCatalog {

    data class ShopTheme(
        val theme: Theme.Builtin,
        val price: Int,
        val displayName: String
    )

    val shopThemes = listOf(
        ShopTheme(
            theme = Theme.Builtin(
                name = "SilverMistLight",
                isDark = false,
                backgroundColor = 0xffeef0f2,
                barColor = 0xfff5f7f9,
                keyboardColor = 0xffeef0f2,
                keyBackgroundColor = 0xfffbfcfd,
                keyTextColor = 0xff2b3138,
                candidateTextColor = 0xff2b3138,
                candidateLabelColor = 0xff565f68,
                candidateCommentColor = 0xff7a838c,
                altKeyBackgroundColor = 0xffdde3e8,
                altKeyTextColor = 0xff565f68,
                accentKeyBackgroundColor = 0xff46708a,
                accentKeyTextColor = 0xffffffff,
                keyPressHighlightColor = 0x24000000,
                keyShadowColor = 0xffc3ccd4,
                popupBackgroundColor = 0xfff5f7f9,
                popupTextColor = 0xff2b3138,
                spaceBarColor = 0xffdde3e8,
                dividerColor = 0x24000000,
                clipboardEntryColor = 0xfffbfcfd,
                genericActiveBackgroundColor = 0xff3a6d8a,
                genericActiveForegroundColor = 0xffffffff
            ),
            price = PointPricing.NORMAL_THEME_PRICE,
            displayName = "은빛 안개"
        ),
        ShopTheme(
            theme = Theme.Builtin(
                name = "PineDark",
                isDark = true,
                backgroundColor = 0xff0f1512,
                barColor = 0xff0a100e,
                keyboardColor = 0xff0f1512,
                keyBackgroundColor = 0xff1f2a24,
                keyTextColor = 0xffe8f0e9,
                candidateTextColor = 0xffe8f0e9,
                candidateLabelColor = 0xffb7c4ba,
                candidateCommentColor = 0xff97a49a,
                altKeyBackgroundColor = 0xff18211c,
                altKeyTextColor = 0xffb7c4ba,
                accentKeyBackgroundColor = 0xff4f9e6b,
                accentKeyTextColor = 0xffffffff,
                keyPressHighlightColor = 0x33ffffff,
                keyShadowColor = 0xff060a08,
                popupBackgroundColor = 0xff1f2a24,
                popupTextColor = 0xffe8f0e9,
                spaceBarColor = 0xff2e3d34,
                dividerColor = 0x26ffffff,
                clipboardEntryColor = 0xff1f2a24,
                genericActiveBackgroundColor = 0xff2e7d4f,
                genericActiveForegroundColor = 0xffffffff
            ),
            price = PointPricing.NORMAL_THEME_PRICE,
            displayName = "소나무 밤"
        ),
        ShopTheme(
            theme = Theme.Builtin(
                name = "RosyDuskDark",
                isDark = true,
                backgroundColor = 0xff191117,
                barColor = 0xff120b10,
                keyboardColor = 0xff191117,
                keyBackgroundColor = 0xff2e1f2a,
                keyTextColor = 0xfff7e9ee,
                candidateTextColor = 0xfff7e9ee,
                candidateLabelColor = 0xffd3b9c6,
                candidateCommentColor = 0xffb098a6,
                altKeyBackgroundColor = 0xff241722,
                altKeyTextColor = 0xffd3b9c6,
                accentKeyBackgroundColor = 0xffd1607e,
                accentKeyTextColor = 0xffffffff,
                keyPressHighlightColor = 0x33ffffff,
                keyShadowColor = 0xff0a0508,
                popupBackgroundColor = 0xff2e1f2a,
                popupTextColor = 0xfff7e9ee,
                spaceBarColor = 0xff402c3a,
                dividerColor = 0x26ffffff,
                clipboardEntryColor = 0xff2e1f2a,
                genericActiveBackgroundColor = 0xffa34a63,
                genericActiveForegroundColor = 0xffffffff
            ),
            price = PointPricing.PREMIUM_THEME_PRICE,
            displayName = "황혼 로즈"
        ),
        ShopTheme(
            theme = Theme.Builtin(
                name = "CeladonJadeLight",
                isDark = false,
                backgroundColor = 0xffe9f2ec,
                barColor = 0xfff0f7f2,
                keyboardColor = 0xffe9f2ec,
                keyBackgroundColor = 0xfffafdfb,
                keyTextColor = 0xff1f3a2e,
                candidateTextColor = 0xff1f3a2e,
                candidateLabelColor = 0xff48665a,
                candidateCommentColor = 0xff6d8a7d,
                altKeyBackgroundColor = 0xffd2e4da,
                altKeyTextColor = 0xff48665a,
                accentKeyBackgroundColor = 0xff2e8b6a,
                accentKeyTextColor = 0xffffffff,
                keyPressHighlightColor = 0x24000000,
                keyShadowColor = 0xffbdd5c8,
                popupBackgroundColor = 0xfff0f7f2,
                popupTextColor = 0xff1f3a2e,
                spaceBarColor = 0xffd2e4da,
                dividerColor = 0x24000000,
                clipboardEntryColor = 0xfffafdfb,
                genericActiveBackgroundColor = 0xff27775a,
                genericActiveForegroundColor = 0xffffffff
            ),
            price = PointPricing.PREMIUM_THEME_PRICE,
            displayName = "청자 비취"
        )
    )

    fun find(name: String): ShopTheme? = shopThemes.find { it.theme.name == name }

    fun allThemes(): List<Theme.Builtin> = shopThemes.map { it.theme }
}
