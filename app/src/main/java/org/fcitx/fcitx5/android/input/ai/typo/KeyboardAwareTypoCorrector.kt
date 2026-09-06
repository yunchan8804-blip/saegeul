/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.typo

import kotlin.math.min

/**
 * 두벌식 키보드 인접 키 오타를 원래 의도한 어절로 교정하는 트라이 기반 엔진.
 * 어휘를 키 시퀀스 트라이로 색인해 두고, 입력된 키 시퀀스와 트라이 경로 사이의
 * 가중 Damerau-Levenshtein 거리를 DP로 계산하며 가지치기해 빠르게 후보를 찾는다.
 */
class KeyboardAwareTypoCorrector(
    private val substitutionCost: (Char, Char) -> Float = DubeolsikKeyMap::substitutionCost,
    private val insertCost: Float = 0.9f,
    private val deleteCost: Float = 0.9f,
    private val transposeCost: Float = 0.6f,
) {

    data class Correction(val word: String, val cost: Float, val prior: Float, val score: Float)

    private class Node {
        val children = HashMap<Char, Node>()
        val words = mutableListOf<Pair<String, Float>>()
        var maxPriorInSubtree: Float = 0f
    }

    private val root = Node()
    private var wordCount = 0

    @Synchronized
    fun addWord(word: String, prior: Float) {
        if (word.isEmpty()) return
        val keySeq = DubeolsikKeyMap.keySequence(word)
        var node = root
        node.maxPriorInSubtree = maxOf(node.maxPriorInSubtree, prior)
        for (c in keySeq) {
            node = node.children.getOrPut(c) { Node() }
            node.maxPriorInSubtree = maxOf(node.maxPriorInSubtree, prior)
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
    fun removeWord(word: String) {
        if (word.isEmpty()) return
        val keySeq = DubeolsikKeyMap.keySequence(word)
        var node = root
        for (c in keySeq) {
            node = node.children[c] ?: return
        }
        if (node.words.removeAll { it.first == word }) {
            wordCount--
        }
    }

    @Synchronized
    fun size(): Int = wordCount

    @Synchronized
    fun correct(
        typed: String,
        limit: Int = 3,
        maxCost: Float = defaultMaxCost(typed),
        contextBoost: (String) -> Float = { 0f }
    ): List<Correction> {
        val target = DubeolsikKeyMap.keySequence(typed)
        val out = mutableListOf<Correction>()
        val dp0 = FloatArray(target.length + 1) { j -> j * insertCost }
        val dummyPrev = FloatArray(target.length + 1)
        dfsCorrect(root, 0, dp0, dummyPrev, ' ', target, maxCost, typed, contextBoost, out)
        return out.sortedWith(compareByDescending<Correction> { it.score }.thenBy { it.cost }).take(limit)
    }

    @Synchronized
    fun completeFuzzy(
        typedPrefix: String,
        limit: Int = 5,
        maxPrefixCost: Float = 0.8f,
        contextBoost: (String) -> Float = { 0f }
    ): List<Correction> {
        val target = DubeolsikKeyMap.keySequence(typedPrefix)
        val out = mutableListOf<Correction>()
        val counter = intArrayOf(0)
        val dp0 = FloatArray(target.length + 1) { j -> j * insertCost }
        val dummyPrev = FloatArray(target.length + 1)
        dfsPrefix(root, 0, dp0, dummyPrev, ' ', target, maxPrefixCost, typedPrefix, contextBoost, out, counter)
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
        target: String
    ): FloatArray {
        val m = target.length
        val newRow = FloatArray(m + 1)
        newRow[0] = (depth + 1) * deleteCost
        for (j in 1..m) {
            val del = row[j] + deleteCost
            val ins = newRow[j - 1] + insertCost
            val sub = row[j - 1] + substitutionCost(c, target[j - 1])
            var best = min(del, min(ins, sub))
            if (depth >= 1 && j >= 2 && c == target[j - 2] && lastChar == target[j - 1]) {
                best = min(best, prevRow[j - 2] + transposeCost)
            }
            newRow[j] = best
        }
        return newRow
    }

    private fun dfsCorrect(
        node: Node,
        depth: Int,
        row: FloatArray,
        prevRow: FloatArray,
        lastChar: Char,
        target: String,
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
            val newRow = childRow(c, depth, row, prevRow, lastChar, target)
            dfsCorrect(child, depth + 1, newRow, row, c, target, maxCost, typed, contextBoost, out)
        }
    }

    private fun dfsPrefix(
        node: Node,
        depth: Int,
        row: FloatArray,
        prevRow: FloatArray,
        lastChar: Char,
        target: String,
        maxPrefixCost: Float,
        typed: String,
        contextBoost: (String) -> Float,
        out: MutableList<Correction>,
        counter: IntArray
    ) {
        if (counter[0] >= MAX_FUZZY_CANDIDATES) return
        val cost = row[target.length]
        if (cost <= maxPrefixCost) {
            collectSubtree(node, cost, typed, contextBoost, out, counter)
            return
        }
        if (rowMin(row) > maxPrefixCost) return
        for ((c, child) in node.children) {
            if (counter[0] >= MAX_FUZZY_CANDIDATES) return
            val newRow = childRow(c, depth, row, prevRow, lastChar, target)
            dfsPrefix(child, depth + 1, newRow, row, c, target, maxPrefixCost, typed, contextBoost, out, counter)
        }
    }

    private fun collectSubtree(
        node: Node,
        cost: Float,
        typed: String,
        contextBoost: (String) -> Float,
        out: MutableList<Correction>,
        counter: IntArray
    ) {
        if (counter[0] >= MAX_FUZZY_CANDIDATES) return
        for ((word, prior) in node.words) {
            if (counter[0] >= MAX_FUZZY_CANDIDATES) return
            if (word == typed) continue
            val score = prior + contextBoost(word) - 2.0f * cost
            out.add(Correction(word, cost, prior, score))
            counter[0]++
        }
        if (node.children.isEmpty()) return
        val sortedChildren = node.children.values.sortedByDescending { it.maxPriorInSubtree }
        for (child in sortedChildren) {
            if (counter[0] >= MAX_FUZZY_CANDIDATES) return
            collectSubtree(child, cost, typed, contextBoost, out, counter)
        }
    }

    companion object {
        private const val MAX_FUZZY_CANDIDATES = 64

        fun defaultMaxCost(typed: String): Float {
            val len = DubeolsikKeyMap.keySequence(typed).length
            return min(2.4f, 0.6f + 0.15f * len)
        }

        /**
         * 두 어절의 키 시퀀스 사이 가중 Damerau-Levenshtein 거리.
         * 트라이 없이 전체 문자열 두 개를 직접 비교할 때 사용한다.
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
