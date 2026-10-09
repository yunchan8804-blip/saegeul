/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import org.fcitx.fcitx5.android.input.ai.PersonalNgramTokenizer
import org.fcitx.fcitx5.android.input.ai.vault.PlainVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultFile
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * On-device store for the "personal knowledge graph" the on-device enrichment pipeline builds
 * from the user's own sentences: a small set of frequent words/phrases (nodes), their pairwise
 * relations (edges) and topic clusters. Kept entirely in memory, persisted encrypted at rest, and
 * consumed purely as a local re-ranking signal via [proximityBoost] - no network, no model.
 */
class PersonalGraphStore(
    private val storeFile: File? = null,
    private val cipher: VaultCipher = PlainVaultCipher
) {

    data class Node(val id: String, val tags: List<String>, val weight: Float)

    data class Edge(val a: String, val b: String, val weight: Float)

    data class Topic(val id: String, val label: String, val members: List<String>)

    data class GraphStats(
        val nodes: Int,
        val edges: Int,
        val topics: Int,
        val builtMs: Long,
        val sourceSentenceCount: Int = 0
    )

    // node id -> Node
    private val nodes = HashMap<String, Node>()

    // alias (canonical id or stem) -> canonical node id, so proximityBoost can match a
    // particle-stripped context/candidate stem back to the node id the graph actually stores.
    private val nodeAliases = HashMap<String, String>()

    // normalized (sorted) node-id pair -> Edge. A structured pair key (not a joined string) so a
    // node id that is itself a multi-word phrase can never collide with a different pair.
    private val edges = HashMap<Pair<String, String>, Edge>()

    private var topics: List<Topic> = emptyList()

    // Raised by clear() so a merge built on an older snapshot cannot bring erased data back.
    private var generation = 0L

    private var graphBuiltMs: Long = 0L

    // Vault sentence count at the time the graph was last built, so the enrichment loop can tell
    // how many new sentences have accumulated since then.
    private var sourceSentenceCount: Int = 0

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
    fun replaceGraph(
        nodes: List<Node>,
        edges: List<Edge>,
        topics: List<Topic>,
        builtMs: Long,
        sourceSentenceCount: Int = 0,
        expectedGeneration: Long? = null
    ): Boolean {
        if (expectedGeneration != null && expectedGeneration != generation) {
            Timber.w("Personal graph update dropped: the graph was erased while it was being built")
            return false
        }
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
        this.sourceSentenceCount = sourceSentenceCount
        rebuildAliases()
        return true
    }

    @Synchronized
    fun stats(): GraphStats = GraphStats(nodes.size, edges.size, topics.size, graphBuiltMs, sourceSentenceCount)

    /**
     * The currently stored graph, for a caller (incremental graph enrichment) that wants to merge a
     * new fragment into it via [PersonalGraphEnricher.mergeGraphs] and write the result back through
     * [replaceGraph] - which still owns cap enforcement and alias rebuilding either way.
     */
    @Synchronized
    fun generation(): Long = generation

    @Synchronized
    fun snapshot(): GraphSnapshot =
        GraphSnapshot(nodes.values.toList(), edges.values.toList(), topics, generation)

    /**
     * The stored graph plus the [generation] it was read at. Destructures as nodes, edges, topics;
     * pass [generation] back to [replaceGraph] so a merge built on this snapshot is dropped when
     * [clear] ran in between.
     */
    data class GraphSnapshot(
        val nodes: List<Node>,
        val edges: List<Edge>,
        val topics: List<Topic>,
        val generation: Long
    )

    @Synchronized
    fun clear() {
        generation++
        nodes.clear()
        edges.clear()
        nodeAliases.clear()
        topics = emptyList()
        graphBuiltMs = 0L
        sourceSentenceCount = 0
        vaultFile?.deleteForUser()
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
        val contextNodes = contextStems.mapNotNull { nodeAliases[it] }.distinct()
        val candidateNodes = candidateStems.mapNotNull { nodeAliases[it] }.distinct()
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
            root.put("src", sourceSentenceCount)
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
        }.getOrThrow()
    }

    private fun sameTopic(a: String, b: String): Boolean =
        topics.any { it.members.contains(a) && it.members.contains(b) }

    private fun edgeKey(a: String, b: String): Pair<String, String> = if (a <= b) a to b else b to a

    private fun load() {
        val vf = vaultFile ?: return
        if (!vf.exists()) return
        val raw = try {
            vf.readTextAndMigrate()
        } catch (exception: Exception) {
            // The file stays read-blocked, so save() cannot replace it with the empty graph.
            Timber.w(exception, "Personal graph could not be read")
            return
        } ?: return
        if (raw.isBlank()) return
        val loaded = try {
            parseGraph(raw)
        } catch (exception: Exception) {
            vf.markUnreadable()
            Timber.w("Personal graph could not be parsed (%s)", exception.javaClass.simpleName)
            return
        }
        graphBuiltMs = loaded.builtMs
        sourceSentenceCount = loaded.sourceSentenceCount
        nodes.putAll(loaded.nodes)
        edges.putAll(loaded.edges)
        topics = loaded.topics
        rebuildAliases()
    }

    private class LoadedGraph(
        val builtMs: Long,
        val sourceSentenceCount: Int,
        val nodes: Map<String, Node>,
        val edges: Map<Pair<String, String>, Edge>,
        val topics: List<Topic>
    )

    /** Parses the whole graph into new collections so a failure midway never leaves a partial graph. */
    private fun parseGraph(raw: String): LoadedGraph {
        val root = JSONObject(raw)
        val loadedNodes = LinkedHashMap<String, Node>()
        val loadedEdges = LinkedHashMap<Pair<String, String>, Edge>()

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
            loadedNodes[id] = Node(id, tags, weight)
        }

        val edgesArr = root.optJSONArray("edges") ?: JSONArray()
        for (i in 0 until edgesArr.length()) {
            val o = edgesArr.optJSONObject(i) ?: continue
            val a = o.optString("a", "")
            val b = o.optString("b", "")
            if (a.isBlank() || b.isBlank()) continue
            if (!loadedNodes.containsKey(a) || !loadedNodes.containsKey(b)) continue
            val weight = o.optDouble("w", 1.0).toFloat()
            loadedEdges[edgeKey(a, b)] = Edge(a, b, weight)
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
        return LoadedGraph(
            builtMs = root.optLong("built", 0L),
            sourceSentenceCount = root.optInt("src", 0),
            nodes = loadedNodes,
            edges = loadedEdges,
            topics = loadedTopics
        )
    }

    /**
     * Rebuilds [nodeAliases] from the current [nodes]: each node id maps to itself, and its
     * particle-stripped [PersonalNgramTokenizer.stem] additionally maps to it whenever that stem
     * is non-empty, different from the id, and not already claimed by another node's own id
     * (canonical ids always take priority over stem aliases).
     */
    private fun rebuildAliases() {
        nodeAliases.clear()
        nodes.keys.forEach { id -> nodeAliases[id] = id }
        nodes.keys.forEach { id ->
            val stem = PersonalNgramTokenizer.stem(id)
            if (!stem.isNullOrEmpty() && stem != id) {
                nodeAliases.putIfAbsent(stem, id)
            }
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
