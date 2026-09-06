/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.ads

import android.app.Activity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loads and shows a full-screen interstitial after Typing DNA sync.
 * Initialization stays in the dashboard Activity, not the IME.
 */
class TypingDnaInterstitialController(
    private val activity: Activity
) {
    private var interstitialAd: InterstitialAd? = null
    private val loading = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)
    private val showWhenLoaded = AtomicBoolean(false)

    fun prepare() {
        if (!TypingDnaAdGate.shouldShowInterstitial(offlineMode = isOffline())) return
        ensureInitialized { loadAd() }
    }

    fun showAfterAction() {
        if (!TypingDnaAdGate.shouldShowInterstitial(offlineMode = isOffline())) return
        val ad = interstitialAd
        if (ad == null) {
            showWhenLoaded.set(true)
            prepare()
            return
        }
        present(ad)
    }

    private fun ensureInitialized(onReady: () -> Unit) {
        if (initialized.get()) {
            onReady()
            return
        }
        runCatching {
            MobileAds.initialize(activity) {
                initialized.set(true)
                onReady()
            }
        }.onFailure {
            Timber.w(it, "MobileAds.initialize failed")
        }
    }

    private fun loadAd() {
        if (!loading.compareAndSet(false, true)) return
        val request = AdRequest.Builder().build()
        InterstitialAd.load(
            activity,
            BuildConfig.ADMOB_INTERSTITIAL_UNIT_ID,
            request,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    loading.set(false)
                    if (showWhenLoaded.compareAndSet(true, false) && canShow()) {
                        present(ad)
                    } else {
                        interstitialAd = ad
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    loading.set(false)
                    interstitialAd = null
                    Timber.w("Typing DNA interstitial failed to load: ${adError.message}")
                }
            }
        )
    }

    private fun present(ad: InterstitialAd) {
        interstitialAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                prepare()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Timber.w("Typing DNA interstitial failed to show: ${adError.message}")
                prepare()
            }
        }
        ad.show(activity)
    }

    private fun canShow(): Boolean {
        return !activity.isFinishing && !activity.isDestroyed
    }

    private fun isOffline(): Boolean {
        return runCatching {
            AppPrefs.getInstance().advanced.offlineMode.getValue()
        }.getOrDefault(false)
    }
}
