package com.eignex.klause.lp.cut

import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.engine.Relation
import com.eignex.util.collections.IntArrayDeque
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongHashSet

// LP columns of a circuit's arc indicators `y_ij` (1 iff `succ(i) = j`), stored per candidate arc so the
// model is O(arcs) rather than O(n²).
internal class CircuitArcModel(
    val n: Int,
    val tails: IntArray,
    val heads: IntArray,
    val cols: IntArray,
)

// The degree rows make every integer arc solution a union of cycles, so the gap to a Hamiltonian circuit is
// the subtours, removed by `Σ_{i∈S, j∉S} y_ij ≥ 1` for every proper node subset `S` (Dantzig, Fulkerson and
// Johnson, "Solution of a large-scale traveling-salesman problem", Oper. Res. 1954,
// https://doi.org/10.1287/opre.2.4.393). A `root → t` max flow below 1 over the fractional support exposes a
// violated `S` as the min cut's source side; trying every sink finds one whenever one exists.
internal class CircuitSeparator : CutSeparator {
    override val family: CutFamily = CutFamily.CIRCUIT

    private val queue = IntArrayDeque()

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
        if (n < 3) return
        // Paired adjacency over the fractional support: the reverse of edge e is e xor 1.
        var support = 0
        for (k in model.cols.indices) if (ctx.primalOf(model.cols[k]) > TOL) support++
        if (support == 0) return
        val eTo = IntArray(2 * support)
        val eNext = IntArray(2 * support)
        val capOrig = DoubleArray(2 * support)
        val eHead = IntArray(n) { -1 }
        var ec = 0
        for (k in model.cols.indices) {
            val y = ctx.primalOf(model.cols[k])
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
        val seen = LongHashSet()
        val scratch = CircuitFlowScratch(n, capOrig.size)
        val reach = scratch.reach
        for (t in 1 until n) {
            val flow = maxFlowMinCut(eHead, eTo, eNext, capOrig, root, t, scratch)
            if (flow >= 1.0 - TOL) continue
            // A hash collision only drops a distinct cut, never admits a wrong one.
            var key = HASH_SEED
            for (i in 0 until n) key = key * HASH_MULT + if (reach[i]) 1L else 0L
            if (!seen.add(key)) continue
            val cols = IntArrayList()
            for (k in model.cols.indices) {
                if (reach[model.tails[k]] && !reach[model.heads[k]]) cols.add(model.cols[k])
            }
            if (cols.isEmpty()) continue
            cuts.add(Cut(cols.toIntArray(), LongArray(cols.size) { 1L }, Relation.GE, 1L, global = true))
            if (cuts.size >= MAX_CUTS) return
        }
    }

    // Edmonds–Karp (Edmonds and Karp, J. ACM 1972, https://doi.org/10.1145/321694.321699). Leaves the min cut's
    // source side in scratch.reach; capacities are copied per call so the graph is reused across sinks.
    @Suppress("LongParameterList")
    private fun maxFlowMinCut(
        eHead: IntArray,
        eTo: IntArray,
        eNext: IntArray,
        capOrig: DoubleArray,
        s: Int,
        t: Int,
        scratch: CircuitFlowScratch,
    ): Double {
        val cap = scratch.cap
        val parentEdge = scratch.parentEdge
        val reach = scratch.reach
        capOrig.copyInto(cap)
        var flow = 0.0
        while (true) {
            parentEdge.fill(-1)
            parentEdge[s] = -2
            queue.clear()
            queue.addLast(s)
            while (queue.isNotEmpty()) {
                val u = queue.removeFirst()
                var e = eHead[u]
                while (e != -1) {
                    val v = eTo[e]
                    if (parentEdge[v] == -1 && cap[e] > TOL) {
                        parentEdge[v] = e
                        queue.addLast(v)
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
        reach.fill(false)
        reach[s] = true
        queue.clear()
        queue.addLast(s)
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            var e = eHead[u]
            while (e != -1) {
                val v = eTo[e]
                if (!reach[v] && cap[e] > TOL) {
                    reach[v] = true
                    queue.addLast(v)
                }
                e = eNext[e]
            }
        }
        return flow
    }

    private companion object {
        const val TOL: Double = 1e-6
        const val MAX_CUTS: Int = 64
        const val HASH_SEED: Long = 1125899906842597L
        const val HASH_MULT: Long = 1000003L
    }
}

private class CircuitFlowScratch(nodes: Int, edges: Int) {
    val cap = DoubleArray(edges)
    val parentEdge = IntArray(nodes)
    val reach = BooleanArray(nodes)
}
