/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

/**
 * Keeps actionable setup states separate from privacy and policy blocks.
 *
 * Only [SetupRequired] may offer a shortcut to AI settings. Private editors and disabled network
 * policy must stay fail-closed instead of suggesting that credentials can bypass the restriction.
 */
enum class AiFeatureEntryGate {
    Ready,
    PrivateEditor,
    NetworkPolicyBlocked,
    SetupRequired;

    val offersSetupAction: Boolean
        get() = this == SetupRequired

    companion object {
        fun evaluate(
            allowsTextInspection: Boolean,
            allowsAiInput: Boolean,
            hasConfiguredProfile: Boolean
        ): AiFeatureEntryGate = when {
            !allowsTextInspection -> PrivateEditor
            !allowsAiInput -> NetworkPolicyBlocked
            !hasConfiguredProfile -> SetupRequired
            else -> Ready
        }
    }
}
