/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.ClipData
import org.fcitx.fcitx5.android.BuildConfig

val TRANSIENT_BUFFERED_PASTE_LABEL =
    "${BuildConfig.APPLICATION_ID}.TRANSIENT_BUFFERED_PASTE"

fun ClipData.isTransientBufferedPaste(): Boolean =
    description.label?.toString() == TRANSIENT_BUFFERED_PASTE_LABEL
