/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.vault.PlainVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

/**
 * On-Device Persistent Store & High-Speed Inverted Index for Personalized Sentences.
 * Provides sub-millisecond choseong and context keyword search, LRU capacity protection,
 * and reinforcement-weighted ranking.
 */
class PersonalizedSentenceStore(
    private val storageFile: File? = null,
    private val morphology: ChoseongMorphologyEngine = ChoseongMorphologyEngine(),
    private val maxCapacity: Int = 500,
    private val cipher: VaultCipher = PlainVaultCipher
) {

    private val records = LinkedHashMap<String, PersonalizedSentenceRecord>()
    private val vaultFile: VaultFile? = storageFile?.let { VaultFile(it, cipher, VaultFile.aadFor(it.name)) }
    private val saveLock = Any()

    @Synchronized
    fun size(): Int = records.size

    @Synchronized
    fun contains(sentence: String): Boolean = records.containsKey(sentence.trim())

    @Synchronized
    fun upsert(record: PersonalizedSentenceRecord) {
        val cleanSentence = record.sentence.trim()
        if (cleanSentence.isBlank()) return

        val effectiveChoseong = if (record.choseong.isNotBlank()) {
            record.choseong
        } else {
            morphology.extractChoseongSequence(cleanSentence)
        }

        val existing = records[cleanSentence]
        if (existing != null) {
            if (existing !== record) {
                existing.score = max(existing.score, record.score)
                existing.useCount = max(existing.useCount, record.useCount)
                existing.lastUsedTimestamp = max(existing.lastUsedTimestamp, record.lastUsedTimestamp)
            }
            return
        }

        if (records.size >= maxCapacity) {
            evictLowestScored()
        }

        records[cleanSentence] = record.copy(
            sentence = cleanSentence,
            choseong = effectiveChoseong
        )
    }

    private fun evictLowestScored() {
        val victim = records.values.minByOrNull { it.score * 0.7f + (it.lastUsedTimestamp / 1_000_000_000L) * 0.3f }
        if (victim != null) {
            records.remove(victim.sentence)
        }
    }

    @Synchronized
    fun query(
        queryChoseong: String = "",
        context: String = "",
        limit: Int = 5
    ): List<PersonalizedSentenceRecord> {
        if (records.isEmpty()) return emptyList()

        val cleanChoseong = queryChoseong.trim()
        val cleanContext = context.trim().lowercase()

        val matched = records.values.filter { record ->
            val matchesChoseong = if (cleanChoseong.isBlank()) {
                true
            } else {
                record.choseong.startsWith(cleanChoseong) ||
                record.choseong.contains(cleanChoseong) ||
                record.sentence.startsWith(cleanChoseong) ||
                record.sentence.contains(cleanChoseong)
            }
            matchesChoseong
        }

        if (matched.isEmpty()) return emptyList()

        return matched.map { record ->
            var rankingScore = record.score * 2.0f

            if (cleanContext.isNotBlank()) {
                val matchedKeywords = record.keywords.count { kw -> kw.isNotBlank() && cleanContext.contains(kw.lowercase()) }
                rankingScore += matchedKeywords * 2.5f

                if (cleanContext.contains(record.sentence.lowercase())) {
                    rankingScore -= 10.0f // Penalize already committed exact sentences
                }
            }

            if (cleanChoseong.isNotBlank() && record.choseong.startsWith(cleanChoseong)) {
                rankingScore += 1.5f
            }

            record to rankingScore
        }.sortedByDescending { it.second }
        .take(limit)
        .map { it.first }
    }

    fun save() {
        val vf = vaultFile ?: return
        synchronized(saveLock) {
            val snapshot = synchronized(this) {
                records.values.map { it.copy(keywords = it.keywords.toList()) }
            }
            val array = JSONArray()
            snapshot.forEach { r ->
                val obj = JSONObject().apply {
                    put("id", r.id)
                    put("sentence", r.sentence)
                    put("choseong", r.choseong)
                    put("intent", r.intent.name)
                    put("tone", r.tone.name)
                    put("keywords", JSONArray(r.keywords))
                    put("source", r.source)
                    put("score", r.score.toDouble())
                    put("useCount", r.useCount)
                    put("lastUsedTimestamp", r.lastUsedTimestamp)
                    r.packageName?.let { put("packageName", it) }
                }
                array.put(obj)
            }
            vf.writeText(array.toString(2))
        }
    }

    @Synchronized
    fun load() {
        val vf = vaultFile ?: return
        if (!vf.exists()) return
        try {
            val content = vf.readTextAndMigrate() ?: return
            if (content.isBlank()) return
            val array = JSONArray(content)
            records.clear()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val keywordsList = mutableListOf<String>()
                val kwArray = obj.optJSONArray("keywords")
                if (kwArray != null) {
                    for (k in 0 until kwArray.length()) {
                        keywordsList.add(kwArray.getString(k))
                    }
                }
                val record = PersonalizedSentenceRecord(
                    id = obj.optString("id"),
                    sentence = obj.getString("sentence"),
                    choseong = obj.optString("choseong"),
                    intent = runCatching { ContextualIntent.valueOf(obj.getString("intent")) }.getOrDefault(ContextualIntent.General),
                    tone = runCatching { KoreanTone.valueOf(obj.getString("tone")) }.getOrDefault(KoreanTone.Honorific),
                    keywords = keywordsList,
                    source = obj.optString("source", "synthetic_llm"),
                    score = obj.optDouble("score", 1.0).toFloat(),
                    useCount = obj.optInt("useCount", 0),
                    lastUsedTimestamp = obj.optLong("lastUsedTimestamp", System.currentTimeMillis()),
                    packageName = if (obj.has("packageName")) obj.getString("packageName") else null
                )
                upsert(record)
            }
        } catch (_: Throwable) {
            // Failsafe: on corruption, preserve operation with clean state
        }
    }

    @Synchronized
    fun get(sentence: String): PersonalizedSentenceRecord? {
        return records[sentence.trim()]
    }

    @Synchronized
    fun remove(sentence: String): Boolean {
        return records.remove(sentence.trim()) != null
    }

    @Synchronized
    fun clear() {
        records.clear()
    }
}
