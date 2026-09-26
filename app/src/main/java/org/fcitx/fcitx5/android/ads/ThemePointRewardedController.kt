/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ads

import android.app.Activity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.data.points.PointLedger
import org.fcitx.fcitx5.android.data.points.PointPricing
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opt-in rewarded ad that grants app points: one completed ad, one point.
 * Fails closed on the AVENUE gate and frequency limits; points are granted
 * only after the user actually earns the reward.
 */
class ThemePointRewardedController(
    private val activity: Activity,
    private val onPointEarned: (Int) -> Unit
) {
    private val frequencyStore = AvenueFrequencyStore(activity.applicationContext)
    private val ledger = PointLedger(activity.applicationContext)
    private var rewardedAd: RewardedAd? = null
    private val loading = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)

    fun prepare() {
        if (!gateAllows()) return
        ensureInitialized { loadAd() }
    }

    fun isEligible(): Boolean = gateAllows()

    fun showIfEligible() {
        if (!gateAllows()) return
        val ad = rewardedAd
        if (ad == null) {
            prepare()
            return
        }
        configureCallbacks(ad)
        ad.show(activity) { rewardItem ->
            val granted = ledger.earn(
                PointPricing.POINTS_PER_AD,
                LocalAvenueCatalog.THEME_POINT_EARN,
                System.currentTimeMillis(),
                "rewarded:${rewardItem.type}"
            )
            if (granted) onPointEarned(PointPricing.POINTS_PER_AD)
        }
        rewardedAd = null
    }

    private fun configureCallbacks(ad: RewardedAd) {
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                frequencyStore.recordExposure(
                    LocalAvenueCatalog.THEME_POINT_EARN,
                    System.currentTimeMillis()
                )
            }

            override fun onAdDismissedFullScreenContent() {
                prepare()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Timber.w("Theme point rewarded failed to show: ${adError.message}")
                prepare()
            }
        }
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
            Timber.w(it, "MobileAds.initialize failed for rewarded")
        }
    }

    private fun loadAd() {
        if (!loading.compareAndSet(false, true)) return
        val request = AdRequest.Builder().build()
        RewardedAd.load(
            activity,
            BuildConfig.ADMOB_REWARDED_UNIT_ID,
            request,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    loading.set(false)
                    rewardedAd = ad
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    loading.set(false)
                    rewardedAd = null
                    Timber.w("Theme point rewarded failed to load: ${adError.message}")
                }
            }
        )
    }

    private fun gateAllows(): Boolean {
        val now = System.currentTimeMillis()
        return TypingDnaAdGate.shouldShowThemePointEarn(
            offlineMode = isOffline(),
            frequency = frequencyStore.state(LocalAvenueCatalog.THEME_POINT_EARN),
            nowEpochMs = now
        )
    }

    private fun isOffline(): Boolean {
        return runCatching {
            AppPrefs.getInstance().advanced.offlineMode.getValue()
        }.getOrDefault(false)
    }
}
