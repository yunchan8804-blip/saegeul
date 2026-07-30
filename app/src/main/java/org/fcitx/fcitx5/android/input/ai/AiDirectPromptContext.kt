/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R

/** Resolves the exact reviewed source that a custom AI instruction will transform. */
internal object AiDirectPromptContext {
    @StringRes
    fun labelRes(origin: AiReplySourceOrigin?, scope: AiSourceScope): Int = when (origin) {
        AiReplySourceOrigin.Shared -> R.string.ai_direct_prompt_context_shared
        AiReplySourceOrigin.Clipboard -> R.string.ai_direct_prompt_context_clipboard
        null -> when (scope) {
            AiSourceScope.Selection -> R.string.ai_direct_prompt_context_selection
            AiSourceScope.EntireEditor -> R.string.ai_direct_prompt_context_editor
            AiSourceScope.CursorContext -> R.string.ai_direct_prompt_context_cursor
            AiSourceScope.ExternalReply -> R.string.ai_direct_prompt_context_shared
        }
    }
}
