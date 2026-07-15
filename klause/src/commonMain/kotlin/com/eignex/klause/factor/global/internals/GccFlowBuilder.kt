package com.eignex.klause.factor.global.internals

import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.RevIntArray
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList

/**
 * Integer max-flow (Dinic's) over a graph stored as parallel arrays. Edges come in forward/reverse
 * pairs; even indices are forward edges (original capacity), odd indices are residual reverses
 * (initially zero). Flow pushed on edge `e` is `originalCap - cap(e)` for forward edges.
 *
 * Two lifetimes:
 *  - **Rebuild** ([reset] + [addEdge]): the structure and residual capacities live in plain
 *    `IntArrayList`s, refilled per fire — used for the count-var / optional / first-fire paths.
 *  - **Persistent** ([freeze]): after a build the residual capacities are copied onto the engine undo
 *    trail ([RevIntArray]), so the flow *survives across fires and restores on backtrack* — the
 *    variable→value edge set is invariant (built for the root, widest domains), and a fire only blocks
 *    the edges whose value left ([blockEdge]) and tops the flow back up. This removes the per-fire
 *    network rebuild and the O(n) warm-start replay (#669).
 */
internal class GccFlowBuilder {
    private var adj: Array<IntArrayList> = emptyArray()
    private val edgeTo = IntArrayList()
    private val cap = IntArrayList()
    private val originalCap = IntArrayList()

    /** Non-null once [freeze]d: the reversible residual capacities. Reads/writes route through
     *  [capGet]/[capSet] so the flow rolls back with the engine trail on backtrack. */
    private var revCap: RevIntArray? = null

    /** Non-null once [freeze]d: per-edge reversible removed flag (1 = the value left the domain).
     *  A removed edge reads as capacity 0 and flow 0 everywhere, and restores on backtrack. */
    private var removed: RevIntArray? = null

    var numNodes: Int = 0
        private set

    private var parentEdge = EmptyIntArray
    private var bfsQueue = EmptyIntArray
    private var level = EmptyIntArray
    private var edgeIter = EmptyIntArray

    private fun isRemoved(e: Int): Boolean = (removed?.get(e) ?: 0) == 1

    private fun capGet(e: Int): Int = if (isRemoved(e)) 0 else (revCap?.get(e) ?: cap[e])

    private fun capSet(e: Int, v: Int) {
        val r = revCap
        if (r != null) r[e] = v else cap[e] = v
    }

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
        revCap = null // back to build mode
        removed = null
        numNodes = nodes
    }

    /** Number of directed edges (forward+reverse pairs count as two). */
    val edgeCount: Int get() = edgeTo.size

    /** Copy the just-built residual capacities onto the engine undo trail so the flow persists across
     *  fires and restores on backtrack. Called once after a [reset]+[addEdge] build establishes the
     *  initial feasible max flow; subsequent fires mutate the flow reversibly via [blockEdge]/[maxFlow]. */
    fun freeze(state: PropagationState) {
        val r = RevIntArray(state, cap.size)
        for (e in 0 until cap.size) r[e] = cap[e]
        revCap = r
        removed = RevIntArray(state, cap.size)
    }

    /** Whether the flow is persistent (frozen onto the trail). */
    val frozen: Boolean get() = revCap != null

    /** Mark forward edge [eIdx] (and its reverse) removed — its value left the domain. Reversible: it
     *  reads as capacity/flow 0 until a backtrack restores it. Sound only for an edge carrying no flow
     *  ([flowOf] `== 0`); the caller rebuilds when a flow-carrying edge is removed, since re-routing that
     *  variable's unit is what the rebuild does. */
    fun blockEdge(eIdx: Int) {
        val r = removed ?: return
        r[eIdx] = 1
        r[eIdx xor 1] = 1
    }

    /** Restore every edge to its original capacity with zero flow, keeping the built structure (the
     *  non-persistent per-fire counterpart to [reset]+re-`addEdge`). */
    fun resetFlow() {
        for (e in 0 until edgeTo.size step 2) {
            capSet(e, originalCap[e])
            capSet(e + 1, 0)
        }
    }

    fun augmentThroughEdge(source: Int, sink: Int, viaU: Int, viaV: Int): Boolean {
        var viaEdge = -1
        val nu = adj[viaU]
        for (k in 0 until nu.size) {
            val e = nu[k]
            if (edgeTo[e] == viaV && capGet(e) > 0) {
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
                    if (parent[v] != -1 || capGet(e) <= 0) continue
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
            if (capGet(e) < bottleneck) bottleneck = capGet(e)
            cur = edgeTo[e xor 1]
        }
        cur = sink
        while (cur != source) {
            val e = parent[cur]
            capSet(e, capGet(e) - bottleneck)
            capSet(e xor 1, capGet(e xor 1) + bottleneck)
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

    fun flowOf(eIdx: Int): Int = if (isRemoved(eIdx)) 0 else originalCap[eIdx] - (revCap?.get(eIdx) ?: cap[eIdx])

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
                if (capGet(eIdx) <= 0) continue
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
     * propagator builds. Any maximum flow is equally valid for the Régin GAC filtering that reads the final
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
                    if (capGet(eIdx) <= 0) continue
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
            val ce = capGet(eIdx)
            if (ce > 0 && level[v] == level[u] + 1) {
                val pushed = blockingDfs(v, sink, if (limit < ce) limit else ce, level, iter)
                if (pushed > 0) {
                    capSet(eIdx, capGet(eIdx) - pushed)
                    capSet(eIdx xor 1, capGet(eIdx xor 1) + pushed)
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
                if (w < limit && capGet(eIdx) > 0) nodeAdj[v].add(w)
            }
        }
        val res = reginTarjanScc(nodeAdj, limit)
        for (i in 0 until limit) sccId[i] = res[i]
    }
}
