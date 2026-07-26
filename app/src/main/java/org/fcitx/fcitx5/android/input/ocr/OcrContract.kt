/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

data class OcrEditorTarget(
    val packageName: String,
    val fieldId: Int,
    val inputType: Int,
    val cursor: Int
)

data class OcrTextBlock(
    val id: String,
    val text: String
)

object OcrTextContract {
    const val MAX_BLOCKS = 200
    const val MAX_BLOCK_CHARACTERS = 1_000
    const val MAX_RESULT_CHARACTERS = 12_000

    fun bindEditor(
        packageName: String?,
        fieldId: Int,
        inputType: Int,
        selectionStart: Int,
        selectionEnd: Int
    ): OcrEditorTarget? {
        if (packageName.isNullOrBlank() || selectionStart < 0 || selectionStart != selectionEnd) {
            return null
        }
        return OcrEditorTarget(packageName, fieldId, inputType, selectionStart)
    }

    fun parse(raw: String?): List<OcrTextBlock>? {
        val lines = raw
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toList()
            .orEmpty()
        if (lines.isEmpty() || lines.size > MAX_BLOCKS) return null
        if (lines.any { it.length > MAX_BLOCK_CHARACTERS }) return null
        if (lines.sumOf(String::length) + lines.lastIndex > MAX_RESULT_CHARACTERS) return null
        return lines.mapIndexed { index, text -> OcrTextBlock("line:$index", text) }
    }

    fun format(blocks: List<OcrTextBlock>, selectedIds: Set<String>): String? {
        if (selectedIds.isEmpty()) return null
        val selected = blocks.filter { it.id in selectedIds }
        if (selected.isEmpty() || selected.size != selectedIds.size) return null
        return selected.joinToString("\n", transform = OcrTextBlock::text)
            .takeIf { it.length <= MAX_RESULT_CHARACTERS }
    }
}

/** One reviewed OCR result can cause at most one editor mutation. */
internal class OcrCommitGate {
    private var consumed = false

    @Synchronized
    fun claim(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }

    @Synchronized
    fun resetForReview() {
        consumed = false
    }
}
