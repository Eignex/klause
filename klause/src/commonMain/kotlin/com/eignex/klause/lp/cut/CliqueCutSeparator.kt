package com.eignex.klause.lp.cut

import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.engine.Relation
import com.eignex.util.collections.IntHashSet

// Each at-most-one base clique is greedily extended with the highest-fractional variables adjacent to every
// member, and `Σ_{clique} x ≤ 1` is emitted when the LP violates it. The base row is already in the relaxation;
// the gain is the extension across constraints. Atamtürk, Nemhauser and Savelsbergh, "Conflict graphs in
// solving integer programming problems", Eur. J. Oper. Res. 2000, https://doi.org/10.1016/S0377-2217(99)00015-6.
internal class CliqueCutSeparator : CutSeparator {
    override val family: CutFamily = CutFamily.CLIQUE

    private val tol = 1e-6

    override fun separate(ctx: CutContext): List<Cut> {
        val graph = conflictGraph(ctx.problem)
        val adj = graph.adjacency
        val baseCliques = graph.baseCliques
        if (baseCliques.isEmpty()) return emptyList()

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
            cuts.add(Cut(cols, LongArray(cols.size) { 1L }, Relation.LE, 1L, global = true))
        }
        return cuts
    }
}
