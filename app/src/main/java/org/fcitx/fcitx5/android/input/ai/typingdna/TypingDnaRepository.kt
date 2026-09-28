/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.typingdna

import kotlinx.coroutines.CancellationException
import org.fcitx.fcitx5.android.input.ai.vault.PlainVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultFile
import org.fcitx.fcitx5.android.input.ai.vault.VaultFileVersion
import java.io.File

/**
 * On-Device Encrypted/Isolated Repository for the persistent [TypingDnaProfile].
 * Manages incremental accumulation, knowledge evolution, and atomic persistence.
 */
class TypingDnaRepository(
    storageFile: File,
    private val cipher: VaultCipher = PlainVaultCipher
) {

    private var cachedProfile: TypingDnaProfile? = null
    private var cachedVersion: VaultFileVersion? = null
    private var cachedSummary: DerivedCache<TypingDnaSummary>? = null
    private var cachedStats: DerivedCache<TypingDnaStats>? = null
    private val vaultFile = VaultFile(storageFile, cipher, VaultFile.aadFor(storageFile.name))

    @Synchronized
    fun load(forceReload: Boolean = false): TypingDnaProfile = vaultFile.withLock {
        loadLocked(forceReload)
    }

    @Synchronized
    fun save(profile: TypingDnaProfile) {
        vaultFile.withLock {
            saveLocked(profile)
        }
    }

    @Synchronized
    fun invalidateCache() {
        clearCaches()
    }

    @Synchronized
    fun updatePersona(newPersona: PersonaDna, analyzedSentenceCount: Int = 15) {
        vaultFile.withLock {
            updatePersonaLocked(newPersona, analyzedSentenceCount)
        }
    }

    @Synchronized
    fun clear() {
        vaultFile.withLock {
            runCatching {
                vaultFile.delete()
            }
            clearCaches()
        }
    }

    private fun loadLocked(forceReload: Boolean): TypingDnaProfile {
        val version = vaultFile.version()
        val cached = cachedProfile
        if (!forceReload && cached != null && cachedVersion == version) {
            return cached
        }

        invalidateDerivedCaches()
        val text = try {
            vaultFile.readTextAndMigrate()
        } catch (_: Exception) {
            clearCaches()
            return TypingDnaProfile.fromJson("")
        }
        val profile = TypingDnaProfile.fromJson(text ?: "")
        cachedProfile = profile
        cachedVersion = vaultFile.version()
        return profile
    }

    private fun saveLocked(profile: TypingDnaProfile) {
        try {
            vaultFile.writeText(profile.toJson())
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw TypingDnaPersistenceException(exception)
        }
        cachedProfile = profile
        cachedVersion = vaultFile.version()
        invalidateDerivedCaches()
    }

    private fun updatePersonaLocked(newPersona: PersonaDna, analyzedSentenceCount: Int) {
        val current = loadLocked(forceReload = false)
        val currentPersonas = current.personas.toMutableMap()
        val existing = currentPersonas[newPersona.category]

        val mergedEndings = (newPersona.habitualEndings + existing?.habitualEndings.orEmpty())
            .distinct()
            .take(15)

        val merged = if (existing == null) {
            newPersona.copy(habitualEndings = mergedEndings)
        } else {
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
        saveLocked(updatedProfile)
        if (analyzedSentenceCount > 0) {
            onSentencesAnalyzed?.invoke(
                analyzedSentenceCount,
                TypingDnaLevelCurve.levelFor(updatedProfile.totalAnalyzedSentences)
            )
        }
    }

    @Synchronized
    fun getSummary(forceReload: Boolean = false): TypingDnaSummary {
        val profile = load(forceReload = forceReload)
        val version = cachedVersion
        cachedSummary?.takeIf { it.profile === profile && it.version == version }?.let {
            return it.value
        }
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

        val summary = TypingDnaSummary(
            totalSentences = profile.totalAnalyzedSentences,
            endingsCount = endingsCount,
            bigramsCount = bigramsCount,
            phrasesCount = phrasesCount,
            personasSummary = dominantTones.joinToString(", ")
        )
        if (cachedProfile === profile && version != null) {
            cachedSummary = DerivedCache(profile, version, summary)
        }
        return summary
    }

    @Synchronized
    fun getStats(forceReload: Boolean = false): TypingDnaStats {
        val profile = load(forceReload = forceReload)
        val version = cachedVersion
        cachedStats?.takeIf { it.profile === profile && it.version == version }?.let {
            return it.value
        }
        var endingsCount = 0
        var bigramsCount = 0
        var phrasesCount = 0
        val allBigrams = mutableListOf<DynamicBigram>()
        val allEndings = mutableListOf<String>()

        var honorificCount = 0
        var informalCount = 0

        // Registry id (lowercased) -> weighted count, generalized from the old fixed
        // messenger/work/general buckets so newer personas get their own bucket instead of
        // being folded into "general".
        val categoryCounts = mutableMapOf<String, Int>()

        profile.personas.values.forEach { p ->
            endingsCount += p.habitualEndings.size
            bigramsCount += p.frequentBigrams.size
            phrasesCount += p.cannedPhrases.size
            allBigrams.addAll(p.frequentBigrams)
            allEndings.addAll(p.habitualEndings)

            if (p.dominantTone.equals("Honorific", ignoreCase = true)) {
                honorificCount += p.frequentBigrams.size + p.habitualEndings.size + 1
            } else {
                informalCount += p.frequentBigrams.size + p.habitualEndings.size + 1
            }

            val categoryKey = p.category.lowercase()
            categoryCounts[categoryKey] = (categoryCounts[categoryKey] ?: 0) + p.frequentBigrams.size + 1
        }

        val totalTone = (honorificCount + informalCount).coerceAtLeast(1)
        val honorificRatio = honorificCount.toFloat() / totalTone
        val informalRatio = informalCount.toFloat() / totalTone

        val messengerCount = categoryCounts[TypingDnaVault.CATEGORY_MESSENGER] ?: 0
        val workCount = categoryCounts[TypingDnaVault.CATEGORY_WORK] ?: 0
        val generalCount = categoryCounts[TypingDnaVault.CATEGORY_GENERAL] ?: 0
        val totalCat = categoryCounts.values.sum().coerceAtLeast(1)
        val messengerRatio = messengerCount.toFloat() / totalCat
        val workRatio = workCount.toFloat() / totalCat
        val generalRatio = generalCount.toFloat() / totalCat

        // Deduplicate and rank top bigrams
        val topBigrams = allBigrams
            .groupBy { Pair(it.prev, it.next) }
            .map { (pair, list) -> TopBigramStat(pair.first, pair.second, list.maxOf { it.weight }) }
            .sortedByDescending { it.weight }
            .take(6)

        // Endings carry no per-persona weight (unlike bigrams), so an ending seen across more
        // personas - i.e. mentioned more often overall - ranks higher; ties keep first-seen order.
        val topEndings = allEndings
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(6)
            .map { it.key }

        val totalSentences = profile.totalAnalyzedSentences
        val hasLearnedData = totalSentences > 0 || profile.personas.isNotEmpty()
        val levelProgress = TypingDnaLevelCurve.describe(totalSentences)
        val level = levelProgress.level
        val levelTitle = levelProgress.title
        val target = levelProgress.nextTargetSentences
        val progress = levelProgress.progressPercent

        val emptyTone = honorificCount == 0 && informalCount == 0
        val emptyCat = categoryCounts.values.all { it == 0 }

        val stats = TypingDnaStats(
            level = level,
            levelTitle = levelTitle,
            levelProgressPercent = progress,
            nextLevelTargetSentences = target,
            totalSentences = totalSentences,
            bigramsCount = bigramsCount,
            endingsCount = endingsCount,
            phrasesCount = phrasesCount,
            topBigrams = topBigrams,
            topEndings = topEndings,
            honorificRatio = if (emptyTone) 0f else honorificRatio,
            informalRatio = if (emptyTone) 0f else informalRatio,
            messengerSentencesRatio = if (emptyCat) 0f else messengerRatio,
            workSentencesRatio = if (emptyCat) 0f else workRatio,
            generalSentencesRatio = if (emptyCat) 0f else generalRatio,
            categoryCounts = categoryCounts.toMap(),
            lastUpdatedTimestamp = profile.updatedAt,
            privacyOnDevicePercent = 100,
            cloudBytesExported = 0,
            hasLearnedData = hasLearnedData
        )
        if (cachedProfile === profile && version != null) {
            cachedStats = DerivedCache(profile, version, stats)
        }
        return stats
    }

    private fun invalidateDerivedCaches() {
        cachedSummary = null
        cachedStats = null
    }

    private fun clearCaches() {
        cachedProfile = null
        cachedVersion = null
        invalidateDerivedCaches()
    }

    private data class DerivedCache<T>(
        val profile: TypingDnaProfile,
        val version: VaultFileVersion,
        val value: T
    )

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    companion object {
        /**
         * Set by the app on startup so this class stays free of Android
         * dependencies; carries analyzed sentence counts and the new level
         * to the habit tracker and level-up rewards.
         */
        var onSentencesAnalyzed: ((analyzed: Int, newLevel: Int) -> Unit)? = null
    }
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
    /** Habitual sentence endings ranked by how many personas share them, most common first (max 6). */
    val topEndings: List<String> = emptyList(),
    val honorificRatio: Float,
    val informalRatio: Float,
    val messengerSentencesRatio: Float,
    val workSentencesRatio: Float,
    val generalSentencesRatio: Float,
    val lastUpdatedTimestamp: Long,
    val privacyOnDevicePercent: Int = 100,
    val cloudBytesExported: Int = 0,
    val hasLearnedData: Boolean = totalSentences > 0,
    /** Registry persona id -> weighted analyzed-sentence count. Source of the ratio fields above. */
    val categoryCounts: Map<String, Int> = emptyMap()
)

data class TypingDnaSummary(
    val totalSentences: Int,
    val endingsCount: Int,
    val bigramsCount: Int,
    val phrasesCount: Int,
    val personasSummary: String
)
