package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * Global Cardinality Constraint (GCC). Covers the four MiniZinc variants in one factor:
 *
 *  - `global_cardinality(xs, cover, counts)` — `counts[k] = #{i : xs[i] = cover[k]}`. Use
 *    [countVars] (`size = cover.size`) and [closed] = `false`.
 *  - `global_cardinality_low_up(xs, cover, lo, up)` — `lo[k] ≤ #{i : xs[i] = cover[k]} ≤ up[k]`.
 *    Use [countLow] / [countHigh] (constant arrays) and [countVars] = `null`.
 *  - `_closed` variants additionally require every `xs[i] ∈ cover` — i.e. no value outside
 *    the cover set may appear. Pass [closed] = `true`.
 *
 * Exactly one of ([countVars], [countLow]+[countHigh]) is non-null — the constructor
 * validates.
 *
 * Propagation: count-bound tightening (definite/possible matchers per cover value) plus
 * Régin-style max-flow GAC. The flow has lower bounds on `cover_k → sink` (matching the
 * cover lo/hi or current `countVars[k]` domain), is reduced to standard max-flow via the
 * super-source/super-sink trick, solved by Edmonds-Karp, then the residual graph is
 * SCC'd. Any `xᵢ → cover_k` edge with zero flow whose endpoints sit in different SCCs
 * cannot extend to a feasible solution and is pruned from `dom(xᵢ)`.
 */
