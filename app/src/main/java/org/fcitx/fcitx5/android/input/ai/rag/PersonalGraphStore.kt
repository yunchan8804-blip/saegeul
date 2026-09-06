/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import org.fcitx.fcitx5.android.input.ai.vault.PlainVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-device store for the "personal knowledge graph" the companion enrichment pipeline builds
 * from the user's own sentences: a small set of frequent words/phrases (nodes), their pairwise
 * relations (edges) and topic clusters. Kept entirely in memory, persisted encrypted at rest, and
 * consumed purely as a local re-ranking signal via [proximityBoost] - no network, no model.
 */
class PersonalGraphStore(
    private val storeFile: File? = null,
    private val cipher: VaultCipher = PlainVaultCipher,
    private val clock: () -> Long = System::currentTimeMillis
) {

    data class Node(val id: String, val tags: List<String>, val weight: Float)

    data class Edge(val a: String, val b: String, val weight: Float)

    data class Topic(val id: String, val label: String, val members: List<String>)

    data class GraphStats(val nodes: Int, val edges: Int, val topics: Int, val builtMs: Long)

    // node id -> Node
    private val nodes = HashMap<String, Node>()

    // normalized (sorted) node-id pair -> Edge. A structured pair key (not a joined string) so a
    // node id that is itself a multi-word phrase can never collide with a different pair.
    private val edges = HashMap<Pair<String, String>, Edge>()

    private var topics: List<Topic> = emptyList()

    private var graphBuiltMs: Long = 0L

    private val vaultFile: VaultFile? = storeFile?.let { VaultFile(it, cipher, VaultFile.aadFor(it.name)) }

    init {
        load()
    }

    /**
     * Replaces the whole graph. Nodes are capped to the top [MAX_NODES] by weight, edges to the
     * top [MAX_EDGES] by weight after dropping any edge referring to a node that did not make the
     * cut, and topics are capped to [MAX_TOPICS]. Updates memory only; call [save] to persist.
     */
    @Synchronized
    fun replaceGraph(nodes: List<Node>, edges: List<Edge>, topics: List<Topic>, builtMs: Long) {
        this.nodes.clear()
        this.edges.clear()
        nodes.sortedByDescending { it.weight }
            .take(MAX_NODES)
            .forEach { this.nodes[it.id] = it }
        edges
            .filter { this.nodes.containsKey(it.a) && this.nodes.containsKey(it.b) }
            .sortedByDescending { it.weight }
            .take(MAX_EDGES)
            .forEach { this.edges[edgeKey(it.a, it.b)] = it }
        this.topics = topics.take(MAX_TOPICS)
        this.graphBuiltMs = builtMs
    }

    @Synchronized
    fun stats(): GraphStats = GraphStats(nodes.size, edges.size, topics.size, graphBuiltMs)

    @Synchronized
    fun clear() {
        nodes.clear()
        edges.clear()
        topics = emptyList()
        graphBuiltMs = 0L
        vaultFile?.delete()
    }

    /**
     * Local re-ranking signal: how strongly [candidateStems] are connected to [contextStems] in
     * the graph, via either a direct edge or shared topic membership. Returns 1.0 (no boost) when
     * the graph is empty or nothing is connected; otherwise `1.0 + 0.10 * min(connectedPairs, 3)`,
     * so the boost never exceeds 1.30.
     */
    @Synchronized
    fun proximityBoost(contextStems: Set<String>, candidateStems: Set<String>): Float {
        if (nodes.isEmpty()) return 1.0f
        val contextNodes = contextStems.filter { nodes.containsKey(it) }
        val candidateNodes = candidateStems.filter { nodes.containsKey(it) }
        if (contextNodes.isEmpty() || candidateNodes.isEmpty()) return 1.0f

        var linkCount = 0
        outer@ for (c in contextNodes) {
            for (k in candidateNodes) {
                if (c == k) continue
                val connected = edges.containsKey(edgeKey(c, k)) || sameTopic(c, k)
                if (connected) {
                    linkCount++
                    if (linkCount >= PROXIMITY_MAX_LINKS) break@outer
                }
            }
        }
        val strength = linkCount.coerceAtMost(PROXIMITY_MAX_LINKS)
        return 1.0f + PROXIMITY_PER_LINK * strength
    }

    @Synchronized
    fun save() {
        val vf = vaultFile ?: return
        runCatching {
            val root = JSONObject()
            root.put("v", 1)
            root.put("built", graphBuiltMs)
            val nodesArr = JSONArray()
            nodes.values.forEach { n ->
                val o = JSONObject()
                o.put("id", n.id)
                val tagsArr = JSONArray()
                n.tags.forEach { tagsArr.put(it) }
                o.put("tags", tagsArr)
                o.put("w", n.weight.toDouble())
                nodesArr.put(o)
            }
            root.put("nodes", nodesArr)
            val edgesArr = JSONArray()
            edges.values.forEach { e ->
                val o = JSONObject()
                o.put("a", e.a)
                o.put("b", e.b)
                o.put("w", e.weight.toDouble())
                edgesArr.put(o)
            }
            root.put("edges", edgesArr)
            val topicsArr = JSONArray()
            topics.forEach { t ->
                val o = JSONObject()
                o.put("id", t.id)
                o.put("label", t.label)
                val membersArr = JSONArray()
                t.members.forEach { membersArr.put(it) }
                o.put("members", membersArr)
                topicsArr.put(o)
            }
            root.put("topics", topicsArr)
            vf.writeText(root.toString())
        }
    }

    private fun sameTopic(a: String, b: String): Boolean =
        topics.any { it.members.contains(a) && it.members.contains(b) }

    private fun edgeKey(a: String, b: String): Pair<String, String> = if (a <= b) a to b else b to a

    private fun load() {
        val vf = vaultFile ?: return
        if (!vf.exists()) return
        runCatching {
            vf.migrateIfLegacy()
            val raw = vf.readText() ?: return
            if (raw.isBlank()) return
            val root = JSONObject(raw)
            graphBuiltMs = root.optLong("built", 0L)

            val nodesArr = root.optJSONArray("nodes") ?: JSONArray()
            for (i in 0 until nodesArr.length()) {
                val o = nodesArr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isBlank()) continue
                val tagsArr = o.optJSONArray("tags")
                val tags = mutableListOf<String>()
                if (tagsArr != null) {
                    for (j in 0 until tagsArr.length()) tags.add(tagsArr.optString(j, ""))
                }
                val weight = o.optDouble("w", 1.0).toFloat()
                nodes[id] = Node(id, tags, weight)
            }

            val edgesArr = root.optJSONArray("edges") ?: JSONArray()
            for (i in 0 until edgesArr.length()) {
                val o = edgesArr.optJSONObject(i) ?: continue
                val a = o.optString("a", "")
                val b = o.optString("b", "")
                if (a.isBlank() || b.isBlank()) continue
                if (!nodes.containsKey(a) || !nodes.containsKey(b)) continue
                val weight = o.optDouble("w", 1.0).toFloat()
                edges[edgeKey(a, b)] = Edge(a, b, weight)
            }

            val topicsArr = root.optJSONArray("topics") ?: JSONArray()
            val loadedTopics = mutableListOf<Topic>()
            for (i in 0 until topicsArr.length()) {
                val o = topicsArr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                val label = o.optString("label", "")
                val membersArr = o.optJSONArray("members")
                val members = mutableListOf<String>()
                if (membersArr != null) {
                    for (j in 0 until membersArr.length()) members.add(membersArr.optString(j, ""))
                }
                loadedTopics.add(Topic(id, label, members))
            }
            topics = loadedTopics
        }.onFailure {
            nodes.clear()
            edges.clear()
            topics = emptyList()
            graphBuiltMs = 0L
        }
    }

    companion object {
        private const val MAX_NODES = 2000
        private const val MAX_EDGES = 6000
        private const val MAX_TOPICS = 200
        private const val PROXIMITY_MAX_LINKS = 3
        private const val PROXIMITY_PER_LINK = 0.10f
    }
}
