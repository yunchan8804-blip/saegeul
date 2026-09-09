/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.typo

import org.fcitx.fcitx5.android.input.ai.KoreanPiiScrubber
import org.fcitx.fcitx5.android.input.ai.PersonalNgramTokenizer
import org.fcitx.fcitx5.android.input.ai.vault.PlainVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.pow

/**
 * 사용자가 "지우고 다시 쓴" 오타-교정 쌍을 온디바이스로 학습하는 저장소.
 * 키 시퀀스 사이의 표준 편집 거리를 역추적해 개인화된 키 혼동 패턴을 누적하고,
 * [DubeolsikKeyMap]의 기본 치환 비용을 개인화된 비용으로 보정한다.
 */
class CorrectionPatternStore(
    private val storeFile: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxPairs: Int = 2000,
    private val cipher: VaultCipher = PlainVaultCipher
) {

    data class CorrectionPair(val typed: String, val corrected: String, val count: Float, val lastSeenMs: Long)

    private data class PairRecord(val typed: String, val corrected: String, var count: Float, var lastSeenMs: Long)

    private val pairs = LinkedHashMap<String, PairRecord>()
    private val confusions = HashMap<Pair<Char, Char>, Float>()
    private val vaultFile: VaultFile? = storeFile?.let { VaultFile(it, cipher, VaultFile.aadFor(it.name)) }
    private val persistenceLock = Any()

    init {
        vaultFile?.let { vf ->
            if (vf.exists()) {
                loadFromDisk(vf)
            }
        }
    }

    @Synchronized
    fun recordCorrection(typed: String, corrected: String): Boolean {
        val scrubbedTyped = KoreanPiiScrubber.scrub(typed)
        val scrubbedCorrected = KoreanPiiScrubber.scrub(corrected)
        val typedTokens = PersonalNgramTokenizer.tokenize(scrubbedTyped)
        val correctedTokens = PersonalNgramTokenizer.tokenize(scrubbedCorrected)
        if (typedTokens.size != 1 || correctedTokens.size != 1) return false

        val t = typedTokens[0]
        val c = correctedTokens[0]
        if (t == c) return false

        val maxCost = KeyboardAwareTypoCorrector.defaultMaxCost(t) + 0.5f
        val distance = KeyboardAwareTypoCorrector.weightedDistance(t, c)
        if (distance > maxCost) return false

        val key = pairKey(t, c)
        val now = clock()
        val existing = pairs[key]
        if (existing != null) {
            existing.count += 1f
            existing.lastSeenMs = now
        } else {
            if (pairs.size >= maxPairs) evictWeakest()
            pairs[key] = PairRecord(t, c, 1f, now)
        }

        recordConfusions(t, c)
        return true
    }

    @Synchronized
    fun lookup(typed: String, limit: Int = 3): List<CorrectionPair> {
        return pairs.values.asSequence()
            .filter { it.typed == typed }
            .sortedByDescending { it.count }
            .take(limit)
            .map { CorrectionPair(it.typed, it.corrected, it.count, it.lastSeenMs) }
            .toList()
    }

    @Synchronized
    fun confusionCount(fromKey: Char, toKey: Char): Float = confusions[fromKey to toKey] ?: 0f

    @Synchronized
    fun personalizedSubstitutionCost(a: Char, b: Char): Float {
        val base = DubeolsikKeyMap.substitutionCost(a, b)
        val count = confusionCount(a, b)
        val adjusted = base * (1f / (1f + 0.5f * count))
        return maxOf(0.2f, adjusted)
    }

    @Synchronized
    fun stats(): Pair<Int, Int> = pairs.size to confusions.size

    fun save() {
        val vf = vaultFile ?: return
        synchronized(persistenceLock) {
            val snapshot = synchronized(this) {
                val obj = JSONObject()
                val pairsArray = JSONArray()
                pairs.values.forEach { p ->
                    pairsArray.put(
                        JSONObject().apply {
                            put("typed", p.typed)
                            put("corrected", p.corrected)
                            put("count", p.count.toDouble())
                            put("lastSeenMs", p.lastSeenMs)
                        }
                    )
                }
                obj.put("pairs", pairsArray)
                val confusionArray = JSONArray()
                confusions.forEach { (key, count) ->
                    confusionArray.put(
                        JSONObject().apply {
                            put("from", key.first.toString())
                            put("to", key.second.toString())
                            put("count", count.toDouble())
                        }
                    )
                }
                obj.put("confusions", confusionArray)
                obj
            }
            vf.writeText(snapshot.toString(2))
        }
    }

    fun clear() {
        synchronized(persistenceLock) {
            synchronized(this) {
                pairs.clear()
                confusions.clear()
            }
            vaultFile?.delete() ?: storeFile?.delete()
        }
    }

    private fun loadFromDisk(vaultFile: VaultFile) {
        try {
            val content = vaultFile.readTextAndMigrate() ?: return
            if (content.isBlank()) return
            val obj = JSONObject(content)
            val pairsArray = obj.optJSONArray("pairs")
            if (pairsArray != null) {
                for (i in 0 until pairsArray.length()) {
                    val p = pairsArray.getJSONObject(i)
                    val t = p.getString("typed")
                    val c = p.getString("corrected")
                    val count = p.optDouble("count", 1.0).toFloat()
                    val lastSeen = p.optLong("lastSeenMs", 0L)
                    pairs[pairKey(t, c)] = PairRecord(t, c, count, lastSeen)
                }
            }
            val confusionArray = obj.optJSONArray("confusions")
            if (confusionArray != null) {
                for (i in 0 until confusionArray.length()) {
                    val entry = confusionArray.getJSONObject(i)
                    val from = entry.getString("from")
                    val to = entry.getString("to")
                    if (from.isEmpty() || to.isEmpty()) continue
                    val count = entry.optDouble("count", 0.0).toFloat()
                    confusions[from[0] to to[0]] = count
                }
            }
        } catch (_: Exception) {
            pairs.clear()
            confusions.clear()
        }
    }

    private fun recordConfusions(typed: String, corrected: String) {
        val a = DubeolsikKeyMap.keySequence(typed)
        val b = DubeolsikKeyMap.keySequence(corrected)
        val n = a.length
        val m = b.length
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) dp[i][0] = i
        for (j in 0..m) dp[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            when {
                dp[i][j] == dp[i - 1][j - 1] + cost -> {
                    if (cost == 1) {
                        val confusionKey = a[i - 1] to b[j - 1]
                        confusions[confusionKey] = (confusions[confusionKey] ?: 0f) + 1f
                    }
                    i--
                    j--
                }
                dp[i][j] == dp[i - 1][j] + 1 -> i--
                else -> j--
            }
        }
    }

    private fun evictWeakest() {
        val now = clock()
        val victimKey = pairs.entries.minByOrNull { decayedCount(it.value, now) }?.key
        if (victimKey != null) pairs.remove(victimKey)
    }

    private fun decayedCount(record: PairRecord, now: Long): Float {
        val elapsedMs = (now - record.lastSeenMs).coerceAtLeast(0L)
        val halfLifeMs = 30L * 24 * 60 * 60 * 1000
        val halfLives = elapsedMs.toDouble() / halfLifeMs
        return (record.count * 0.5.pow(halfLives)).toFloat()
    }

    private fun pairKey(typed: String, corrected: String): String = "$typed $corrected"
}