class GlobalCardinality(
    val xs: IntArray,
    val cover: IntArray,
    val countVars: IntArray? = null,
    val countLow: IntArray? = null,
    val countHigh: IntArray? = null,
    val closed: Boolean = false,
) : LocalSearchFactor {

    init {
        require(xs.isNotEmpty()) { "gcc: empty xs" }
        require(cover.isNotEmpty()) { "gcc: empty cover" }
        if (countVars != null) {
            require(countVars.size == cover.size) { "gcc: countVars size mismatch" }
            require(countLow == null && countHigh == null) { "gcc: pass either countVars OR countLow+countHigh" }
        } else {
            require(countLow != null && countHigh != null) { "gcc: missing countLow/countHigh" }
            require(countLow.size == cover.size && countHigh.size == cover.size) { "gcc: lo/hi size mismatch" }
        }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = run {
        val cv = countVars
        if (cv != null) xs + cv else xs
    }

    private val coverIndexByValue: HashMap<Int, Int> = run {
        val m = HashMap<Int, Int>(cover.size * 2)
        for (i in cover.indices) m[cover[i]] = i
        m
    }

    /** Per-cover-index count under the current assignment. */
    private class State(val counts: IntArray)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val counts = IntArray(cover.size)
        for (x in xs) {
            val value = state.assignment.intValue(x)
            val idx = coverIndexByValue[value] ?: continue  // out-of-cover; counts unaffected
            counts[idx]++
        }
        state.refPayload[factorId] = State(counts)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        // Per-cover constraint check.
        for (k in cover.indices) {
            if (countVars != null) {
                if (state.assignment.intValue(countVars[k]) != s.counts[k]) return true
            } else {
                val cnt = s.counts[k]
                if (cnt < countLow!![k] || cnt > countHigh!![k]) return true
            }
        }
        // Closed variant: every xs[i] must be in cover.
        if (closed) {
            for (x in xs) if (state.assignment.intValue(x) !in coverIndexByValue) return true
        }
        return false
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val wasViolated = isViolated(state, factorId)
        // Simulate the change by adjusting a counts copy.
        val sim = s.counts.copyOf()
        var occurrencesInXs = 0
        for (x in xs) if (x == intVar) occurrencesInXs++
        if (occurrencesInXs > 0) {
            val old = state.assignment.intValue(intVar)
            val oldIdx = coverIndexByValue[old]
            if (oldIdx != null) sim[oldIdx] -= occurrencesInXs
            val newIdx = coverIndexByValue[newValue]
            if (newIdx != null) sim[newIdx] += occurrencesInXs
        }
        val willViolate = simulatedViolation(state, intVar, newValue, sim)
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    private fun simulatedViolation(
        state: LocalSearchState, intVar: Int, newValue: Int, simCounts: IntArray,
    ): Boolean {
        for (k in cover.indices) {
            if (countVars != null) {
                val expected = if (countVars[k] == intVar) newValue
                else state.assignment.intValue(countVars[k])
                if (expected != simCounts[k]) return true
            } else {
                if (simCounts[k] < countLow!![k] || simCounts[k] > countHigh!![k]) return true
            }
        }
        if (closed) {
            for (x in xs) {
                val v = if (x == intVar) newValue else state.assignment.intValue(x)
                if (v !in coverIndexByValue) return true
            }
        }
        return false
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        val wasViolated = state.refPayload[factorId].let { p ->
            // Use the existing isViolated against pre-update counts.
            // The counts haven't been updated yet — we compare against assignment which IS post-update.
            // To compare against pre-update, simulate the inverse.
            val sim = s.counts.copyOf()
            var occ = 0
            for (x in xs) if (x == intVar) occ++
            if (occ > 0) {
                val oldIdx = coverIndexByValue[oldValue]
                val newIdx = coverIndexByValue[cur]
                if (newIdx != null) sim[newIdx] -= occ  // undo post-update
                if (oldIdx != null) sim[oldIdx] += occ  // restore pre-update
            }
            simulatedViolation(state, intVar, oldValue, sim)
        }
        var occurrencesInXs = 0
        for (x in xs) if (x == intVar) occurrencesInXs++
        if (occurrencesInXs > 0) {
            val oldIdx = coverIndexByValue[oldValue]
            val newIdx = coverIndexByValue[cur]
            if (oldIdx != null) s.counts[oldIdx] -= occurrencesInXs
            if (newIdx != null) s.counts[newIdx] += occurrencesInXs
        }
        val nowViolated = isViolated(state, factorId)
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // ---- 1. Count tightening + closure --------------------------------------------
        val n = xs.size
        val m = cover.size
        val coverSet = coverIndexByValue.keys
        // LCG antecedents: the union of every xs var's int trail. Same coarse approach
        // as AllDifferent — every prune/tighten in GCC reasoning involves all xs vars'
        // domains, so attributing to all of them is sound (analyzer minimization shrinks).
        val gccAntecedents = composeGccAntecedents(state)
        if (closed) {
            for (x in xs) {
                val d = state.intDomains[x]
                val toRemove = ArrayList<Int>()
                d.forEach { if (it !in coverSet) toRemove.add(it) }
                for (v in toRemove) if (!state.excludeIntValue(x, v, gccAntecedents)) return false
            }
        }
        val definite = IntArray(m)
        val possible = IntArray(m)
        for (k in cover.indices) {
            val target = cover[k]
            for (x in xs) {
                val d = state.intDomains[x]
                if (d.min == d.max && d.min == target) definite[k]++
                if (target in d) possible[k]++
            }
            if (countVars != null) {
                if (!state.tightenIntMin(countVars[k], definite[k], gccAntecedents)) return false
                if (!state.tightenIntMax(countVars[k], possible[k], gccAntecedents)) return false
            } else {
                if (countLow!![k] > possible[k]) return false
                if (countHigh!![k] < definite[k]) return false
            }
        }

        // Resolve effective [lo_k, hi_k] for cover values (post count-tightening).
        val lo = IntArray(m)
        val hi = IntArray(m)
        for (k in 0 until m) {
            if (countVars != null) {
                val cd = state.intDomains[countVars[k]]
                lo[k] = cd.min
                hi[k] = cd.max
            } else {
                lo[k] = countLow!![k]
                hi[k] = countHigh!![k]
            }
        }

        // Detect whether any xᵢ has an out-of-cover value reachable (drives "other" arc).
        val hasOtherVar = BooleanArray(n)
        var anyOther = !closed && run {
            var any = false
            for (i in 0 until n) {
                val d = state.intDomains[xs[i]]
                var found = false
                d.forEach { if (!found && it !in coverSet) found = true }
                hasOtherVar[i] = found
                if (found) any = true
            }
            any
        }

        // ---- 2. Régin GAC via max-flow -----------------------------------------------
        // Node layout: 0 = S, 1 = T, 2..2+n-1 = var nodes, 2+n..2+n+m-1 = cover nodes,
        // (optional) 2+n+m = other node. Plus SS, ST appended for lower-bound reduction.
        val S = 0
        val T = 1
        val varNode = IntArray(n) { 2 + it }
        val covNode = IntArray(m) { 2 + n + it }
        val otherNode = if (anyOther) 2 + n + m else -1
        val baseNodes = 2 + n + m + (if (anyOther) 1 else 0)
        val SS = baseNodes
        val ST = baseNodes + 1
        val totalNodes = baseNodes + 2

        // Edge list: parallel arrays (to, cap, rev). `headForward[i]` = first forward
        // edge index in `edgeTo` for node i; we just keep flat lists per node.
        val flow = FlowBuilder(totalNodes)

        // S → x_i with bounds [1, 1]: reduces to cap 0, excess[S] -= 1, excess[x_i] += 1.
        // Encoded by accumulating into `excess` and adding zero-cap edge.
        val excess = IntArray(totalNodes)
        // Track xᵢ → cover_k edge indices so we can read out flow + check residual later.
        val xToCovEdgeIdx = Array(n) { IntArray(m) { -1 } }
        val xToOtherEdgeIdx = IntArray(n) { -1 }

        for (i in 0 until n) {
            // S → x_i lower bound 1, upper 1.
            excess[S] -= 1
            excess[varNode[i]] += 1
            // No residual capacity on this edge (l == h).
            flow.addEdge(S, varNode[i], 0)
        }

        for (i in 0 until n) {
            val d = state.intDomains[xs[i]]
            for (k in 0 until m) {
                if (cover[k] in d) {
                    val eIdx = flow.addEdge(varNode[i], covNode[k], 1)  // [0, 1]
                    xToCovEdgeIdx[i][k] = eIdx
                }
            }
            if (otherNode != -1 && hasOtherVar[i]) {
                xToOtherEdgeIdx[i] = flow.addEdge(varNode[i], otherNode, 1)  // [0, 1]
            }
        }

        for (k in 0 until m) {
            if (lo[k] > hi[k]) return false
            // cover_k → T with bounds [lo_k, hi_k]
            excess[covNode[k]] -= lo[k]
            excess[T] += lo[k]
            flow.addEdge(covNode[k], T, hi[k] - lo[k])
        }
        if (otherNode != -1) {
            // other → T with bounds [0, n]; no excess shift.
            flow.addEdge(otherNode, T, n)
        }
        // T → S back-edge to convert s-t feasibility into a circulation: bounds [n, n].
        excess[T] -= n
        excess[S] += n
        flow.addEdge(T, S, 0)

        // SS / ST excess-balancing edges.
        var requiredSSFlow = 0
        for (v in 0 until baseNodes) {
            when {
                excess[v] > 0 -> {
                    flow.addEdge(SS, v, excess[v])
                    requiredSSFlow += excess[v]
                }
                excess[v] < 0 -> flow.addEdge(v, ST, -excess[v])
            }
        }

        // Edmonds-Karp max-flow from SS to ST. If the saturation of all ss-out edges
        // is less than requiredSSFlow → infeasible.
        val obtained = flow.maxFlow(SS, ST)
        if (obtained < requiredSSFlow) return false

        // ---- 3. SCC on residual graph (excluding SS, ST) -----------------------------
        val sccId = IntArray(baseNodes) { -1 }
        flow.computeSccResidual(baseNodes, sccId)

        // ---- 4. Prune zero-flow xᵢ→cover_k edges across SCC boundaries ---------------
        for (i in 0 until n) {
            for (k in 0 until m) {
                val eIdx = xToCovEdgeIdx[i][k]
                if (eIdx < 0) continue
                if (flow.flowOf(eIdx) > 0) continue  // active in current flow; alive.
                if (sccId[varNode[i]] == sccId[covNode[k]]) continue  // may carry flow elsewhere.
                if (!state.excludeIntValue(xs[i], cover[k], gccAntecedents)) return false
            }
            // If the var→other arc exists but cannot carry flow in any feasible flow,
            // every non-cover value in dom(xᵢ) is dead — prune them all.
            val oIdx = xToOtherEdgeIdx[i]
            if (oIdx >= 0 && flow.flowOf(oIdx) == 0 && sccId[varNode[i]] != sccId[otherNode]) {
                val d = state.intDomains[xs[i]]
                val toRemove = ArrayList<Int>()
                d.forEach { if (it !in coverSet) toRemove.add(it) }
                for (v in toRemove) if (!state.excludeIntValue(xs[i], v, gccAntecedents)) return false
            }
        }
        return true
    }

    /** Coarse LCG antecedents: union of every `xs` var's int trail. Used for every
     *  prune / tighten in GCC propagation — minimization can shrink redundancy. */
    private fun composeGccAntecedents(state: PropagationState): IntArray? {
        val seen = HashSet<Int>()
        val out = ArrayList<Int>()
        for (v in xs) {
            state.intMinAntecedents[v]?.let { for (l in it) if (seen.add(l)) out.add(l) }
            state.intMaxAntecedents[v]?.let { for (l in it) if (seen.add(l)) out.add(l) }
        }
        if (out.isEmpty()) return null
        return out.toIntArray()
    }

    /**
     * Minimal Edmonds-Karp max-flow over an integer-capacity graph stored as parallel
     * arrays. Edges come in forward/reverse pairs; even indices are forward edges (with
     * the original capacity), odd indices are residual reverses (initially zero). Flow
     * pushed on edge `e` shows up as `originalCap - cap[e]` for forward edges.
     */
    private class FlowBuilder(val numNodes: Int) {
        private val adj: Array<ArrayList<Int>> = Array(numNodes) { ArrayList() }
        private val edgeTo = ArrayList<Int>()
        private val cap = ArrayList<Int>()
        private val originalCap = ArrayList<Int>()

        fun addEdge(u: Int, v: Int, c: Int): Int {
            val eIdx = edgeTo.size
            edgeTo.add(v); cap.add(c); originalCap.add(c)
            adj[u].add(eIdx)
            edgeTo.add(u); cap.add(0); originalCap.add(0)
            adj[v].add(eIdx + 1)
            return eIdx
        }

        fun flowOf(eIdx: Int): Int = originalCap[eIdx] - cap[eIdx]

        fun maxFlow(source: Int, sink: Int): Int {
            var total = 0
            val parentEdge = IntArray(numNodes)
            val queue = IntArray(numNodes)
            while (true) {
                parentEdge.fill(-1)
                parentEdge[source] = -2
                var qHead = 0; var qTail = 0
                queue[qTail++] = source
                var found = false
                while (qHead < qTail && !found) {
                    val u = queue[qHead++]
                    for (eIdx in adj[u]) {
                        val v = edgeTo[eIdx]
                        if (parentEdge[v] != -1 || cap[eIdx] <= 0) continue
                        parentEdge[v] = eIdx
                        if (v == sink) { found = true; break }
                        queue[qTail++] = v
                    }
                }
                if (!found) break
                // Find bottleneck along the path.
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
                    cap[eIdx] -= bottleneck
                    cap[eIdx xor 1] += bottleneck
                    cur = edgeTo[eIdx xor 1]
                }
                total += bottleneck
            }
            return total
        }

        /** Tarjan SCC over the residual subgraph induced by nodes `[0, limit)`. Edges
         *  with positive remaining capacity (forward residuals + already-used reverses)
         *  define the directed graph. */
        fun computeSccResidual(limit: Int, sccId: IntArray) {
            val index = IntArray(limit) { -1 }
            val lowlink = IntArray(limit)
            val onStack = BooleanArray(limit)
            val tarjanStack = IntArray(limit)
            var stackTop = 0
            var nextIndex = 0
            var nextScc = 0
            val callStack = IntArray(limit + 1)
            val iterStack = IntArray(limit + 1)
            for (start in 0 until limit) {
                if (index[start] != -1) continue
                var depth = 0
                callStack[depth] = start
                iterStack[depth] = 0
                index[start] = nextIndex
                lowlink[start] = nextIndex
                nextIndex++
                tarjanStack[stackTop++] = start
                onStack[start] = true
                while (depth >= 0) {
                    val v = callStack[depth]
                    val neigh = adj[v]
                    val it = iterStack[depth]
                    if (it < neigh.size) {
                        iterStack[depth] = it + 1
                        val eIdx = neigh[it]
                        val w = edgeTo[eIdx]
                        if (w >= limit || cap[eIdx] <= 0) continue
                        if (index[w] == -1) {
                            depth++
                            callStack[depth] = w
                            iterStack[depth] = 0
                            index[w] = nextIndex
                            lowlink[w] = nextIndex
                            nextIndex++
                            tarjanStack[stackTop++] = w
                            onStack[w] = true
                        } else if (onStack[w]) {
                            if (index[w] < lowlink[v]) lowlink[v] = index[w]
                        }
                    } else {
                        if (lowlink[v] == index[v]) {
                            while (true) {
                                val w = tarjanStack[--stackTop]
                                onStack[w] = false
                                sccId[w] = nextScc
                                if (w == v) break
                            }
                            nextScc++
                        }
                        depth--
                        if (depth >= 0) {
                            val parent = callStack[depth]
                            if (lowlink[v] < lowlink[parent]) lowlink[parent] = lowlink[v]
                        }
                    }
                }
            }
        }
    }
}
