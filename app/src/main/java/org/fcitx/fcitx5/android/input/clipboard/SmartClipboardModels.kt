/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

data class SmartClipboardItem(val id: Int, val text: String)

enum class SmartClipboardAction {
    PlainText,
    Combine,
    PhoneNumber,
    AccountNumber,
    MaskPersonalData
}

enum class SmartClipboardTransformError {
    NoSelection,
    PlainTextNeedsOneItem,
    CombineNeedsMultipleItems,
    NumberNeedsOneItem,
    InvalidPhoneNumber,
    InvalidAccountNumber,
    NoPersonalData,
    OutputTooLong
}

enum class SmartClipboardPersonalDataKind {
    Email,
    Phone,
    Account
}

data class SmartClipboardMaskCandidate(
    val kind: SmartClipboardPersonalDataKind,
    val start: Int,
    val end: Int,
    val masked: String
)

data class SmartClipboardPreview(
    val action: SmartClipboardAction,
    val output: String,
    val sourceCount: Int,
    val maskCandidates: List<SmartClipboardMaskCandidate> = emptyList()
)

sealed interface SmartClipboardTransformResult {
    data class Success(val preview: SmartClipboardPreview) : SmartClipboardTransformResult
    data class Failure(val reason: SmartClipboardTransformError) : SmartClipboardTransformResult
}

enum class SmartClipboardSelectionResult {
    Added,
    Removed,
    LimitReached
}

/** Preserves the user's explicit tap order and never stores more than [maxItems]. */
class SmartClipboardSelectionState(private val maxItems: Int = 10) {
    private val selected = linkedMapOf<Int, SmartClipboardItem>()

    init {
        require(maxItems > 0)
    }

    val items: List<SmartClipboardItem>
        get() = selected.values.toList()

    val ids: Set<Int>
        get() = selected.keys.toSet()

    fun toggle(item: SmartClipboardItem): SmartClipboardSelectionResult {
        if (selected.remove(item.id) != null) return SmartClipboardSelectionResult.Removed
        if (selected.size >= maxItems) return SmartClipboardSelectionResult.LimitReached
        selected[item.id] = item
        return SmartClipboardSelectionResult.Added
    }

    fun clear() = selected.clear()
}

data class SmartClipboardEditorTarget(
    val packageName: String,
    val fieldId: Int,
    val inputType: Int,
    val selectionStart: Int,
    val selectionEnd: Int
)
