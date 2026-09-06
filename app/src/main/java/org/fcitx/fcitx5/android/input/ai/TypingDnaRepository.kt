/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import java.io.File

/**
 * On-Device Encrypted/Isolated Repository for the persistent [TypingDnaProfile].
 * Manages incremental accumulation, knowledge evolution, and atomic persistence.
 */
class TypingDnaRepository(
    private val storageFile: File
) {

    private var cachedProfile: TypingDnaProfile? = null
    private var lastLoadedTimestamp: Long = 0L

    @Synchronized
    fun load(forceReload: Boolean = false): TypingDnaProfile {
        val currentMod = if (storageFile.exists()) storageFile.lastModified() else 0L
        if (!forceReload && cachedProfile != null && currentMod <= lastLoadedTimestamp) {
            return cachedProfile!!
        }

        if (!storageFile.exists()) {
            val fresh = TypingDnaProfile()
            cachedProfile = fresh
            lastLoadedTimestamp = 0L
            return fresh
        }

        val jsonStr = runCatching { storageFile.readText() }.getOrDefault("")
        val profile = TypingDnaProfile.fromJson(jsonStr)
        cachedProfile = profile
        lastLoadedTimestamp = currentMod
        return profile
    }

    @Synchronized
    fun save(profile: TypingDnaProfile) {
        cachedProfile = profile
        runCatching {
            storageFile.parentFile?.mkdirs()
            storageFile.writeText(profile.toJson())
            lastLoadedTimestamp = storageFile.lastModified()
        }
    }

    @Synchronized
    fun invalidateCache() {
        cachedProfile = null
        lastLoadedTimestamp = 0L
    }

    @Synchronized
    fun updatePersona(newPersona: PersonaDna, analyzedSentenceCount: Int = 15) {
        val current = load()
        val currentPersonas = current.personas.toMutableMap()
        val existing = currentPersonas[newPersona.category]

        val merged = if (existing == null) {
            newPersona
        } else {
            val mergedEndings = (existing.habitualEndings + newPersona.habitualEndings).distinct().take(15)
            val bigramMap = mutableMapOf<Pair<String, String>, Float>()
            existing.frequentBigrams.forEach { bigramMap[Pair(it.prev, it.next)] = it.weight }
            newPersona.frequentBigrams.forEach {
                val currentWeight = bigramMap[Pair(it.prev, it.next)] ?: 0.5f
                bigramMap[Pair(it.prev, it.next)] = ((currentWeight + it.weight) / 2.0f).coerceIn(0.5f, 1.0f)
            }
            val mergedBigrams = bigramMap.map { (pair, weight) ->
                DynamicBigram(pair.first, pair.second, weight)
            }.sortedByDescending { it.weight }.take(30)

            val mergedPhrases = (existing.cannedPhrases + newPersona.cannedPhrases).distinct().take(20)

            PersonaDna(
                category = newPersona.category,
                dominantTone = newPersona.dominantTone,
                habitualEndings = mergedEndings,
                frequentBigrams = mergedBigrams,
                cannedPhrases = mergedPhrases
            )
        }

        currentPersonas[newPersona.category] = merged
        val updatedProfile = current.copy(
            updatedAt = System.currentTimeMillis(),
            totalAnalyzedSentences = current.totalAnalyzedSentences + analyzedSentenceCount.coerceAtLeast(0),
            personas = currentPersonas
        )
        save(updatedProfile)
    }

    @Synchronized
    fun clear() {
        cachedProfile = TypingDnaProfile()
        lastLoadedTimestamp = 0L
        runCatching {
            if (storageFile.exists()) {
                storageFile.delete()
            }
        }
    }

    @Synchronized
    fun getSummary(forceReload: Boolean = false): TypingDnaSummary {
        val profile = load(forceReload = forceReload)
        var endingsCount = 0
        var bigramsCount = 0
        var phrasesCount = 0
        val dominantTones = mutableListOf<String>()

        profile.personas.values.forEach { p ->
            endingsCount += p.habitualEndings.size
            bigramsCount += p.frequentBigrams.size
            phrasesCount += p.cannedPhrases.size
            dominantTones.add("${p.category}:${p.dominantTone}")
        }

        return TypingDnaSummary(
            totalSentences = profile.totalAnalyzedSentences,
            endingsCount = endingsCount,
            bigramsCount = bigramsCount,
            phrasesCount = phrasesCount,
            personasSummary = dominantTones.joinToString(", ")
        )
    }

    @Synchronized
    fun getStats(forceReload: Boolean = false): TypingDnaStats {
        val profile = load(forceReload = forceReload)
        var endingsCount = 0
        var bigramsCount = 0
        var phrasesCount = 0
        val allBigrams = mutableListOf<DynamicBigram>()

        var honorificCount = 0
        var informalCount = 0

        var messengerCount = 0
        var workCount = 0
        var generalCount = 0

        profile.personas.values.forEach { p ->
            endingsCount += p.habitualEndings.size
            bigramsCount += p.frequentBigrams.size
            phrasesCount += p.cannedPhrases.size
            allBigrams.addAll(p.frequentBigrams)

            if (p.dominantTone.equals("Honorific", ignoreCase = true)) {
                honorificCount += p.frequentBigrams.size + p.habitualEndings.size + 1
            } else {
                informalCount += p.frequentBigrams.size + p.habitualEndings.size + 1
            }

            when (p.category.lowercase()) {
                "messenger" -> messengerCount += p.frequentBigrams.size + 1
                "work" -> workCount += p.frequentBigrams.size + 1
                else -> generalCount += p.frequentBigrams.size + 1
            }
        }

        val totalTone = (honorificCount + informalCount).coerceAtLeast(1)
        val honorificRatio = honorificCount.toFloat() / totalTone
        val informalRatio = informalCount.toFloat() / totalTone

        val totalCat = (messengerCount + workCount + generalCount).coerceAtLeast(1)
        val messengerRatio = messengerCount.toFloat() / totalCat
        val workRatio = workCount.toFloat() / totalCat
        val generalRatio = generalCount.toFloat() / totalCat

        // Deduplicate and rank top bigrams
        val topBigrams = allBigrams
            .groupBy { Pair(it.prev, it.next) }
            .map { (pair, list) -> TopBigramStat(pair.first, pair.second, list.maxOf { it.weight }) }
            .sortedByDescending { it.weight }
            .take(6)

        val totalSentences = profile.totalAnalyzedSentences
        val hasLearnedData = totalSentences > 0 || profile.personas.isNotEmpty()
        val (level, levelTitle, target, progress) = when {
            totalSentences <= 0 -> Tuple4(1, "새싹 학습자", 15, 0)
            totalSentences < 15 -> Tuple4(1, "새싹 학습자", 15, (totalSentences * 100 / 15).coerceIn(1, 99))
            totalSentences < 45 -> Tuple4(2, "성장하는 AI 파트너", 45, ((totalSentences - 15) * 100 / 30).coerceIn(0, 99))
            totalSentences < 100 -> Tuple4(3, "어휘 습관 형성", 100, ((totalSentences - 45) * 100 / 55).coerceIn(0, 99))
            totalSentences < 200 -> Tuple4(4, "정밀 문체 동기화", 200, ((totalSentences - 100) * 100 / 100).coerceIn(0, 99))
            else -> Tuple4(5, "언어 지문 마스터", totalSentences, 100)
        }

        val emptyTone = honorificCount == 0 && informalCount == 0
        val emptyCat = messengerCount == 0 && workCount == 0 && generalCount == 0

        return TypingDnaStats(
            level = level,
            levelTitle = levelTitle,
            levelProgressPercent = progress,
            nextLevelTargetSentences = target,
            totalSentences = totalSentences,
            bigramsCount = bigramsCount,
            endingsCount = endingsCount,
            phrasesCount = phrasesCount,
            topBigrams = topBigrams,
            honorificRatio = if (emptyTone) 0f else honorificRatio,
            informalRatio = if (emptyTone) 0f else informalRatio,
            messengerSentencesRatio = if (emptyCat) 0f else messengerRatio,
            workSentencesRatio = if (emptyCat) 0f else workRatio,
            generalSentencesRatio = if (emptyCat) 0f else generalRatio,
            lastUpdatedTimestamp = profile.updatedAt,
            privacyOnDevicePercent = 100,
            cloudBytesExported = 0,
            hasLearnedData = hasLearnedData
        )
    }

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}

data class TopBigramStat(
    val prev: String,
    val next: String,
    val weight: Float
)

data class TypingDnaStats(
    val level: Int,
    val levelTitle: String,
    val levelProgressPercent: Int,
    val nextLevelTargetSentences: Int,
    val totalSentences: Int,
    val bigramsCount: Int,
    val endingsCount: Int,
    val phrasesCount: Int,
    val topBigrams: List<TopBigramStat>,
    val honorificRatio: Float,
    val informalRatio: Float,
    val messengerSentencesRatio: Float,
    val workSentencesRatio: Float,
    val generalSentencesRatio: Float,
    val lastUpdatedTimestamp: Long,
    val privacyOnDevicePercent: Int = 100,
    val cloudBytesExported: Int = 0,
    val hasLearnedData: Boolean = totalSentences > 0
)

data class TypingDnaSummary(
    val totalSentences: Int,
    val endingsCount: Int,
    val bigramsCount: Int,
    val phrasesCount: Int,
    val personasSummary: String
)
