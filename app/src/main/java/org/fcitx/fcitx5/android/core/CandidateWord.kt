/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.core

data class CandidateWord @JvmOverloads constructor(
    val label: String,
    val text: String,
    val comment: String,
    val spaceBetweenComment: Boolean = true
) {
    fun textWithComment(): String {
        return buildString {
            append(text)
            if (comment.isNotBlank()) {
                if (spaceBetweenComment) {
                    append(" ")
                }
                append(comment)
            }
        }
    }

    override fun toString(): String =
        "CandidateWord(labelLen=${label.length}, textLen=${text.length}, commentLen=${comment.length})"

    companion object {
        val Empty = CandidateWord("", "", "", false)
    }
}
