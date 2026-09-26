/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.install

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaInstallFailure
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaInstallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [GemmaInstallUiState.from] (the copy/button every install surface shares), its
 * byte/eta formatting helpers, and the vault-card visibility and metered-network decision
 * functions the design packet calls out as separately testable.
 */
class GemmaInstallUiStateTest {

    @Test
    fun unsupportedHasNoDetailOrButton() {
        val state = GemmaInstallUiState.from(GemmaInstallState.Unsupported)
        assertEquals(R.string.gemma_install_title_unsupported, state.titleRes)
        assertNull(state.detailRes)
        assertNull(state.button)
        assertEquals(GemmaInstallUiState.ProgressKind.NONE, state.progressKind)
    }

    @Test
    fun notInstalledOffersTheInstallButtonWithFormattedSize() {
        val state = GemmaInstallUiState.from(GemmaInstallState.NotInstalled)
        assertEquals(R.string.gemma_install_title_not_installed, state.titleRes)
        assertEquals(R.string.gemma_install_detail_not_installed, state.detailRes)
        assertEquals(listOf("2.6GB"), state.detailArgs)
        assertEquals(R.string.gemma_install_button_install, state.buttonRes)
        assertEquals(GemmaInstallUiState.Button.INSTALL, state.button)
    }

    @Test
    fun downloadingShowsDeterminateProgressAndPauseButton() {
        val downloading = GemmaInstallState.Downloading(
            downloadedBytes = 1_200_000_000L,
            totalBytes = 2_588_147_712L,
            bytesPerSecond = 2_500_000L,
            allowMobileData = false
        )
        val state = GemmaInstallUiState.from(downloading)
        assertEquals(R.string.gemma_install_title_downloading, state.titleRes)
        assertEquals(R.string.gemma_install_detail_downloading_eta, state.detailRes)
        // remaining = 2_588_147_712 - 1_200_000_000 = 1_388_147_712 bytes / 2_500_000 B/s = 555s -> 9 min
        assertEquals(listOf("1.2GB", "2.6GB", 46, 9), state.detailArgs)
        assertEquals(GemmaInstallUiState.ProgressKind.DETERMINATE, state.progressKind)
        assertEquals(46, state.progressPercent)
        assertEquals(GemmaInstallUiState.Button.PAUSE, state.button)
    }

    @Test
    fun downloadingWithNoSpeedSampleShowsCalculatingInsteadOfEta() {
        val downloading = GemmaInstallState.Downloading(
            downloadedBytes = 0L,
            totalBytes = 2_588_147_712L,
            bytesPerSecond = 0L,
            allowMobileData = false
        )
        val state = GemmaInstallUiState.from(downloading)
        assertEquals(R.string.gemma_install_detail_downloading_calculating, state.detailRes)
        assertEquals(3, state.detailArgs.size)
    }

    @Test
    fun waitingForNetworkWifiOnlyOffersMobileDataButton() {
        val state = GemmaInstallUiState.from(
            GemmaInstallState.WaitingForNetwork(downloadedBytes = 500_000_000L, totalBytes = 2_588_147_712L, wifiOnly = true)
        )
        assertEquals(R.string.gemma_install_title_waiting_wifi, state.titleRes)
        assertEquals(GemmaInstallUiState.Button.MOBILE_DATA, state.button)
        assertEquals(R.string.gemma_install_button_mobile_data, state.buttonRes)
    }

    @Test
    fun waitingForNetworkNotWifiOnlyHasNoButton() {
        val state = GemmaInstallUiState.from(
            GemmaInstallState.WaitingForNetwork(downloadedBytes = 500_000_000L, totalBytes = 2_588_147_712L, wifiOnly = false)
        )
        assertEquals(R.string.gemma_install_title_waiting_network, state.titleRes)
        assertNull(state.button)
        assertNull(state.buttonRes)
    }

    @Test
    fun pausedOffersResumeButton() {
        val state = GemmaInstallUiState.from(
            GemmaInstallState.Paused(downloadedBytes = 950_000_000L, totalBytes = 2_588_147_712L)
        )
        assertEquals(R.string.gemma_install_title_paused, state.titleRes)
        assertEquals(GemmaInstallUiState.Button.RESUME, state.button)
    }

    @Test
    fun verifyingIsIndeterminateWithNoButton() {
        val state = GemmaInstallUiState.from(GemmaInstallState.Verifying)
        assertEquals(GemmaInstallUiState.ProgressKind.INDETERMINATE, state.progressKind)
        assertNull(state.button)
    }

    @Test
    fun installedHasNoDetailProgressOrButton() {
        val state = GemmaInstallUiState.from(GemmaInstallState.Installed)
        assertEquals(R.string.gemma_install_title_installed, state.titleRes)
        assertNull(state.detailRes)
        assertNull(state.button)
        assertEquals(GemmaInstallUiState.ProgressKind.NONE, state.progressKind)
    }

