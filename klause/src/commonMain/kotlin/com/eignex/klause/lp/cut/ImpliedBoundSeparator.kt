package com.eignex.klause.lp.cut

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.propagation.propagatedImplicationGraph

// Each probed implication `A ⇒ B` is the inequality `litVal(A) ≤ litVal(B)`, with `litVal` of a negative literal
// `1 − x_c`. Explicit binary clauses already enter the LP as rows; probing is expensive, so the graph is cached.
internal class ImpliedBoundSeparator(private val maxCandidates: Int = DEFAULT_MAX_CANDIDATES) : CutSeparator {
    override val family: CutFamily = CutFamily.IMPLIED_BOUND

    private var graph: Array<IntArray>? = null

    override fun separate(ctx: CutContext): List<Cut> {
        val g = graph ?: implicationGraph(ctx.problem).also { graph = it }
        val boolColOf = ctx.relaxation.boolColOf
        val cuts = ArrayList<Cut>()
        for (fromLit in g.indices) {
            for (toLit in g[fromLit]) {
                val cut = impliedBoundCut(fromLit, toLit, boolColOf) ?: continue
                val fromVal = litValue(fromLit, boolColOf, ctx)
                val toVal = litValue(toLit, boolColOf, ctx)
                if (fromVal - toVal > VIOLATION_TOL) cuts.add(cut)
            }
        }
        return cuts
    }

    private fun litValue(lit: Int, boolColOf: IntArray, ctx: CutContext): Double {
        val col = boolColOf[Lit.variable(lit)]
        val x = ctx.primalOf(col)
        return if (Lit.isPositive(lit)) x else 1.0 - x
    }

    private fun implicationGraph(problem: Problem): Array<IntArray> = problem.propagatedImplicationGraph(maxCandidates)

    internal companion object {
        const val DEFAULT_MAX_CANDIDATES: Int = 512

        private const val VIOLATION_TOL: Double = 1e-6

        fun impliedBoundCut(fromLit: Int, toLit: Int, boolColOf: IntArray): Cut? {
            val fv = Lit.variable(fromLit)
            val tv = Lit.variable(toLit)
            if (fv == tv) return null // a unit fact, not a pairwise bound
            val fc = boolColOf.getOrElse(fv) { -1 }
            val tc = boolColOf.getOrElse(tv) { -1 }
            if (fc < 0 || tc < 0) return null
            val fromPos = Lit.isPositive(fromLit)
            val toPos = Lit.isPositive(toLit)
            // litVal(from) contributes (+1·x_fc, const 0) if positive, else (−1·x_fc, const +1).
            // −litVal(to)   contributes (−1·x_tc, const 0) if positive, else (+1·x_tc, const −1).
            val cFrom = if (fromPos) 1L else -1L
            val cTo = if (toPos) -1L else 1L
            val constSum = (if (fromPos) 0L else 1L) + (if (toPos) 0L else -1L)
            // cFrom·x_fc + cTo·x_tc + constSum ≤ 0  ⇒  rhs = −constSum.
            return Cut(intArrayOf(fc, tc), longArrayOf(cFrom, cTo), Relation.LE, -constSum, global = true)
        }
    }
}
