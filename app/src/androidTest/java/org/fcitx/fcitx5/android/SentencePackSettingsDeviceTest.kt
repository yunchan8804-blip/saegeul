/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Intent
import android.os.SystemClock
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.behavior.PrivacyAiSettingsFragment
import org.junit.Test

class SentencePackSettingsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun privacySettingsCreatesSentencePackPreferenceAfterRecreation() {
        var activity: MainActivity? = null
        var recreatedActivity: MainActivity? = null
        var recreationMonitor: android.app.Instrumentation.ActivityMonitor? = null
        try {
            val launchedActivity = instrumentation.startActivitySync(privacySettingsIntent()) as MainActivity
            activity = launchedActivity
            assertSentencePackPreference(launchedActivity)

            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            recreationMonitor = monitor
            instrumentation.runOnMainSync { launchedActivity.recreate() }
            val recreated = monitor.waitForActivityWithTimeout(TIMEOUT_MILLIS) as? MainActivity
                ?: throw AssertionError("재생성된 MainActivity가 준비되지 않았습니다.")
            recreatedActivity = recreated
            assertSentencePackPreference(recreated)
        } finally {
            recreationMonitor?.let(instrumentation::removeMonitor)
            recreatedActivity?.let { recreated ->
                instrumentation.runOnMainSync { recreated.finish() }
            }
            activity?.let { initial ->
                instrumentation.runOnMainSync { initial.finish() }
            }
        }
    }

    private fun assertSentencePackPreference(activity: MainActivity) {
        waitForSentencePackPreference(activity)
    }

    private fun waitForSentencePackPreference(activity: MainActivity) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            var hasPreference = false
            instrumentation.runOnMainSync {
                val navHost = activity.supportFragmentManager
                    .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                val fragment = navHost?.childFragmentManager?.fragments
                    ?.filterIsInstance<PrivacyAiSettingsFragment>()
                    ?.firstOrNull()
                if (fragment?.view != null) {
                    hasPreference = findPreferenceByTitle(
                        requireNotNull(fragment.preferenceScreen),
                        activity.getString(R.string.sentence_packs_title)
                    ) != null
                }
            }
            if (hasPreference) return
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError("개인정보·AI 설정 Fragment 뷰 또는 문장팩 Preference가 생성되지 않았습니다.")
    }

    private fun findPreferenceByTitle(group: PreferenceGroup, title: String): Preference? {
        for (index in 0 until group.preferenceCount) {
            val preference = group.getPreference(index)
            if (preference.title == title) return preference
            (preference as? PreferenceGroup)?.let { nested ->
                findPreferenceByTitle(nested, title)?.let { return it }
            }
        }
        return null
    }

    private fun privacySettingsIntent() = Intent(
        InstrumentationRegistry.getInstrumentation().targetContext,
        MainActivity::class.java
    ).apply {
        action = Intent.ACTION_RUN
        putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, SettingsRoute.PrivacyAi)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_INTERVAL_MILLIS = 50L
    }
}
