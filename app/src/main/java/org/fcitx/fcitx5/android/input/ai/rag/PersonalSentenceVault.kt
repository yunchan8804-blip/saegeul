/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import org.fcitx.fcitx5.android.input.ai.KoreanPiiScrubber
import org.fcitx.fcitx5.android.input.ai.PersonalNgramTokenizer
import org.fcitx.fcitx5.android.input.ai.TypingDnaVault
import org.fcitx.fcitx5.android.input.ai.vault.PlainVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ln
import kotlin.math.pow

/**
 * On-device personal sentence RAG (retrieval-augmented) vault.
 *
 * Stores committed Korean sentences (PII-scrubbed, encrypted at rest) and retrieves the ones most
 * relevant to what the user is currently typing via a classic BM25 inverted index kept entirely in
 * memory. No LLM, no embeddings, no network: this is a local search index over the user's own past
 * sentences, so they can be resurfaced as continuation candidates.
 */
class PersonalSentenceVault(
    private val storeFile: File? = null,
    private val cipher: VaultCipher = PlainVaultCipher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxSentences: Int = 3000,
    private val halfLifeMs: Long = 45L * 24 * 60 * 60 * 1000,
) {

    data class Retrieved(val sentence: String, val score: Float, val startsWithLastWord: Boolean)

    data class VaultStats(val sentences: Int, val uniqueTerms: Int)

    private data class Doc(
        val id: String,
        val text: String,
        val category: String,
        val lastSeenMs: Long,
        val count: Int,
        // Cached once at index time; doc.text never changes for a given id, so retrieval and
        // eviction reuse these tokens instead of re-tokenizing the sentence on every query.
        val tokens: List<String>
    )

    // docId == normalized (scrubbed, trimmed) sentence text.
    private val docs = HashMap<String, Doc>()

    // term -> (docId -> term frequency in that doc)
    private val postings = HashMap<String, HashMap<String, Int>>()

    // docId -> token count
    private val docLen = HashMap<String, Int>()

    private val vaultFile: VaultFile? = storeFile?.let { VaultFile(it, cipher, VaultFile.aadFor(it.name)) }
    private val persistenceLock = Any()

    init {
        load()
    }

    /**
     * Records a confirmed sentence. Returns false (and does not store it) when the sentence
     * scrubs to fewer than two tokens, since a single word is not worth surfacing later as a
     * "sentence I once wrote". Updates memory only; call [save] to persist to disk.
     */
    @Synchronized
    fun record(sentence: String, packageName: String): Boolean {
        val scrubbed = KoreanPiiScrubber.scrub(sentence).trim()
        if (scrubbed.isEmpty()) return false
        val tokens = PersonalNgramTokenizer.tokenize(scrubbed)
        if (tokens.size < 2) return false

        val now = clock()
        val existing = docs[scrubbed]
        if (existing != null) {
            docs[scrubbed] = existing.copy(count = existing.count + 1, lastSeenMs = now)
            return true
        }

        val doc = Doc(
            id = scrubbed,
            text = scrubbed,
            category = TypingDnaVault.categorizePackage(packageName),
            lastSeenMs = now,
            count = 1,
            tokens = tokens
        )
        docs[scrubbed] = doc
        indexDoc(doc)
        enforceCapacity(now)
        return true
    }

    /**
     * Retrieves up to [limit] previously recorded sentences relevant to [contextBeforeCursor],
     * ranked by BM25 score (k1=1.2, b=0.75) with a category-match boost (x1.3), a recency decay
     * boost (halving every [halfLifeMs]), and a continuation boost (x1.5) for sentences whose
     * leading tokens already match the full query so far - useful to resume typing from.
     */
    @Synchronized
    fun retrieve(contextBeforeCursor: String, packageName: String, limit: Int = 5): List<Retrieved> {
        if (docs.isEmpty()) return emptyList()
        val scrubbedContext = KoreanPiiScrubber.scrub(contextBeforeCursor).trim()
        val queryTokens = PersonalNgramTokenizer.tokenize(scrubbedContext)
        if (queryTokens.isEmpty()) return emptyList()

        val n = docs.size
        val avgDocLen = docLen.values.sum().toDouble() / n
        val category = TypingDnaVault.categorizePackage(packageName)
        val now = clock()

        val scores = HashMap<String, Double>()

        fun addTermScore(term: String, weight: Double) {
            val posting = postings[term] ?: return
            val df = posting.size
            if (df == 0) return
            val idf = ln(1.0 + (n - df + 0.5) / (df + 0.5))
            posting.forEach { (docId, tf) ->
                val dl = docLen[docId] ?: 0
                val denom = tf + BM25_K1 * (1.0 - BM25_B + BM25_B * dl / avgDocLen)
                val bm25 = idf * (tf * (BM25_K1 + 1.0)) / denom
                scores[docId] = (scores[docId] ?: 0.0) + bm25 * weight
            }
        }

        for (token in queryTokens.toSet()) {
            addTermScore(token, 1.0)
            val stemmed = PersonalNgramTokenizer.stem(token)
            if (stemmed != null && stemmed != token) {
                addTermScore(stemmed, STEM_BACKOFF_WEIGHT)
            }
        }
        if (scores.isEmpty()) return emptyList()

        val results = mutableListOf<Retrieved>()
        for ((docId, rawScore) in scores) {
            if (rawScore <= 0.0) continue
            val doc = docs[docId] ?: continue
            if (doc.text == scrubbedContext) continue // exclude the in-progress sentence itself

            var score = rawScore
            if (doc.category == category) score *= CATEGORY_BOOST
            score *= decayFactor(doc.lastSeenMs, now)

            val docTokens = doc.tokens
            val startsWith = docTokens.size >= queryTokens.size &&
                docTokens.subList(0, queryTokens.size) == queryTokens
            if (startsWith) score *= CONTINUATION_BOOST

            results.add(Retrieved(doc.text, score.toFloat(), startsWith))
        }

        return results.sortedByDescending { it.score }.take(limit)
    }

    @Synchronized
    fun stats(): VaultStats = VaultStats(sentences = docs.size, uniqueTerms = postings.size)

    /**
     * Exports up to [limit] stored sentences (already PII-scrubbed) for the companion
     * enrichment pipeline, ranked by [decayedCount] descending so the sentences that are still
     * most "alive" (recent and/or repeated) are sent first. Read-only; does not affect retrieval.
     */
    @Synchronized
    fun exportForEnrichment(limit: Int): List<String> {
        if (docs.isEmpty()) return emptyList()
        val now = clock()
        return docs.values
            .sortedByDescending { decayedCount(it, now) }
            .take(limit)
            .map { it.text }
    }

    fun clear() {
        synchronized(persistenceLock) {
            synchronized(this) {
                docs.clear()
                postings.clear()
                docLen.clear()
            }
            vaultFile?.delete()
        }
    }

    fun save() {
        val vf = vaultFile ?: return
        synchronized(persistenceLock) {
            runCatching {
                val snapshot = synchronized(this) {
                    val root = JSONObject()
                    root.put("v", 1)
                    val arr = JSONArray()
                    docs.values.forEach { doc ->
                        val o = JSONObject()
                        o.put("t", doc.text)
                        o.put("cnt", doc.count)
                        o.put("cat", doc.category)
                        o.put("ls", doc.lastSeenMs)
                        arr.put(o)
                    }
                    root.put("docs", arr)
                    root
                }
                vf.writeText(snapshot.toString())
            }
        }
    }

    private fun indexDoc(doc: Doc) {
        docLen[doc.id] = doc.tokens.size
        val tf = HashMap<String, Int>()
        doc.tokens.forEach { tf[it] = (tf[it] ?: 0) + 1 }
        tf.forEach { (term, freq) ->
            postings.getOrPut(term) { HashMap() }[doc.id] = freq
        }
    }

    private fun removeDoc(docId: String) {
        val doc = docs.remove(docId) ?: return
        docLen.remove(docId)
        doc.tokens.toSet().forEach { term ->
            val posting = postings[term] ?: return@forEach
            posting.remove(docId)
            if (posting.isEmpty()) postings.remove(term)
        }
    }

    private fun enforceCapacity(now: Long) {
        val excess = docs.size - maxSentences
        if (excess <= 0) return
        // record() calls this right after inserting one doc, so excess is normally 1; a single
        // min scan is enough there and avoids sorting the whole vault on every sentence commit.
        if (excess == 1) {
            docs.values.minByOrNull { decayedCount(it, now) }?.let { removeDoc(it.id) }
            return
        }
        docs.values
            .sortedBy { decayedCount(it, now) }
            .take(excess)
            .forEach { removeDoc(it.id) }
    }

    private fun decayFactor(lastSeenMs: Long, now: Long): Double =
        2.0.pow(-(now - lastSeenMs).coerceAtLeast(0L).toDouble() / halfLifeMs)

    private fun decayedCount(doc: Doc, now: Long): Double = doc.count * decayFactor(doc.lastSeenMs, now)

    private fun load() {
        val vf = vaultFile ?: return
        if (!vf.exists()) return
        runCatching {
            val raw = vf.readTextAndMigrate() ?: return
            if (raw.isBlank()) return
            val root = JSONObject(raw)
            val arr = root.optJSONArray("docs") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val text = o.optString("t", "")
                if (text.isBlank()) continue
                val tokens = PersonalNgramTokenizer.tokenize(text)
                if (tokens.size < 2) continue
                val doc = Doc(
                    id = text,
                    text = text,
                    category = o.optString("cat", TypingDnaVault.CATEGORY_GENERAL),
                    lastSeenMs = o.optLong("ls", clock()),
                    count = o.optInt("cnt", 1),
                    tokens = tokens
                )
                docs[doc.text] = doc
                indexDoc(doc)
            }
        }.onFailure {
            docs.clear()
            postings.clear()
            docLen.clear()
        }
    }

    companion object {
        private const val BM25_K1 = 1.2
        private const val BM25_B = 0.75
        private const val STEM_BACKOFF_WEIGHT = 0.5
        private const val CATEGORY_BOOST = 1.3
        private const val CONTINUATION_BOOST = 1.5
    }
}
