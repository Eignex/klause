package com.eignex.klause.factor.global.internals

import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList

/**
 * Minimal Edmonds-Karp max-flow over an integer-capacity graph stored as parallel arrays.
 * Edges come in forward/reverse pairs; even indices are forward edges (with the original
 * capacity), odd indices are residual reverses (initially zero). Flow pushed on edge `e`
 * shows up as `originalCap - cap.get(e)` for forward edges.
 *
 * Reusable across propagate calls: [reset] grows the adjacency array on demand, clears the
 * live `[0, numNodes)` lists, and empties the parallel edge arrays — so a fire refills the
 * same backing instead of allocating a fresh graph (the dominant per-fire GCC allocation).
 */
internal class GccFlowBuilder {
    private var adj: Array<IntArrayList> = emptyArray()
    private val edgeTo = IntArrayList()
    private val cap = IntArrayList()
    private val originalCap = IntArrayList()

    var numNodes: Int = 0
        private set

    private var parentEdge = EmptyIntArray
    private var bfsQueue = EmptyIntArray
    private var level = EmptyIntArray
    private var edgeIter = EmptyIntArray

    fun reset(nodes: Int) {
        if (adj.size < nodes) {
            val old = adj
            adj = Array(nodes) { if (it < old.size) old[it] else IntArrayList() }
        }
        if (parentEdge.size < nodes) {
            parentEdge = IntArray(nodes)
            bfsQueue = IntArray(nodes)
            level = IntArray(nodes)
            edgeIter = IntArray(nodes)
        }
        for (i in 0 until nodes) adj[i].clear()
        edgeTo.clear()
        cap.clear()
        originalCap.clear()
        numNodes = nodes
    }

    fun augmentThroughEdge(source: Int, sink: Int, viaU: Int, viaV: Int): Boolean {
        var viaEdge = -1
        val nu = adj[viaU]
        for (k in 0 until nu.size) {
            val e = nu[k]
            if (edgeTo[e] == viaV && cap[e] > 0) {
                viaEdge = e
                break
            }
        }
        if (viaEdge < 0) return false
        val parent = parentEdge
        parent.fill(-1, 0, numNodes)
        parent[source] = -2
        val q = bfsQueue
        var h = 0
        var t = 0
        q[t++] = source
        var found = false
        while (h < t && !found) {
            val u = q[h++]
            if (u == viaU) {
                if (parent[viaV] == -1) {
                    parent[viaV] = viaEdge
                    if (viaV == sink) found = true else q[t++] = viaV
                }
            } else {
                val neigh = adj[u]
                for (k in 0 until neigh.size) {
                    val e = neigh[k]
                    val v = edgeTo[e]
                    if (parent[v] != -1 || cap[e] <= 0) continue
                    parent[v] = e
                    if (v == sink) {
                        found = true
                        break
                    }
                    q[t++] = v
                }
            }
        }
        if (!found) return false
        var bottleneck = Int.MAX_VALUE
        var cur = sink
        while (cur != source) {
            val e = parent[cur]
            if (cap[e] < bottleneck) bottleneck = cap[e]
            cur = edgeTo[e xor 1]
        }
        cur = sink
        while (cur != source) {
            val e = parent[cur]
            cap[e] = cap[e] - bottleneck
            cap[e xor 1] = cap[e xor 1] + bottleneck
            cur = edgeTo[e xor 1]
        }
        return true
    }

    fun addEdge(u: Int, v: Int, c: Int): Int {
        val eIdx = edgeTo.size
        edgeTo.add(v)
        cap.add(c)
        originalCap.add(c)
        adj[u].add(eIdx)
        edgeTo.add(u)
        cap.add(0)
        originalCap.add(0)
        adj[v].add(eIdx + 1)
        return eIdx
    }

    fun flowOf(eIdx: Int): Int = originalCap[eIdx] - cap[eIdx]

