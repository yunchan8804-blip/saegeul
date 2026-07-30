/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

/** Prevents a reviewed result card from offering a meaningless editor mutation. */
internal object AiResultApplyPolicy {
    /**
     * A blank editor can accept an insertion and an external reply is always an explicit insert
     * into the current editor. For a normal reviewed editor snapshot, identical text must not
     * create Replace/Append controls or a misleading Undo entry.
     */
    fun canApply(source: String, suggestion: String, hasExternalReplySource: Boolean): Boolean =
        source.isEmpty() || hasExternalReplySource || source != suggestion
}
