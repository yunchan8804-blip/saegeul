/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.panel

import android.content.Context
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.InputFeatureBlock
import org.fcitx.fcitx5.android.input.ai.AiSettingsNavigator
import org.fcitx.fcitx5.android.utils.AppUtil

/**
 * A fix-it action shown next to a blocking message.
 *
 * A panel that stops should say what went wrong and, whenever the user can actually
 * resolve it, hand them the one destination that does so instead of leaving them to
 * hunt through settings.
 */
class PanelRecovery(
    @StringRes val labelRes: Int,
    val run: () -> Unit
)

/** Standard recovery destinations for the fork's IME panels. */
internal object PanelRecoveries {

    /**
     * The fix for a closed network gate, or null for [InputFeatureBlock.PrivateEditor]:
     * that one is a privacy guarantee, not a setting to reopen.
     */
    fun forBlock(
        service: FcitxInputMethodService,
        block: InputFeatureBlock
    ): PanelRecovery? = when (block) {
        InputFeatureBlock.PrivateEditor -> null
        InputFeatureBlock.OfflineMode -> PanelRecovery(R.string.cta_open_offline_mode) {
            openSettings(service, AiSettingsNavigator::open)
        }
        InputFeatureBlock.AppPolicy -> PanelRecovery(R.string.cta_open_app_profile) {
            openSettings(service, AppUtil::launchMainToAppProfiles)
        }
    }

    /** The message that names [block], paired with [forBlock]. */
    @StringRes
    fun messageFor(block: InputFeatureBlock, privateEditorMessage: Int): Int = when (block) {
        InputFeatureBlock.PrivateEditor -> privateEditorMessage
        InputFeatureBlock.OfflineMode -> R.string.blocked_offline_mode
        InputFeatureBlock.AppPolicy -> R.string.blocked_app_network_policy
    }

    fun writingSetup(service: FcitxInputMethodService) =
        PanelRecovery(R.string.cta_open_writing_setup) {
            openSettings(service, AiSettingsNavigator::openWritingSetup)
        }

    fun voiceSetup(service: FcitxInputMethodService) =
        PanelRecovery(R.string.cta_open_voice_setup) {
            openSettings(service, AiSettingsNavigator::openVoiceSetup)
        }

    fun gifSettings(service: FcitxInputMethodService) =
        PanelRecovery(R.string.cta_open_gif_settings) {
            openSettings(service, AiSettingsNavigator::open)
        }

    fun phraseSettings(service: FcitxInputMethodService) =
        PanelRecovery(R.string.cta_open_phrase_settings) {
            openSettings(service, AppUtil::launchMainToQuickPhraseList)
        }

    /**
     * Settings are only ever opened from a user tap, and the IME is released first so the
     * editor returns to the normal keyboard once the user comes back.
     */
    private fun openSettings(service: FcitxInputMethodService, open: (Context) -> Unit) {
        service.prepareForSettingsActivity()
        open(service)
    }
}
