/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HangulKeyLegendsTest {

    @Test
    fun dubeolsikNormalLegendsFollowQwertyPositions() {
        val legends = "QWERTYUIOPASDFGHJKLZXCVBNM".map {
            HangulKeyLegends.legend(it.toString(), shifted = false, layout = "Dubeolsik")
        }.joinToString("")

        assertEquals("ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ", legends)
    }

    @Test
    fun dubeolsikShiftLegendsExposeDoubleJamo() {
        val legends = "QWERTOP".associateWith {
            HangulKeyLegends.legend(it.toString(), shifted = true, layout = "Dubeolsik")
        }

        assertEquals("ㅃ", legends['Q'])
        assertEquals("ㅉ", legends['W'])
        assertEquals("ㄸ", legends['E'])
        assertEquals("ㄲ", legends['R'])
        assertEquals("ㅆ", legends['T'])
        assertEquals("ㅒ", legends['O'])
        assertEquals("ㅖ", legends['P'])
    }

    @Test
    fun unsupportedHangulLayoutsKeepLatinLegends() {
        assertNull(HangulKeyLegends.legend("Q", shifted = false, layout = "Sebeolsik 390"))
        assertNull(HangulKeyLegends.legend("Q", shifted = true, layout = "Dubeolsik Yetgeul"))
        assertTrue(HangulKeyLegends.isHangulInputMethod("hangul", "ko-KR"))
        assertTrue(HangulKeyLegends.isHangulInputMethod("hangul", "ko_KR"))
        assertFalse(HangulKeyLegends.isHangulInputMethod("androidkeyboard", "ko"))
        assertFalse(HangulKeyLegends.isHangulInputMethod("hangul", "en"))
    }
}
