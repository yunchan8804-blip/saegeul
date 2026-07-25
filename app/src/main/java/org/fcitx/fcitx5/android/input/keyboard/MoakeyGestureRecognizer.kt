/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import kotlin.math.abs

/** Recognizes Moakey directional, return, and turn vowel gestures. */
class MoakeyGestureRecognizer(
    private val threshold: Float = 28f,
    private val standaloneVowelKey: Boolean = false
) {
    enum class Zone { Center, Right, Left, Up, Down, UpperDiagonal, LowerDiagonal }

    private var originX = 0f
    private var originY = 0f
    private val path = mutableListOf<Zone>()

    fun onEvent(event: CustomGestureView.Event): MobileHangulComposer.Token? = when (event.type) {
        CustomGestureView.GestureType.Down -> {
            originX = event.x
            originY = event.y
            path.clear()
            null
        }
        CustomGestureView.GestureType.Move -> {
            zone(event.x - originX, event.y - originY)?.let {
                if (path.lastOrNull() != it) path += it
            }
            null
        }
        CustomGestureView.GestureType.Up -> resolve(path)?.let(MobileHangulComposer.Token::Jamo)
    }

    fun resolve(zones: List<Zone>): Char? {
        val p = zones.dropWhile { it == Zone.Center }
        if (p.isEmpty()) return null
        val first = p.first()
        if (standaloneVowelKey) return when (first) {
            Zone.Right -> 'ㅣ'
            Zone.Left -> 'ㅡ'
            else -> null
        }
        val returnAt = p.indexOf(Zone.Center).takeIf { it > 0 }
        val afterReturn = returnAt?.let { p.drop(it + 1).firstOrNull { z -> z != Zone.Center } }
        val tailAfterReturn = returnAt?.let { p.drop(it + 2) }.orEmpty()
        val turned = p.drop(1).any { it != Zone.Center && it != first }
        return when (first) {
            Zone.Right -> when { afterReturn == Zone.Right -> 'ㅑ'; turned -> 'ㅐ'; else -> 'ㅏ' }
            Zone.Left -> when { afterReturn == Zone.Left -> 'ㅕ'; turned -> 'ㅔ'; else -> 'ㅓ' }
            Zone.Up -> when {
                afterReturn == Zone.Up -> 'ㅛ'
                afterReturn == Zone.Right && tailAfterReturn.any { it != Zone.Center && it != Zone.Right } -> 'ㅙ'
                afterReturn == Zone.Right -> 'ㅘ'
                turned -> 'ㅚ'
                else -> 'ㅗ'
            }
            Zone.Down -> when {
                afterReturn == Zone.Down -> 'ㅠ'
                afterReturn == Zone.Left && tailAfterReturn.any { it != Zone.Center && it != Zone.Left } -> 'ㅞ'
                afterReturn == Zone.Left -> 'ㅝ'
                turned -> 'ㅟ'
                else -> 'ㅜ'
            }
            Zone.UpperDiagonal -> 'ㅣ'
            Zone.LowerDiagonal -> if (Zone.Center in p.drop(1)) 'ㅢ' else 'ㅡ'
            Zone.Center -> null
        }
    }

    private fun zone(dx: Float, dy: Float): Zone? {
        val ax = abs(dx)
        val ay = abs(dy)
        if (ax < threshold && ay < threshold) return if (path.isEmpty()) null else Zone.Center
        if (ax > ay * 1.8f) return if (dx > 0) Zone.Right else Zone.Left
        if (ay > ax * 1.8f) return if (dy < 0) Zone.Up else Zone.Down
        return if (dy < 0) Zone.UpperDiagonal else Zone.LowerDiagonal
    }
}
