/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.install

import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaInstallFailure
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaInstallState
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelFiles
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelInstaller
import java.util.Locale

/**
 * The single, frozen mapping from [GemmaInstallState] to the copy and button every install
 * surface shows - the vault home card, the "개인정보·AI" settings row, the onboarding page, the
 * product model-management screen ([org.fcitx.fcitx5.android.ui.main.ai.install.GemmaModelActivity])
 * and the keyboard's continue-writing prompt. Kept Context-free (string resource ids + plain
 * args) like [org.fcitx.fcitx5.android.ui.main.ai.dashboard.LearningStatusUiState] so [from] stays a
 * plain-JVM-testable pure function; each surface resolves [titleRes]/[detailRes]/[buttonRes] with
 * a [android.content.Context]. Do not change wording or which button fires which action without
 * updating every surface that reads this.
 */
data class GemmaInstallUiState(
    @StringRes val titleRes: Int,
    @StringRes val detailRes: Int? = null,
    val detailArgs: List<Any> = emptyList(),
    val progressKind: ProgressKind = ProgressKind.NONE,
    /** Only meaningful when [progressKind] is [ProgressKind.DETERMINATE]; 0..100. */
    val progressPercent: Int = 0,
    @StringRes val buttonRes: Int? = null,
    /** The action [buttonRes] fires; null exactly when [buttonRes] is null. */
    val button: Button? = null
) {
    enum class ProgressKind { NONE, DETERMINATE, INDETERMINATE }

    /** What a surface should do when the state's single button is tapped; see [GemmaInstallFlow.handle]. */
    enum class Button { INSTALL, PAUSE, MOBILE_DATA, RESUME, RETRY, OPEN_SETTINGS }

    companion object {
        fun from(state: GemmaInstallState): GemmaInstallUiState = when (state) {
            is GemmaInstallState.Unsupported -> GemmaInstallUiState(
                titleRes = R.string.gemma_install_title_unsupported
            )
            is GemmaInstallState.NotInstalled -> GemmaInstallUiState(
                titleRes = R.string.gemma_install_title_not_installed,
                detailRes = R.string.gemma_install_detail_not_installed,
                detailArgs = listOf(formatGemmaInstallBytes(GemmaModelFiles.MODEL_BYTES)),
                buttonRes = R.string.gemma_install_button_install,
                button = Button.INSTALL
            )
            is GemmaInstallState.Downloading -> {
                val percent = gemmaInstallPercent(state.downloadedBytes, state.totalBytes)
                val etaMinutes = GemmaModelInstaller.estimatedSecondsRemaining(state)
                    ?.let(::gemmaInstallEtaMinutes)
                GemmaInstallUiState(
                    titleRes = R.string.gemma_install_title_downloading,
                    detailRes = if (etaMinutes != null) {
                        R.string.gemma_install_detail_downloading_eta
                    } else {
                        R.string.gemma_install_detail_downloading_calculating
                    },
                    detailArgs = listOfNotNull(
                        formatGemmaInstallBytes(state.downloadedBytes),
                        formatGemmaInstallBytes(state.totalBytes),
                        percent,
                        etaMinutes
                    ),
                    progressKind = ProgressKind.DETERMINATE,
                    progressPercent = percent,
                    buttonRes = R.string.gemma_install_button_pause,
                    button = Button.PAUSE
                )
            }
            is GemmaInstallState.WaitingForNetwork -> {
                val percent = gemmaInstallPercent(state.downloadedBytes, state.totalBytes)
                GemmaInstallUiState(
                    titleRes = if (state.wifiOnly) {
                        R.string.gemma_install_title_waiting_wifi
                    } else {
                        R.string.gemma_install_title_waiting_network
                    },
                    detailRes = R.string.gemma_install_detail_percent_received,
                    detailArgs = listOf(percent),
                    progressKind = ProgressKind.DETERMINATE,
                    progressPercent = percent,
                    buttonRes = if (state.wifiOnly) R.string.gemma_install_button_mobile_data else null,
                    button = if (state.wifiOnly) Button.MOBILE_DATA else null
                )
            }
            is GemmaInstallState.Paused -> {
                val percent = gemmaInstallPercent(state.downloadedBytes, state.totalBytes)
                GemmaInstallUiState(
                    titleRes = R.string.gemma_install_title_paused,
                    detailRes = R.string.gemma_install_detail_percent,
                    detailArgs = listOf(percent),
                    progressKind = ProgressKind.DETERMINATE,
                    progressPercent = percent,
                    buttonRes = R.string.gemma_install_button_resume,
                    button = Button.RESUME
                )
            }
            is GemmaInstallState.Verifying -> GemmaInstallUiState(
                titleRes = R.string.gemma_install_title_verifying,
                progressKind = ProgressKind.INDETERMINATE
            )
            is GemmaInstallState.Installed -> GemmaInstallUiState(
                titleRes = R.string.gemma_install_title_installed
            )
            is GemmaInstallState.Failed -> when (state.reason) {
                GemmaInstallFailure.STORAGE_FULL -> GemmaInstallUiState(
                    titleRes = R.string.gemma_install_title_failed_storage,
                    detailRes = R.string.gemma_install_detail_failed_storage,
                    detailArgs = listOf(formatGemmaInstallBytes(GemmaModelFiles.REQUIRED_FREE_BYTES)),
                    buttonRes = R.string.gemma_install_button_retry,
                    button = Button.RETRY
                )
                GemmaInstallFailure.OFFLINE_MODE -> GemmaInstallUiState(
                    titleRes = R.string.gemma_install_title_failed_offline,
                    buttonRes = R.string.gemma_install_button_open_settings,
                    button = Button.OPEN_SETTINGS
                )
                GemmaInstallFailure.VERIFY_FAILED -> GemmaInstallUiState(
                    titleRes = R.string.gemma_install_title_failed_verify,
                    buttonRes = R.string.gemma_install_button_retry_fresh,
                    button = Button.RETRY
                )
                GemmaInstallFailure.NETWORK,
                GemmaInstallFailure.SERVER,
                GemmaInstallFailure.UNKNOWN -> GemmaInstallUiState(
                    titleRes = R.string.gemma_install_title_failed_generic,
                    detailRes = R.string.gemma_install_detail_failed_generic,
                    buttonRes = R.string.gemma_install_button_retry,
                    button = Button.RETRY
                )
            }
        }

        /** Whether the vault home's "새글 AI" card should be shown at all: hidden once installed, and on unsupported hardware. */
        fun shouldShowVaultCard(state: GemmaInstallState): Boolean =
            state !is GemmaInstallState.Installed && state !is GemmaInstallState.Unsupported
    }
}

/** GB with one decimal once the value reaches 1GB, MB (whole number) below that - e.g. 2_588_147_712L -> "2.6GB", 950_000_000L -> "950MB". */
internal fun formatGemmaInstallBytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    return if (gb >= 1.0) {
        String.format(Locale.US, "%.1fGB", gb)
    } else {
        val mb = Math.round(bytes / 1_000_000.0)
        "${mb}MB"
    }
}

internal fun gemmaInstallPercent(downloadedBytes: Long, totalBytes: Long): Int {
    if (totalBytes <= 0L) return 0
    return ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
}

/** Seconds rounded to the nearest whole minute, at least 1. */
internal fun gemmaInstallEtaMinutes(seconds: Long): Int =
    (((seconds + 30L) / 60L).toInt()).coerceAtLeast(1)

/** Whether starting an install should skip straight to [GemmaModelInstaller.start] instead of first asking about mobile data. */
internal fun gemmaInstallShouldStartImmediately(isUnmeteredNetwork: Boolean): Boolean = isUnmeteredNetwork
