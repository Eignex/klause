package com.eignex.klause.factor.global.internals

import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.RevIntArray
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntArrayList

// Edges come in forward/reverse pairs: even indices are forward edges, odd ones their residual reverses.
//
// Two lifetimes. Rebuild (reset + addEdge) refills plain lists per fire, for the count-var, optional and
// first-fire paths. Persistent (freeze) copies the residual capacities onto the undo trail, so the flow
// survives across fires and restores on backtrack: the edge set spans the root domains, and a fire only
// blocks edges whose value left, rerouting any unit they carried.
internal class GccFlowBuilder {
    private var adj: Array<IntArrayList> = emptyArray()
    private val edgeTo = IntArrayList()
    private val cap = IntArrayList()
    private val originalCap = IntArrayList()

    // Non-null once frozen; capGet and capSet route through it so the flow rolls back with the trail.
    private var revCap: RevIntArray? = null

    // 1 when the edge's value left the domain; a removed edge reads as capacity 0 and flow 0.
    private var removed: RevIntArray? = null

    var numNodes: Int = 0
        private set

    private var parentEdge = emptyIntArray()
    private var bfsQueue = emptyIntArray()
    private var level = emptyIntArray()
    private var edgeIter = emptyIntArray()

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
        revCap = null
        removed = null
        numNodes = nodes
    }

    val edgeCount: Int get() = edgeTo.size

    fun freeze(state: PropagationState) {
        val r = RevIntArray(state, cap.size)
        for (e in 0 until cap.size) r[e] = cap[e]
        revCap = r
        removed = RevIntArray(state, cap.size)
    }

    val frozen: Boolean get() = revCap != null

    // Sound only for an edge carrying no flow: the caller first reroutes a carried unit with recoverEdge, or
    // re-solves when no alternate path exists.
    fun blockEdge(eIdx: Int) {
        val r = removed ?: return
        r[eIdx] = 1
        r[eIdx xor 1] = 1
    }

    fun resetFlow() {
        for (e in 0 until edgeTo.size step 2) {
            capSet(e, originalCap[e])
            capSet(e + 1, 0)
        }
    }

    // Cancels the saturated edge tail→head's unit and reroutes it along another residual path, keeping the
    // flow maximum. On false the unit is restored and the caller falls back to a full re-solve.
    fun recoverEdge(e: Int, tail: Int, head: Int): Boolean {
        capSet(e, capGet(e) + 1)
        capSet(e xor 1, capGet(e xor 1) - 1)
        if (bfsReroute(tail, head, e)) return true
        capSet(e, capGet(e) - 1)
        capSet(e xor 1, capGet(e xor 1) + 1)
        return false
    }

    private fun bfsReroute(s: Int, t: Int, ex: Int): Boolean {
        val parent = parentEdge
        parent.fill(-1, 0, numNodes)
        parent[s] = -2
        val q = bfsQueue
        var h = 0
        var tl = 0
        q[tl++] = s
        var found = false
        while (h < tl && !found) {
            val u = q[h++]
            val neigh = adj[u]
            for (k in 0 until neigh.size) {
                val e = neigh[k]
                if (e == ex || e == (ex xor 1)) continue
                val v = edgeTo[e]
                if (parent[v] != -1 || capGet(e) <= 0) continue
                parent[v] = e
                if (v == t) {
                    found = true
                    break
                }
                q[tl++] = v
            }
        }
        if (!found) return false
        var cur = t
        while (cur != s) {
            val e = parent[cur]
            capSet(e, capGet(e) - 1)
            capSet(e xor 1, capGet(e xor 1) + 1)
            cur = edgeTo[e xor 1]
        }
        return true
    }

    fun seedAssignment(replay: GccAssignmentReplay, variable: Int, value: Int, choice: Int) {
        val supply = replay.supply[variable]
        if (capGet(supply) == 0 || capGet(choice) == 0) return
        val minimum = replay.minimum[value]
        if (minimum >= 0 && capGet(minimum) > 0) {
            pushUnit(minimum)
        } else {
            val aboveMinimum = replay.aboveMinimum[value]
            val remainingTotal = replay.remainingTotal
            if (remainingTotal < 0 || capGet(aboveMinimum) == 0 || capGet(remainingTotal) == 0) return
            pushUnit(aboveMinimum)
            pushUnit(remainingTotal)
        }
        pushUnit(supply)
        pushUnit(choice)
    }

    private fun pushUnit(edge: Int) {
        capSet(edge, capGet(edge) - 1)
        capSet(edge xor 1, capGet(edge xor 1) + 1)
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

    // O(V²E) in general and near O(E√V) on unit-capacity matching graphs. Any maximum flow serves the
    // residual-SCC filtering that reads the result.
    // Dinitz, "Algorithm for solution of a problem of maximum flow in networks with power estimation",
    // Soviet Math. Doklady 11, 1970.
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

    // The per-node cursor skips edges exhausted this phase, keeping the blocking flow linear in the edges.
    // Recursion depth is the level-graph height, a handful for the matching graph.
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

    private var sccIndex = emptyIntArray()
    private var sccLow = emptyIntArray()
    private var sccOnStack = BooleanArray(0)
    private var sccStk = emptyIntArray()
    private var sccCall = emptyIntArray()
    private var sccIter = emptyIntArray()

    // Iterative index/lowlink pass straight over the residual edges: materializing an adjacency would be a top
    // cost on cardinality-heavy propagation. Callers read the partition only, not label values.
    // Tarjan, "Depth-first search and linear graph algorithms", SIAM J. Comput. 1(2), 1972,
    // https://doi.org/10.1137/0201010
    fun computeSccResidual(limit: Int, sccId: IntArray) {
        if (sccIndex.size < limit) {
            sccIndex = IntArray(limit)
            sccLow = IntArray(limit)
            sccOnStack = BooleanArray(limit)
            sccStk = IntArray(limit)
            sccCall = IntArray(limit)
            sccIter = IntArray(limit)
        }
        val index = sccIndex
        val low = sccLow
        val onStack = sccOnStack
        val stk = sccStk
        val call = sccCall
        val iter = sccIter
        for (i in 0 until limit) {
            index[i] = -1
            onStack[i] = false
            sccId[i] = -1
        }
        var stackTop = 0
        var nextIndex = 0
        var nextScc = 0
        for (start in 0 until limit) {
            if (index[start] != -1) continue
            var depth = 0
            call[0] = start
            iter[0] = 0
            index[start] = nextIndex
            low[start] = nextIndex
            nextIndex++
            stk[stackTop++] = start
            onStack[start] = true
            while (depth >= 0) {
                val v = call[depth]
                val neigh = adj[v]
                var it = iter[depth]
                var w = -1
                while (it < neigh.size) {
                    val e = neigh[it]
                    val t = edgeTo[e]
                    if (t < limit && capGet(e) > 0) {
                        w = t
                        break
                    }
                    it++
                }
                if (w >= 0) {
                    iter[depth] = it + 1
                    if (index[w] == -1) {
                        depth++
                        call[depth] = w
                        iter[depth] = 0
                        index[w] = nextIndex
                        low[w] = nextIndex
                        nextIndex++
                        stk[stackTop++] = w
                        onStack[w] = true
                    } else if (onStack[w] && index[w] < low[v]) {
                        low[v] = index[w]
                    }
                } else {
                    if (low[v] == index[v]) {
                        while (true) {
                            val x = stk[--stackTop]
                            onStack[x] = false
                            sccId[x] = nextScc
                            if (x == v) break
                        }
                        nextScc++
                    }
                    depth--
                    if (depth >= 0) {
                        val p = call[depth]
                        if (low[v] < low[p]) low[p] = low[v]
                    }
                }
            }
        }
    }
}
