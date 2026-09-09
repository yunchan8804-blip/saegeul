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
import kotlin.math.ln

/**
 * On-device personalized next-word and word-completion n-gram engine.
 * Learns from committed sentences per app-category persona, with exponential
 * time decay so recent usage always outranks stale usage.
 */
class PersonalNgramModel(
    private val storeFile: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxUnigrams: Int = 6000,
    private val maxBigrams: Int = 24000,
    private val maxTrigrams: Int = 12000,
    private val halfLifeMs: Long = 30L * 24 * 60 * 60 * 1000,
    private val cipher: VaultCipher = PlainVaultCipher,
) {

    data class NgramCandidate(val word: String, val probability: Float, val evidence: Float, val level: Int)

    data class NgramStats(
        val unigrams: Int,
        val bigrams: Int,
        val trigrams: Int,
        val learnedSentences: Int,
        val lastLearnedMs: Long
    )

    private data class Entry(var count: Float, var lastSeenMs: Long)

    private data class Scored(val probability: Float, val evidence: Float, val level: Int)

    private class Table {
        val uni = HashMap<String, Entry>()
        val bi = HashMap<String, HashMap<String, Entry>>()
        val tri = HashMap<String, HashMap<String, Entry>>()
        var learnedSentences: Int = 0
        var lastLearnedMs: Long = 0L
    }

    companion object {
        private const val START = "<s>"
        private const val SEP = ""
        private const val LAMBDA_3 = 0.6f
        private const val LAMBDA_2 = 0.3f
        private const val LAMBDA_1 = 0.1f

        /**
         * 개인 n-gram에서 관찰된 감쇠 카운트를 [org.fcitx.fcitx5.android.input.ai.typo.KeyboardAwareTypoCorrector]
         * 트라이에 넣을 prior 가중치로 변환한다. 개인 어휘가 항상 기본 어휘(0..1 범위)보다
         * 우선하도록 1.0f 이상에서 시작해 로그 스케일로 증가하고 3.0f에서 상한선을 둔다.
         */
        fun personalPrior(count: Float): Float {
            val raw = 1.0f + 0.5f * ln(1.0 + count).toFloat()
            return raw.coerceAtMost(3.0f)
        }
    }

    private val morphology = ChoseongMorphologyEngine()
    private val tables = HashMap<String, Table>()
    private val vaultFile: VaultFile? = storeFile?.let { VaultFile(it, cipher, VaultFile.aadFor(it.name)) }
    private val persistenceLock = Any()

    /**
     * complete()의 매 호출마다 decomposeHangul()을 재계산하지 않도록 유니그램 단어의
     * 자모열·초성열을 메모이즈한다. 단어 철자에 대한 순수 함수 결과이므로 만료가 필요 없다.
     */
    private val completionIndex = HashMap<String, Pair<String, String>>()

    /** 표면형 첫 글자 → 그 글자로 시작하는 단어 목록. complete()의 후보 폭을 좁히는 데 쓰인다. */
    private val wordsByFirstChar = HashMap<Char, MutableList<String>>()

    /** 자모열 첫 자모 → 해당 자모로 시작하는 단어 목록. */
    private val wordsByJamoFirst = HashMap<Char, MutableList<String>>()

    /** 초성열 첫 자모 → 해당 초성으로 시작하는 단어 목록. */
    private val wordsByChoseongFirst = HashMap<Char, MutableList<String>>()

    init {
        load()
    }

    @Synchronized
    fun learn(sentence: String, packageName: String) {
        val scrubbed = KoreanPiiScrubber.scrub(sentence)
        val tokens = PersonalNgramTokenizer.tokenize(scrubbed)
        if (tokens.isEmpty()) return
        val category = TypingDnaVault.categorizePackage(packageName)
        val now = clock()
        for (name in setOf("*", category)) {
            val t = tableFor(name)
            for (i in tokens.indices) {
                val word = tokens[i]
                val prev1 = if (i == 0) START else tokens[i - 1]
                val prev2 = if (i >= 2) tokens[i - 2] else null
                bumpEntry(t.uni, word, 1f, now)
                ensureCompletionIndexed(word)
                bumpBigram(t, prev1, word, 1f, now)
                val stemmed = PersonalNgramTokenizer.stem(prev1)
                if (stemmed != null && stemmed != prev1) {
                    bumpBigram(t, "~$stemmed", word, 1f, now)
                }
                if (prev2 != null) {
                    bumpTrigram(t, prev2 + SEP + prev1, word, 1f, now)
                }
            }
            t.learnedSentences++
            t.lastLearnedMs = now
        }
        prune()
    }

    @Synchronized
    fun learnBigram(prev: String, next: String, weight: Float, packageName: String) {
        val category = TypingDnaVault.categorizePackage(packageName)
        val now = clock()
        for (name in setOf("*", category)) {
            val t = tableFor(name)
            bumpEntry(t.uni, next, weight, now)
            ensureCompletionIndexed(next)
            bumpBigram(t, prev, next, weight, now)
        }
        prune()
    }

    @Synchronized
    fun reinforce(contextBeforeCursor: String, selectedWord: String, packageName: String) {
        val selectedTokens = PersonalNgramTokenizer.tokenize(selectedWord)
        if (selectedTokens.isEmpty()) return
        val contextTokens = PersonalNgramTokenizer.tokenize(contextBeforeCursor)
        val ctxPrev1 = contextTokens.lastOrNull() ?: START
        val ctxPrev2 = if (contextTokens.size >= 2) contextTokens[contextTokens.size - 2] else null
        val category = TypingDnaVault.categorizePackage(packageName)
        val now = clock()
        for (name in setOf("*", category)) {
            val t = tableFor(name)
            for (i in selectedTokens.indices) {
                val word = selectedTokens[i]
                val prev1 = if (i == 0) ctxPrev1 else selectedTokens[i - 1]
                val prev2 = when {
                    i == 0 -> ctxPrev2
                    i == 1 -> ctxPrev1
                    else -> selectedTokens[i - 2]
                }
                bumpEntry(t.uni, word, 0.5f, now)
                ensureCompletionIndexed(word)
                bumpBigram(t, prev1, word, 0.5f, now)
                if (prev2 != null) {
                    bumpTrigram(t, prev2 + SEP + prev1, word, 0.5f, now)
                }
            }
        }
        prune()
    }

    @Synchronized
    fun predictNext(contextBeforeCursor: String, packageName: String, limit: Int): List<NgramCandidate> {
        val now = clock()
        val ctxTokens = PersonalNgramTokenizer.tokenize(contextBeforeCursor)
        val prev1 = ctxTokens.lastOrNull() ?: START
        val prev2 = if (ctxTokens.size >= 2) ctxTokens[ctxTokens.size - 2] else null
        val category = TypingDnaVault.categorizePackage(packageName)
        val combined = combinedScores(prev2, prev1, category, now)
        return combined.entries
            .asSequence()
            .filter { KoreanSuggestionSurface.isDisplayable(it.key) }
            .sortedWith(
                compareByDescending<Map.Entry<String, Scored>> { it.value.probability }
                    .thenByDescending { it.value.evidence }
            )
            .take(limit)
            .map { NgramCandidate(it.key, it.value.probability, it.value.evidence, it.value.level) }
            .toList()
    }

    /**
     * 현재 문맥에서 실제로 관찰된 전이만 반환한다. 유니그램은 어휘 빈도일 뿐 다음 단어의
     * 문맥 근거가 아니므로 제외한다. 빈 문맥에서는 문장 시작 마커의 bigram을 전이로 취급한다.
     */
    @Synchronized
    fun predictContextualNext(contextBeforeCursor: String, packageName: String, limit: Int): List<NgramCandidate> {
        val now = clock()
        val ctxTokens = PersonalNgramTokenizer.tokenize(contextBeforeCursor)
        val prev1 = ctxTokens.lastOrNull() ?: START
        val prev2 = if (ctxTokens.size >= 2) ctxTokens[ctxTokens.size - 2] else null
        val category = TypingDnaVault.categorizePackage(packageName)
        val combined = combinedScores(prev2, prev1, category, now)
        return combined.entries
            .asSequence()
            .filter { it.value.level >= 2 && it.value.evidence > 0f && it.value.probability > 0f }
            .filter { KoreanSuggestionSurface.isDisplayable(it.key) }
            .sortedWith(
                compareByDescending<Map.Entry<String, Scored>> { it.value.probability }
                    .thenByDescending { it.value.evidence }
            )
            .take(limit)
            .map { NgramCandidate(it.key, it.value.probability, it.value.evidence, it.value.level) }
            .toList()
    }

    @Synchronized
    fun complete(stroke: String, contextBeforeCursor: String, packageName: String, limit: Int): List<NgramCandidate> {
        val now = clock()
        val ctxTokens = PersonalNgramTokenizer.tokenize(contextBeforeCursor)
        val prev1 = ctxTokens.lastOrNull() ?: START
        val prev2 = if (ctxTokens.size >= 2) ctxTokens[ctxTokens.size - 2] else null
        val category = TypingDnaVault.categorizePackage(packageName)

        val contextScores = combinedScores(prev2, prev1, category, now)
        val p1Blend = combinedUnigramProbabilities(category, now)

        val categoryTable = tableFor(category)
        val starTable = tableFor("*")
        val vocabulary = HashSet<String>()
        vocabulary.addAll(categoryTable.uni.keys)
        vocabulary.addAll(starTable.uni.keys)
        vocabulary.remove(START)

        val strokeChoseongOnly = stroke.isNotEmpty() && stroke.all { morphology.isChoseong(it) }
        val strokeJamo = morphology.decomposeHangul(stroke)

        // 후보 폭 좁히기: stroke가 비어있으면(모든 단어가 매치) 전체 vocabulary를 그대로 쓰고,
        // 그 외에는 표면/자모/초성 첫 글자 인덱스에서 뽑은 후보만 검사한다. 인덱스는 학습 시점부터
        // 누적되어 절대 줄어들지 않으므로(pruning으로 인한 stale 항목 가능) 아래 루프에서
        // 반드시 현재 vocabulary 소속 여부를 다시 확인한다 — 완전성은 각 매칭 조건의 첫 글자가
        // 곧 해당 인덱스의 키와 같다는 사실로 보장된다.
        val candidateWords: Collection<String> = if (stroke.isEmpty()) {
            vocabulary
        } else {
            val gathered = HashSet<String>()
            wordsByFirstChar[stroke[0]]?.let { gathered.addAll(it) }
            strokeJamo.firstOrNull()?.let { jf -> wordsByJamoFirst[jf]?.let { gathered.addAll(it) } }
            if (strokeChoseongOnly) {
                wordsByChoseongFirst[stroke[0]]?.let { gathered.addAll(it) }
            }
            gathered
        }

        val scored = mutableListOf<NgramCandidate>()
        for (w in candidateWords) {
            if (w == stroke) continue
            if (stroke.isNotEmpty() && w !in vocabulary) continue
            val (jamo, choseong) = completionIndexFor(w)
            val matched = w.startsWith(stroke) ||
                jamo.startsWith(strokeJamo) ||
                (strokeChoseongOnly && choseong.startsWith(stroke))
            if (!matched) continue
            val ctx = contextScores[w]
            val contextP = ctx?.probability ?: 0f
            val p1 = p1Blend[w] ?: 0f
            val score = 0.7f * contextP + 0.3f * p1
            scored.add(NgramCandidate(w, score, ctx?.evidence ?: 0f, ctx?.level ?: 0))
        }

        return scored
            .asSequence()
            .filter { KoreanSuggestionSurface.isDisplayable(it.word) }
            .sortedWith(
                compareByDescending<NgramCandidate> { it.probability }
                    .thenByDescending { it.evidence }
            )
            .take(limit)
            .toList()
    }

    /** 개인 어휘("*" 테이블)에서 [word]의 시간 감쇠된 유니그램 카운트. 없으면 0. */
    @Synchronized
    fun unigramCount(word: String): Float {
        val entry = tables["*"]?.uni?.get(word) ?: return 0f
        return decayed(entry, clock())
    }

    /** 개인 어휘("*" 테이블)의 모든 단어를 시간 감쇠된 카운트와 함께 순회한다. 문장 시작 마커는 제외한다. */
    @Synchronized
    fun forEachUnigram(action: (word: String, decayedCount: Float) -> Unit) {
        val t = tables["*"] ?: return
        val now = clock()
        for ((word, entry) in t.uni) {
            if (word == START) continue
            action(word, decayed(entry, now))
        }
    }

    /**
     * 카테고리별("*" 테이블 제외) 유니그램의 시간 감쇠 카운트 합. 개인 언어 금고 대시보드의
     * 앱 카테고리 분포(메신저/업무/일반)를 그리는 데 쓰인다.
     */
    @Synchronized
    fun categoryCounts(): Map<String, Float> {
        val now = clock()
        val result = LinkedHashMap<String, Float>()
        for ((name, t) in tables) {
            if (name == "*") continue
            var sum = 0f
            for ((word, entry) in t.uni) {
                if (word == START) continue
                sum += decayed(entry, now)
            }
            result[name] = sum
        }
        return result
    }

    @Synchronized
    fun prune() {
        val now = clock()
        for (t in tables.values) {
            pruneUni(t, now)
            pruneNested(t.bi, maxBigrams, now)
            pruneNested(t.tri, maxTrigrams, now)
        }
    }

    @Synchronized
    fun stats(): NgramStats {
        val t = tables["*"] ?: return NgramStats(0, 0, 0, 0, 0L)
        val bigramCount = t.bi.values.sumOf { it.size }
        val trigramCount = t.tri.values.sumOf { it.size }
        return NgramStats(t.uni.size, bigramCount, trigramCount, t.learnedSentences, t.lastLearnedMs)
    }

    fun clear() {
        synchronized(persistenceLock) {
            synchronized(this) {
                tables.clear()
                completionIndex.clear()
                wordsByFirstChar.clear()
                wordsByJamoFirst.clear()
                wordsByChoseongFirst.clear()
            }
            vaultFile?.delete() ?: storeFile?.delete()
        }
    }

    fun save() {
        val vf = vaultFile ?: return
        synchronized(persistenceLock) {
            val snapshot = synchronized(this) {
                val root = JSONObject()
                root.put("v", 1)
                val starTable = tables["*"]
                root.put("learned", starTable?.learnedSentences ?: 0)
                root.put("last", starTable?.lastLearnedMs ?: 0L)
                val tablesJson = JSONObject()
                for ((name, t) in tables) {
                    tablesJson.put(name, serializeTable(t))
                }
                root.put("tables", tablesJson)
                root
            }
            runCatching {
                vf.writeText(snapshot.toString())
            }
        }
    }

    private fun tableFor(name: String): Table = tables.getOrPut(name) { Table() }

    /** [word]의 (자모열, 초성열)을 캐시에서 얻는다. 최초 관찰 시 [ensureCompletionIndexed]가 채우며,
     * 만에 하나 누락됐다면 여기서 지연 계산 후 캐시에 채워 넣어 항상 정확한 값을 보장한다. */
    private fun completionIndexFor(word: String): Pair<String, String> =
        completionIndex.getOrPut(word) {
            morphology.decomposeHangul(word) to morphology.extractChoseongSequence(word)
        }

    /** 단어가 유니그램 테이블에 처음 추가될 때 자모열·초성열을 계산해 캐시와 첫 글자 인덱스에 등록한다. */
    private fun ensureCompletionIndexed(word: String) {
        if (word.isEmpty() || completionIndex.containsKey(word)) return
        val jamo = morphology.decomposeHangul(word)
        val choseong = morphology.extractChoseongSequence(word)
        completionIndex[word] = jamo to choseong
        wordsByFirstChar.getOrPut(word[0]) { mutableListOf() }.add(word)
        if (jamo.isNotEmpty()) {
            wordsByJamoFirst.getOrPut(jamo[0]) { mutableListOf() }.add(word)
        }
        if (choseong.isNotEmpty()) {
            wordsByChoseongFirst.getOrPut(choseong[0]) { mutableListOf() }.add(word)
        }
    }

    private fun bumpEntry(map: HashMap<String, Entry>, key: String, weight: Float, now: Long) {
        val existing = map[key]
        if (existing == null) {
            map[key] = Entry(weight, now)
        } else {
            existing.count += weight
            existing.lastSeenMs = now
        }
    }

    private fun bumpBigram(t: Table, prev: String, next: String, weight: Float, now: Long) {
        bumpEntry(t.bi.getOrPut(prev) { HashMap() }, next, weight, now)
    }

    private fun bumpTrigram(t: Table, key: String, next: String, weight: Float, now: Long) {
        bumpEntry(t.tri.getOrPut(key) { HashMap() }, next, weight, now)
    }

    private fun decayed(e: Entry, now: Long): Float {
        val elapsed = (now - e.lastSeenMs).coerceAtLeast(0L)
        val exponent = -(elapsed.toDouble() / halfLifeMs.toDouble())
        return (e.count * Math.pow(2.0, exponent)).toFloat()
    }

    private fun tableScores(t: Table, prev2: String?, prev1: String, now: Long): Map<String, Scored> {
        val triMap: Map<String, Entry>? = prev2?.let { t.tri[it + SEP + prev1] }?.takeIf { it.isNotEmpty() }
        var biMap: Map<String, Entry>? = t.bi[prev1]?.takeIf { it.isNotEmpty() }
        if (biMap == null) {
            val stemmed = PersonalNgramTokenizer.stem(prev1)
            if (stemmed != null) {
                biMap = t.bi["~$stemmed"]?.takeIf { it.isNotEmpty() }
            }
        }
        val uniMap: Map<String, Entry>? = t.uni.takeIf { it.isNotEmpty() }

        if (triMap == null && biMap == null && uniMap == null) return emptyMap()

        val triTotal = triMap?.values?.sumOf { decayed(it, now).toDouble() }?.toFloat() ?: 0f
        val biTotal = biMap?.values?.sumOf { decayed(it, now).toDouble() }?.toFloat() ?: 0f
        val uniTotal = uniMap?.values?.sumOf { decayed(it, now).toDouble() }?.toFloat() ?: 0f

        val has3 = triMap != null && triTotal > 0f
        val has2 = biMap != null && biTotal > 0f
        val has1 = uniMap != null && uniTotal > 0f

        var l3 = if (has3) LAMBDA_3 else 0f
        var l2 = if (has2) LAMBDA_2 else 0f
        var l1 = if (has1) LAMBDA_1 else 0f
        val sum = l3 + l2 + l1
        if (sum <= 0f) return emptyMap()
        l3 /= sum; l2 /= sum; l1 /= sum

        val words = HashSet<String>()
        if (has3) words.addAll(triMap.keys)
        if (has2) words.addAll(biMap.keys)
        if (has1) words.addAll(uniMap.keys)
        words.remove(START)

        val result = HashMap<String, Scored>(words.size)
        for (w in words) {
            var evidence = 0f
            var level = 0
            var prob = 0f
            if (has3) {
                triMap[w]?.let { entry ->
                    val d = decayed(entry, now)
                    prob += l3 * (d / triTotal)
                    if (level < 3) { evidence = d; level = 3 }
                }
            }
            if (has2) {
                biMap[w]?.let { entry ->
                    val d = decayed(entry, now)
                    prob += l2 * (d / biTotal)
                    if (level < 2) { evidence = d; level = 2 }
                }
            }
            if (has1) {
                uniMap[w]?.let { entry ->
                    val d = decayed(entry, now)
                    prob += l1 * (d / uniTotal)
                    if (level < 1) { evidence = d; level = 1 }
                }
            }
            result[w] = Scored(prob, evidence, level)
        }
        return result
    }

    private fun combinedScores(prev2: String?, prev1: String, category: String, now: Long): Map<String, Scored> {
        val categoryScores = tableScores(tableFor(category), prev2, prev1, now)
        val starScores = tableScores(tableFor("*"), prev2, prev1, now)
        if (categoryScores.isEmpty()) return starScores
        val keys = HashSet<String>()
        keys.addAll(categoryScores.keys)
        keys.addAll(starScores.keys)
        val merged = HashMap<String, Scored>(keys.size)
        for (w in keys) {
            val c = categoryScores[w]
            val s = starScores[w]
            val prob = (c?.probability ?: 0f) + 0.5f * (s?.probability ?: 0f)
            val provenance = when {
                c == null -> s
                s == null -> c
                s.level > c.level -> s
                s.level < c.level -> c
                s.evidence > c.evidence -> s
                else -> c
            }
            val evidence = provenance?.evidence ?: 0f
            val level = provenance?.level ?: 0
            merged[w] = Scored(prob, evidence, level)
        }
        return merged
    }

    private fun unigramProbabilities(t: Table, now: Long): Map<String, Float> {
        if (t.uni.isEmpty()) return emptyMap()
        val total = t.uni.values.sumOf { decayed(it, now).toDouble() }.toFloat()
        if (total <= 0f) return emptyMap()
        val result = HashMap<String, Float>(t.uni.size)
        for ((w, e) in t.uni) {
            if (w == START) continue
            result[w] = decayed(e, now) / total
        }
        return result
    }

    private fun combinedUnigramProbabilities(category: String, now: Long): Map<String, Float> {
        val categoryP1 = unigramProbabilities(tableFor(category), now)
        val starP1 = unigramProbabilities(tableFor("*"), now)
        if (categoryP1.isEmpty()) return starP1
        val keys = HashSet<String>()
        keys.addAll(categoryP1.keys)
        keys.addAll(starP1.keys)
        val merged = HashMap<String, Float>(keys.size)
        for (w in keys) {
            merged[w] = (categoryP1[w] ?: 0f) + 0.5f * (starP1[w] ?: 0f)
        }
        return merged
    }

    private fun pruneUni(t: Table, now: Long) {
        if (t.uni.size <= maxUnigrams) return
        val target = (maxUnigrams * 0.9).toInt()
        val sorted = t.uni.entries.sortedBy { decayed(it.value, now) }
        val toRemove = t.uni.size - target
        for (i in 0 until toRemove) {
            t.uni.remove(sorted[i].key)
        }
    }

    private fun pruneNested(map: HashMap<String, HashMap<String, Entry>>, max: Int, now: Long) {
        val total = map.values.sumOf { it.size }
        if (total <= max) return
        val target = (max * 0.9).toInt()
        val flat = mutableListOf<Triple<String, String, Entry>>()
        for ((prev, inner) in map) {
            for ((next, e) in inner) {
                flat.add(Triple(prev, next, e))
            }
        }
        flat.sortBy { decayed(it.third, now) }
        var toRemove = total - target
        var idx = 0
        while (toRemove > 0 && idx < flat.size) {
            val (prev, next, _) = flat[idx]
            map[prev]?.remove(next)
            idx++
            toRemove--
        }
        val emptyKeys = map.filterValues { it.isEmpty() }.keys.toList()
        for (key in emptyKeys) map.remove(key)
    }

    private fun serializeTable(t: Table): JSONObject {
        val obj = JSONObject()
        val uniJson = JSONObject()
        for ((w, e) in t.uni) {
            uniJson.put(w, JSONArray().apply { put(e.count.toDouble()); put(e.lastSeenMs) })
        }
        obj.put("uni", uniJson)
        obj.put("bi", serializeNested(t.bi))
        obj.put("tri", serializeNested(t.tri))
        return obj
    }

    private fun serializeNested(map: HashMap<String, HashMap<String, Entry>>): JSONObject {
        val obj = JSONObject()
        for ((prev, inner) in map) {
            val innerJson = JSONObject()
            for ((next, e) in inner) {
                innerJson.put(next, JSONArray().apply { put(e.count.toDouble()); put(e.lastSeenMs) })
            }
            obj.put(prev, innerJson)
        }
        return obj
    }

    private fun load() {
        val vf = vaultFile ?: return
        if (!vf.exists()) return
        runCatching {
            val raw = vf.readTextAndMigrate() ?: return@runCatching
            if (raw.isBlank()) return@runCatching
            val root = JSONObject(raw)
            val tablesJson = root.optJSONObject("tables") ?: return@runCatching
            val starLearned = root.optInt("learned", 0)
            val starLast = root.optLong("last", 0L)
            val keys = tablesJson.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val tableJson = tablesJson.optJSONObject(name) ?: continue
                val t = Table()
                val uniJson = tableJson.optJSONObject("uni")
                if (uniJson != null) {
                    val uniKeys = uniJson.keys()
                    while (uniKeys.hasNext()) {
                        val w = uniKeys.next()
                        val arr = uniJson.optJSONArray(w) ?: continue
                        if (arr.length() >= 2) {
                            t.uni[w] = Entry(arr.getDouble(0).toFloat(), arr.getLong(1))
                        }
                    }
                }
                deserializeNested(tableJson.optJSONObject("bi"), t.bi)
                deserializeNested(tableJson.optJSONObject("tri"), t.tri)
                if (name == "*") {
                    t.learnedSentences = starLearned
                    t.lastLearnedMs = starLast
                }
                tables[name] = t
            }
            for (t in tables.values) {
                for (w in t.uni.keys) {
                    if (w == START) continue
                    ensureCompletionIndexed(w)
                }
            }
        }
    }

    private fun deserializeNested(json: JSONObject?, target: HashMap<String, HashMap<String, Entry>>) {
        if (json == null) return
        val keys = json.keys()
        while (keys.hasNext()) {
            val prev = keys.next()
            val innerJson = json.optJSONObject(prev) ?: continue
            val innerMap = HashMap<String, Entry>()
            val innerKeys = innerJson.keys()
            while (innerKeys.hasNext()) {
                val next = innerKeys.next()
                val arr = innerJson.optJSONArray(next) ?: continue
                if (arr.length() >= 2) {
                    innerMap[next] = Entry(arr.getDouble(0).toFloat(), arr.getLong(1))
                }
            }
            target[prev] = innerMap
        }
    }
}
