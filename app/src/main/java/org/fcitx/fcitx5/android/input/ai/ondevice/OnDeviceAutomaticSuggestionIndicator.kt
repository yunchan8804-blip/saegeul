/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

/** UI state for the toolbar's automatic-suggestion indicator button. */
sealed interface OnDeviceAutomaticSuggestionIndicator {
    data object Hidden : OnDeviceAutomaticSuggestionIndicator
    data object Preparing : OnDeviceAutomaticSuggestionIndicator
    data class Blocked(val code: String) : OnDeviceAutomaticSuggestionIndicator
}
