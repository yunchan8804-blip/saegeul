/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

data class AiEditorTarget(
    val packageName: String,
    val fieldId: Int,
    val inputType: Int,
    val selectionStart: Int,
    val selectionEnd: Int,
    /** The Android input session that captured this target; stale async replies must fail closed. */
    val inputSessionEpoch: Long = Long.MIN_VALUE
)

enum class AiSourceKind {
    Selection,
    BeforeCursor
}

data class AiInputSnapshot(
    val editor: AiEditorTarget,
    val source: String,
    val sourceKind: AiSourceKind
)

enum class AiApplyMode {
    Replace,
    Append
}

data class AiAppliedEdit(
    val editor: AiEditorTarget,
    val inserted: String,
    val restore: String
)

object AiTextSource {
    const val MAX_CHARACTERS = 4_000

    fun beforeCursor(text: String): String? {
        val normalized = text.takeLast(MAX_CHARACTERS)
        val paragraph = normalized.substringAfterLast('\n')
        return paragraph.takeIf(String::isNotBlank)
            ?: normalized.takeIf(String::isNotBlank)
    }
}
