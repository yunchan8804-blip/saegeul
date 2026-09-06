/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.typo

import org.fcitx.fcitx5.android.input.ai.ChoseongMorphologyEngine
import java.io.BufferedReader
import java.io.Reader
import kotlin.math.ln

/**
 * 앱에 번들되는 기본 한국어 어휘. TSV(단어\t빈도)를 로드해 표면형·자모열·초성열
 * 접두 완성과 로그 스케일 사전확률(prior)을 제공한다.
 */
class BaseKoreanVocabulary(private val source: () -> Reader) {

    private data class Entry(val word: String, val jamo: String, val choseong: String, val count: Int)

    private val morphology = ChoseongMorphologyEngine()
    private val entries = ArrayList<Entry>()
    private val indexByWord = HashMap<String, Int>()
    private var maxCount = 0
    private var loadedFlag = false

    /** 표면형 첫 글자 → 그 글자로 시작하는 entries 인덱스. completions()의 후보 폭을 좁히는 데 쓰인다. */
    private val surfaceByFirstChar = HashMap<Char, MutableList<Int>>()

    /** 자모열 첫 자모 → 해당 자모로 시작하는 entries 인덱스. */
    private val jamoByFirst = HashMap<Char, MutableList<Int>>()

    /** 초성열 첫 자모 → 해당 초성으로 시작하는 entries 인덱스. */
    private val choseongByFirst = HashMap<Char, MutableList<Int>>()

    val isLoaded: Boolean
        get() = loadedFlag

    @Synchronized
    fun load() {
        if (loadedFlag) return
        loadedFlag = true
        try {
            BufferedReader(source()).use { reader ->
                reader.lineSequence().forEach { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEach
                    val tabIdx = line.indexOf('\t')
                    if (tabIdx < 0) return@forEach
                    val word = line.substring(0, tabIdx).trim()
                    val countStr = line.substring(tabIdx + 1).trim()
                    val count = countStr.toIntOrNull() ?: return@forEach
                    if (word.isEmpty() || count < 0) return@forEach
                    if (indexByWord.containsKey(word)) return@forEach
                    val jamo = morphology.decomposeHangul(word)
                    val choseong = morphology.extractChoseongSequence(word)
                    entries.add(Entry(word, jamo, choseong, count))
                    val idx = entries.size - 1
                    indexByWord[word] = idx
                    if (count > maxCount) maxCount = count
                    surfaceByFirstChar.getOrPut(word[0]) { mutableListOf() }.add(idx)
                    if (jamo.isNotEmpty()) {
                        jamoByFirst.getOrPut(jamo[0]) { mutableListOf() }.add(idx)
                    }
                    if (choseong.isNotEmpty()) {
                        choseongByFirst.getOrPut(choseong[0]) { mutableListOf() }.add(idx)
                    }
                }
            }
            sortBucketsByPriorDescending()
        } catch (_: Exception) {
            entries.clear()
            indexByWord.clear()
            maxCount = 0
            surfaceByFirstChar.clear()
            jamoByFirst.clear()
            choseongByFirst.clear()
        }
    }

    /**
     * 각 첫 글자 버킷 내부를 prior 내림차순으로 정렬해 둔다(로드 1회 비용). 정렬은 안정 정렬이므로
     * prior가 같은 항목끼리는 로드 순서(=entries 인덱스 오름차순)가 유지되어, 전수 스캔 후
     * sortedByDescending으로 얻는 원래 결과 순서와 동일하게 재현된다.
     */
    private fun sortBucketsByPriorDescending() {
        val effectiveMaxCount = if (maxCount > 0) maxCount else 1
        val denom = ln(1.0 + effectiveMaxCount)
        val comparator = Comparator<Int> { a, b ->
            val pa = ln(1.0 + entries[a].count) / denom
            val pb = ln(1.0 + entries[b].count) / denom
            pb.compareTo(pa)
        }
        surfaceByFirstChar.values.forEach { it.sortWith(comparator) }
        jamoByFirst.values.forEach { it.sortWith(comparator) }
        choseongByFirst.values.forEach { it.sortWith(comparator) }
    }

    fun size(): Int = entries.size

    fun contains(word: String): Boolean = indexByWord.containsKey(word)

    fun prior(word: String): Float {
        val idx = indexByWord[word] ?: return 0f
        if (maxCount <= 0) return 0f
        val count = entries[idx].count
        return (ln(1.0 + count) / ln(1.0 + maxCount)).toFloat()
    }

    fun completions(stroke: String, limit: Int): List<Pair<String, Float>> {
        if (stroke.isEmpty()) return emptyList()
        val isAllChoseong = stroke.all { morphology.isChoseong(it) }
        val effectiveMaxCount = if (maxCount > 0) maxCount else 1
        val firstChar = stroke[0]

        // 전수 스캔 대신 stroke의 첫 글자(및 초성 전용 스트로크면 초성)로 뽑은 후보만 검사한다.
        // 완전성 근거: word.startsWith(stroke)이면 word[0]==stroke[0]이라 surfaceByFirstChar에,
        // jamo.startsWith(stroke)이면 jamo[0]==stroke[0]이라 jamoByFirst에, 초성 매칭이면
        // choseong[0]==stroke[0]이라 choseongByFirst에 반드시 포함되어 있다(각 버킷은 로드 시
        // 해당 필드의 첫 글자를 키로 모든 엔트리를 등록했으므로).
        val candidateIndices = HashSet<Int>()
        surfaceByFirstChar[firstChar]?.let { candidateIndices.addAll(it) }
        jamoByFirst[firstChar]?.let { candidateIndices.addAll(it) }
        if (isAllChoseong) {
            choseongByFirst[firstChar]?.let { candidateIndices.addAll(it) }
        }
        if (candidateIndices.isEmpty()) return emptyList()

        val matches = ArrayList<Pair<Int, Float>>(candidateIndices.size)
        for (idx in candidateIndices) {
            val entry = entries[idx]
            val matched = entry.word.startsWith(stroke) ||
                entry.jamo.startsWith(stroke) ||
                (isAllChoseong && entry.choseong.startsWith(stroke))
            if (matched) {
                val prior = (ln(1.0 + entry.count) / ln(1.0 + effectiveMaxCount)).toFloat()
                matches.add(idx to prior)
            }
        }
        // prior 내림차순, 동점이면 entries 인덱스 오름차순(=로드 순서) — 원래 전수 스캔 +
        // 안정 정렬(sortedByDescending)의 결과 순서와 정확히 일치한다.
        return matches
            .sortedWith(compareByDescending<Pair<Int, Float>> { it.second }.thenBy { it.first })
            .take(limit)
            .map { entries[it.first].word to it.second }
    }

    fun forEachWord(action: (String, Float) -> Unit) {
        val effectiveMaxCount = if (maxCount > 0) maxCount else 1
        for (entry in entries) {
            action(entry.word, (ln(1.0 + entry.count) / ln(1.0 + effectiveMaxCount)).toFloat())
        }
    }
}
