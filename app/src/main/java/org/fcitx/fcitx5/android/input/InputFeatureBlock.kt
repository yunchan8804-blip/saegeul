/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

/**
 * Why a network-backed input feature is unavailable right now.
 *
 * Panels use this to name the actual cause instead of blaming the editor, and to offer
 * the one place that resolves it. [PrivateEditor] is the exception: it is a privacy
 * guarantee rather than a setting, so it never carries a fix-it action.
 */
enum class InputFeatureBlock {
    /** Password or otherwise private editor. Never resolvable from settings. */
    PrivateEditor,

    /** Full offline mode is on for every app. */
    OfflineMode,

    /** This app's keyboard profile blocks the feature. */
    AppPolicy
}
