/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.view.inputmethod.EditorInfo

/**
 * The (package, field, input type) triple that most "is this still the same editor field" checks
 * across the IME compare against.
 *
 * Selection lives separately in [EditorSelection], and an Android input session epoch stays its
 * own parameter wherever it is compared, because not every call site treats either as part of an
 * editor's identity.
 */
data class EditorIdentity(
    val packageName: String?,
    val fieldId: Int,
    val inputType: Int
) {
    /** Field-level identity: package, field id, and input type. */
    fun sameField(other: EditorIdentity): Boolean =
        packageName == other.packageName && fieldId == other.fieldId && inputType == other.inputType

    companion object {
        fun of(info: EditorInfo): EditorIdentity =
            EditorIdentity(info.packageName, info.fieldId, info.inputType)
    }
}

/** An editor's selection range, kept apart from [EditorIdentity] for call sites that don't need it. */
data class EditorSelection(val start: Int, val end: Int) {
    companion object {
        /** A collapsed selection at [cursor], for call sites that only ever track a caret position. */
        fun collapsed(cursor: Int) = EditorSelection(cursor, cursor)
    }
}
