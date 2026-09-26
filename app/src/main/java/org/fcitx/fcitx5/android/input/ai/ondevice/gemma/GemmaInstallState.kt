/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

/**
 * The on-device Gemma model's installation state, as [GemmaModelInstaller.state] reports it. This is
 * the single frozen contract the model-management, vault and onboarding screens are built on; do not
 * change its shape without updating every screen that reads it.
 */
sealed interface GemmaInstallState {
    /** [org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAiSupport.isSupported] is false on this device's ABI. */
    data object Unsupported : GemmaInstallState

    data object NotInstalled : GemmaInstallState

    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
        val allowMobileData: Boolean
    ) : GemmaInstallState

    /** A download was started but is waiting on its network constraint (Wi-Fi required, or connectivity lost mid-transfer). */
    data class WaitingForNetwork(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val wifiOnly: Boolean
    ) : GemmaInstallState

    /** The user paused the download; the partial file is kept and [GemmaModelInstaller.start] resumes it. */
    data class Paused(
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : GemmaInstallState

    data object Verifying : GemmaInstallState

    data object Installed : GemmaInstallState

    data class Failed(
        val reason: GemmaInstallFailure,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : GemmaInstallState
}

enum class GemmaInstallFailure { NETWORK, STORAGE_FULL, OFFLINE_MODE, VERIFY_FAILED, SERVER, UNKNOWN }
