/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Rect
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef

sealed class PopupAction {

    abstract val viewId: Int

    data class PreviewAction(
        override val viewId: Int,
        val content: String,
        val bounds: Rect
    ) : PopupAction()

    data class PreviewUpdateAction(
        override val viewId: Int,
        val content: String,
    ) : PopupAction()

    data class DismissAction(
        override val viewId: Int
    ) : PopupAction()

    data class ShowKeyboardAction(
        override val viewId: Int,
        val keyboard: KeyDef.Popup.Keyboard,
        val bounds: Rect,
        /**
         * When set, replaces the [KeyDef.Popup.Keyboard.Preset] lookup as the popup's key
         * values (what gets committed). Lets a keyboard correct or replace a preset's entries
         * without needing a matching [PopupPreset] table entry.
         */
        val keysOverride: Array<String>? = null,
        /**
         * When set, used as the popup's displayed labels as-is, bypassing punctuation
         * transformation. Needed whenever a label must differ from what it commits, e.g. a
         * Hangul jamo label whose key value is the Latin letter fcitx expects for that shift
         * state.
         */
        val labelsOverride: Array<String>? = null
    ) : PopupAction()

    data class ShowMenuAction(
        override val viewId: Int,
        val menu: KeyDef.Popup.Menu,
        val bounds: Rect
    ) : PopupAction()

    data class ChangeFocusAction(
        override val viewId: Int,
        val x: Float,
        val y: Float,
        var outResult: Boolean = false
    ) : PopupAction()

    data class TriggerAction(
        override val viewId: Int,
        var outAction: KeyAction? = null
    ) : PopupAction()
}
