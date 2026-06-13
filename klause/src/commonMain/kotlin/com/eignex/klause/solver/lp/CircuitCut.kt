package com.eignex.klause.solver.lp

/**
 * Arc-indicator model for one [com.eignex.klause.solver.factor.Circuit]: the LP columns of the
 * binary arc variables `y_ij` (1 iff `succ(i) = j`) that the base relaxation creates under the
 * circuit-arc flag, plus the node count. The degree and channelling rows tying these to the integer
 * `succ` columns live in the relaxation; this record is what a [CircuitSeparator] needs to read the
 * fractional arc values back and separate subtour-elimination cuts.
 *
 * Arcs are stored **sparsely** — parallel per-arc arrays, one entry per candidate arc — so the model
 * is O(arcs), not O(n²). That lets the relaxation scale to large but sparse routing graphs (small
 * per-node successor domains) instead of being capped at a small node count (#431).
 */
internal class CircuitArcModel(
    /** Number of nodes. */
    val n: Int,
    /** Tail node of arc `k`. Parallel to [heads] / [cols]. */
    val tails: IntArray,
    /** Head node of arc `k`. */
    val heads: IntArray,
    /** LP column of arc `k` (`tails(k) → heads(k)`). */
    val cols: IntArray,
)

/**
 * Genuine subtour-elimination cuts for [com.eignex.klause.solver.factor.Circuit]. The base
 * relaxation's degree rows (each node has exactly one out- and one in-arc) make every *integer*
 * arc solution a permutation — a disjoint union of cycles. A Hamiltonian circuit is the single-cycle
 * case, so the gap to feasibility is exactly the subtours, removed by the directed cutset inequality
 *
 *   `Σ_{i∈S, j∉S} y_ij ≥ 1`   for every proper node subset `S` (the cycle must leave `S`).
 *
 * There are exponentially many such `S`; they are separated exactly by max-flow. With the fractional
 * `y` as directed arc capacities, the minimum `root → t` cut equals the max flow; a value below 1
 * means the source side of that cut is a violated subtour-elimination constraint. Separating over
 * every sink `t ≠ root` finds a violated cut whenever one exists. Every emitted cut is a valid
 * inequality of the Hamiltonian-circuit polytope, so the bound stays sound.
 *
 * Max-flow runs over the **sparse** fractional support (paired-adjacency residual graph; the reverse
 * of edge `e` is `e xor 1`), so separation cost scales with the number of fractional arcs rather than
 * n² — the relaxation is no longer limited to a small node count (#431).
 */
internal class CircuitSeparator : CutSeparator {
    override fun separate(ctx: CutContext): List<Cut> {
        val cuts = ArrayList<Cut>()
        for (model in ctx.relaxation.circuitArcs) {
            separateModel(model, ctx, cuts)
            if (cuts.size >= MAX_CUTS) break
        }
        return cuts
    }

    private fun separateModel(model: CircuitArcModel, ctx: CutContext, cuts: MutableList<Cut>) {
        val n = model.n
        if (n < 3) return // no proper subtour possible
        // Residual graph over the fractional support: a forward edge (capacity y) and a zero-capacity
        // reverse per arc with y > TOL, in paired adjacency (the reverse of edge e is e xor 1).
        var support = 0
        for (k in model.cols.indices) if (ctx.solution.primal(model.cols[k]) > TOL) support++
        if (support == 0) return
        val eTo = IntArray(2 * support)
        val eNext = IntArray(2 * support)
        val capOrig = DoubleArray(2 * support)
        val eHead = IntArray(n) { -1 }
        var ec = 0
        for (k in model.cols.indices) {
            val y = ctx.solution.primal(model.cols[k])
            if (y <= TOL) continue
            val u = model.tails[k]
            val v = model.heads[k]
            eTo[ec] = v
            capOrig[ec] = y
            eNext[ec] = eHead[u]
            eHead[u] = ec
            ec++
            eTo[ec] = u
            capOrig[ec] = 0.0
            eNext[ec] = eHead[v]
            eHead[v] = ec
            ec++
        }
        val root = 0
        val seen = HashSet<Long>()
        for (t in 1 until n) {
            val (flow, reach) = maxFlowMinCut(n, eHead, eTo, eNext, capOrig, root, t)
            if (flow >= 1.0 - TOL) continue
            // Dedup the source side S by a content hash — n may exceed 64, so a Long set-bitmask
            // would collide. A hash collision only ever drops a distinct cut (sound, never unsound).
            var key = HASH_SEED
            for (i in 0 until n) key = key * HASH_MULT + if (reach[i]) 1L else 0L
            if (!seen.add(key)) continue
            val cols = ArrayList<Int>()
            for (k in model.cols.indices) {
                if (reach[model.tails[k]] && !reach[model.heads[k]]) cols.add(model.cols[k])
            }
            if (cols.isEmpty()) continue
            // A cutset inequality of the Hamiltonian-circuit polytope holds for every circuit — global.
            cuts.add(Cut(cols.toIntArray(), LongArray(cols.size) { 1L }, Relation.GE, 1L, global = true))
            if (cuts.size >= MAX_CUTS) return
        }
    }

    /**
     * Edmonds–Karp max flow from [s] to [t] over the paired-adjacency residual graph
     * ([eHead] / [eTo] / [eNext] / [capOrig]; the reverse of edge `e` is `e xor 1`, and `eTo(e xor 1)`
     * is therefore the tail of `e`). Returns the flow value and the source-reachable set of the
     * residual graph — the minimum cut's source side. The capacities are copied per call, so the
     * separator can reuse the same graph across every sink.
     */
    @Suppress("LongParameterList")
    private fun maxFlowMinCut(
        n: Int,
        eHead: IntArray,
        eTo: IntArray,
        eNext: IntArray,
        capOrig: DoubleArray,
        s: Int,
        t: Int,
    ): Pair<Double, BooleanArray> {
        val cap = capOrig.copyOf()
        val parentEdge = IntArray(n)
        var flow = 0.0
        while (true) {
            parentEdge.fill(-1)
            parentEdge[s] = -2 // source visited; no parent edge
            val queue = ArrayDeque<Int>()
            queue.add(s)
            while (queue.isNotEmpty()) {
                val u = queue.removeFirst()
                var e = eHead[u]
                while (e != -1) {
                    val v = eTo[e]
                    if (parentEdge[v] == -1 && cap[e] > TOL) {
                        parentEdge[v] = e
                        queue.add(v)
                    }
                    e = eNext[e]
                }
            }
            if (parentEdge[t] == -1) break
            var bottleneck = Double.MAX_VALUE
            var v = t
            while (v != s) {
                val e = parentEdge[v]
                if (cap[e] < bottleneck) bottleneck = cap[e]
                v = eTo[e xor 1]
            }
            v = t
            while (v != s) {
                val e = parentEdge[v]
                cap[e] -= bottleneck
                cap[e xor 1] += bottleneck
                v = eTo[e xor 1]
            }
            flow += bottleneck
        }
        val reach = BooleanArray(n)
        reach[s] = true
        val queue = ArrayDeque<Int>()
        queue.add(s)
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            var e = eHead[u]
            while (e != -1) {
                val v = eTo[e]
                if (!reach[v] && cap[e] > TOL) {
                    reach[v] = true
                    queue.add(v)
                }
                e = eNext[e]
            }
        }
        return flow to reach
    }

    private companion object {
        const val TOL: Double = 1e-6
        const val MAX_CUTS: Int = 64
        const val HASH_SEED: Long = 1125899906842597L
        const val HASH_MULT: Long = 1000003L
    }
}
