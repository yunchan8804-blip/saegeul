/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

/** Opens the canonical prompt keyboard once per AI writing window session. */
class AiPromptEntryPolicy {
    private var entryConsumed = false

    fun consumeShouldOpen(
        featureReady: Boolean,
        editorTargetBound: Boolean
    ): Boolean {
        if (entryConsumed || !featureReady || !editorTargetBound) return false
        entryConsumed = true
        return true
    }
}