    fun residualReachable(source: Int): BooleanArray {
        val seen = BooleanArray(numNodes)
        val queue = IntArray(numNodes)
        var qHead = 0
        var qTail = 0
        seen[source] = true
        queue[qTail++] = source
        while (qHead < qTail) {
            val u = queue[qHead++]
            val neigh = adj[u]
            for (i in 0 until neigh.size) {
                val eIdx = neigh[i]
                if (cap[eIdx] <= 0) continue
                val v = edgeTo[eIdx]
                if (!seen[v]) {
                    seen[v] = true
                    queue[qTail++] = v
                }
            }
        }
        return seen
    }

    /**
     * Dinic's max-flow: repeatedly build a BFS level graph over the residual edges, then saturate it with
     * a blocking flow (a DFS that advances a per-node edge cursor so each edge is examined once per phase).
     * `O(V²E)` in general and near `O(E√V)` on the unit-capacity variable→value matching graphs the GCC
     * propagator builds — far below plain Edmonds-Karp's per-augmentation BFS when a matching needs `V`
     * augmenting paths. Any maximum flow is equally valid for the Régin GAC filtering that reads the final
     * residual graph, so the value is unchanged.
     */
    fun maxFlow(source: Int, sink: Int): Int {
        var total = 0
        val srcAdj = adj[source]
        for (k in 0 until srcAdj.size) total += flowOf(srcAdj[k])
        val level = this.level
        val iter = edgeIter
        val queue = bfsQueue
        while (true) {
            level.fill(-1, 0, numNodes)
            level[source] = 0
            var qHead = 0
            var qTail = 0
            queue[qTail++] = source
            while (qHead < qTail) {
                val u = queue[qHead++]
                val neigh = adj[u]
                for (k in 0 until neigh.size) {
                    val eIdx = neigh[k]
                    if (cap[eIdx] <= 0) continue
                    val v = edgeTo[eIdx]
                    if (level[v] < 0) {
                        level[v] = level[u] + 1
                        queue[qTail++] = v
                    }
                }
            }
            if (level[sink] < 0) break
            iter.fill(0, 0, numNodes)
            while (true) {
                val pushed = blockingDfs(source, sink, Int.MAX_VALUE, level, iter)
                if (pushed == 0) break
                total += pushed
            }
        }
        return total
    }

    /** Push flow along one level-respecting residual path from [u] to [sink], bounded by [limit]. The
     *  per-node cursor [iter] skips edges already exhausted this phase, so the blocking flow stays linear
     *  in the edges. Recursion depth is the level-graph height — a handful for the GCC matching graph. */
    private fun blockingDfs(u: Int, sink: Int, limit: Int, level: IntArray, iter: IntArray): Int {
        if (u == sink) return limit
        val neigh = adj[u]
        while (iter[u] < neigh.size) {
            val eIdx = neigh[iter[u]]
            val v = edgeTo[eIdx]
            if (cap[eIdx] > 0 && level[v] == level[u] + 1) {
                val pushed = blockingDfs(v, sink, if (limit < cap[eIdx]) limit else cap[eIdx], level, iter)
                if (pushed > 0) {
                    cap[eIdx] = cap[eIdx] - pushed
                    cap[eIdx xor 1] = cap[eIdx xor 1] + pushed
                    return pushed
                }
            }
            iter[u]++
        }
        return 0
    }

    fun computeSccResidual(limit: Int, sccId: IntArray) {
        val nodeAdj = Array(limit) { IntArrayList() }
        for (v in 0 until limit) {
            val neigh = adj[v]
            for (k in 0 until neigh.size) {
                val eIdx = neigh[k]
                val w = edgeTo[eIdx]
                if (w < limit && cap[eIdx] > 0) nodeAdj[v].add(w)
            }
        }
        val res = reginTarjanScc(nodeAdj, limit)
        for (i in 0 until limit) sccId[i] = res[i]
    }
}
