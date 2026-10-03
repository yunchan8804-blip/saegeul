/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.typo

import org.fcitx.fcitx5.android.input.ai.ChoseongMorphologyEngine
import java.io.BufferedReader
import java.io.Reader
import kotlin.math.ln
import timber.log.Timber

/**
 * 앱에 번들되는 기본 한국어 어휘. TSV(단어\t빈도)를 로드해 표면형·자모열·초성열
 * 접두 완성과 로그 스케일 사전확률(prior)을 제공한다.
 *
 * 내부는 어절 하나당 객체(Entry)를 만들지 않고, id(count 내림차순 순위)로 색인되는
 * 원시 배열(Array<String>/IntArray/CharArray)의 불변 스냅샷으로 유지한다. [load]는
 * 지역 변수에서 스냅샷을 완성한 뒤 [snapshot] 한 필드에 한 번에 대입해 공개하므로,
 * 로드 중에 다른 스레드가 [contains]/[prior]/[completions]/[size]/[forEachWord]를
 * 호출해도 예외 없이 로드 전 빈 결과 또는 로드 후 완성된 결과만 보게 된다.
 */
class BaseKoreanVocabulary(private val source: () -> Reader) {

    /**
     * 완성된 불변 어휘 스냅샷. id는 0..size-1이며 count 내림차순(동률은 파일 등장 순서)으로
     * 부여된다. 즉 id 오름차순 순회가 곧 prior 내림차순 순회다.
     */
    private class Snapshot(
        val words: Array<String>,
        val counts: IntArray,
        val jamoChars: CharArray,
        val jamoOffsets: IntArray,
        val choseongChars: CharArray,
        val choseongOffsets: IntArray,
        val surfaceByFirstChar: HashMap<Char, IntArray>,
        val jamoByFirst: HashMap<Char, IntArray>,
        val choseongByFirst: HashMap<Char, IntArray>,
        val sortedWordIds: IntArray,
        maxCount: Int
    ) {
        val size: Int get() = words.size

        private val logDenom: Double = ln(1.0 + (if (maxCount > 0) maxCount else 1))

        fun priorOf(id: Int): Float = (ln(1.0 + counts[id]) / logDenom).toFloat()

        fun jamoStartsWith(id: Int, stroke: String): Boolean {
            val start = jamoOffsets[id]
            val len = jamoOffsets[id + 1] - start
            if (len < stroke.length) return false
            for (i in stroke.indices) {
                if (jamoChars[start + i] != stroke[i]) return false
            }
            return true
        }

        fun choseongStartsWith(id: Int, stroke: String): Boolean {
            val start = choseongOffsets[id]
            val len = choseongOffsets[id + 1] - start
            if (len < stroke.length) return false
            for (i in stroke.indices) {
                if (choseongChars[start + i] != stroke[i]) return false
            }
            return true
        }

        /** [words]에서 word와 정확히 일치하는 id, 없으면 -1. sortedWordIds에 이진 탐색한다. */
        fun indexOfWord(word: String): Int {
            var lo = 0
            var hi = sortedWordIds.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val id = sortedWordIds[mid]
                val cmp = words[id].compareTo(word)
                when {
                    cmp < 0 -> lo = mid + 1
                    cmp > 0 -> hi = mid - 1
                    else -> return id
                }
            }
            return -1
        }
    }

    private class RawEntry(val word: String, val jamo: String, val choseong: String, val count: Int)

    private val morphology = ChoseongMorphologyEngine()

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var loadedFlag = false

    val isLoaded: Boolean
        get() = loadedFlag

    @Synchronized
    fun load() {
        if (loadedFlag) return
        loadedFlag = true
        try {
            val raw = ArrayList<RawEntry>()
            val seen = HashSet<String>()
            BufferedReader(source()).use { reader ->
                reader.lineSequence().forEach { raw0 ->
                    val line = raw0.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEach
                    val tabIdx = line.indexOf('\t')
                    if (tabIdx < 0) return@forEach
                    val word = line.substring(0, tabIdx).trim()
                    val rest = line.substring(tabIdx + 1)
                    val nextTabIdx = rest.indexOf('\t')
                    val countStr = (if (nextTabIdx >= 0) rest.substring(0, nextTabIdx) else rest).trim()
                    val count = countStr.toIntOrNull() ?: return@forEach
                    if (word.isEmpty() || count < 0) return@forEach
                    if (!seen.add(word)) return@forEach
                    val jamo = morphology.decomposeHangul(word)
                    val choseong = morphology.extractChoseongSequence(word)
                    raw.add(RawEntry(word, jamo, choseong, count))
                }
            }
            snapshot = buildSnapshot(raw)
        } catch (e: Exception) {
            Timber.w(e, "Base Korean vocabulary load failed; typo correction has no base words")
            snapshot = null
        }
    }

    /**
     * count 내림차순(동률은 파일 등장 순서=raw 인덱스 오름차순)으로 id를 매긴 뒤, 그 id 순서로
     * words/counts/jamo/choseong 배열과 첫 글자 버킷(id 오름차순=prior 내림차순)을 만든다.
     * 정렬 키를 (Int.MAX_VALUE-count, rawIndex) 쌍으로 하나의 Long에 인코딩해 박싱 없이
     * LongArray#sort()로 정렬한다.
     */
    private fun buildSnapshot(raw: ArrayList<RawEntry>): Snapshot {
        val n = raw.size
        val sortKeys = LongArray(n)
        for (rawIdx in 0 until n) {
            val countRank = (Int.MAX_VALUE.toLong() - raw[rawIdx].count)
            sortKeys[rawIdx] = (countRank shl 32) or (rawIdx.toLong() and 0xFFFFFFFFL)
        }
        sortKeys.sort()
        val order = IntArray(n) { (sortKeys[it] and 0xFFFFFFFFL).toInt() }

        val words = Array(n) { raw[order[it]].word }
        val counts = IntArray(n) { raw[order[it]].count }

        val jamoOffsets = IntArray(n + 1)
        var jamoTotal = 0
        for (id in 0 until n) {
            jamoTotal += raw[order[id]].jamo.length
            jamoOffsets[id + 1] = jamoTotal
        }
        val jamoChars = CharArray(jamoTotal)
        for (id in 0 until n) {
            val s = raw[order[id]].jamo
            val start = jamoOffsets[id]
            for (i in s.indices) jamoChars[start + i] = s[i]
        }

        val choseongOffsets = IntArray(n + 1)
        var choseongTotal = 0
        for (id in 0 until n) {
            choseongTotal += raw[order[id]].choseong.length
            choseongOffsets[id + 1] = choseongTotal
        }
        val choseongChars = CharArray(choseongTotal)
        for (id in 0 until n) {
            val s = raw[order[id]].choseong
            val start = choseongOffsets[id]
            for (i in s.indices) choseongChars[start + i] = s[i]
        }

        val surfaceBuckets = HashMap<Char, ArrayList<Int>>()
        val jamoBuckets = HashMap<Char, ArrayList<Int>>()
        val choseongBuckets = HashMap<Char, ArrayList<Int>>()
        for (id in 0 until n) {
            val r = raw[order[id]]
            surfaceBuckets.getOrPut(r.word[0]) { ArrayList() }.add(id)
            if (r.jamo.isNotEmpty()) {
                jamoBuckets.getOrPut(r.jamo[0]) { ArrayList() }.add(id)
            }
            if (r.choseong.isNotEmpty()) {
                choseongBuckets.getOrPut(r.choseong[0]) { ArrayList() }.add(id)
            }
        }
        val surfaceByFirstChar = HashMap<Char, IntArray>(surfaceBuckets.size * 2)
        surfaceBuckets.forEach { (c, list) -> surfaceByFirstChar[c] = list.toIntArray() }
        val jamoByFirst = HashMap<Char, IntArray>(jamoBuckets.size * 2)
        jamoBuckets.forEach { (c, list) -> jamoByFirst[c] = list.toIntArray() }
        val choseongByFirst = HashMap<Char, IntArray>(choseongBuckets.size * 2)
        choseongBuckets.forEach { (c, list) -> choseongByFirst[c] = list.toIntArray() }

        val sortedWordIds = (0 until n).sortedBy { words[it] }.toIntArray()

        val maxCount = if (n > 0) counts[0] else 0

        return Snapshot(
            words = words,
            counts = counts,
            jamoChars = jamoChars,
            jamoOffsets = jamoOffsets,
            choseongChars = choseongChars,
            choseongOffsets = choseongOffsets,
            surfaceByFirstChar = surfaceByFirstChar,
            jamoByFirst = jamoByFirst,
            choseongByFirst = choseongByFirst,
            sortedWordIds = sortedWordIds,
            maxCount = maxCount
        )
    }

    fun size(): Int = snapshot?.size ?: 0

    fun contains(word: String): Boolean {
        val snap = snapshot ?: return false
        return snap.indexOfWord(word) >= 0
    }

    /** 빈도 상위 [limit]위 안에 있는 어절인지. 오타 교정은 [TYPO_VOCAB_LIMIT] 안의 어절만 '아는 단어'로 본다. */
    fun containsWithinTop(word: String, limit: Int): Boolean {
        val snap = snapshot ?: return false
        val id = snap.indexOfWord(word)
        return id in 0 until limit
    }

    /** 빈도 순위(1부터, 1이 가장 흔함). 어휘에 없으면 0. */
    fun rankOf(word: String): Int {
        val snap = snapshot ?: return 0
        return snap.indexOfWord(word) + 1
    }

    /** [rankOf]가 돌려준 순위의 사전확률. [prior]와 같은 값이다. */
    fun priorAtRank(rank: Int): Float {
        val snap = snapshot ?: return 0f
        if (rank < 1 || rank > snap.size) return 0f
        return snap.priorOf(rank - 1)
    }

    fun prior(word: String): Float {
        val snap = snapshot ?: return 0f
        val id = snap.indexOfWord(word)
        if (id < 0) return 0f
        return snap.priorOf(id)
    }

    /**
     * stroke(표면/자모/초성 접두)로 시작하는 어절을 prior 내림차순으로 최대 limit개 반환한다.
     *
     * 후보는 stroke 첫 글자로 좁힌 표면/자모/(초성 전용 stroke면) 초성 버킷 셋뿐이다.
     * word.startsWith(stroke)이면 word[0]==stroke[0]이라 surfaceByFirstChar에, jamo면
     * jamoByFirst에, 초성 매칭이면 choseongByFirst에 반드시 포함되므로 완전하다.
     *
     * 각 버킷은 이미 id 오름차순(=prior 내림차순, 동률은 로드 순서)이므로, 세 버킷을 id
     * 기준으로 k-way 병합하며 실제 startsWith를 만족하는 항목만 모으면, 별도 정렬 없이
     * 그대로 prior 내림차순 결과가 된다. limit개를 채우면 멈춘다.
     */
    fun completions(stroke: String, limit: Int): List<Pair<String, Float>> {
        if (stroke.isEmpty() || limit <= 0) return emptyList()
        val snap = snapshot ?: return emptyList()
        val firstChar = stroke[0]
        val isAllChoseong = stroke.all { morphology.isChoseong(it) }

        val surfaceIds = snap.surfaceByFirstChar[firstChar] ?: EMPTY_INT_ARRAY
        val jamoIds = snap.jamoByFirst[firstChar] ?: EMPTY_INT_ARRAY
        val choseongIds = if (isAllChoseong) (snap.choseongByFirst[firstChar] ?: EMPTY_INT_ARRAY) else EMPTY_INT_ARRAY

        if (surfaceIds.isEmpty() && jamoIds.isEmpty() && choseongIds.isEmpty()) return emptyList()

        var i = 0
        var j = 0
        var k = 0
        val result = ArrayList<Pair<String, Float>>(
            minOf(limit, surfaceIds.size + jamoIds.size + choseongIds.size)
        )
        while (result.size < limit && (i < surfaceIds.size || j < jamoIds.size || k < choseongIds.size)) {
            var candidate = Int.MAX_VALUE
            if (i < surfaceIds.size) candidate = minOf(candidate, surfaceIds[i])
            if (j < jamoIds.size) candidate = minOf(candidate, jamoIds[j])
            if (k < choseongIds.size) candidate = minOf(candidate, choseongIds[k])

            if (i < surfaceIds.size && surfaceIds[i] == candidate) i++
            if (j < jamoIds.size && jamoIds[j] == candidate) j++
            if (k < choseongIds.size && choseongIds[k] == candidate) k++

            val matched = snap.words[candidate].startsWith(stroke) ||
                snap.jamoStartsWith(candidate, stroke) ||
                (isAllChoseong && snap.choseongStartsWith(candidate, stroke))
            if (matched) {
                result.add(snap.words[candidate] to snap.priorOf(candidate))
            }
        }
        return result
    }

    fun forEachWord(action: (String, Float) -> Unit) {
        val snap = snapshot ?: return
        for (id in 0 until snap.size) {
            action(snap.words[id], snap.priorOf(id))
        }
    }

    /** count 내림차순으로 상위 [limit]개만 순회한다. */
    fun forEachWord(limit: Int, action: (String, Float) -> Unit) {
        val snap = snapshot ?: return
        val n = minOf(limit, snap.size)
        for (id in 0 until n) {
            action(snap.words[id], snap.priorOf(id))
        }
    }

    companion object {
        /**
         * 오타 교정 트라이에 등록하고 '아는 단어'로 취급하는 기본 어휘 상한(빈도 순위).
         * 트라이는 어절마다 노드 객체를 만들고, 드문 어절까지 아는 단어로 치면 인접 키 오타가
         * 교정되지 않으므로 전체 어휘가 아니라 상위만 쓴다.
         */
        const val TYPO_VOCAB_LIMIT = 30_000

        private val EMPTY_INT_ARRAY = IntArray(0)
    }
}
