package com.eignex.klause.lp.cut

import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.util.IntHashSet

/**
 * Clique cuts for set-packing structure. Two Boolean variables are *mutually exclusive* when at most
 * one can be true; a set of pairwise mutually exclusive variables is a clique, and `Σ_{clique} x ≤ 1`
 * is a valid inequality. The conflict graph is read straight off the problem: a binary clause
 * `¬a ∨ ¬b` is an edge, and an at-most-one constraint (a `Cardinality` with `max = 1`, or a unit-weight
 * `Σ x ≤ 1` PseudoBoolean) over positive literals is a base clique whose members are all pairwise
 * adjacent. Each base clique is greedily extended with the highest-fractional variables that are
 * adjacent to every current member — keeping it a true clique — and the cut is emitted when the
 * extended clique's LP value exceeds 1. The base constraint alone is already in the relaxation; the
 * value is the extension across constraints.
 */
internal class CliqueCutSeparator : CutSeparator {
    private val tol = 1e-6

    override fun separate(ctx: CutContext): List<Cut> {
        val graph = conflictGraph(ctx.problem)
        val adj = graph.adjacency
        val baseCliques = graph.baseCliques
        if (baseCliques.isEmpty()) return emptyList()

        // Variables with a Boolean column, ordered by descending fractional value — the extension order.
        val adjKeys = ArrayList<Int>(adj.size)
        adj.forEach { k, _ -> adjKeys.add(k) }
        val ranked = adjKeys
            .filter { ctx.relaxation.boolColOf[it] >= 0 }
            .sortedByDescending { ctx.primalOf(ctx.relaxation.boolColOf[it]) }

        val cuts = ArrayList<Cut>()
        val emitted = HashSet<String>()
        for (base in baseCliques) {
            val clique = base.filter { ctx.relaxation.boolColOf[it] >= 0 }.toMutableList()
            if (clique.size < 2) continue
            val members = IntHashSet()
            clique.forEach { members.add(it) }
            for (cand in ranked) {
                if (cand in members) continue
                val neigh = adj[cand] ?: continue
                if (clique.all { it in neigh }) {
                    clique.add(cand)
                    members.add(cand)
                }
            }
            val cols = IntArray(clique.size) { ctx.relaxation.boolColOf[clique[it]] }
            var lhs = 0.0
            for (c in cols) lhs += ctx.primalOf(c)
            if (lhs <= 1.0 + tol) continue
            val key = cols.sorted().joinToString(",")
            if (!emitted.add(key)) continue
            // The conflict graph is read off binary clauses and at-most-one factors — global.
            cuts.add(Cut(cols, LongArray(cols.size) { 1L }, Relation.LE, 1L, global = true))
        }
        return cuts
    }
}
