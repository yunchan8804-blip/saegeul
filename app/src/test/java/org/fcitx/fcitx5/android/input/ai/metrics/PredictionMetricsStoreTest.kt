/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

class PredictionMetricsStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun millisFor(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun `recording events accumulates accurate summary values`() {
        val now = millisFor(LocalDate.of(2026, 1, 1))
        val store = PredictionMetricsStore(clock = { now }, zone = ZoneOffset.UTC)

        store.recordShown(5)
        store.recordAccepted("personal_ngram", 3)
        store.recordAccepted("cloud_llm", 2)
        store.recordLearned(2, 4)

        val summary = store.summary()
        assertEquals(5, summary.totalShown)
        assertEquals(2, summary.totalAccepted)
        assertEquals(0.4f, summary.acceptRate, 0.0001f)
        assertEquals(0.5f, summary.personalShare, 0.0001f)
        assertEquals(5, summary.keystrokesSaved)
        assertEquals(0, summary.typoCorrected)
        assertEquals(2, summary.learnedSentences)
        assertEquals(4, summary.learnedWords)
        assertEquals(1, summary.activeDays)
        assertEquals("2026-01-01", summary.firstDay)
        assertEquals("2026-01-01", summary.lastDay)
    }

    @Test
    fun `recent fills gap days with zero between two recorded days`() {
        var now = millisFor(LocalDate.of(2026, 1, 1))
        val store = PredictionMetricsStore(clock = { now }, zone = ZoneOffset.UTC)
        store.recordShown(2)

        now = millisFor(LocalDate.of(2026, 1, 4))
        store.recordShown(7)

        val recent = store.summary().recent
        assertEquals(30, recent.size)
        val byDay = recent.associateBy { it.day }
        assertEquals(2, byDay.getValue("2026-01-01").shown)
        assertEquals(0, byDay.getValue("2026-01-02").shown)
        assertEquals(0, byDay.getValue("2026-01-03").shown)
        assertEquals(7, byDay.getValue("2026-01-04").shown)
        assertEquals("2026-01-04", recent.last().day)
    }

    @Test
    fun `source classification marks personal and typo buckets correctly`() {
        val now = millisFor(LocalDate.of(2026, 2, 1))
        val store = PredictionMetricsStore(clock = { now }, zone = ZoneOffset.UTC)

        store.recordAccepted("personal_ngram", 1) // personal only
        store.recordAccepted("typo_personal", 1) // personal + typo
        store.recordAccepted("personalized_style", 1) // personal only
        store.recordAccepted("rag_personal", 1) // personal only
        store.recordAccepted("typo_keyboard", 1) // typo only
        store.recordAccepted("cloud_llm", 1) // neither

        val summary = store.summary()
        assertEquals(6, summary.totalAccepted)
        val today = summary.recent.last()
        assertEquals(4, today.acceptedPersonal)
        assertEquals(2, today.typoCorrected)
    }

    @Test
    fun `negative saved keystrokes are ignored`() {
        val now = millisFor(LocalDate.of(2026, 3, 1))
        val store = PredictionMetricsStore(clock = { now }, zone = ZoneOffset.UTC)

        store.recordAccepted("cloud_llm", -5)
        store.recordAccepted("cloud_llm", 4)

        assertEquals(4, store.summary().keystrokesSaved)
    }

    @Test
    fun `days beyond maxDays are archived while totals and first day are preserved`() {
        var now = millisFor(LocalDate.of(2026, 4, 1))
        val store = PredictionMetricsStore(clock = { now }, zone = ZoneOffset.UTC, maxDays = 3)

        repeat(5) { index ->
            now = millisFor(LocalDate.of(2026, 4, 1).plusDays(index.toLong()))
            store.recordShown(1)
            store.recordAccepted("cloud_llm", 1)
        }

        val summary = store.summary()
        assertEquals(listOf("2026-04-03", "2026-04-04", "2026-04-05"), summary.recent.map { it.day })
        assertEquals(5, summary.totalShown)
        assertEquals(5, summary.totalAccepted)
        assertEquals(5, summary.activeDays)
        assertEquals("2026-04-01", summary.firstDay)
        assertEquals("2026-04-05", summary.lastDay)
    }

    @Test
    fun `save then reload preserves the same summary`() {
        val file = File(tempFolder.newFolder(), "metrics.json")
        var now = millisFor(LocalDate.of(2026, 5, 1))
        val store = PredictionMetricsStore(storeFile = file, clock = { now }, zone = ZoneOffset.UTC, maxDays = 3)

        repeat(5) { index ->
            now = millisFor(LocalDate.of(2026, 5, 1).plusDays(index.toLong()))
            store.recordShown(2)
            store.recordAccepted("personal_ngram", 1)
            store.recordLearned(1, 1)
        }
        store.save()

        val reloaded = PredictionMetricsStore(storeFile = file, clock = { now }, zone = ZoneOffset.UTC, maxDays = 3)
        assertEquals(store.summary(), reloaded.summary())
    }

    @Test
    fun `clear resets state and deletes the backing file`() {
        val file = File(tempFolder.newFolder(), "metrics.json")
        val now = millisFor(LocalDate.of(2026, 6, 1))
        val store = PredictionMetricsStore(storeFile = file, clock = { now }, zone = ZoneOffset.UTC)

        store.recordShown(3)
        store.save()
        assertTrue(file.exists())

        store.clear()

        assertFalse(file.exists())
        val summary = store.summary()
        assertEquals(0, summary.totalShown)
        assertEquals(0, summary.activeDays)
        assertNull(summary.firstDay)
        assertNull(summary.lastDay)
    }

    @Test
    fun `corrupted backing file starts from empty state`() {
        val file = tempFolder.newFile("corrupt.json")
        file.writeText("{not valid json", Charsets.UTF_8)
        val now = millisFor(LocalDate.of(2026, 7, 1))

        val store = PredictionMetricsStore(storeFile = file, clock = { now }, zone = ZoneOffset.UTC)
        val summary = store.summary()

        assertEquals(0, summary.totalShown)
        assertEquals(0, summary.totalAccepted)
        assertEquals(0, summary.activeDays)
        assertNull(summary.firstDay)
    }
}
