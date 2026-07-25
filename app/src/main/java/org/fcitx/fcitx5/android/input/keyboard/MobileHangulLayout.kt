/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class MobileHangulLayout(override val stringRes: Int) : ManagedPreferenceEnum {
    Physical(R.string.mobile_hangul_physical),
    Chunjiin(R.string.mobile_hangul_chunjiin),
    ChunjiinPlus(R.string.mobile_hangul_chunjiin_plus),
    Danmoum(R.string.mobile_hangul_danmoum),
    MoakeyOneHand(R.string.mobile_hangul_moakey_one_hand),
    MoakeyTwoHand(R.string.mobile_hangul_moakey_two_hand),
    Vega(R.string.mobile_hangul_vega),
    VegaCenter(R.string.mobile_hangul_vega_center),
    Naratgul(R.string.mobile_hangul_naratgul),
    NaratgulCenter(R.string.mobile_hangul_naratgul_center)
}
