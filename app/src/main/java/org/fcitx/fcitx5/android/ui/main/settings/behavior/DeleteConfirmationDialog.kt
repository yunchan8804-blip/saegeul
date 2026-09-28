/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import org.fcitx.fcitx5.android.R

/** Asks before removing stored data; [onDelete] runs only when the user confirms. */
internal fun showDeleteConfirmation(
    context: Context,
    @StringRes title: Int,
    @StringRes message: Int,
    onDelete: () -> Unit
) {
    AlertDialog.Builder(context)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(R.string.delete) { _, _ -> onDelete() }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
