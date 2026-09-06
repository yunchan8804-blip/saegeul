/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.metrics

import org.fcitx.fcitx5.android.input.ai.vault.PlainVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultFile
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TreeMap

/**
 * Accumulates day-bucketed prediction quality metrics (impressions, acceptances, personal-data
 * hits, keystrokes saved, typo corrections, and daily learning volume) for the "내 언어 금고"
 * dashboard. Days older than [maxDays] are folded into a running [Archived] total so long-term
 * totals never shrink even though per-day detail is only kept for a bounded window.
 */
class PredictionMetricsStore(
    private val storeFile: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val maxDays: Int = 400,
    private val cipher: VaultCipher = PlainVaultCipher
) {

    data class DayStat(
        val day: String,
        val shown: Int,
        val accepted: Int,
        val acceptedPersonal: Int,
        val keystrokesSaved: Int,
        val typoCorrected: Int,
        val learnedSentences: Int,
        val learnedWords: Int
    )

    data class Summary(
        val totalShown: Int,
        val totalAccepted: Int,
        val acceptRate: Float,
        val personalShare: Float,
        val keystrokesSaved: Int,
        val typoCorrected: Int,
        val learnedSentences: Int,
        val learnedWords: Int,
        val activeDays: Int,
        val firstDay: String?,
        val lastDay: String?,
        val recent: List<DayStat>
    )

    private class MutableDay(
        var shown: Int = 0,
        var accepted: Int = 0,
        var acceptedPersonal: Int = 0,
        var keystrokesSaved: Int = 0,
        var typoCorrected: Int = 0,
        var learnedSentences: Int = 0,
        var learnedWords: Int = 0
    )

    private class Archived(
        var shown: Int = 0,
        var accepted: Int = 0,
        var acceptedPersonal: Int = 0,
        var keystrokesSaved: Int = 0,
        var typoCorrected: Int = 0,
        var learnedSentences: Int = 0,
        var learnedWords: Int = 0,
        var dayCount: Int = 0,
        var firstDay: String? = null
    )

    private val days = TreeMap<String, MutableDay>()
    private var archived = Archived()
    private val vaultFile: VaultFile? = storeFile?.let { VaultFile(it, cipher, VaultFile.aadFor(it.name)) }

    init {
        load()
    }

    @Synchronized
    fun recordShown(count: Int) {
        getOrCreateToday().shown += count.coerceAtLeast(0)
    }

    @Synchronized
    fun recordAccepted(source: String, savedKeystrokes: Int) {
        val today = getOrCreateToday()
        today.accepted += 1
        if (source in PERSONAL_SOURCES) today.acceptedPersonal += 1
        if (source in TYPO_SOURCES) today.typoCorrected += 1
        if (savedKeystrokes >= 0) today.keystrokesSaved += savedKeystrokes
    }

    @Synchronized
    fun recordLearned(sentences: Int, newWords: Int) {
        val today = getOrCreateToday()
        today.learnedSentences += sentences.coerceAtLeast(0)
        today.learnedWords += newWords.coerceAtLeast(0)
    }

    @Synchronized
    fun summary(): Summary {
        var totalShown = archived.shown
        var totalAccepted = archived.accepted
        var totalAcceptedPersonal = archived.acceptedPersonal
        var totalKeystrokesSaved = archived.keystrokesSaved
        var totalTypoCorrected = archived.typoCorrected
        var totalLearnedSentences = archived.learnedSentences
        var totalLearnedWords = archived.learnedWords
        days.values.forEach { day ->
            totalShown += day.shown
            totalAccepted += day.accepted
            totalAcceptedPersonal += day.acceptedPersonal
            totalKeystrokesSaved += day.keystrokesSaved
            totalTypoCorrected += day.typoCorrected
            totalLearnedSentences += day.learnedSentences
            totalLearnedWords += day.learnedWords
        }

        val activeDays = archived.dayCount + days.size
        val firstDay = archived.firstDay ?: days.keys.firstOrNull()
        val lastDay = days.keys.lastOrNull()

        return Summary(
            totalShown = totalShown,
            totalAccepted = totalAccepted,
            acceptRate = if (totalShown > 0) totalAccepted.toFloat() / totalShown else 0f,
            personalShare = if (totalAccepted > 0) totalAcceptedPersonal.toFloat() / totalAccepted else 0f,
            keystrokesSaved = totalKeystrokesSaved,
            typoCorrected = totalTypoCorrected,
            learnedSentences = totalLearnedSentences,
            learnedWords = totalLearnedWords,
            activeDays = activeDays,
            firstDay = firstDay,
            lastDay = lastDay,
            recent = buildRecent()
        )
    }

    private fun buildRecent(): List<DayStat> {
        val windowLength = minOf(RECENT_WINDOW_DAYS, maxDays).coerceAtLeast(0)
        if (windowLength == 0) return emptyList()
        val today = todayLocalDate()
        return (windowLength - 1 downTo 0).map { offset ->
            val key = today.minusDays(offset.toLong()).format(DATE_FORMATTER)
            val day = days[key]
            DayStat(
                day = key,
                shown = day?.shown ?: 0,
                accepted = day?.accepted ?: 0,
                acceptedPersonal = day?.acceptedPersonal ?: 0,
                keystrokesSaved = day?.keystrokesSaved ?: 0,
                typoCorrected = day?.typoCorrected ?: 0,
                learnedSentences = day?.learnedSentences ?: 0,
                learnedWords = day?.learnedWords ?: 0
            )
        }
    }

    @Synchronized
    fun save() {
        val vf = vaultFile ?: return
        val daysJson = JSONObject()
        days.forEach { (day, stat) ->
            daysJson.put(
                day,
                JSONObject().apply {
                    put("shown", stat.shown)
                    put("accepted", stat.accepted)
                    put("acceptedPersonal", stat.acceptedPersonal)
                    put("keystrokesSaved", stat.keystrokesSaved)
                    put("typoCorrected", stat.typoCorrected)
                    put("learnedSentences", stat.learnedSentences)
                    put("learnedWords", stat.learnedWords)
                }
            )
        }
        val archivedJson = JSONObject().apply {
            put("shown", archived.shown)
            put("accepted", archived.accepted)
            put("acceptedPersonal", archived.acceptedPersonal)
            put("keystrokesSaved", archived.keystrokesSaved)
            put("typoCorrected", archived.typoCorrected)
            put("learnedSentences", archived.learnedSentences)
            put("learnedWords", archived.learnedWords)
            put("dayCount", archived.dayCount)
            archived.firstDay?.let { put("firstDay", it) }
        }
        val root = JSONObject().apply {
            put("version", FORMAT_VERSION)
            put("days", daysJson)
            put("archived", archivedJson)
        }

        vf.writeText(root.toString())
    }

    private fun load() {
        val vf = vaultFile ?: return
        if (!vf.exists()) return
        try {
            vf.migrateIfLegacy()
            val content = vf.readText() ?: return
            if (content.isBlank()) return
            val root = JSONObject(content)
            val daysJson = root.optJSONObject("days") ?: JSONObject()
            val loadedDays = TreeMap<String, MutableDay>()
            daysJson.keys().forEach { key ->
                val obj = daysJson.getJSONObject(key)
                loadedDays[key] = MutableDay(
                    shown = obj.optInt("shown", 0),
                    accepted = obj.optInt("accepted", 0),
                    acceptedPersonal = obj.optInt("acceptedPersonal", 0),
                    keystrokesSaved = obj.optInt("keystrokesSaved", 0),
                    typoCorrected = obj.optInt("typoCorrected", 0),
                    learnedSentences = obj.optInt("learnedSentences", 0),
                    learnedWords = obj.optInt("learnedWords", 0)
                )
            }
            val archivedJson = root.optJSONObject("archived")
            val loadedArchived = Archived()
            if (archivedJson != null) {
                loadedArchived.shown = archivedJson.optInt("shown", 0)
                loadedArchived.accepted = archivedJson.optInt("accepted", 0)
                loadedArchived.acceptedPersonal = archivedJson.optInt("acceptedPersonal", 0)
                loadedArchived.keystrokesSaved = archivedJson.optInt("keystrokesSaved", 0)
                loadedArchived.typoCorrected = archivedJson.optInt("typoCorrected", 0)
                loadedArchived.learnedSentences = archivedJson.optInt("learnedSentences", 0)
                loadedArchived.learnedWords = archivedJson.optInt("learnedWords", 0)
                loadedArchived.dayCount = archivedJson.optInt("dayCount", 0)
                loadedArchived.firstDay = if (archivedJson.has("firstDay") && !archivedJson.isNull("firstDay")) {
                    archivedJson.getString("firstDay")
                } else {
                    null
                }
            }
            days.clear()
            days.putAll(loadedDays)
            archived = loadedArchived
        } catch (_: Throwable) {
            days.clear()
            archived = Archived()
        }
    }

    @Synchronized
    fun clear() {
        days.clear()
        archived = Archived()
        vaultFile?.delete()
    }

    private fun getOrCreateToday(): MutableDay {
        val key = todayKey()
        val existing = days[key]
        if (existing != null) return existing
        val created = MutableDay()
        days[key] = created
        evictIfNeeded()
        return created
    }

    private fun evictIfNeeded() {
        while (days.size > maxDays) {
            val oldestKey = days.firstKey()
            val oldest = days.remove(oldestKey) ?: break
            archived.shown += oldest.shown
            archived.accepted += oldest.accepted
            archived.acceptedPersonal += oldest.acceptedPersonal
            archived.keystrokesSaved += oldest.keystrokesSaved
            archived.typoCorrected += oldest.typoCorrected
            archived.learnedSentences += oldest.learnedSentences
            archived.learnedWords += oldest.learnedWords
            archived.dayCount += 1
            val currentFirst = archived.firstDay
            if (currentFirst == null || oldestKey < currentFirst) {
                archived.firstDay = oldestKey
            }
        }
    }

    private fun todayKey(): String = todayLocalDate().format(DATE_FORMATTER)

    private fun todayLocalDate(): LocalDate =
        Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()

    companion object {
        val PERSONAL_SOURCES = setOf("personal_ngram", "typo_personal", "personalized_style_user")
        val TYPO_SOURCES = setOf(
            "typo_personal",
            "typo_keyboard",
            "typo_correction",
            "typo_word_correction",
            "typo_sentence_correction"
        )

        private const val FORMAT_VERSION = 1
        private const val RECENT_WINDOW_DAYS = 30
        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
