package com.eignex.klause.solver.lp

/**
 * Arc-indicator model for one [com.eignex.klause.solver.factor.Circuit]: the LP columns of the
 * binary arc variables `y_ij` (1 iff `succ[i] = j`) that the base relaxation creates under the
 * circuit-arc flag, plus the node count. The degree and channelling rows tying these to the
 * integer `succ` columns live in the relaxation; this record is what a [CircuitSeparator] needs to
 * read the fractional arc values back and separate subtour-elimination cuts.
 */
internal class CircuitArcModel(
    /** Number of nodes. */
    val n: Int,
    /** `arcCol[i][j]` = the LP column for arc `i → j`, or -1 when that arc does not exist. */
    val arcCol: Array<IntArray>,
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
        val cap = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in 0 until n) {
                val c = model.arcCol[i][j]
                if (c >= 0) {
                    val y = ctx.solution.primal(c)
                    if (y > TOL) cap[i][j] = y
                }
            }
        }
        val root = 0
        val seen = HashSet<Long>()
        for (t in 1 until n) {
            val (flow, reach) = maxFlowMinCut(n, cap, root, t)
            if (flow >= 1.0 - TOL) continue
            // S = the source-reachable side of the minimum cut: contains root, excludes t.
            var key = 0L
            for (i in 0 until n) if (reach[i]) key = key or (1L shl i)
            if (!seen.add(key)) continue
            val cols = ArrayList<Int>()
            for (i in 0 until n) {
                if (!reach[i]) continue
                for (j in 0 until n) {
                    if (reach[j]) continue
                    val c = model.arcCol[i][j]
                    if (c >= 0) cols.add(c)
                }
            }
            if (cols.isEmpty()) continue
            cuts.add(Cut(cols.toIntArray(), LongArray(cols.size) { 1L }, Relation.GE, 1L))
            if (cuts.size >= MAX_CUTS) return
        }
    }

    /**
     * Edmonds–Karp max flow from [s] to [t] over directed capacities [cap], returning the flow value
     * and the source-reachable set of the residual graph (the minimum cut's source side).
     */
    private fun maxFlowMinCut(n: Int, cap: Array<DoubleArray>, s: Int, t: Int): Pair<Double, BooleanArray> {
        val res = Array(n) { cap[it].copyOf() }
        var flow = 0.0
        while (true) {
            val parent = IntArray(n) { -1 }
            parent[s] = s
            val queue = ArrayDeque<Int>()
            queue.add(s)
            while (queue.isNotEmpty()) {
                val u = queue.removeFirst()
                for (v in 0 until n) {
                    if (parent[v] == -1 && res[u][v] > TOL) {
                        parent[v] = u
                        queue.add(v)
                    }
                }
            }
            if (parent[t] == -1) break
            var bottleneck = Double.MAX_VALUE
            var v = t
            while (v != s) {
                val u = parent[v]
                if (res[u][v] < bottleneck) bottleneck = res[u][v]
                v = u
            }
            v = t
            while (v != s) {
                val u = parent[v]
                res[u][v] -= bottleneck
                res[v][u] += bottleneck
                v = u
            }
            flow += bottleneck
        }
        val reach = BooleanArray(n)
        reach[s] = true
        val queue = ArrayDeque<Int>()
        queue.add(s)
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            for (v in 0 until n) {
                if (!reach[v] && res[u][v] > TOL) {
                    reach[v] = true
                    queue.add(v)
                }
            }
        }
        return flow to reach
    }

    private companion object {
        const val TOL: Double = 1e-6
        const val MAX_CUTS: Int = 64
    }
}
