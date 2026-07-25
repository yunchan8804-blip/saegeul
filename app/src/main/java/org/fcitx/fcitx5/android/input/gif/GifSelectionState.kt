/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

class GifSelectionState {
    var selectedId: Long? = null
        private set

    fun tap(id: Long): Long? {
        selectedId = id.takeUnless { selectedId == id }
        return selectedId
    }

    fun clear() {
        selectedId = null
    }
}
