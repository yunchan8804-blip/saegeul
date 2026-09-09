/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.ui.main.ai.DashboardSnapshot
import org.fcitx.fcitx5.android.ui.main.ai.DashboardSnapshotReader
import org.fcitx.fcitx5.android.ui.main.ai.TypingDnaCardSnapshot
import org.fcitx.fcitx5.android.ui.main.ai.TypingDnaCardSnapshotReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DashboardReadCostDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun readersHaveStableDataAcrossColdAndRepeatedIoReads() {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "dashboard-read-cost-io")
        }
        try {
            val measurements = executor.submit<List<ReadMeasurement>> {
                readMeasurements(instrumentation.targetContext)
            }.get(EXECUTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertEquals(READ_COUNT, measurements.size)
            val firstFingerprint = measurements.first().fingerprintSha256
            measurements.forEach { measurement ->
                assertValidSnapshot(measurement.home, measurement.dashboard)
                assertEquals(
                    "reader data changed during the same measurement condition",
                    firstFingerprint,
                    measurement.fingerprintSha256
                )
            }
            report(measurements, firstFingerprint)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun readMeasurements(targetContext: android.content.Context): List<ReadMeasurement> {
        val dashboardReader = DashboardSnapshotReader(targetContext)
        return List(READ_COUNT) {
            val totalStartedAt = System.nanoTime()

            val homeStartedAt = System.nanoTime()
            val home = TypingDnaCardSnapshotReader.read()
            val homeDurationMs = nanosToMillis(System.nanoTime() - homeStartedAt)

            val dashboardStartedAt = System.nanoTime()
            val dashboard = dashboardReader.read()
            val dashboardDurationMs = nanosToMillis(System.nanoTime() - dashboardStartedAt)

            ReadMeasurement(
                home = home,
                dashboard = dashboard,
                homeDurationMs = homeDurationMs,
                dashboardDurationMs = dashboardDurationMs,
                totalDurationMs = nanosToMillis(System.nanoTime() - totalStartedAt),
                fingerprintSha256 = fingerprint(home, dashboard)
            )
        }
    }

    private fun assertValidSnapshot(home: TypingDnaCardSnapshot, dashboard: DashboardSnapshot) {
        assertTrue(
            "home reader must return non-negative counters",
            home.stats.totalSentences >= 0 &&
                home.stats.bigramsCount >= 0 &&
                home.stats.endingsCount >= 0 &&
                home.stats.phrasesCount >= 0 &&
                home.ngramUnigrams >= 0 &&
                home.pendingSentences >= 0
        )
        assertTrue(
            "dashboard reader must return non-negative counters",
            dashboard.typingStats.totalSentences >= 0 &&
                dashboard.typingStats.bigramsCount >= 0 &&
                dashboard.typingStats.endingsCount >= 0 &&
                dashboard.typingStats.phrasesCount >= 0 &&
                dashboard.ngramUnigrams >= 0 &&
                dashboard.ngramBigrams >= 0 &&
                dashboard.pendingSentences >= 0 &&
                dashboard.vaultSentences >= 0 &&
                dashboard.enrichment.graphNodes >= 0 &&
                dashboard.enrichment.graphEdges >= 0 &&
                dashboard.enrichment.graphTopics >= 0
        )
        assertEquals(home.stats.totalSentences, dashboard.typingStats.totalSentences)
        assertEquals(home.stats.bigramsCount, dashboard.typingStats.bigramsCount)
        assertEquals(home.stats.endingsCount, dashboard.typingStats.endingsCount)
        assertEquals(home.stats.phrasesCount, dashboard.typingStats.phrasesCount)
    }

    private fun fingerprint(home: TypingDnaCardSnapshot, dashboard: DashboardSnapshot): String {
        val material = listOf(
            home.stats.totalSentences,
            home.stats.bigramsCount,
            home.stats.endingsCount,
            home.stats.phrasesCount,
            home.ngramUnigrams,
            home.pendingSentences,
            dashboard.typingStats.totalSentences,
            dashboard.typingStats.bigramsCount,
            dashboard.typingStats.endingsCount,
            dashboard.typingStats.phrasesCount,
            dashboard.ngramUnigrams,
            dashboard.ngramBigrams,
            dashboard.ngramLastLearnedMs,
            dashboard.pendingSentences,
            dashboard.vaultSentences,
            dashboard.enrichment.graphNodes,
            dashboard.enrichment.graphEdges,
            dashboard.enrichment.graphTopics,
            dashboard.enrichment.graphBuiltMs
        ).joinToString(separator = "|")
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun report(measurements: List<ReadMeasurement>, fingerprintSha256: String) {
        val homeDurationsMs = measurements.map { it.homeDurationMs }
        val dashboardDurationsMs = measurements.map { it.dashboardDurationMs }
        val totalDurationsMs = measurements.map { it.totalDurationMs }
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("measurementScope", "reader_io_not_ui_first_frame")
                putString("coldDefinition", "first_reader_call_in_this_test")
                putString("homeReadDurationsMs", homeDurationsMs.asNumberArray())
                putString("dashboardReadDurationsMs", dashboardDurationsMs.asNumberArray())
                putString("totalReadDurationsMs", totalDurationsMs.asNumberArray())
                putLong("coldHomeReadMs", homeDurationsMs.first())
                putLong("coldDashboardReadMs", dashboardDurationsMs.first())
                putLong("coldTotalReadMs", totalDurationsMs.first())
                putLong("homeReadMedianMs", homeDurationsMs.median())
                putLong("dashboardReadMedianMs", dashboardDurationsMs.median())
                putLong("totalReadMedianMs", totalDurationsMs.median())
                putString("dataFingerprintSha256", fingerprintSha256)
            }
        )
    }

    private fun nanosToMillis(durationNanos: Long): Long = durationNanos / NANOS_PER_MILLISECOND

    private fun List<Long>.asNumberArray(): String = joinToString(prefix = "[", postfix = "]")

    private fun List<Long>.median(): Long = sorted()[size / 2]

    private data class ReadMeasurement(
        val home: TypingDnaCardSnapshot,
        val dashboard: DashboardSnapshot,
        val homeDurationMs: Long,
        val dashboardDurationMs: Long,
        val totalDurationMs: Long,
        val fingerprintSha256: String
    )

    private companion object {
        const val READ_COUNT = 7
        const val EXECUTOR_TIMEOUT_SECONDS = 45L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
