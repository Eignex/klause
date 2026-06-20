package com.eignex.klause.solver.factor.global.internals

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

    private var parentEdge = IntArray(0)
    private var bfsQueue = IntArray(0)

    fun reset(nodes: Int) {
        if (adj.size < nodes) {
            val old = adj
            adj = Array(nodes) { if (it < old.size) old[it] else IntArrayList() }
        }
        if (parentEdge.size < nodes) {
            parentEdge = IntArray(nodes)
            bfsQueue = IntArray(nodes)
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

    fun maxFlow(source: Int, sink: Int): Int {
        var total = 0
        val srcAdj = adj[source]
        for (k in 0 until srcAdj.size) total += flowOf(srcAdj[k])
        val parentEdge = this.parentEdge
        val queue = bfsQueue
        while (true) {
            parentEdge.fill(-1, 0, numNodes)
            parentEdge[source] = -2
            var qHead = 0
            var qTail = 0
            queue[qTail++] = source
            var found = false
            while (qHead < qTail && !found) {
                val u = queue[qHead++]
                val neigh = adj[u]
                for (k in 0 until neigh.size) {
                    val eIdx = neigh[k]
                    val v = edgeTo[eIdx]
                    if (parentEdge[v] != -1 || cap[eIdx] <= 0) continue
                    parentEdge[v] = eIdx
                    if (v == sink) {
                        found = true
                        break
                    }
                    queue[qTail++] = v
                }
            }
            if (!found) break
            var bottleneck = Int.MAX_VALUE
            var cur = sink
            while (cur != source) {
                val eIdx = parentEdge[cur]
                if (cap[eIdx] < bottleneck) bottleneck = cap[eIdx]
                cur = edgeTo[eIdx xor 1]
            }
            cur = sink
            while (cur != source) {
                val eIdx = parentEdge[cur]
                cap[eIdx] = cap[eIdx] - bottleneck
                cap[eIdx xor 1] = cap[eIdx xor 1] + bottleneck
                cur = edgeTo[eIdx xor 1]
            }
            total += bottleneck
        }
        return total
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
