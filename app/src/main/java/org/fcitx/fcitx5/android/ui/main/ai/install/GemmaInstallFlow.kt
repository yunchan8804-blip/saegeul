/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.install

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.ai.AiSettingsNavigator
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelInstaller

/**
 * The single entry point every surface uses to start (or resume) installing the on-device Gemma
 * model - see the design packet's install-flow section. Shows the one-time consent notice (unless
 * [org.fcitx.fcitx5.android.data.prefs.AppPrefs.Internal.gemmaInstallConsentShown] is already set,
 * e.g. by the onboarding page showing the same notice inline), then either starts immediately on
 * an unmetered network or asks about mobile data, requesting the notification permission once
 * along the way. [handle] dispatches a [GemmaInstallUiState.Button] tap from
 * [GemmaInstallStatusView] to the right one of these.
 */
object GemmaInstallFlow {

    fun start(activity: Activity) {
        showConsentIfNeeded(activity) { proceedAfterConsent(activity) }
    }

    fun pause(context: Context) {
        GemmaModelInstaller.pause(context)
    }

    /** WaitingForNetwork(wifiOnly)'s own button: the user just explicitly chose mobile data, so this skips the metered-network dialog. */
    fun switchToMobileData(context: Context) {
        GemmaModelInstaller.start(context, allowMobileData = true)
    }

    fun openOfflineModeSettings(context: Context) {
        AiSettingsNavigator.open(context)
    }

    fun handle(activity: Activity, button: GemmaInstallUiState.Button) {
        when (button) {
            GemmaInstallUiState.Button.INSTALL,
            GemmaInstallUiState.Button.RESUME,
            GemmaInstallUiState.Button.RETRY -> start(activity)
            GemmaInstallUiState.Button.PAUSE -> pause(activity)
            GemmaInstallUiState.Button.MOBILE_DATA -> switchToMobileData(activity)
            GemmaInstallUiState.Button.OPEN_SETTINGS -> openOfflineModeSettings(activity)
        }
    }

    private fun showConsentIfNeeded(activity: Activity, onProceed: () -> Unit) {
        val consentShown = AppPrefs.getInstance().internal.gemmaInstallConsentShown
        if (consentShown.getValue()) {
            onProceed()
            return
        }
        AlertDialog.Builder(activity)
            .setMessage(R.string.gemma_install_consent_message)
            .setNeutralButton(R.string.gemma_install_consent_terms_button) { _, _ ->
                openTermsLink(activity)
            }
            .setPositiveButton(R.string.gemma_install_consent_confirm) { _, _ ->
                consentShown.setValue(true)
                onProceed()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun proceedAfterConsent(activity: Activity) {
        requestNotificationPermissionIfNeeded(activity)
        if (gemmaInstallShouldStartImmediately(GemmaModelInstaller.isOnUnmeteredNetwork(activity))) {
            GemmaModelInstaller.start(activity, allowMobileData = false)
        } else {
            showMeteredNetworkDialog(activity)
        }
    }

    private fun showMeteredNetworkDialog(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.gemma_install_metered_title)
            .setMessage(R.string.gemma_install_metered_message)
            .setPositiveButton(R.string.gemma_install_metered_wifi_button) { _, _ ->
                GemmaModelInstaller.start(activity, allowMobileData = false)
            }
            .setNegativeButton(R.string.gemma_install_metered_mobile_button) { _, _ ->
                GemmaModelInstaller.start(activity, allowMobileData = true)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                activity, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        ActivityCompat.requestPermissions(
            activity, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS
        )
    }

    private fun openTermsLink(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(GEMMA_TERMS_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private const val GEMMA_TERMS_URL = "https://ai.google.dev/gemma/terms"
    private const val REQUEST_POST_NOTIFICATIONS = 4102
}
