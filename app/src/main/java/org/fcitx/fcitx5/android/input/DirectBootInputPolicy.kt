/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

/**
 * Direct Boot may render the keyboard before credential-encrypted storage is available.
 * Keep only the basic input path alive until the first unlock after a reboot.
 */
object DirectBootInputPolicy {
    fun allowsCredentialProtectedFeatures(isDirectBootMode: Boolean): Boolean =
        !isDirectBootMode

    fun allowsTextInspection(
        isDirectBootMode: Boolean,
        editorAllowsTextInspection: Boolean
    ): Boolean = allowsCredentialProtectedFeatures(isDirectBootMode) && editorAllowsTextInspection
}
