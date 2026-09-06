/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import org.json.JSONObject

/**
 * Orchestrates the companion enrichment pipeline: batches the user's own committed sentences from
 * [vault], sends them to the companion LLM CLI (via the injected [enrich] `generate` callback) to
 * extract a "personal knowledge graph", parses the returned JSON, merges it across chunks, and
 * persists the result into [graphStore]. No network code lives here - [enrich]'s caller wires the
 * actual companion transport.
 */
class PersonalGraphEnricher(
    private val vault: PersonalSentenceVault,
    private val graphStore: PersonalGraphStore,
    private val clock: () -> Long = System::currentTimeMillis
) {

    data class EnrichResult(val ok: Boolean, val reason: String, val nodes: Int, val edges: Int, val topics: Int)

    /**
     * Exports up to [maxSentences] sentences from [vault], batches them into chunks of at most
     * [maxCharsPerChunk] characters, sends each chunk through [generate] with
     * [ENRICHMENT_INSTRUCTION], and merges the parsed graph fragments it gets back. Replaces the
     * stored graph and persists it only if at least one chunk parsed successfully; on total
     * failure the existing graph is left untouched.
     */
    suspend fun enrich(
        generate: suspend (instruction: String, input: String) -> List<String>,
        maxSentences: Int = 400,
        maxCharsPerChunk: Int = 3500
    ): EnrichResult {
        val sentences = vault.exportForEnrichment(maxSentences)
        if (sentences.isEmpty()) return EnrichResult(false, "no_data", 0, 0, 0)

        val chunks = chunkSentences(sentences, maxCharsPerChunk)

        val mergedNodes = LinkedHashMap<String, PersonalGraphStore.Node>()
        val mergedEdges = LinkedHashMap<Pair<String, String>, PersonalGraphStore.Edge>()
        val mergedTopics = LinkedHashMap<String, PersonalGraphStore.Topic>()
        var successCount = 0
        var failCount = 0

        for (chunk in chunks) {
            val outs = generate(ENRICHMENT_INSTRUCTION, chunk)
            val raw = outs.firstOrNull()
            val parsed = raw?.let { parseChunk(it) }
            if (parsed == null) {
                failCount++
                continue
            }
            successCount++
            mergeNodes(mergedNodes, parsed.nodes)
            mergeEdges(mergedEdges, parsed.edges)
            mergeTopics(mergedTopics, parsed.topics)
        }

        if (successCount == 0) return EnrichResult(false, "parse_failed", 0, 0, 0)

        graphStore.replaceGraph(
            mergedNodes.values.toList(),
            mergedEdges.values.toList(),
            mergedTopics.values.toList(),
            clock()
        )
        graphStore.save()

        val reason = if (failCount == 0) "ok" else "partial"
        val stats = graphStore.stats()
        return EnrichResult(true, reason, stats.nodes, stats.edges, stats.topics)
    }

    /** Greedily packs sentences (newline-joined) into chunks no longer than [maxCharsPerChunk]. */
    private fun chunkSentences(sentences: List<String>, maxCharsPerChunk: Int): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            val piece = sentence.take(HARD_CHAR_CAP)
            when {
                current.isEmpty() -> current.append(piece)
                current.length + 1 + piece.length <= maxCharsPerChunk -> current.append('\n').append(piece)
                else -> {
                    chunks.add(current.toString())
                    current.clear()
                    current.append(piece)
                }
            }
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }

    private data class ParsedChunk(
        val nodes: List<PersonalGraphStore.Node>,
        val edges: List<PersonalGraphStore.Edge>,
        val topics: List<PersonalGraphStore.Topic>
    )

    private fun parseChunk(raw: String): ParsedChunk? = runCatching {
        val root = JSONObject(raw)

        val nodes = mutableListOf<PersonalGraphStore.Node>()
        val nodesArr = root.optJSONArray("nodes")
        if (nodesArr != null) {
            for (i in 0 until nodesArr.length()) {
                val o = nodesArr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isBlank()) continue
                val tagsArr = o.optJSONArray("tags")
                val tags = mutableListOf<String>()
                if (tagsArr != null) {
                    for (j in 0 until tagsArr.length()) tags.add(tagsArr.optString(j, ""))
                }
                val w = o.optDouble("w", 1.0).toFloat()
                nodes.add(PersonalGraphStore.Node(id, tags, w))
            }
        }

        val edges = mutableListOf<PersonalGraphStore.Edge>()
        val edgesArr = root.optJSONArray("edges")
        if (edgesArr != null) {
            for (i in 0 until edgesArr.length()) {
                val o = edgesArr.optJSONObject(i) ?: continue
                val a = o.optString("a", "")
                val b = o.optString("b", "")
                if (a.isBlank() || b.isBlank()) continue
                val w = o.optDouble("w", 1.0).toFloat()
                edges.add(PersonalGraphStore.Edge(a, b, w))
            }
        }

        val topics = mutableListOf<PersonalGraphStore.Topic>()
        val topicsArr = root.optJSONArray("topics")
        if (topicsArr != null) {
            for (i in 0 until topicsArr.length()) {
                val o = topicsArr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                val label = o.optString("label", "")
                if (id.isBlank() || label.isBlank()) continue
                val membersArr = o.optJSONArray("members")
                val members = mutableListOf<String>()
                if (membersArr != null) {
                    for (j in 0 until membersArr.length()) members.add(membersArr.optString(j, ""))
                }
                topics.add(PersonalGraphStore.Topic(id, label, members))
            }
        }

        ParsedChunk(nodes, edges, topics)
    }.getOrNull()

    private fun mergeNodes(target: LinkedHashMap<String, PersonalGraphStore.Node>, nodes: List<PersonalGraphStore.Node>) {
        nodes.forEach { n ->
            val existing = target[n.id]
            target[n.id] = if (existing == null) {
                n
            } else {
                existing.copy(weight = existing.weight + n.weight, tags = (existing.tags + n.tags).distinct())
            }
        }
    }

    private fun mergeEdges(target: LinkedHashMap<Pair<String, String>, PersonalGraphStore.Edge>, edges: List<PersonalGraphStore.Edge>) {
        edges.forEach { e ->
            val key = if (e.a <= e.b) e.a to e.b else e.b to e.a
            val (x, y) = key
            val existing = target[key]
            target[key] = if (existing == null) {
                PersonalGraphStore.Edge(x, y, e.weight)
            } else {
                existing.copy(weight = existing.weight + e.weight)
            }
        }
    }

    private fun mergeTopics(target: LinkedHashMap<String, PersonalGraphStore.Topic>, topics: List<PersonalGraphStore.Topic>) {
        topics.forEach { t ->
            val existing = target[t.label]
            target[t.label] = if (existing == null) {
                t
            } else {
                existing.copy(members = (existing.members + t.members).distinct())
            }
        }
    }

    companion object {
        private const val HARD_CHAR_CAP = 4000

        const val ENRICHMENT_INSTRUCTION = """다음은 한 사용자가 실제로 입력한 한국어 문장들이다. 이 문장들에서 자주 쓰는 핵심 어절/구절과 그 사이의 관계를 추출해 개인 지식 그래프를 만들어라. 다른 설명 없이, 정확히 1개의 제안으로 아래 형식의 JSON 객체 문자열만 반환하라: {"nodes":[{"id":"어절","tags":["주제"],"w":가중치}],"edges":[{"a":"어절1","b":"어절2","w":관계강도}],"topics":[{"id":"t0","label":"주제명","members":["어절"]}]}. 개인정보(이름·번호 등)는 노드로 만들지 마라. Return exactly 1 suggestion."""
    }
}