    @Test
    fun failedStorageFullShowsFreeSpaceNeededAndRetry() {
        val state = GemmaInstallUiState.from(
            GemmaInstallState.Failed(GemmaInstallFailure.STORAGE_FULL, downloadedBytes = 0L, totalBytes = 2_588_147_712L)
        )
        assertEquals(R.string.gemma_install_title_failed_storage, state.titleRes)
        assertEquals(listOf("2.7GB"), state.detailArgs)
        assertEquals(GemmaInstallUiState.Button.RETRY, state.button)
        assertEquals(R.string.gemma_install_button_retry, state.buttonRes)
    }

    @Test
    fun failedOfflineModeOpensSettingsInsteadOfRetrying() {
        val state = GemmaInstallUiState.from(
            GemmaInstallState.Failed(GemmaInstallFailure.OFFLINE_MODE, downloadedBytes = 0L, totalBytes = 2_588_147_712L)
        )
        assertEquals(R.string.gemma_install_title_failed_offline, state.titleRes)
        assertNull(state.detailRes)
        assertEquals(GemmaInstallUiState.Button.OPEN_SETTINGS, state.button)
        assertEquals(R.string.gemma_install_button_open_settings, state.buttonRes)
    }

    @Test
    fun failedVerifyShowsDownloadAgainWithNoDetail() {
        val state = GemmaInstallUiState.from(
            GemmaInstallState.Failed(GemmaInstallFailure.VERIFY_FAILED, downloadedBytes = 2_588_147_712L, totalBytes = 2_588_147_712L)
        )
        assertEquals(R.string.gemma_install_title_failed_verify, state.titleRes)
        assertNull(state.detailRes)
        assertEquals(R.string.gemma_install_button_retry_fresh, state.buttonRes)
        assertEquals(GemmaInstallUiState.Button.RETRY, state.button)
    }

    @Test
    fun failedNetworkServerAndUnknownShareTheGenericRetryCopy() {
        listOf(GemmaInstallFailure.NETWORK, GemmaInstallFailure.SERVER, GemmaInstallFailure.UNKNOWN).forEach { reason ->
            val state = GemmaInstallUiState.from(
                GemmaInstallState.Failed(reason, downloadedBytes = 100L, totalBytes = 2_588_147_712L)
            )
            assertEquals(R.string.gemma_install_title_failed_generic, state.titleRes)
            assertEquals(R.string.gemma_install_detail_failed_generic, state.detailRes)
            assertEquals(GemmaInstallUiState.Button.RETRY, state.button)
        }
    }

    @Test
    fun vaultCardHidesOnceInstalledOrUnsupported() {
        assertFalse(GemmaInstallUiState.shouldShowVaultCard(GemmaInstallState.Installed))
        assertFalse(GemmaInstallUiState.shouldShowVaultCard(GemmaInstallState.Unsupported))
        assertTrue(GemmaInstallUiState.shouldShowVaultCard(GemmaInstallState.NotInstalled))
        assertTrue(GemmaInstallUiState.shouldShowVaultCard(GemmaInstallState.Verifying))
    }

    @Test
    fun byteFormattingUsesOneDecimalGbAboveOneGbAndWholeMbBelow() {
        assertEquals("2.6GB", formatGemmaInstallBytes(2_588_147_712L))
        assertEquals("1.2GB", formatGemmaInstallBytes(1_200_000_000L))
        assertEquals("950MB", formatGemmaInstallBytes(950_000_000L))
        assertEquals("0MB", formatGemmaInstallBytes(0L))
    }

    @Test
    fun percentIsFlooredAndClamped() {
        assertEquals(46, gemmaInstallPercent(1_200_000_000L, 2_588_147_712L))
        assertEquals(100, gemmaInstallPercent(3_000_000_000L, 2_588_147_712L))
        assertEquals(0, gemmaInstallPercent(0L, 2_588_147_712L))
        assertEquals(0, gemmaInstallPercent(100L, 0L))
    }

    @Test
    fun etaMinutesRoundsToNearestAndNeverGoesBelowOne() {
        assertEquals(1, gemmaInstallEtaMinutes(10L))
        assertEquals(8, gemmaInstallEtaMinutes(8 * 60L))
        assertEquals(9, gemmaInstallEtaMinutes(8 * 60L + 31L))
    }

    @Test
    fun installStartsImmediatelyOnlyWhenUnmetered() {
        assertTrue(gemmaInstallShouldStartImmediately(isUnmeteredNetwork = true))
        assertFalse(gemmaInstallShouldStartImmediately(isUnmeteredNetwork = false))
    }
}
