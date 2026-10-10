/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.typo

import kotlin.math.max
import kotlin.math.min

/**
 * 두벌식 키보드 인접 키 오타를 원래 의도한 어절로 교정하는 트라이 기반 엔진.
 * 어휘를 키 시퀀스 트라이로 색인해 두고, 입력된 키 시퀀스와 트라이 경로 사이의
 * 가중 Damerau-Levenshtein 거리를 DP로 계산하며 가지치기해 빠르게 후보를 찾는다.
 *
 * [substitutionCost]와 [personalDiscount]의 인자 순서는 (친 키, 의도한 키)다.
 * [CorrectionPatternStore]가 혼동을 기록하는 순서와 같다. [correct]에 [TouchTrace]가 있고
 * 어절의 키 순서와 흔적 끝부분이 정확히 같으면, 그 키 위치의 치환 비용은 격자 대신 실제 터치
 * 지점([TouchSubstitutionCost])으로 계산하고 [personalDiscount]를 곱한다. [touchHabit]은 그 터치 비용의
 * 의도한 키 중심을 그 키가 속한 군집의 손버릇만큼 옮긴다.
 */
class KeyboardAwareTypoCorrector(
    private val substitutionCost: (Char, Char) -> Float = DubeolsikKeyMap::substitutionCost,
    private val personalDiscount: (Char, Char) -> Float = { _, _ -> 1f },
    private val touchHabit: TouchHabit? = null,
    private val insertCost: Float = 0.9f,
    private val deleteCost: Float = 0.9f,
    private val transposeCost: Float = 0.6f,
) {

    data class Correction(val word: String, val cost: Float, val prior: Float, val score: Float)

    private class Node {
        val children = HashMap<Char, Node>()
        val words = mutableListOf<Pair<String, Float>>()
    }

    private val root = Node()
    private var wordCount = 0

    @Synchronized
    fun addWord(word: String, prior: Float) {
        if (word.isEmpty()) return
        val keySeq = DubeolsikKeyMap.keySequence(word)
        var node = root
        for (c in keySeq) {
            node = node.children.getOrPut(c) { Node() }
        }
        val existingIdx = node.words.indexOfFirst { it.first == word }
        if (existingIdx >= 0) {
            val existing = node.words[existingIdx]
            if (prior > existing.second) {
                node.words[existingIdx] = word to prior
            }
        } else {
            node.words.add(word to prior)
            wordCount++
        }
    }

    @Synchronized
    fun size(): Int = wordCount

    @Synchronized
    fun correct(
        typed: String,
        limit: Int = 3,
        maxCost: Float = defaultMaxCost(typed),
        contextBoost: (String) -> Float = { 0f },
        touchTrace: TouchTrace? = null,
        reading: TypedKeyReading? = null
    ): List<Correction> {
        val target = reading?.keys ?: DubeolsikKeyMap.keySequence(typed)
        // 흔적 끝부분이 이 어절의 키 순서와 정확히 같을 때만 위치별 터치를 쓴다. 다르면(좌표가 없는
        // 입력이 섞였거나 고쳐 쳤으면) 흔적 없이 격자 비용으로 계산한다.
        val touches = touchTrace?.alignedTo(target)
        val out = mutableListOf<Correction>()
        val dp0 = FloatArray(target.length + 1) { j -> j * insertCost }
        val dummyPrev = FloatArray(target.length + 1)
        dfsCorrect(root, 0, dp0, dummyPrev, ' ', target, touches, reading, maxCost, typed, contextBoost, out)
        return out.sortedWith(compareByDescending<Correction> { it.score }.thenBy { it.cost }).take(limit)
    }

    private fun rowMin(row: FloatArray): Float {
        var m = row[0]
        for (i in 1 until row.size) if (row[i] < m) m = row[i]
        return m
    }

    private fun childRow(
        c: Char,
        depth: Int,
        row: FloatArray,
        prevRow: FloatArray,
        lastChar: Char,
        target: String,
        touches: List<KeyTouch>?,
        reading: TypedKeyReading?
    ): FloatArray {
        val m = target.length
        val newRow = FloatArray(m + 1)
        newRow[0] = (depth + 1) * deleteCost
        for (j in 1..m) {
            val del = row[j] + deleteCost
            val ins = newRow[j - 1] + insertCost
            val sub = row[j - 1] + substitution(target[j - 1], c, touches?.get(j - 1)) +
                (reading?.extraCost(j - 1) ?: 0f)
            var best = min(del, min(ins, sub))
            if (depth >= 1 && j >= 2 && c == target[j - 2] && lastChar == target[j - 1]) {
                val extra = if (reading == null) 0f else reading.extraCost(j - 1) + reading.extraCost(j - 2)
                best = min(best, prevRow[j - 2] + transposeCost + extra)
            }
            newRow[j] = best
        }
        return newRow
    }

    /** 친 키 [typedKey] 대신 [intendedKey]를 의도했을 때의 비용. 터치 흔적이 없거나 계산할 수 없으면 격자 비용이다. */
    private fun substitution(typedKey: Char, intendedKey: Char, touch: KeyTouch?): Float {
        val touchCost = touch?.let { TouchSubstitutionCost.cost(it, intendedKey, touchHabit) }
            ?: return substitutionCost(typedKey, intendedKey)
        return max(MIN_TOUCH_SUBSTITUTION_COST, touchCost * personalDiscount(typedKey, intendedKey))
    }

    private fun dfsCorrect(
        node: Node,
        depth: Int,
        row: FloatArray,
        prevRow: FloatArray,
        lastChar: Char,
        target: String,
        touches: List<KeyTouch>?,
        reading: TypedKeyReading?,
        maxCost: Float,
        typed: String,
        contextBoost: (String) -> Float,
        out: MutableList<Correction>
    ) {
        if (node.words.isNotEmpty()) {
            val cost = row[target.length]
            if (cost <= maxCost) {
                for ((word, prior) in node.words) {
                    if (word == typed) continue
                    val score = prior + contextBoost(word) - 2.0f * cost
                    out.add(Correction(word, cost, prior, score))
                }
            }
        }
        if (rowMin(row) > maxCost) return
        for ((c, child) in node.children) {
            val newRow = childRow(c, depth, row, prevRow, lastChar, target, touches, reading)
            dfsCorrect(child, depth + 1, newRow, row, c, target, touches, reading, maxCost, typed, contextBoost, out)
        }
    }

    companion object {
        /** 터치 비용에 개인 할인을 곱한 뒤에도 치환이 이보다 공짜가 되지는 않는다. */
        const val MIN_TOUCH_SUBSTITUTION_COST = 0.1f

        fun defaultMaxCost(typed: String): Float = standardMaxCost(DubeolsikKeyMap.keySequence(typed).length)

        /** 키가 [keyCount]개인 어절의 표준 비용 상한. */
        fun standardMaxCost(keyCount: Int): Float = min(2.4f, 0.6f + 0.15f * keyCount)

        /**
         * 키보드 교정이 받아들이는 비용 상한. 키가 [LONG_WORD_KEYS]개 이상인 긴 어절은 `0.6 + 0.2 × 키 수`
         * (최대 3.0)까지 넓힌다: 키가 많으면 한두 글자가 뭉개져도 나머지가 의도한 어절을 가리키기 때문이다.
         * 짧은 어절은 [standardMaxCost]와 같다.
         */
        fun keyboardMaxCost(keyCount: Int): Float =
            if (keyCount >= LONG_WORD_KEYS) min(3.0f, 0.6f + 0.2f * keyCount) else standardMaxCost(keyCount)

        const val LONG_WORD_KEYS = 10

        /**
         * 두 어절의 키 시퀀스 사이 가중 Damerau-Levenshtein 거리.
         * 트라이 없이 전체 문자열 두 개를 직접 비교할 때 사용한다. [a]가 친 어절, [b]가 의도한 어절이다.
         */
        fun weightedDistance(
            a: String,
            b: String,
            substitutionCost: (Char, Char) -> Float = DubeolsikKeyMap::substitutionCost
        ): Float {
            val sa = DubeolsikKeyMap.keySequence(a)
            val sb = DubeolsikKeyMap.keySequence(b)
            val insertCost = 0.9f
            val deleteCost = 0.9f
            val transposeCost = 0.6f
            val n = sa.length
            val m = sb.length
            val dp = Array(n + 1) { FloatArray(m + 1) }
            for (i in 0..n) dp[i][0] = i * deleteCost
            for (j in 0..m) dp[0][j] = j * insertCost
            for (i in 1..n) {
                for (j in 1..m) {
                    val del = dp[i - 1][j] + deleteCost
                    val ins = dp[i][j - 1] + insertCost
                    val sub = dp[i - 1][j - 1] + substitutionCost(sa[i - 1], sb[j - 1])
                    var best = min(del, min(ins, sub))
                    if (i >= 2 && j >= 2 && sa[i - 1] == sb[j - 2] && sa[i - 2] == sb[j - 1]) {
                        best = min(best, dp[i - 2][j - 2] + transposeCost)
                    }
                    dp[i][j] = best
                }
            }
            return dp[n][m]
        }
    }
}
