/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.prediction

/**
 * What a typo-correction candidate was built to erase: the [length] characters before the cursor,
 * which at prediction time read [fragment]. [fragment] is null when the prediction context did
 * not hold that many characters, so the erase can never be verified.
 */
internal class ReplaceTarget(val length: Int, val fragment: String?) {

    /**
     * Whether the [removedText] read from the editor right before the erase is the very text the
     * candidate was built for. The memoized predictions can be overwritten by a newer context
     * while an older chip is still on screen; erasing then would delete unrelated text.
     */
    fun accepts(removedText: String): Boolean = fragment != null && removedText == fragment

    companion object {
        /** [rawFullContext] is the text before the cursor the prediction was computed for. */
        fun of(rawFullContext: String, replaceLength: Int): ReplaceTarget? {
            if (replaceLength <= 0) return null
            val fragment = if (replaceLength <= rawFullContext.length) rawFullContext.takeLast(replaceLength) else null
            return ReplaceTarget(replaceLength, fragment)
        }
    }
}
