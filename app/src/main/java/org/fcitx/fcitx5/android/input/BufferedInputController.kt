/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

/** Holds text finalized by an engine until a compatibility transport submits it as one unit. */
class BufferedInputController {

    private val committed = StringBuilder()

    val prefix: String
        get() = committed.toString()

    val isEmpty: Boolean
        get() = committed.isEmpty()

    fun capture(text: String) {
        committed.append(text)
    }

    fun snapshot(currentPreedit: String = ""): String = buildString {
        append(committed)
        append(currentPreedit)
    }

    fun deleteLastCodePoint(): Boolean {
        if (committed.isEmpty()) return false
        val start = committed.offsetByCodePoints(committed.length, -1)
        committed.delete(start, committed.length)
        return true
    }

    fun clear() {
        committed.clear()
    }
}
