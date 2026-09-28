/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.policy

import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.EditorPrivacyPolicy
import org.fcitx.fcitx5.android.input.InputFeatureBlock
import org.fcitx.fcitx5.android.input.prompt.InternalPromptFeature
import org.fcitx.fcitx5.android.input.profile.AppFeaturePolicy
import org.fcitx.fcitx5.android.input.profile.EffectiveAppKeyboardProfile

/**
 * Read-only feature gates for the current editor session.
 *
 * Owns no state of its own: every decision reads the direct-boot mode, the editor, its capability
 * flags, the offline setting and the per-app profile through the accessors it was created with.
 */
class InputFeaturePolicy(
    private val isDirectBootMode: () -> Boolean,
    private val editorInfo: () -> EditorInfo,
    private val capabilityFlags: () -> CapabilityFlags,
    private val offlineMode: () -> Boolean,
    private val appProfile: () -> EffectiveAppKeyboardProfile?
) {
    fun allowsTextInspectionFeatures(): Boolean =
        DirectBootInputPolicy.allowsTextInspection(
            isDirectBootMode = isDirectBootMode(),
            editorAllowsTextInspection = !EditorPrivacyPolicy.forbidsTextInspection(
                editorInfo(),
                capabilityFlags()
            )
        )

    /** Network-backed input features must never inspect or contact a server for private editors. */
    fun allowsNetworkInputFeatures(): Boolean =
        allowsTextInspectionFeatures() && !offlineMode() &&
            appProfile()?.source?.networkPolicy != AppFeaturePolicy.Block

    /** Explicit local completion never opens a network path, but uses the same privacy and AI policy gates. */
    fun allowsOnDeviceContextCompletionFeatures(): Boolean =
        allowsTextInspectionFeatures() && appProfile()?.source?.aiPolicy != AppFeaturePolicy.Block

    fun allowsInternalPromptFeature(feature: InternalPromptFeature): Boolean = when (feature) {
        InternalPromptFeature.GifSearch -> allowsNetworkInputFeatures()
    }

    /**
     * Which of the three gates in [allowsNetworkInputFeatures] is closed, so a panel can
     * name the real cause and point at the setting that reopens it. Null when allowed.
     */
    fun networkInputBlock(): InputFeatureBlock? = when {
        !allowsTextInspectionFeatures() -> InputFeatureBlock.PrivateEditor
        offlineMode() -> InputFeatureBlock.OfflineMode
        appProfile()?.source?.networkPolicy == AppFeaturePolicy.Block ->
            InputFeatureBlock.AppPolicy
        else -> null
    }
}
