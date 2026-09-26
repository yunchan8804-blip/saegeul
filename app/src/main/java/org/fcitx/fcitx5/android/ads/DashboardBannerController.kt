/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ads

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Renders an AVENUE-gated banner at the bottom of an app surface
 * (the Typing DNA dashboard), never the IME.
 * Fails closed: without a passing gate the container stays hidden.
 */
class DashboardBannerController(
    private val activity: Activity,
    private val container: ViewGroup
) : Application.ActivityLifecycleCallbacks {

    interface VisibilityListener {
        fun onBannerVisibilityChanged(visible: Boolean)
    }

    private var adView: AdView? = null
    private var visibilityListener: VisibilityListener? = null
    private val registered = AtomicBoolean(false)
    private val frequencyStore = AvenueFrequencyStore(activity.applicationContext)

    fun attach(listener: VisibilityListener) {
        visibilityListener = listener
        container.visibility = View.GONE
        if (registered.compareAndSet(false, true)) {
            activity.application.registerActivityLifecycleCallbacks(this)
        }
        if (
            !TypingDnaAdGate.shouldShowBanner(
                offlineMode = isOffline(),
                frequency = frequencyStore.state(LocalAvenueCatalog.TYPING_DNA_DASHBOARD_BANNER)
            )
        ) {
            return
        }
        ensureInitialized { loadAd() }
    }

    private fun ensureInitialized(onReady: () -> Unit) {
        runCatching {
            MobileAds.initialize(activity) { onReady() }
        }.onFailure {
            Timber.w(it, "MobileAds.initialize failed for banner")
        }
    }

    private fun loadAd() {
        val view = adView ?: AdView(activity).also {
            it.setAdSize(AdSize.BANNER)
            it.adUnitId = BuildConfig.ADMOB_BANNER_UNIT_ID
            it.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            it.adListener = bannerListener
            adView = it
        }
        view.loadAd(AdRequest.Builder().build())
    }

    private val bannerListener = object : AdListener() {
        override fun onAdLoaded() {
            val view = adView ?: return
            if (!canShow()) {
                destroyAd()
                return
            }
            (view.parent as? ViewGroup)?.removeView(view)
            container.removeAllViews()
            container.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            container.visibility = View.VISIBLE
            visibilityListener?.onBannerVisibilityChanged(true)
        }

        override fun onAdFailedToLoad(adError: LoadAdError) {
            hide()
            Timber.w("Dashboard banner failed to load: ${adError.message}")
        }
    }

    private fun hide() {
        destroyAd()
        container.visibility = View.GONE
        visibilityListener?.onBannerVisibilityChanged(false)
    }

    private fun destroyAd() {
        adView?.adListener = object : AdListener() {}
        adView?.destroy()
        adView = null
    }

    private fun canShow(): Boolean {
        return !activity.isFinishing && !activity.isDestroyed
    }

    private fun isOffline(): Boolean {
        return runCatching {
            AppPrefs.getInstance().advanced.offlineMode.getValue()
        }.getOrDefault(false)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity === this.activity) adView?.resume()
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity === this.activity) adView?.pause()
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity === this.activity && registered.compareAndSet(true, false)) {
            activity.application.unregisterActivityLifecycleCallbacks(this)
            destroyAd()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
